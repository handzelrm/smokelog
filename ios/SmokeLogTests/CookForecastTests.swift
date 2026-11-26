import XCTest
@testable import SmokeLog

final class CookForecastTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    private func newton(_ minutes: Int, pit: Double = 250, k: Double = 0.35, start: Double = 40) -> [CookForecast.Point] {
        (0...minutes).map { m in
            CookForecast.Point(time: t0 + Double(m * 60), meatF: pit - (pit - start) * exp(-k * Double(m) / 60),
                               pitF: pit + (m % 2 == 0 ? 4 : -4), setF: pit)
        }
    }

    func testMatchesTheHeatingCurve() throws {
        let pts = newton(180)
        let f = CookForecast.forecast(pts, goal: 203)
        XCTAssertEqual(f.kind, .onTrack)
        let cur = pts.last!.meatF
        let trueH = log((250 - cur) / (250 - 203)) / 0.35
        let gotH = try XCTUnwrap(f.doneAt).timeIntervalSince(pts.last!.time) / 3600
        XCTAssertLessThan(abs(gotH - trueH) / trueH, 0.08)
        XCTAssertEqual(try XCTUnwrap(f.project(at: f.doneAt!)), 203, accuracy: 0.5)
    }

    func testStall() {
        let climb = newton(240).filter { $0.meatF < 158 }
        let last = climb.last!.time
        let flat = (1...60).map { CookForecast.Point(time: last + Double($0 * 60), meatF: 161 + Double($0 % 3) * 0.5, pitF: 251, setF: 250) }
        let f = CookForecast.forecast(climb + flat, goal: 203)
        XCTAssertEqual(f.kind, .stalled)
        XCTAssertNotNil(f.afterStallMinutes)
        XCTAssertNil(f.doneAt)
    }

    func testOtherCases() {
        XCTAssertEqual(CookForecast.forecast(newton(60, pit: 200), goal: 203).kind, .pitTooLow)
        XCTAssertEqual(CookForecast.forecast(newton(8), goal: 203).needMinutes, 7)
        let line = stride(from: 0, through: 30, by: 10).map { CookForecast.Point(time: t0 + Double($0 * 60), meatF: 100 + Double($0), pitF: nil, setF: nil) }
        XCTAssertEqual(CookForecast.forecast(line, goal: 160).minutesLeft(t0 + 30 * 60), 30)
    }

    func testPerProbeGoals() {
        // Bluetooth 1 has a grill target; the wired probe falls back to brisket's usual 203°F.
        let rs = (0...60).map { m in
            TempReading(time: t0 + Double(m * 60), grillF: 250, grillSetF: 250, probeF: 100 + Double(m) * 0.5,
                        probeTargetF: nil, source: "traeger", probe3F: 90 + Double(m) * 0.6)
        }
        let live = CookForecast.forProbes(meat: "Brisket", cut: "", readings: rs, liveTargets: [nil, nil, 165, nil])
        XCTAssertEqual(live.map(\.slot), [0, 2])
        XCTAssertEqual(live[0].forecast.goalF, 203); XCTAssertTrue(live[0].goalIsTypical)
        XCTAssertEqual(live[1].forecast.goalF, 165); XCTAssertFalse(live[1].goalIsTypical)
        XCTAssertNil(MeatCatalog.typicalDoneF("Beef jerky"))
    }
}
