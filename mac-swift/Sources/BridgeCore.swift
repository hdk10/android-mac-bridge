import AppKit
import Foundation
import UserNotifications

/// Orchestrator + view-model. Runs the LAN server, relay client, and Bonjour;
/// decrypts + de-dupes incoming notifications; publishes state to SwiftUI.
@MainActor
final class BridgeCore: ObservableObject {
    static let shared = BridgeCore()

    /// A paired phone the Mac has heard from.
    struct Phone: Identifiable { let id: String; var name: String; var lastSeen: Date }

    @Published var recent: [NotifItem] = []
    @Published var connected = false          // any phone present (drives the menu-bar icon)
    @Published var phones: [Phone] = []        // all phones seen recently (multi-device)
    @Published var selectedPhone: String? = nil  // nil = show all phones; else filter to this did
    @Published var lastCopied: String? = nil
    @Published var silenced: Set<String> = []  // app packages the user muted
    @Published var lanConnected = false        // a phone holds a LAN socket → "open on phone" works
    @Published var clips: [ClipItem] = []      // clipboard texts received from phones (newest first)
    @Published var clipAutoPush = true         // auto-push the Mac's clipboard to phones on copy

    /// Phones considered live right now (heard from within the timeout).
    var connectedPhones: [Phone] {
        let now = Date()
        return phones.filter { now.timeIntervalSince($0.lastSeen) < presenceTimeout }
            .sorted { $0.name < $1.name }
    }

    let config = Config.loadOrCreate()
    private let silencedURL = FileManager.default.homeDirectoryForCurrentUser
        .appendingPathComponent(".androidbridge/silenced.json")
    private let clipSyncURL = FileManager.default.homeDirectoryForCurrentUser
        .appendingPathComponent(".androidbridge/clipsync.json")

    private var lan: LanServer?
    private var relay: RelayClient?
    private let bonjour = Bonjour()
    private var seen = Set<String>()
    private var seenOrder: [String] = []
    private let presenceTimeout: TimeInterval = 90   // > 2 missed 30s heartbeats

    // Clipboard sync state.
    private var macId = UUID().uuidString            // stable id for this Mac (persisted via clipsync.json)
    private var macName = Host.current().localizedName ?? "Mac"
    private var lastAppliedClip: String?             // last text we wrote/sent — echo-guard both ways
    private var lastSeenChangeCount = NSPasteboard.general.changeCount

    /// Persisted clip-sync prefs: the auto-push toggle + this Mac's stable id.
    private struct ClipSyncState: Codable { var autoPush: Bool; var macId: String }

    func start() {
        loadSilenced()
        loadClipSync()
        let server = LanServer(port: UInt16(Config.port)) { [weak self] blob in
            Task { @MainActor in self?.ingest(blob, source: "LAN") }
        }
        server.onConnChange = { [weak self] up in Task { @MainActor in self?.lanConnected = up } }
        lan = server
        lan?.start()

        relay = RelayClient(
            base: Config.relayBase, room: config.room,
            onBlob: { [weak self] blob in Task { @MainActor in self?.ingest(blob, source: "Relay") } },
            onState: { _ in }   // relay link state is NOT the user-facing "connected"
        )
        relay?.start()

        bonjour.publish(room: config.room, port: Config.port)
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }

        // Presence: connected only while we've heard from the phone within the timeout.
        Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.refreshPresence() }
        }

        // Clipboard: baseline the changeCount NOW so a pre-existing clipboard at launch
        // is never broadcast — only genuine post-launch copies fire.
        lastSeenChangeCount = NSPasteboard.general.changeCount
        // Poll the pasteboard's changeCount and push fresh copies to phones.
        Timer.scheduledTimer(withTimeInterval: 0.7, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.pollPasteboard() }
        }
    }

    private func refreshPresence() {
        let now = Date()
        phones.removeAll { now.timeIntervalSince($0.lastSeen) > 600 }  // forget after 10 min
        if let sel = selectedPhone, !phones.contains(where: { $0.id == sel }) {
            selectedPhone = phones.first?.id   // fall back to another phone, not "All"
        }
        connected = !connectedPhones.isEmpty
    }

    private func seenPhone(id: String, name: String) {
        if let i = phones.firstIndex(where: { $0.id == id }) {
            phones[i].lastSeen = Date(); phones[i].name = name
        } else {
            phones.append(Phone(id: id, name: name, lastSeen: Date()))
        }
        if selectedPhone == nil { selectedPhone = id }   // default to the first phone heard
        connected = true
    }

    // MARK: - receive

    private func ingest(_ blob: String, source: String) {
        guard let data = Crypto.open(blob, keyB64: config.key),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return }

        let did = obj["did"] as? String ?? "phone"
        let dname = obj["dname"] as? String ?? "Phone"

        // Any valid (decryptable) message proves that phone is alive.
        if obj["type"] as? String == "bye" {        // phone unpaired → drop it now
            phones.removeAll { $0.id == did }
            if selectedPhone == did { selectedPhone = phones.first?.id }
            connected = !connectedPhones.isEmpty
            return
        }
        seenPhone(id: did, name: dname)
        if obj["type"] as? String == "ping" { return }  // heartbeat / probe — don't show

        if obj["type"] as? String == "clip" {           // clipboard text from a phone
            let id = obj["id"] as? String ?? UUID().uuidString
            if seen.contains(id) { return }              // dedup (LAN + relay dual-send)
            seen.insert(id); seenOrder.append(id)
            if seenOrder.count > 300 { seen.remove(seenOrder.removeFirst()) }
            let text = obj["clip"] as? String ?? ""
            if text.isEmpty { return }
            // Echo-guard: if it's the text we last applied/sent, record presence only — don't re-write.
            if text != lastAppliedClip {
                let pb = NSPasteboard.general
                pb.clearContents()
                pb.setString(text, forType: .string)
                lastAppliedClip = text
                lastSeenChangeCount = pb.changeCount   // don't let the poller bounce this write back
            }
            let clip = ClipItem(id: id, text: text, dname: dname, date: Date(), source: source)
            clips.insert(clip, at: 0)
            if clips.count > 15 { clips.removeLast() }
            FileHandle.standardError.write("RX [\(source)] clip from \(dname) | \(text)\n".data(using: .utf8)!)
            postClipBanner(text: text, dname: dname)
            return
        }

        let app = obj["app"] as? String ?? "phone"
        if silenced.contains(app) { return }                  // user-muted app
        let id = obj["id"] as? String ?? UUID().uuidString
        if seen.contains(id) { return }                       // dedup (LAN + relay dual-send)
        seen.insert(id); seenOrder.append(id)
        if seenOrder.count > 300 { seen.remove(seenOrder.removeFirst()) }

        let title = obj["title"] as? String ?? ""
        let text = obj["text"] as? String ?? ""
        let item = NotifItem(
            id: id,
            did: did,
            app: app,
            title: title, text: text,
            otp: (obj["otp"] as? String) ?? OTP.extract(title, text),
            date: Date(), source: source,
            iconB64: obj["icon"] as? String,
            notifKey: obj["key"] as? String
        )
        recent.insert(item, at: 0)
        if recent.count > 15 { recent.removeLast() }
        FileHandle.standardError.write(
            "RX [\(source)] \(item.appLabel) | \(item.text) | otp=\(item.otp ?? "-")\n".data(using: .utf8)!)
        postBanner(item)
    }

    private func postBanner(_ item: NotifItem) {
        let content = UNMutableNotificationContent()
        if let otp = item.otp {
            content.title = "OTP \(otp)"
            content.subtitle = item.appLabel
        } else {
            content.title = item.title.isEmpty ? item.appLabel : item.title
        }
        content.body = item.text
        let req = UNNotificationRequest(identifier: item.id, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(req)
    }

    private func postClipBanner(text: String, dname: String) {
        let content = UNMutableNotificationContent()
        content.title = "Clipboard from \(dname)"
        content.body = text
        let req = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(req)
    }

    // MARK: - clipboard sync (Mac → phone)

    /// Pasteboard changeCount poller: on a fresh user copy, broadcast it to every paired phone.
    private func pollPasteboard() {
        let pb = NSPasteboard.general
        guard pb.changeCount != lastSeenChangeCount else { return }
        lastSeenChangeCount = pb.changeCount
        guard clipAutoPush else { return }                        // auto-push disabled
        // Never sync passwords / transient drags.
        let types = pb.types ?? []
        if types.contains(NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType"))
            || types.contains(NSPasteboard.PasteboardType("org.nspasteboard.TransientType")) { return }
        guard let text = pb.string(forType: .string), !text.isEmpty else { return }
        if text == lastAppliedClip { return }                     // echo from a write we just did
        sendClip(text)
    }

    /// Read the current pasteboard and push it to phones now (manual "Send" button path).
    func sendClipboardNow() {
        guard let text = NSPasteboard.general.string(forType: .string), !text.isEmpty else { return }
        sendClip(text)
    }

    /// Build the clip JSON, encrypt it, and fan it out over LAN + relay to the phones.
    private func sendClip(_ text: String) {
        lastAppliedClip = text                                    // symmetric echo-guard
        let payload: [String: Any] = [
            "type": "clip", "clip": text, "id": UUID().uuidString,
            "did": macId, "dname": macName, "time": Int(Date().timeIntervalSince1970 * 1000),
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: payload),
              let blob = Crypto.seal(data, keyB64: config.key) else { return }
        lan?.broadcast(blob)       // home fast path — reach every paired phone
        relay?.postClip(blob)      // off-LAN path
        FileHandle.standardError.write("TX clip → phones | \(text)\n".data(using: .utf8)!)
    }

    func setClipAutoPush(_ on: Bool) { clipAutoPush = on; saveClipSync() }

    // MARK: - actions

    func copy(_ value: String) {
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(value, forType: .string)
        lastCopied = value
    }

    func clearRecent() { recent.removeAll() }

    /// Ask the phone to open this notification (LAN only — uses the live phone socket).
    func openOnPhone(_ item: NotifItem) {
        guard lanConnected, let key = item.notifKey else { return }
        let payload: [String: Any] = ["type": "open", "key": key, "app": item.app]
        guard let data = try? JSONSerialization.data(withJSONObject: payload),
              let blob = Crypto.seal(data, keyB64: config.key) else { return }
        lan?.send(blob)
    }

    func silence(_ app: String) {
        silenced.insert(app)
        recent.removeAll { $0.app == app }
        saveSilenced()
    }
    func unsilence(_ app: String) { silenced.remove(app); saveSilenced() }

    private func loadSilenced() {
        if let d = try? Data(contentsOf: silencedURL),
           let a = try? JSONDecoder().decode([String].self, from: d) {
            silenced = Set(a)
        }
    }
    private func saveSilenced() {
        try? JSONEncoder().encode(Array(silenced)).write(to: silencedURL)
    }

    private func loadClipSync() {
        if let d = try? Data(contentsOf: clipSyncURL),
           let s = try? JSONDecoder().decode(ClipSyncState.self, from: d) {
            clipAutoPush = s.autoPush
            if !s.macId.isEmpty { macId = s.macId }
        }
        saveClipSync()   // persist a freshly-generated macId on first run
    }
    private func saveClipSync() {
        let s = ClipSyncState(autoPush: clipAutoPush, macId: macId)
        try? JSONEncoder().encode(s).write(to: clipSyncURL)
    }

    func quit() { NSApp.terminate(nil) }
}
