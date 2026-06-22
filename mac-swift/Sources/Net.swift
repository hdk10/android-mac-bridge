import Foundation
import Network

/// LAN WebSocket server (home fast path). Receives one base64 secretbox blob per WS message.
final class LanServer {
    private var listener: NWListener?
    private let port: UInt16
    private let onBlob: (String) -> Void

    init(port: UInt16, onBlob: @escaping (String) -> Void) {
        self.port = port
        self.onBlob = onBlob
    }

    func start() {
        let params = NWParameters.tcp
        let ws = NWProtocolWebSocket.Options()
        ws.autoReplyPing = true
        params.defaultProtocolStack.applicationProtocols.insert(ws, at: 0)
        listener = try? NWListener(using: params, on: NWEndpoint.Port(rawValue: port)!)
        listener?.newConnectionHandler = { [weak self] conn in
            conn.start(queue: .global())
            self?.receive(conn)
        }
        listener?.start(queue: .global())
    }

    private func receive(_ conn: NWConnection) {
        conn.receiveMessage { [weak self] data, _, _, error in
            if let data, let s = String(data: data, encoding: .utf8) {
                self?.onBlob(s)
            }
            if error == nil { self?.receive(conn) } else { conn.cancel() }
        }
    }
}

/// Relay WebSocket client (off-LAN). Subscribes to /pair/<room>/listen, decrypts forwarded blobs,
/// keeps alive with pings, and auto-reconnects.
final class RelayClient: NSObject, URLSessionWebSocketDelegate {
    private let url: URL
    private let onBlob: (String) -> Void
    private let onState: (Bool) -> Void
    private var task: URLSessionWebSocketTask?
    private lazy var session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
    private var stopped = false

    init?(base: String, room: String, onBlob: @escaping (String) -> Void, onState: @escaping (Bool) -> Void) {
        guard let u = URL(string: "wss://\(base)/pair/\(room)/listen") else { return nil }
        self.url = u; self.onBlob = onBlob; self.onState = onState
    }

    func start() { connect() }

    private func connect() {
        task = session.webSocketTask(with: url)
        task?.resume()
        receive()
        schedulePing()
    }

    private func receive() {
        task?.receive { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let msg):
                switch msg {
                case .string(let s): if s != "pong" { self.onBlob(s) }
                case .data(let d): if let s = String(data: d, encoding: .utf8) { self.onBlob(s) }
                @unknown default: break
                }
                self.receive()
            case .failure:
                self.onState(false)
                self.reconnect()
            }
        }
    }

    private func schedulePing() {
        DispatchQueue.global().asyncAfter(deadline: .now() + 30) { [weak self] in
            guard let self, !self.stopped else { return }
            self.task?.sendPing { _ in }
            self.schedulePing()
        }
    }

    private func reconnect() {
        guard !stopped else { return }
        DispatchQueue.global().asyncAfter(deadline: .now() + 5) { [weak self] in
            guard let self, !self.stopped else { return }
            self.connect()
        }
    }

    // delegate: connection state for the green/red dot
    func urlSession(_ s: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didOpenWithProtocol p: String?) { onState(true) }
    func urlSession(_ s: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didCloseWith c: URLSessionWebSocketTask.CloseCode, reason: Data?) { onState(false) }
}

/// Bonjour/mDNS advertise so the phone finds our current LAN IP after a DHCP change.
final class Bonjour: NSObject {
    private var service: NetService?

    func publish(room: String, port: Int) {
        let s = NetService(domain: "local.", type: "_androidbridge._tcp.",
                           name: "AndroidBridge", port: Int32(port))
        let txt = ["room": room.data(using: .utf8) ?? Data()]
        s.setTXTRecord(NetService.data(fromTXTRecord: txt))
        s.publish()
        service = s
    }
}
