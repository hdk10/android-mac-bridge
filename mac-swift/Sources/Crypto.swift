import Foundation

/// NaCl secretbox (XSalsa20-Poly1305) via libsodium's C API (bridged through sodium-bridge.h).
/// Wire format: base64( nonce[24] || mac+cipher ). Compatible with Android (Lazysodium)
/// and the legacy Python agent (PyNaCl).
enum Crypto {
    private static let ready: Bool = { sodium_init() >= 0 }()

    static func open(_ blobB64: String, keyB64: String) -> Data? {
        _ = ready
        let nonceLen = crypto_secretbox_noncebytes()
        let macLen = crypto_secretbox_macbytes()
        let keyLen = crypto_secretbox_keybytes()

        guard let raw = Data(base64Encoded: blobB64),
              let key = Data(base64Encoded: keyB64),
              key.count == keyLen,
              raw.count > nonceLen + macLen
        else { return nil }

        let nonce = [UInt8](raw.prefix(nonceLen))
        let cipher = [UInt8](raw.suffix(from: nonceLen))
        var message = [UInt8](repeating: 0, count: cipher.count - macLen)

        let rc = crypto_secretbox_open_easy(
            &message, cipher, UInt64(cipher.count), nonce, [UInt8](key)
        )
        return rc == 0 ? Data(message) : nil
    }

    static func newKeyB64() -> String {
        _ = ready
        var k = [UInt8](repeating: 0, count: crypto_secretbox_keybytes())
        crypto_secretbox_keygen(&k)
        return Data(k).base64EncodedString()
    }
}
