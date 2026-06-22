import SwiftUI

private let brand = Color(red: 0.086, green: 0.612, blue: 0.463)   // LattIQ green

/// Green gradient rounded-square badge with the white bolt.horizontal.fill — matches the app icon.
struct BoltBadge: View {
    var size: CGFloat = 30
    var body: some View {
        RoundedRectangle(cornerRadius: size * 0.26, style: .continuous)
            .fill(LinearGradient(
                colors: [Color(red: 0.106, green: 0.706, blue: 0.533),
                         Color(red: 0.059, green: 0.494, blue: 0.369)],
                startPoint: .topLeading, endPoint: .bottomTrailing))
            .frame(width: size, height: size)
            .overlay(
                Image(systemName: "bolt.horizontal.fill")
                    .font(.system(size: size * 0.5, weight: .semibold))
                    .foregroundStyle(.white)
            )
    }
}

enum NotifTab: Hashable { case sms, apps }

struct ContentView: View {
    @EnvironmentObject var core: BridgeCore
    @State private var pairing = false
    @State private var tab: NotifTab = .sms

    var body: some View {
        VStack(spacing: 0) {
            if pairing {
                PairView(pairing: $pairing)
            } else {
                Header()
                Divider()
                if core.recent.isEmpty {
                    EmptyState()
                } else {
                    Picker("", selection: $tab) {
                        Text("SMS").tag(NotifTab.sms)
                        Text("Apps").tag(NotifTab.apps)
                    }
                    .pickerStyle(.segmented).labelsHidden()
                    .padding(.horizontal, 12).padding(.top, 10)
                    NotifList(tab: tab)
                }
                Divider()
                Footer(pairing: $pairing)
            }
        }
        .frame(width: 340)
        .animation(.easeInOut(duration: 0.18), value: pairing)
    }
}

// MARK: - Header

private struct Header: View {
    @EnvironmentObject var core: BridgeCore
    var body: some View {
        HStack(spacing: 10) {
            BoltBadge(size: 26)
            Text("Android Bridge").font(.headline)
            Spacer()
            StatusPill()
        }
        .padding(.horizontal, 14).padding(.vertical, 12)
    }
}

private struct StatusPill: View {
    @EnvironmentObject var core: BridgeCore
    var body: some View {
        let live = core.connectedPhones
        if live.count > 1 {
            Menu {
                ForEach(core.phones) { p in
                    let on = live.contains { $0.id == p.id }
                    Label(p.name, systemImage: on ? "circle.fill" : "circle")
                }
            } label: {
                pill(dotGreen: true, text: "\(live.count) phones", chevron: true)
            }
            .menuStyle(.borderlessButton).fixedSize()
        } else {
            pill(dotGreen: !live.isEmpty, text: live.first?.name ?? "Disconnected", chevron: false)
        }
    }

    private func pill(dotGreen: Bool, text: String, chevron: Bool) -> some View {
        HStack(spacing: 6) {
            Circle().fill(dotGreen ? .green : .red).frame(width: 8, height: 8)
            Text(text).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            if chevron { Image(systemName: "chevron.down").font(.system(size: 8)).foregroundStyle(.tertiary) }
        }
        .padding(.horizontal, 9).padding(.vertical, 4)
        .background(.quaternary, in: Capsule())
    }
}

// MARK: - List

private struct NotifList: View {
    @EnvironmentObject var core: BridgeCore
    let tab: NotifTab

    private var items: [NotifItem] {
        core.recent.filter { tab == .sms ? $0.isSMS : !$0.isSMS }
    }

    var body: some View {
        Group {
            if items.isEmpty {
                TabEmpty(tab: tab)
            } else {
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(items) { NotifCard(item: $0, isApps: tab == .apps) }
                    }
                    .padding(12)
                }
                .frame(maxHeight: 380)
            }
        }
    }
}

private struct AppLogo: View {
    let item: NotifItem
    var body: some View {
        if let b64 = item.iconB64, let data = Data(base64Encoded: b64), let img = NSImage(data: data) {
            Image(nsImage: img).resizable().frame(width: 30, height: 30)
                .clipShape(RoundedRectangle(cornerRadius: 7))
        } else {
            ZStack {
                RoundedRectangle(cornerRadius: 7).fill(.quaternary).frame(width: 30, height: 30)
                Image(systemName: item.isSMS ? "message.fill" : "app.fill")
                    .font(.system(size: 13)).foregroundStyle(.secondary)
            }
        }
    }
}

private struct NotifCard: View {
    @EnvironmentObject var core: BridgeCore
    let item: NotifItem
    let isApps: Bool
    @State private var copied = false

    private var copyValue: String { item.otp ?? (item.text.isEmpty ? item.title : item.text) }
    private var copyHelp: String { item.otp.map { "Copy OTP \($0)" } ?? "Copy message" }
    private var copyIcon: String { item.otp != nil ? "key.fill" : "doc.on.doc" }

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            AppLogo(item: item)
            VStack(alignment: .leading, spacing: 5) {
                Text(item.appLabel).font(.subheadline.weight(.semibold))
                if !item.title.isEmpty {
                    Text(item.title).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                if !item.text.isEmpty {
                    Text(item.text).font(.callout).lineLimit(4)
                        .fixedSize(horizontal: false, vertical: true)
                }
                // bottom row: time + OTP on the left, action icons on the right — never overlaps text
                HStack(spacing: 8) {
                    Text(item.date, format: .dateTime.hour().minute())
                        .font(.caption2).foregroundStyle(.tertiary)
                    if let otp = item.otp {
                        Text("OTP \(otp)")
                            .font(.caption.monospacedDigit().weight(.semibold))
                            .padding(.horizontal, 7).padding(.vertical, 2)
                            .background(brand.opacity(0.14), in: Capsule())
                            .foregroundStyle(brand)
                    }
                    Spacer(minLength: 8)
                    actions
                }
                .padding(.top, 1)
            }
        }
        .padding(12)
        .background(.background.secondary, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(.quaternary, lineWidth: 1))
    }

    private var actions: some View {
        HStack(spacing: 13) {
            if core.lanConnected, item.notifKey != nil {
                Button { core.openOnPhone(item) } label: { Image(systemName: "iphone.gen3") }
                    .buttonStyle(.plain).foregroundStyle(.secondary).help("Open on phone")
            }
            if isApps {
                Button { core.silence(item.app) } label: { Image(systemName: "bell.slash") }
                    .buttonStyle(.plain).foregroundStyle(.secondary).help("Silence \(item.appLabel)")
            }
            Button { core.copy(copyValue); flash() } label: {
                Image(systemName: copied ? "checkmark" : copyIcon)
                    .foregroundStyle(copied ? brand : .secondary)
            }
            .buttonStyle(.plain).help(copyHelp)
        }
        .font(.system(size: 13))
    }

    private func flash() {
        copied = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { copied = false }
    }
}

private struct TabEmpty: View {
    let tab: NotifTab
    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: tab == .sms ? "message" : "square.grid.2x2")
                .font(.system(size: 26)).foregroundStyle(.tertiary)
            Text(tab == .sms ? "No texts yet" : "No app notifications yet")
                .font(.callout).foregroundStyle(.secondary)
        }
        .padding(.vertical, 40).frame(maxWidth: .infinity)
    }
}

private struct EmptyState: View {
    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: "bell.slash").font(.system(size: 30)).foregroundStyle(.tertiary)
            Text("No notifications yet").font(.callout).foregroundStyle(.secondary)
            Text("Texts and OTPs from your phone will appear here.")
                .font(.caption).foregroundStyle(.tertiary).multilineTextAlignment(.center)
        }
        .padding(.vertical, 36).padding(.horizontal, 24).frame(maxWidth: .infinity)
    }
}

// MARK: - Footer

private struct Footer: View {
    @EnvironmentObject var core: BridgeCore
    @Binding var pairing: Bool
    var body: some View {
        HStack(spacing: 14) {
            Button { pairing = true } label: {
                Label("Pair phone", systemImage: "qrcode")
            }.buttonStyle(.plain)
            Spacer()
            if !core.recent.isEmpty {
                Button("Clear") { core.clearRecent() }.buttonStyle(.plain).foregroundStyle(.secondary)
            }
            Button { core.quit() } label: { Image(systemName: "power") }
                .buttonStyle(.plain).foregroundStyle(.secondary)
        }
        .font(.callout)
        .padding(.horizontal, 14).padding(.vertical, 10)
    }
}

// MARK: - Pairing

private struct PairView: View {
    @EnvironmentObject var core: BridgeCore
    @Binding var pairing: Bool

    var body: some View {
        VStack(spacing: 14) {
            HStack {
                Button { pairing = false } label: { Image(systemName: "chevron.left") }
                    .buttonStyle(.plain)
                Spacer()
                Text("Pair phone").font(.headline)
                Spacer()
                Image(systemName: "chevron.left").opacity(0)   // balance
            }
            if let img = QRGen.image(QRGen.payload(config: core.config)) {
                Image(nsImage: img)
                    .interpolation(.none)
                    .resizable().frame(width: 220, height: 220)
                    .padding(10)
                    .background(.white, in: RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(.quaternary))
            }
            Text("Open **Mac Bridge** on your phone → **Scan QR to pair**.")
                .font(.callout).multilineTextAlignment(.center).foregroundStyle(.secondary)
            Text("Works on the same Wi-Fi or anywhere over the encrypted relay.")
                .font(.caption).foregroundStyle(.tertiary).multilineTextAlignment(.center)
        }
        .padding(16)
    }
}
