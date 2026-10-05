package com.example.bydbattery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Диагностический блок: адрес запроса и адрес ответа (11 бит). */
data class Ecu(val req: Int, val resp: Int, val name: String, val info: String = "") {
    val short: String get() = "%03X".format(req)
    val label: String get() = "%s  (%03X → %03X)".format(name, req, resp)
}

object EcuStore {
    private const val KEY = "ecus"

    fun load(ctx: Context): MutableList<Ecu> {
        val s = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(KEY, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Ecu(o.getInt("req"), o.getInt("resp"), o.optString("name"), o.optString("info"))
            }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun save(ctx: Context, list: List<Ecu>) {
        val arr = JSONArray()
        for (e in list) arr.put(JSONObject().put("req", e.req).put("resp", e.resp).put("name", e.name).put("info", e.info))
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}

/** Идентификационные параметры UDS. */
val IDENT_DIDS = listOf(
    "F197" to "Название системы",
    "F187" to "Номер детали",
    "F18A" to "Поставщик",
    "F18C" to "Серийный номер",
    "F190" to "VIN",
    "F191" to "Версия железа",
    "F193" to "Версия железа поставщика",
    "F195" to "Версия ПО",
)

fun asciiOrHex(d: IntArray): String {
    val trimmed = d.toList().dropLastWhile { it == 0x00 || it == 0xFF || it == 0xAA || it == 0x20 }
    val printable = trimmed.isNotEmpty() && trimmed.all { it in 0x20..0x7E }
    return if (printable) trimmed.map { it.toChar() }.joinToString("")
    else d.joinToString(" ") { "%02X".format(it) }
}

/** Код неисправности. */
data class Dtc(val code: String, val status: Int) {
    val active get() = (status and 0x01) != 0
    val pending get() = (status and 0x04) != 0
    val confirmed get() = (status and 0x08) != 0
    val lamp get() = (status and 0x80) != 0

    val statusText: String get() {
        val p = mutableListOf<String>()
        if (active) p += "активна сейчас"
        if (confirmed) p += "подтверждена"
        if (pending) p += "ожидает подтверждения"
        if (lamp) p += "горит индикатор"
        if (p.isEmpty()) p += "сохранена в истории"
        return p.joinToString(", ") + "  (статус %02X)".format(status)
    }
}

object Dtcs {
    fun code(b1: Int, b2: Int, b3: Int): String {
        val sys = "PCBU"[(b1 shr 6) and 3]
        val d1 = (b1 shr 4) and 3
        return "%c%d%X%X%X-%02X".format(sys, d1, b1 and 0xF, (b2 shr 4) and 0xF, b2 and 0xF, b3)
    }

    /** data — ответ на 19 02 после байта 59: [02, маска, (3 байта кода + статус)…]. */
    fun parse(data: IntArray): List<Dtc> {
        val list = mutableListOf<Dtc>()
        var i = 2
        while (i + 3 < data.size) {
            val rec = data.copyOfRange(i, i + 4)
            val padding = rec.all { it == 0xAA } || rec.all { it == 0x55 } || (rec[0] == 0 && rec[1] == 0 && rec[2] == 0)
            if (!padding) list += Dtc(code(data[i], data[i + 1], data[i + 2]), data[i + 3])
            i += 4
        }
        return list
    }
}

/** Стандартные параметры OBD-II (сервис 01). */
object ObdPids {
    private fun u16(d: IntArray) = d[0] * 256 + d[1]

    val KNOWN: Map<Int, Pair<String, (IntArray) -> String?>> = linkedMapOf(
        0x01 to ("Индикатор неисправности / кол-во ошибок" to { d: IntArray ->
            if (d.isNotEmpty()) "${if ((d[0] and 0x80) != 0) "горит" else "не горит"}, ошибок: ${d[0] and 0x7F}" else null }),
        0x05 to ("Температура охлаждающей жидкости" to { d: IntArray -> d.getOrNull(0)?.let { "${it - 40} °C" } }),
        0x0C to ("Обороты" to { d: IntArray -> if (d.size >= 2) "%.0f об/мин".format(u16(d) / 4.0) else null }),
        0x0D to ("Скорость" to { d: IntArray -> d.getOrNull(0)?.let { "$it км/ч" } }),
        0x1F to ("Время работы с запуска" to { d: IntArray -> if (d.size >= 2) "${u16(d)} с" else null }),
        0x21 to ("Пробег с горящим индикатором" to { d: IntArray -> if (d.size >= 2) "${u16(d)} км" else null }),
        0x31 to ("Пробег после сброса ошибок" to { d: IntArray -> if (d.size >= 2) "${u16(d)} км" else null }),
        0x42 to ("Напряжение блока (12 В сеть)" to { d: IntArray -> if (d.size >= 2) "%.2f В".format(u16(d) / 1000.0) else null }),
        0x46 to ("Температура снаружи" to { d: IntArray -> d.getOrNull(0)?.let { "${it - 40} °C" } }),
        0x5B to ("Заряд тяговой батареи" to { d: IntArray -> d.getOrNull(0)?.let { "%.1f %%".format(it * 100 / 255.0) } }),
        0xA6 to ("Одометр" to { d: IntArray ->
            if (d.size >= 4) "%.1f км".format(((d[0].toLong() shl 24) or (d[1].toLong() shl 16) or (d[2].toLong() shl 8) or d[3].toLong()) / 10.0) else null }),
    )
}


/** Названия блоков, опознанных по данным BYD e2. */
object EcuNames {
    private val KNOWN = mapOf(
        0x7E7 to "BMS (батарея)",
        0x782 to "IPB: тормоза, ABS/ESP",
        0x783 to "Усилитель руля (вероятно)",
        0x7E0 to "EL22: VCU, силовой блок (вероятно)",
        0x7E2 to "EL22: 2-й адрес 7E0",
        0x7E3 to "Инвертор / мотор (вероятно)",
        0x7E4 to "Зарядка / DC-DC (вероятно)",
        0x793 to "Зарядка / DC-DC, 2-й адрес (вероятно)",
        0x720 to "Кузовной блок (вероятно)",
        0x7F1 to "SEC30-P50",
    )

    fun of(req: Int, reported: String): String {
        val known = KNOWN[req]
        return when {
            known != null -> known
            reported.isNotBlank() -> reported
            else -> "Блок %03X".format(req)
        }
    }

    fun apply(ecu: Ecu): Ecu {
        val generic = ecu.name.isBlank() || ecu.name.startsWith("Блок ")
        return if (KNOWN.containsKey(ecu.req) || generic) ecu.copy(name = of(ecu.req, if (generic) "" else ecu.name)) else ecu
    }
}

/** Ищет в версии ПО/железа дату вида ГГ ММ ДД (BYD: 2-й или 3-й байт). */
fun versionDate(d: IntArray): String? {
    for (i in 0..3) {
        if (i + 2 >= d.size) break
        val y = d[i]; val m = d[i + 1]; val day = d[i + 2]
        if (y in 0x12..0x20 && m in 1..12 && day in 1..31) return "%02d.%02d.20%02d".format(day, m, y)
    }
    return null
}

private val HEX_LIST = Regex("^([0-9A-F]{2}( |$))+$")

/** Добавляет к строкам «Версия …: XX XX …» расшифрованную дату. */
fun decorateInfo(info: String): String = info.lines().joinToString("\n") { line ->
    val parts = line.split(": ", limit = 2)
    if (parts.size == 2 && parts[0].startsWith("Версия") && HEX_LIST.matches(parts[1].trim())) {
        val bytes = parts[1].trim().split(" ").map { it.toInt(16) }.toIntArray()
        versionDate(bytes)?.let { "$line  (дата $it)" } ?: line
    } else line
}
