import XCTest
@testable import SmokeLog

final class GrillStatusTests: XCTestCase {
    // Shape of a real Timberline XL update: empty wired port, one wireless probe, side burner.
    private let timberline = """
    {"thingName":"X","status":{"units":1,"grill":300,"set":320,"connected":true,
     "probe_con":1,"probe":0,"probe_set":0,
     "acc":[{"channel":"BT0","btprobe":{"get_temp":50,"set_temp":150},"type":"btprobe","con":1},
            {"channel":"bt","hob":{"get_temp":32,"set_temp":0},"type":"hob","con":1}]}}
    """

    private func parse(_ s: String) throws -> GrillStatus { try XCTUnwrap(TraegerClient.parseStatus(Data(s.utf8))) }

    func testWirelessProbeGoesToBluetoothSlot() throws {
        let s = try parse(timberline)
        XCTAssertEqual(s.grillF, 300); XCTAssertEqual(s.setF, 320)
        XCTAssertEqual(s.probes, [nil, nil, ProbeTemp(tempF: 50, targetF: 150), nil])
        XCTAssertEqual(s.probeF, 50); XCTAssertEqual(s.probeTargetF, 150)
    }

    func testTwoWiredAndTwoBluetooth() throws {
        let s = try parse("""
        {"status":{"units":1,"grill":250,"set":250,"probe_con":1,"probe":160,"probe_set":203,
         "acc":[{"channel":"BT1","type":"btprobe","con":1,"btprobe":{"get_temp":140,"set_temp":0}},
                {"channel":"p1","type":"probe","con":1,"probe":{"get_temp":171,"set_temp":195}},
                {"channel":"BT0","type":"btprobe","con":1,"btprobe":{"get_temp":120,"set_temp":135}}]}}
        """)
        XCTAssertEqual(s.probes, [ProbeTemp(tempF: 160, targetF: 203), ProbeTemp(tempF: 171, targetF: 195),
                                  ProbeTemp(tempF: 120, targetF: 135), ProbeTemp(tempF: 140, targetF: nil)])
    }

    func testBuiltInPortListedAgainIsNotDoubled() throws {
        let s = try parse("""
        {"status":{"units":1,"grill":250,"set":250,"probe_con":1,"probe":160,"probe_set":203,
         "acc":[{"channel":"p0","type":"probe","con":1,"probe":{"get_temp":160,"set_temp":203}},
                {"channel":"p1","type":"probe","con":1,"probe":{"get_temp":171,"set_temp":0}}]}}
        """)
        XCTAssertEqual(s.probes, [ProbeTemp(tempF: 160, targetF: 203), ProbeTemp(tempF: 171, targetF: nil), nil, nil])
    }

    func testOldCookFilesStillLoadAndNewProbesRoundTrip() throws {
        let old = try JSONDecoder().decode(TempReading.self, from: Data(#"{"id":"\#(UUID().uuidString)","time":0,"probeF":150,"source":"traeger"}"#.utf8))
        XCTAssertNil(old.probe3F); XCTAssertEqual(old.mainProbeF, 150)
        let r = TempReading(time: Date(), grillF: 225, probe3F: 120, probe4F: 90)
        XCTAssertEqual(r.mainProbeF, 120)
        var c = Cook(); c.readings = [r]
        let back = try CookShare.importCook(from: CookShare.fileData(c))
        XCTAssertEqual(back.readings.first?.probe3F, 120); XCTAssertEqual(back.readings.first?.probe4F, 90)
    }
}
