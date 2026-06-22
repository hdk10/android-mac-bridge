import AppKit
import CoreImage

/// Pairing QR — encodes {v:2, key, room, relay, ip, port} as JSON.
enum QRGen {
    static func payload(config: Config) -> String {
        let dict: [String: Any] = [
            "v": 2, "key": config.key, "room": config.room,
            "relay": Config.relayBase, "ip": lanIPv4(), "port": Config.port,
        ]
        let data = (try? JSONSerialization.data(withJSONObject: dict)) ?? Data()
        return String(data: data, encoding: .utf8) ?? ""
    }

    static func image(_ string: String, size: CGFloat = 220) -> NSImage? {
        guard let data = string.data(using: .utf8),
              let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(data, forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage else { return nil }
        let scale = size / output.extent.width
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let rep = NSCIImageRep(ciImage: scaled)
        let img = NSImage(size: rep.size)
        img.addRepresentation(rep)
        return img
    }
}
