import Foundation
import Observation
import UserNotifications

/// Records grill updates into a cook while the app is open.
///
/// iOS doesn't let apps keep a network connection open indefinitely in the background,
/// so the screen is kept awake while recording. Leave SmokeLog open (e.g. on a charger)
/// for an unattended cook.
@Observable
final class Recorder {
    private(set) var cookId: UUID?
    private(set) var status: GrillStatus?
    private(set) var message: String?

    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private var alertedTargets = [Double?](repeating: nil, count: Probes.count)

    var isRecording: Bool { cookId != nil }

    func start(cookId: UUID, thing: String, client: TraegerClient, store: CookStore, settings: AppSettings) {
        stop()
        self.cookId = cookId
        status = nil
        message = "Connecting…"
        alertedTargets = [Double?](repeating: nil, count: Probes.count)
        Platform.keepScreenAwake(true)
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }

        let source = thing == TraegerClient.demoThing ? "demo" : "traeger"
        let interval: TimeInterval = source == "demo" ? 0 : TimeInterval(settings.readingIntervalSec)
        task = Task { @MainActor [weak self] in
            var lastWrite = Date.distantPast
            for await update in client.updates(thing: thing) {
                guard let self else { return }
                switch update {
                case .problem(let m):
                    self.message = m
                case .status(let s):
                    self.status = s
                    self.message = s.connected ? nil : "Grill reports offline"
                    if s.time.timeIntervalSince(lastWrite) >= interval {
                        lastWrite = s.time
                        store.addReading(TempReading(time: s.time, grillF: s.grillF, grillSetF: s.setF, probeF: s.probes[0]?.tempF,
                                                     probe2F: s.probes[1]?.tempF, probeTargetF: s.probeTargetF, source: source,
                                                     probe3F: s.probes[2]?.tempF, probe4F: s.probes[3]?.tempF), to: cookId)
                    }
                    self.checkTarget(s)
                }
            }
        }
    }

    func stop() {
        task?.cancel()
        task = nil
        cookId = nil
        status = nil
        message = nil
        Platform.keepScreenAwake(false)
    }

    /// One alert per probe; re-arms if that probe's target is changed.
    private func checkTarget(_ s: GrillStatus) {
        for (i, probe) in s.probes.enumerated() {
            guard let p = probe?.tempF, let t = probe?.targetF, p >= t, alertedTargets[i] != t else { continue }
            alertedTargets[i] = t
            let name = Probes.labels[i]
            let content = UNMutableNotificationContent()
            content.title = "\(name) reached its target"
            content.body = "\(name) at \(Fmt.temp(p)) (target \(Fmt.temp(t))). Time to check for probe-tender."
            content.sound = .default
            UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "target-\(i)", content: content, trigger: nil))
        }
    }
}
