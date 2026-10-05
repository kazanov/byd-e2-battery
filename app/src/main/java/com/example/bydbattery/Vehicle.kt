package com.example.bydbattery

import android.content.Context

/**
 * Параметры других блоков BYD e2, расшифрованные по скану всех блоков.
 * Все помечены как вероятные: проверены по одному снимку на стоящей машине.
 */
data class VValue(val text: String, val num: Double?)

class VParam(
    val key: String,
    val ecu: Int,
    /** Полные запросы: "22XXXX" (UDS) или "01XX" (OBD-II, адресно). */
    val requests: List<String>,
    val decode: (List<IntArray?>) -> VValue?
)

private fun temps(list: List<IntArray?>): VValue? {
    val t = list.mapNotNull { it?.getOrNull(0)?.let { v -> v - 40 } }
    if (t.isEmpty()) return null
    return VValue(t.joinToString(" / ") + " °C", t.max().toDouble())
}

private fun volts16(r: List<IntArray?>): VValue? =
    r.getOrNull(0)?.let { le16(it, 0) }?.let { VValue("$it В", it.toDouble()) }

private fun tenth(r: List<IntArray?>): VValue? =
    r.getOrNull(0)?.getOrNull(0)?.let { VValue("%.1f В".format(it / 10.0), it / 10.0) }

val VPARAMS = listOf(
    VParam("odo", 0x7E0, listOf("22001B")) { r ->
        r[0]?.let { le24(it, 0) }?.let { VValue("%.1f км".format(it / 10.0), it / 10.0) }
    },
    VParam("speed", 0x7E0, listOf("010D")) { r ->
        r[0]?.getOrNull(1)?.let { VValue("$it км/ч", it.toDouble()) }
    },
    VParam("mcu_v", 0x7E3, listOf("220009")) { r -> volts16(r) },
    VParam("mcu_t", 0x7E3, listOf("22000E", "22000F", "220010")) { r -> temps(r) },
    VParam("mcu_ph", 0x7E3, listOf("221FF2")) { r ->
        r[0]?.let { a -> temps(listOf(0, 2, 4).mapNotNull { i -> a.getOrNull(i)?.let { intArrayOf(it) } }) }
    },
    VParam("obc_v", 0x7E4, listOf("220004")) { r -> volts16(r) },
    VParam("obc_t", 0x7E4, listOf("220013", "220014", "220015", "220016", "220017")) { r -> temps(r) },
    VParam("dcdc_v", 0x793, listOf("22000A")) { r -> volts16(r) },
    VParam("v12a", 0x793, listOf("220004")) { r -> tenth(r) },
    VParam("v12b", 0x793, listOf("220005")) { r -> tenth(r) },
)

/**
 * Идентификаторы, ответившие при скане всех блоков (кроме служебных F1xx и длинных массивов).
 * Используются для «Наблюдения»: какие значения меняются при езде или зарядке.
 */
val WATCH_DEFAULTS: Map<Int, List<Int>> = mapOf(
    0x7E7 to listOf(0x0004, 0x0006, 0x0007, 0x000A, 0x000B, 0x000C, 0x000D, 0x000E, 0x0011, 0x0012, 0x0013, 0x0014, 0x0015, 0x0016, 0x0017, 0x0018, 0x0019, 0x001A, 0x001B, 0x001C, 0x001D, 0x001E, 0x001F, 0x0020, 0x0021, 0x0022, 0x0023, 0x0024, 0x0025, 0x0026, 0x0027, 0x0028, 0x0029, 0x0033, 0x0034, 0x0035, 0x0036, 0x0037, 0x1FEF, 0x1FF0, 0x1FF3, 0x1FF4, 0x1FF5, 0x1FF6, 0x1FF7, 0x1FF8, 0x1FFB, 0x1FFF),
    0x711 to listOf(0x0004, 0x0005),
    0x715 to listOf(0x000A, 0x000B, 0x000C, 0x000D),
    0x720 to listOf(0x000A),
    0x723 to listOf(0x0001, 0x0002, 0x0003, 0x0004, 0x0005, 0x1FF0),
    0x725 to listOf(0x0003, 0x0004, 0x0005),
    0x793 to listOf(0x0001, 0x0002, 0x0003, 0x0004, 0x0005, 0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x1FF0),
    0x794 to listOf(0x0001, 0x0002, 0x0003, 0x0004, 0x0005, 0x0006, 0x0007),
    0x7B3 to listOf(0x0006, 0x0007, 0x0008, 0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x000F, 0x0010, 0x0011),
    0x7D6 to listOf(0x000A, 0x000B, 0x000C, 0x000D),
    0x7E0 to listOf(0x0001, 0x0002, 0x0003, 0x0004, 0x0005, 0x0006, 0x0007, 0x0008, 0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x000E, 0x000F, 0x0010, 0x0011, 0x0012, 0x0013, 0x0014, 0x0015, 0x0016, 0x0017, 0x0018, 0x0019, 0x001A, 0x001B, 0x001C, 0x001D, 0x001E, 0x001F, 0x0020, 0x0021, 0x0022, 0x0023, 0x0026, 0x00D4, 0x00D9, 0x00DA, 0x00DB),
    0x7E3 to listOf(0x0003, 0x0004, 0x0008, 0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x000E, 0x000F, 0x0010, 0x0011, 0x0012, 0x0013, 0x0014, 0x0016, 0x0017, 0x0019, 0x0022, 0x0024, 0x0025, 0x0026, 0x1FF1, 0x1FF2, 0x1FF4),
    0x7E4 to listOf(0x0001, 0x0002, 0x0003, 0x0004, 0x0005, 0x0006, 0x0007, 0x0008, 0x0009, 0x000A, 0x000B, 0x000D, 0x0010, 0x0011, 0x0012, 0x0013, 0x0014, 0x0015, 0x0016, 0x0017, 0x0018, 0x0019, 0x001A, 0x001B, 0x001C, 0x001D, 0x001F, 0x0020, 0x1FF0),
    0x7F1 to listOf(0x0001, 0x0004, 0x0005, 0x000D, 0x0016, 0x0017, 0x001F, 0x0021),
)

/** Списки для наблюдения: сохранённые после сканов, иначе встроенные. */
object WatchStore {
    private fun key(req: Int) = "watch_%03X".format(req)

    fun get(ctx: Context, req: Int): List<Int> {
        val s = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(key(req), null)
        val saved = s?.split(',')?.mapNotNull { it.trim().toIntOrNull(16) }.orEmpty()
        return if (saved.isNotEmpty()) saved else WATCH_DEFAULTS[req].orEmpty()
    }

    fun put(ctx: Context, req: Int, dids: List<Int>) {
        if (dids.isEmpty()) return
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString(key(req), dids.joinToString(",") { "%04X".format(it) }).apply()
    }

    /** Отбор идентификаторов из результатов скана: без служебных F1xx и длинных массивов. */
    fun filter(hits: List<Pair<Int, Int>>): List<Int> = hits.filter { (did, len) -> did < 0xF100 && len <= 40 }.map { it.first }
}
