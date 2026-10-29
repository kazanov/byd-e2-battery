package com.example.bydbattery

import kotlin.math.max

/** Positive power means energy entering the battery. elapsedMs uses a monotonic clock. */
data class ChargePoint(
    val elapsedMs: Long,
    val soc: Double?,
    val powerKw: Double?,
    val tempC: Double?,
    val dvMv: Double?,
    val linked: Boolean
)

data class ChargeSession(
    val id: String,
    val title: String,
    val startedAt: Long,
    var endedAt: Long? = null,
    var endReason: String? = null,
    val points: MutableList<ChargePoint> = mutableListOf(),
    var durationMs: Long? = null
)

data class ChargeSummary(val energyKwh: Double, val coveredMs: Long, val gapMs: Long, val peakKw: Double) {
    val averageKw: Double? get() = if (coveredMs > 0) energyKwh * 3_600_000 / coveredMs else null
}

object ChargeMath {
    const val MAX_GAP_MS = 15_000L

    /** Integral of the positive part of a linear power segment, including a zero crossing. */
    fun enteringEnergy(a: Double, b: Double, dtMs: Long): Double {
        val area = when {
            a >= 0 && b >= 0 -> (a + b) / 2
            a <= 0 && b <= 0 -> 0.0
            a > 0 -> a * a / (2 * (a - b))
            else -> b * b / (2 * (b - a))
        }
        return area * dtMs / 3_600_000.0
    }

    fun summarize(points: List<ChargePoint>, durationMs: Long? = null): ChargeSummary {
        var energy = 0.0
        var covered = 0L
        var gaps = points.firstOrNull()?.elapsedMs?.coerceAtLeast(0) ?: 0L
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            val dt = b.elapsedMs - a.elapsedMs
            if (dt <= 0) continue
            if (b.linked && dt <= MAX_GAP_MS && a.powerKw != null && b.powerKw != null) {
                energy += enteringEnergy(a.powerKw, b.powerKw, dt)
                covered += dt
            } else gaps += dt
        }
        gaps += ((durationMs ?: 0L) - (points.lastOrNull()?.elapsedMs ?: 0L)).coerceAtLeast(0)
        return ChargeSummary(energy, covered, gaps, max(0.0, points.mapNotNull { it.powerKw }.maxOrNull() ?: 0.0))
    }
}

data class SnapshotReading(val did: String, val hex: String?, val decoded: String?, val error: String?)
data class ParameterSnapshot(
    val id: String,
    val title: String,
    val requestId: Int,
    val responseId: Int,
    val startedAt: Long,
    val endedAt: Long,
    val partial: Boolean,
    val readings: List<SnapshotReading>
)

object SnapshotDiff {
    fun compare(a: ParameterSnapshot, b: ParameterSnapshot): String {
        require(a.requestId == b.requestId && a.responseId == b.responseId) { "Выберите снимки одного блока (адреса запроса и ответа должны совпадать)." }
        val left = a.readings.associateBy { it.did }
        val right = b.readings.associateBy { it.did }
        var changed = 0; var same = 0; var unavailable = 0
        val lines = StringBuilder()
        for (did in (left.keys + right.keys).sorted()) {
            val x = left[did]; val y = right[did]
            if (x?.hex == null || y?.hex == null) {
                unavailable++
                lines.append("$did: сравнение недоступно\n  A: ${x?.hex ?: x?.error ?: "не опрашивался"}\n  B: ${y?.hex ?: y?.error ?: "не опрашивался"}\n\n")
            } else if (x.hex == y.hex) {
                same++
            } else {
                changed++
                lines.append("$did: изменился\n  A: ${x.decoded ?: x.hex}\n  B: ${y.decoded ?: y.hex}\n")
                if (x.decoded != null || y.decoded != null) lines.append("  Байты A: ${x.hex}\n  Байты B: ${y.hex}\n")
                val old = x.hex.split(' '); val new = y.hex.split(' ')
                val positions = (0 until max(old.size, new.size)).filter { old.getOrNull(it) != new.getOrNull(it) }
                lines.append("  Изменены позиции (с 0): ${positions.joinToString(", ")}\n\n")
            }
        }
        return "A: ${a.title}\nB: ${b.title}\n" + "Блок %03X → %03X\n".format(a.requestId, a.responseId) +
            "Изменилось: $changed; без изменений: $same; недоступно: $unavailable\n" +
            (if (a.partial || b.partial) "Внимание: есть незавершённый снимок.\n" else "") +
            "Значения читаются последовательно; снимок не является одновременным измерением.\n\n" + lines
    }
}
