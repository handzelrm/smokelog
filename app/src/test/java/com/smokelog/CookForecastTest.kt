package com.smokelog

import com.smokelog.data.CookForecast
import com.smokelog.data.CookForecast.Kind
import com.smokelog.data.CookForecast.Point
import com.smokelog.data.MeatCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

class CookForecastTest {
    private val min = 60_000L

    /** Meat following Newton's law in a [pit]°F smoker, one reading a minute. */
    private fun newton(minutes: Int, pit: Double = 250.0, k: Double = 0.35, start: Double = 40.0, set: Double? = pit) =
        (0..minutes).map { m -> Point(m * min, pit - (pit - start) * exp(-k * m / 60.0), pit + (if (m % 2 == 0) 4 else -4), set) }

    @Test fun matchesTheHeatingCurve() {
        val pts = newton(180)
        val f = CookForecast.forecast(pts, 203.0)
        assertEquals(Kind.ON_TRACK, f.kind)
        val cur = pts.last().meatF
        val trueHours = ln((250 - cur) / (250 - 203.0)) / 0.35
        val gotHours = (f.doneAt!! - pts.last().time) / 3_600_000.0
        assertTrue("got $gotHours h, expected $trueHours h", abs(gotHours - trueHours) / trueHours < 0.08)
        // The projected curve passes the goal at the predicted time.
        assertEquals(203.0, f.projectAt(f.doneAt!!)!!, 0.5)
    }

    @Test fun hotterPitFinishesSooner() {
        val slow = CookForecast.forecast(newton(120, pit = 225.0), 200.0)
        val fast = CookForecast.forecast(newton(120, pit = 300.0), 200.0)
        assertTrue(fast.minutesLeft(120 * min)!! < slow.minutesLeft(120 * min)!!)
    }

    @Test fun usesSetTempForWhatComesNext() {
        // Same plot, but the pit was just turned up from 250 to 300.
        val base = newton(120)
        val turnedUp = base.dropLast(1) + base.last().copy(setF = 300.0)
        val a = CookForecast.forecast(base, 203.0).minutesLeft(0)!!
        val b = CookForecast.forecast(turnedUp, 203.0).minutesLeft(0)!!
        assertTrue("$b < $a", b < a)
    }

    @Test fun reportsTheStall() {
        val climb = newton(240)
        val t0 = climb.last().time
        val flat = (1..60).map { Point(t0 + it * min, 161.0 + (it % 3) * 0.5, 251.0, 250.0) }
        val f = CookForecast.forecast(climb.takeWhile { it.meatF < 158 } + flat, 203.0)
        assertEquals(Kind.STALLED, f.kind)
        assertNotNull(f.stallSince)
        assertNotNull(f.afterStallMinutes)
        assertNull(f.doneAt)
    }

    @Test fun pitBelowGoal() {
        val f = CookForecast.forecast(newton(60, pit = 200.0), 203.0)
        assertEquals(Kind.PIT_TOO_LOW, f.kind)
    }

    @Test fun needsFifteenMinutes() {
        val f = CookForecast.forecast(newton(8), 203.0)
        assertEquals(Kind.NEED_MORE_DATA, f.kind)
        assertEquals(7L, f.needMinutes)
    }

    @Test fun doneWhenAtGoal() {
        assertEquals(Kind.DONE, CookForecast.forecast(listOf(Point(0, 204.0, 250.0, null)), 203.0).kind)
    }

    @Test fun straightLineWithoutPitData() {
        val pts = (0..30 step 10).map { Point(it * min, 100.0 + it, null, null) }   // 60°F/h
        val f = CookForecast.forecast(pts, 160.0)
        assertEquals(Kind.ON_TRACK, f.kind)
        assertEquals(30L, f.minutesLeft(30 * min))
    }

    @Test fun typicalDoneTemps() {
        assertEquals(203.0, MeatCatalog.typicalDoneF("Brisket")!!, 0.0)
        assertEquals(165.0, MeatCatalog.typicalDoneF("Whole turkey", "Spatchcocked")!!, 0.0)
        assertEquals(165.0, MeatCatalog.typicalDoneF("Cornish hen")!!, 0.0)
        assertNull(MeatCatalog.typicalDoneF("Beef jerky"))
    }
}
