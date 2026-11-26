package com.smokelog

import com.smokelog.data.TempReading
import com.smokelog.traeger.ProbeTemp
import com.smokelog.traeger.TraegerClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GrillStatusTest {
    // Shape of a real Timberline XL update: empty wired port, one wireless probe, side burner.
    private val timberline = """
        {"thingName":"X","status":{"units":1,"grill":300,"set":320,"connected":true,
         "probe_con":1,"probe":0,"probe_set":0,
         "acc":[{"channel":"BT0","btprobe":{"get_temp":50,"set_temp":150,"ambient_temp":1086},"type":"btprobe","con":1},
                {"channel":"bt","hob":{"get_temp":32,"set_temp":0},"type":"hob","con":1}]}}
    """.trimIndent()

    private fun parse(json: String) = TraegerClient.parseStatus(json)!!

    @Test fun wirelessProbeGoesToBluetoothSlot() {
        val s = parse(timberline)
        assertEquals(300.0, s.grillF!!, 0.0)
        assertEquals(320.0, s.setF!!, 0.0)
        assertEquals(listOf(null, null, ProbeTemp(50.0, 150.0), null), s.probes)
        assertEquals(50.0, s.probeF!!, 0.0)          // main probe = first connected one
        assertEquals(150.0, s.probeTargetF!!, 0.0)
    }

    @Test fun twoWiredAndTwoBluetooth() {
        val s = parse("""
            {"status":{"units":1,"grill":250,"set":250,"probe_con":1,"probe":160,"probe_set":203,
             "acc":[{"channel":"BT1","type":"btprobe","con":1,"btprobe":{"get_temp":140,"set_temp":0}},
                    {"channel":"p1","type":"probe","con":1,"probe":{"get_temp":171,"set_temp":195}},
                    {"channel":"BT0","type":"btprobe","con":1,"btprobe":{"get_temp":120,"set_temp":135}}]}}
        """.trimIndent())
        assertEquals(
            listOf(ProbeTemp(160.0, 203.0), ProbeTemp(171.0, 195.0), ProbeTemp(120.0, 135.0), ProbeTemp(140.0, null)),
            s.probes,
        )
    }

    @Test fun builtInPortListedAgainAsAccessoryIsNotDoubled() {
        val s = parse("""
            {"status":{"units":1,"grill":250,"set":250,"probe_con":1,"probe":160,"probe_set":203,
             "acc":[{"channel":"p0","type":"probe","con":1,"probe":{"get_temp":160,"set_temp":203}},
                    {"channel":"p1","type":"probe","con":1,"probe":{"get_temp":171,"set_temp":0}}]}}
        """.trimIndent())
        assertEquals(listOf(ProbeTemp(160.0, 203.0), ProbeTemp(171.0, null), null, null), s.probes)
    }

    @Test fun celsiusIsConverted() {
        val s = parse(timberline.replace("\"units\":1", "\"units\":0").replace("\"grill\":300", "\"grill\":100"))
        assertEquals(212.0, s.grillF!!, 0.01)
        assertEquals(122.0, s.probes[2]!!.tempF!!, 0.01)
    }

    @Test fun disconnectedProbeIsSkipped() {
        val s = parse(timberline.replace("\"type\":\"btprobe\",\"con\":1", "\"type\":\"btprobe\",\"con\":0"))
        assertEquals(listOf<ProbeTemp?>(null, null, null, null), s.probes)
        assertNull(s.probeF)
    }

    @Test fun mainProbeIsFirstSlotWithAValue() {
        assertEquals(120.0, TempReading(cookId = 1, time = 0, probe3F = 120.0, probe4F = 90.0).mainProbeF!!, 0.0)
        assertNull(TempReading(cookId = 1, time = 0, grillF = 225.0).mainProbeF)
    }
}
