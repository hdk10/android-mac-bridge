import Foundation

/// Persistent pairing secret, shared with the phone via the QR.
/// Stored at ~/.androidbridge/config.json (0600). `key` = 32-byte secretbox key (base64);
/// `room` = relay room id. Generated once, reused across launches.
struct Config: Codable {
    var key: String
    var room: String

    static let port = 8765
    static let relayBase = "android-bridge-relay.hardikkatyarmal123.workers.dev"

    private static let dir = FileManager.default.homeDirectoryForCurrentUser
        .appendingPathComponent(".androidbridge")
    private static let path = dir.appendingPathComponent("config.json")

    static func loadOrCreate() -> Config {
        if let data = try? Data(contentsOf: path),
           let cfg = try? JSONDecoder().decode(Config.self, from: data),
           !cfg.key.isEmpty, !cfg.room.isEmpty {
            return cfg
        }
        let cfg = Config(key: Crypto.newKeyB64(), room: randomRoom())
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        if let data = try? JSONEncoder().encode(cfg) {
            try? data.write(to: path)
            try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: path.path)
        }
        return cfg
    }

    private static func randomRoom() -> String {
        var bytes = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
