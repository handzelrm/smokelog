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
    @ObservationIgnored private var targetAlerted = false

    var isRecording: Bool { cookId != nil }

    func start(cookId: UUID, thing: String, client: TraegerClient, store: CookStore, settings: AppSettings) {
        stop()
        self.cookId = cookId
        status = nil
        message = "Connecting…"
        targetAlerted = false
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
                        store.addReading(TempReading(time: s.time, grillF: s.grillF, grillSetF: s.setF, probeF: s.probeF,
                                                     probe2F: s.probe2F, probeTargetF: s.probeTargetF, source: source), to: cookId)
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

    private func checkTarget(_ s: GrillStatus) {
        guard !targetAlerted, let p = s.probeF, let t = s.probeTargetF, p >= t else { return }
        targetAlerted = true
        let content = UNMutableNotificationContent()
        content.title = "Probe target reached"
        content.body = "Probe at \(Fmt.temp(p)) (target \(Fmt.temp(t))). Time to check for probe-tender."
        content.sound = .default
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "target", content: content, trigger: nil))
    }
}
