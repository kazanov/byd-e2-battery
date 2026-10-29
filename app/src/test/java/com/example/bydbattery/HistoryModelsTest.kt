package com.example.bydbattery

import org.junit.Assert.*
import org.junit.Test

class HistoryModelsTest {
    private fun point(ms: Long, power: Double?, linked: Boolean = true) = ChargePoint(ms, 50.0, power, 20.0, 5.0, linked)

    @Test fun constantChargePowerUsesHoursNotSeconds() {
        val points = (0..360).map { point(it * 10_000L, 7.0) }
        val summary = ChargeMath.summarize(points)
        assertEquals(7.0, summary.energyKwh, 1e-9)
        assertEquals(7.0, summary.averageKw!!, 1e-9)
        assertEquals(3_600_000L, summary.coveredMs)
    }

    @Test fun signCrossingCountsOnlyIncomingEnergy() {
        assertEquals(0.5, ChargeMath.enteringEnergy(2.0, -2.0, 3_600_000), 1e-9)
        assertEquals(0.5, ChargeMath.enteringEnergy(-2.0, 2.0, 3_600_000), 1e-9)
        assertEquals(0.0, ChargeMath.enteringEnergy(-4.0, -2.0, 3_600_000), 1e-9)
    }

    @Test fun scanOrConnectionBreakDoesNotInventEnergy() {
        val points = listOf(point(0, 10.0), point(10_000, 10.0), point(20_000, 10.0, false),
            point(40_000, 10.0), point(50_000, null), point(60_000, 10.0), point(70_000, 10.0))
        val summary = ChargeMath.summarize(points)
        assertEquals(20_000L, summary.coveredMs)
        assertEquals(50_000L, summary.gapMs)
        assertEquals(10 * 20_000 / 3_600_000.0, summary.energyKwh, 1e-9)
    }

    @Test fun noSamplesDoNotClaimAnAverage() {
        assertNull(ChargeMath.summarize(emptyList()).averageKw)
        assertEquals(0.0, ChargeMath.summarize(listOf(point(0, -2.0))).peakKw, 0.0)
    }

    @Test fun timeBeforeFirstSampleAndAfterLastIsNotCovered() {
        val summary = ChargeMath.summarize(listOf(point(10_000, 7.0), point(20_000, 7.0)), 40_000)
        assertEquals(10_000L, summary.coveredMs)
        assertEquals(30_000L, summary.gapMs)
        assertEquals(40_000L, ChargeMath.summarize(emptyList(), 40_000).gapMs)
    }

    private fun snapshot(id: String, ecu: Int = 0x7E7, partial: Boolean = false, readings: List<SnapshotReading>) =
        ParameterSnapshot(id, id, ecu, ecu + 8, 0, 1000, partial, readings)

    @Test fun comparisonFindsByteAndLengthChangesWithoutCallingErrorsChanges() {
        val a = snapshot("before", readings = listOf(
            SnapshotReading("0005", "32", "SOC: 50 %", null), SnapshotReading("0008", "10 01", null, null),
            SnapshotReading("0009", "00", null, null), SnapshotReading("0032", "40", null, null)))
        val b = snapshot("after", partial = true, readings = listOf(
            SnapshotReading("0005", "33", "SOC: 51 %", null), SnapshotReading("0008", "10 01", null, null),
            SnapshotReading("0009", "00 01", null, null), SnapshotReading("0032", null, null, "TIMEOUT")))
        val report = SnapshotDiff.compare(a, b)
        assertTrue(report.contains("Изменилось: 2; без изменений: 1; недоступно: 1"))
        assertTrue(report.contains("Изменены позиции (с 0): 1"))
        assertTrue(report.contains("SOC: 50 %"))
        assertTrue(report.contains("незавершённый снимок"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun differentEcusCannotBeCompared() {
        SnapshotDiff.compare(snapshot("a", readings = emptyList()), snapshot("b", 0x7E4, readings = emptyList()))
    }

    @Test fun percentSignsInSnapshotNamesAreLiteral() {
        val a = snapshot("SOC 30 %", readings = emptyList())
        val b = snapshot("SOC 80 %", readings = emptyList())
        assertTrue(SnapshotDiff.compare(a, b).contains("A: SOC 30 %"))
    }
}
