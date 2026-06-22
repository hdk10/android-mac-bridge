import AppKit
import Foundation
import UserNotifications

/// Orchestrator + view-model. Runs the LAN server, relay client, and Bonjour;
/// decrypts + de-dupes incoming notifications; publishes state to SwiftUI.
@MainActor
final class BridgeCore: ObservableObject {
    static let shared = BridgeCore()

    @Published var recent: [NotifItem] = []
    @Published var connected = false          // a paired phone has been heard from recently
    @Published var lastCopied: String? = nil
    @Published var silenced: Set<String> = []  // app packages the user muted
    @Published var lanConnected = false        // a phone holds a LAN socket → "open on phone" works

    let config = Config.loadOrCreate()
    private let silencedURL = FileManager.default.homeDirectoryForCurrentUser
        .appendingPathComponent(".androidbridge/silenced.json")

    private var lan: LanServer?
    private var relay: RelayClient?
    private let bonjour = Bonjour()
    private var seen = Set<String>()
    private var seenOrder: [String] = []
    private var lastSeen: Date?               // last valid message from the phone
    private let presenceTimeout: TimeInterval = 90   // > 2 missed 30s heartbeats

    func start() {
        loadSilenced()
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
    }

    private func refreshPresence() {
        if let ls = lastSeen, Date().timeIntervalSince(ls) < presenceTimeout {
            connected = true
        } else {
            connected = false
        }
    }

    // MARK: - receive

    private func ingest(_ blob: String, source: String) {
        guard let data = Crypto.open(blob, keyB64: config.key),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return }

        // Any valid (decryptable) message proves a paired phone is alive.
        if obj["type"] as? String == "bye" {        // phone unpaired → drop presence now
            lastSeen = nil; connected = false; return
        }
        lastSeen = Date(); connected = true
        if obj["type"] as? String == "ping" { return }  // heartbeat / probe — don't show
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

    func quit() { NSApp.terminate(nil) }
}
