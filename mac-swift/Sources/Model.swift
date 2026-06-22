import Foundation

/// One received notification, shown as a card in the popover.
struct NotifItem: Identifiable {
    let id: String
    let app: String
    let title: String
    let text: String
    let otp: String?
    let date: Date
    let source: String      // "LAN" | "Relay"
    let iconB64: String?    // base64 PNG of the source app's icon (sent by the phone)
    let notifKey: String?   // Android StatusBarNotification key — to reopen it on the phone

    var appLabel: String {
        Self.labels[app] ?? app.split(separator: ".").last.map(String.init) ?? "phone"
    }

    /// SMS / messaging notifications go under the "SMS" tab; everything else under "Apps".
    var isSMS: Bool {
        Self.smsApps.contains(app) || app.contains(".messaging") || app.contains(".sms")
    }

    static let labels = [
        "com.google.android.apps.messaging": "Messages",
        "com.whatsapp": "WhatsApp",
        "com.google.android.gm": "Gmail",
        "com.microsoft.android.smsorganizer": "Messages",
    ]
    static let smsApps: Set<String> = [
        "com.google.android.apps.messaging", "com.microsoft.android.smsorganizer",
        "com.samsung.android.messaging", "com.android.messaging", "com.textra",
    ]
}

/// Hint-gated OTP extraction (defensive — the phone already sends `otp`).
enum OTP {
    private static let hints = ["otp", "code", "verification", "verify",
                                "password", "passcode", "2fa", "one-time", "otp:"]
    static func extract(_ title: String, _ text: String) -> String? {
        let blob = title + " " + text
        guard hints.contains(where: blob.lowercased().contains) else { return nil }
        guard let r = blob.range(of: "\\b\\d{4,8}\\b", options: .regularExpression) else { return nil }
        return String(blob[r])
    }
}

/// Best-effort primary LAN IPv4 (for the QR's LAN locator).
func lanIPv4() -> String {
    var address = "127.0.0.1"
    var ifaddr: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return address }
    defer { freeifaddrs(ifaddr) }
    for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
        let flags = Int32(ptr.pointee.ifa_flags)
        let family = ptr.pointee.ifa_addr.pointee.sa_family
        // up, running, not loopback, IPv4
        if (flags & (IFF_UP | IFF_RUNNING | IFF_LOOPBACK)) == (IFF_UP | IFF_RUNNING),
           family == UInt8(AF_INET) {
            let name = String(cString: ptr.pointee.ifa_name)
            if name == "en0" || name == "en1" {
                var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                getnameinfo(ptr.pointee.ifa_addr, socklen_t(ptr.pointee.ifa_addr.pointee.sa_len),
                            &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
                address = String(cString: host)
            }
        }
    }
    return address
}
