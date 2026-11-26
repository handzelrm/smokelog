import Foundation

struct Grill: Identifiable, Hashable { let thingName: String; let name: String; var id: String { thingName } }

/// A probe's reading and the target set for it on the grill (nil = no target).
struct ProbeTemp: Equatable { var tempF: Double?; var targetF: Double? }

struct GrillStatus: Equatable {
    var time: Date
    var grillF: Double?
    var setF: Double?
    /// Always Probes.count slots: wired 1, wired 2, Bluetooth 1, Bluetooth 2; nil = not connected.
    var probes: [ProbeTemp?]
    var connected: Bool

    private var main: ProbeTemp? { probes.compactMap { $0 }.first { $0.tempF != nil } }
    /// The first connected probe.
    var probeF: Double? { main?.tempF }
    var probeTargetF: Double? { main?.targetF }
}

enum GrillUpdate {
    case status(GrillStatus)
    case problem(String)
}

struct TraegerError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// Talks to Traeger's cloud the way the Traeger app does. There is no official public
/// API: these endpoints are the ones community integrations use (as of mid-2026) and may
/// change. Flow: Cognito sign-in → REST (grill list, commands, signed MQTT URL) → MQTT over
/// WebSocket for live status.
final class TraegerClient {
    static let demoThing = "demo"
    private static let cognitoURL = URL(string: "https://cognito-idp.us-west-2.amazonaws.com/")!
    private static let cognitoClientId = "4id473dsrcq4kevlgrikukqn2a"
    private static let apiBase = "https://mobile-iot-api.iot.traegergrills.io"

    private let settings: AppSettings
    private var token: String?
    private var tokenExpires = Date.distantPast
    private var tokenFor: String?

    init(settings: AppSettings) { self.settings = settings }

    private func idToken() async throws -> String {
        let email = settings.traegerEmail.trimmingCharacters(in: .whitespaces).lowercased()
        let creds = email + "\n" + settings.traegerPassword
        if let t = token, tokenFor == creds, Date() < tokenExpires.addingTimeInterval(-60) { return t }
        guard settings.hasTraeger else { throw TraegerError(message: "Add your Traeger account in Settings") }

        var req = URLRequest(url: Self.cognitoURL)
        req.httpMethod = "POST"
        req.setValue("application/x-amz-json-1.1", forHTTPHeaderField: "Content-Type")
        req.setValue("AWSCognitoIdentityProviderService.InitiateAuth", forHTTPHeaderField: "X-Amz-Target")
        req.httpBody = try JSONSerialization.data(withJSONObject: [
            "AuthFlow": "USER_PASSWORD_AUTH",
            "ClientId": Self.cognitoClientId,
            "AuthParameters": ["USERNAME": email, "PASSWORD": settings.traegerPassword],
            "ClientMetadata": [String: String](),
        ])
        let (data, resp) = try await URLSession.shared.data(for: req)
        let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        guard (resp as? HTTPURLResponse)?.statusCode == 200,
              let auth = obj?["AuthenticationResult"] as? [String: Any],
              let tok = auth["IdToken"] as? String else {
            let msg = obj?["message"] as? String ?? "HTTP \((resp as? HTTPURLResponse)?.statusCode ?? 0)"
            throw TraegerError(message: "Traeger sign-in failed: \(msg)")
        }
        token = tok
        tokenFor = creds
        tokenExpires = Date().addingTimeInterval((auth["ExpiresIn"] as? Double) ?? 3600)
        return tok
    }

    private func api(_ method: String, _ path: String, body: [String: Any]? = nil) async throws -> Any {
        var req = URLRequest(url: URL(string: Self.apiBase + path)!)
        req.httpMethod = method
        req.setValue("Bearer \(try await idToken())", forHTTPHeaderField: "Authorization")
        if method == "POST" {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONSerialization.data(withJSONObject: body ?? [:])
        }
        let (data, resp) = try await URLSession.shared.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        if code == 401 || code == 403 { token = nil }
        guard (200..<300).contains(code) else { throw TraegerError(message: "Traeger API \(path) failed: HTTP \(code)") }
        return data.isEmpty ? [:] : try JSONSerialization.jsonObject(with: data)
    }

    func listGrills() async throws -> [Grill] {
        let me = try await api("GET", "/users/self") as? [String: Any]
        let things = me?["things"] as? [[String: Any]] ?? []
        return things.compactMap { t in
            guard let name = t["thingName"] as? String else { return nil }
            return Grill(thingName: name, name: t["friendlyName"] as? String ?? name)
        }
    }

    private func requestStateUpdate(_ thing: String) async throws {
        _ = try await api("POST", "/things/\(thing)/commands", body: ["command": "90"])
    }

    private func signedMqttURL() async throws -> (URL, TimeInterval) {
        let o = try await api("POST", "/mqtt-connections") as? [String: Any]
        guard let s = o?["signedUrl"] as? String, let url = URL(string: s) else { throw TraegerError(message: "No MQTT URL returned") }
        return (url, (o?["expirationSeconds"] as? Double) ?? 3600)
    }

    /// Live updates. Reconnects on drops and before the signed URL expires.
    func updates(thing: String) -> AsyncStream<GrillUpdate> {
        if thing == Self.demoThing { return Self.demoUpdates() }
        return AsyncStream { cont in
            let task = Task {
                while !Task.isCancelled {
                    do {
                        let (url, expires) = try await signedMqttURL()
                        let mqtt = MQTTWebSocketClient(url: url)
                        try await mqtt.connect()
                        try await mqtt.subscribe(topic: "prod/thing/update/\(thing)")
                        try await requestStateUpdate(thing)
                        let deadline = Date().addingTimeInterval(max(60, expires - 120))
                        for try await payload in mqtt.messages(until: deadline) {
                            if let s = Self.parseStatus(payload) { cont.yield(.status(s)) }
                        }
                        mqtt.close()
                    } catch is CancellationError {
                        break
                    } catch {
                        cont.yield(.problem(error.localizedDescription))
                        let signInFailed = (error as? TraegerError)?.message.contains("sign-in") == true
                        try? await Task.sleep(nanoseconds: (signInFailed ? 120 : 15) * 1_000_000_000)
                    }
                }
                cont.finish()
            }
            cont.onTermination = { _ in task.cancel() }
        }
    }

    /// Parses a `prod/thing/update/...` payload.
    static func parseStatus(_ payload: Data) -> GrillStatus? {
        guard let root = (try? JSONSerialization.jsonObject(with: payload)) as? [String: Any],
              let st = root["status"] as? [String: Any] else { return nil }
        func num(_ o: [String: Any]?, _ k: String) -> Double? { (o?[k] as? NSNumber)?.doubleValue }
        let celsius = (st["units"] as? NSNumber)?.intValue == 0
        func f(_ v: Double?) -> Double? { v.map { celsius ? $0 * 9 / 5 + 32 : $0 } }
        // Wired probes: the grill's own port, then any wired accessory probes. Some grills
        // also list the built-in port as an accessory, so skip an exact copy of it.
        // Bluetooth probes come as "btprobe" accessories. An empty wired port reports 0.
        func acc(_ type: String) -> [ProbeTemp] {
            (st["acc"] as? [[String: Any]] ?? [])
                .filter { $0["type"] as? String == type && ($0["con"] as? NSNumber)?.intValue != 0 }
                .sorted { ($0["channel"] as? String ?? "") < ($1["channel"] as? String ?? "") }
                .map { a in
                    let p = (a[type] ?? a["probe"]) as? [String: Any]
                    return ProbeTemp(tempF: f(num(p, "get_temp")), targetF: f(num(p, "set_temp").flatMap { $0 > 0 ? $0 : nil }))
                }
        }
        let wiredCon = (st["probe_con"] as? NSNumber).map { $0.intValue == 1 } ?? true
        var port: ProbeTemp?
        if wiredCon, let w = num(st, "probe"), w != 0 {
            port = ProbeTemp(tempF: f(w), targetF: f(num(st, "probe_set").flatMap { $0 > 0 ? $0 : nil }))
        }
        let wired = (port.map { [$0] } ?? []) + acc("probe").filter { $0 != port }
        let bt = acc("btprobe")
        func at(_ a: [ProbeTemp], _ i: Int) -> ProbeTemp? { i < a.count ? a[i] : nil }
        return GrillStatus(
            time: Date(),
            grillF: f(num(st, "grill")),
            setF: f(num(st, "set")),
            probes: [at(wired, 0), at(wired, 1), at(bt, 0), at(bt, 1)],
            connected: (st["connected"] as? Bool) ?? (st["connected"] as? NSNumber).map { $0.intValue != 0 } ?? true
        )
    }

    /// Simulated brisket: 225°F pit, probe stalls around 160°F. One sim-minute per second.
    private static func demoUpdates() -> AsyncStream<GrillUpdate> {
        AsyncStream { cont in
            let task = Task {
                var probe = 38.0, minute = 0.0
                while !Task.isCancelled {
                    let stall = exp(-((probe - 162) * (probe - 162)) / 60)
                    probe += (225 - probe) * 0.006 * (1 - 0.92 * stall)
                    let pit = 225 + 6 * sin(minute / 9) + Double.random(in: -2...2)
                    cont.yield(.status(GrillStatus(time: Date(), grillF: pit, setF: 225,
                                                   probes: [ProbeTemp(tempF: probe, targetF: 203), ProbeTemp(tempF: probe - 4, targetF: nil),
                                                           ProbeTemp(tempF: probe + 3, targetF: 203), nil], connected: true)))
                    minute += 1
                    try? await Task.sleep(nanoseconds: 1_000_000_000)
                }
                cont.finish()
            }
            cont.onTermination = { _ in task.cancel() }
        }
    }
}

// MARK: - Minimal MQTT 3.1.1 over WebSocket (subscribe-only)

/// Just enough MQTT for AWS IoT: CONNECT, SUBSCRIBE, receive PUBLISH (QoS 0/1 with PUBACK), PINGREQ.
final class MQTTWebSocketClient {
    private let ws: URLSessionWebSocketTask
    private var buffer = Data()
    private var pingTask: Task<Void, Never>?

    init(url: URL) {
        ws = URLSession.shared.webSocketTask(with: url, protocols: ["mqtt"])
    }

    func connect() async throws {
        ws.resume()
        var vh = Data()
        vh.append(Self.string("MQTT"))
        vh.append(4)                                  // protocol level 3.1.1
        vh.append(0x02)                               // clean session
        vh.append(contentsOf: [0, 60])                // keep-alive 60 s
        vh.append(Self.string("smokelog-\(UUID().uuidString.prefix(12))"))
        try await send(type: 0x10, body: vh)
        guard let ack = try await nextPacket(), ack.type == 0x20, ack.body.count >= 2, ack.body[ack.body.startIndex + 1] == 0 else {
            throw TraegerError(message: "MQTT connection refused")
        }
        pingTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 30_000_000_000)
                try? await self?.ws.send(.data(Data([0xC0, 0x00])))
            }
        }
    }

    func subscribe(topic: String) async throws {
        var body = Data([0x00, 0x01])                 // packet id 1
        body.append(Self.string(topic))
        body.append(1)                                // QoS 1
        try await send(type: 0x82, body: body)
    }

    /// PUBLISH payloads until [deadline] or the socket closes.
    func messages(until deadline: Date) -> AsyncThrowingStream<Data, Error> {
        AsyncThrowingStream { cont in
            let task = Task {
                do {
                    while Date() < deadline, !Task.isCancelled {
                        guard let p = try await nextPacket() else { break }
                        guard p.type & 0xF0 == 0x30 else { continue }   // only PUBLISH
                        let qos = (p.type >> 1) & 0x03
                        var i = p.body.startIndex
                        let topicLen = Int(p.body[i]) << 8 | Int(p.body[i + 1]); i += 2 + topicLen
                        if qos > 0 {
                            let id = p.body[i..<i + 2]; i += 2
                            try await ws.send(.data(Data([0x40, 0x02]) + id))   // PUBACK
                        }
                        cont.yield(Data(p.body[i...]))
                    }
                    cont.finish()
                } catch { cont.finish(throwing: error) }
            }
            cont.onTermination = { _ in task.cancel() }
        }
    }

    func close() {
        pingTask?.cancel()
        ws.cancel(with: .normalClosure, reason: nil)
    }

    private func send(type: UInt8, body: Data) async throws {
        var packet = Data([type])
        packet.append(Self.remainingLength(body.count))
        packet.append(body)
        try await ws.send(.data(packet))
    }

    /// Reads one complete MQTT packet, buffering across WebSocket frames.
    private func nextPacket() async throws -> (type: UInt8, body: Data)? {
        while true {
            if let p = takePacket() { return p }
            switch try await ws.receive() {
            case .data(let d): buffer.append(d)
            case .string(let s): buffer.append(Data(s.utf8))
            @unknown default: return nil
            }
        }
    }

    private func takePacket() -> (type: UInt8, body: Data)? {
        guard buffer.count >= 2 else { return nil }
        let bytes = [UInt8](buffer)
        var length = 0, multiplier = 1, i = 1
        while true {
            guard i < bytes.count else { return nil }
            let b = Int(bytes[i]); i += 1
            length += (b & 0x7F) * multiplier
            if b & 0x80 == 0 { break }
            multiplier *= 128
            if multiplier > 128 * 128 * 128 { return nil }
        }
        guard bytes.count >= i + length else { return nil }
        let packet = (bytes[0], Data(bytes[i..<i + length]))
        buffer = Data(bytes[(i + length)...])
        return packet
    }

    private static func string(_ s: String) -> Data {
        let u = Data(s.utf8)
        return Data([UInt8(u.count >> 8), UInt8(u.count & 0xFF)]) + u
    }

    private static func remainingLength(_ n: Int) -> Data {
        var out = Data(), x = n
        repeat {
            var b = UInt8(x % 128); x /= 128
            if x > 0 { b |= 0x80 }
            out.append(b)
        } while x > 0
        return out
    }
}
