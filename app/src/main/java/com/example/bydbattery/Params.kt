package com.example.bydbattery

/**
 * Параметры батареи BYD e2. Адрес BMS 7E7, запросы UDS 22 XXXX.
 * d[0] — первый байт данных после "62 XX XX".
 *
 * group: в какой секции показывать; null — параметр скрыт и используется в вычисляемых строках.
 */
data class Param(
    val key: String,
    val title: String,
    val did: String,
    val unit: String,
    val decimals: Int,
    val group: String?,
    val decode: (IntArray) -> Double?
)

fun le16(d: IntArray, i: Int): Int? =
    if (d.size >= i + 2) d[i + 1] * 256 + d[i] else null

fun le24(d: IntArray, i: Int): Int? =
    if (d.size >= i + 3) d[i + 2] * 65536 + d[i + 1] * 256 + d[i] else null

private fun b(d: IntArray, i: Int = 0): Double? = d.getOrNull(i)?.toDouble()

const val G_MAIN = "main"
const val G_CAP = "cap"

val PARAMS = listOf(
    // Основное
    Param("soc_d", "Заряд (как на приборке)", "0005", "%", 0, G_MAIN) { d -> b(d) },
    Param("soc", "Заряд BMS (точный)", "1FFC", "%", 2, G_MAIN) { d -> le16(d, 0)?.let { it / 100.0 } },
    Param("volt", "Напряжение батареи", "0008", "В", 0, G_MAIN) { d -> le16(d, 0)?.toDouble() },
    Param("amp", "Ток (− заряд, + разряд)", "0009", "А", 1, G_MAIN) { d -> le16(d, 0)?.let { it * 0.1 - 500 } },
    Param("temp", "Температура батареи", "0032", "°C", 0, G_MAIN) { d -> b(d)?.let { it - 40 } },

    // Мин/макс ячеек и температур по всей батарее (скрытые, выводятся парами)
    Param("cmin_n", "", "002A", "", 0, null) { d -> b(d) },
    Param("cmin_v", "", "002B", "", 3, null) { d -> le16(d, 0)?.let { it / 1000.0 } },
    Param("cmax_n", "", "002C", "", 0, null) { d -> b(d) },
    Param("cmax_v", "", "002D", "", 3, null) { d -> le16(d, 0)?.let { it / 1000.0 } },
    Param("tmin_n", "", "002E", "", 0, null) { d -> b(d) },
    Param("tmin", "", "002F", "", 0, null) { d -> b(d)?.let { it - 40 } },
    Param("tmax_n", "", "0030", "", 0, null) { d -> b(d) },
    Param("tmax", "", "0031", "", 0, null) { d -> b(d)?.let { it - 40 } },

    // Ёмкость и ресурс
    Param("cap_act", "Ёмкость фактическая (вероятно)", "1FFC", "А·ч", 2, G_CAP) { d -> le16(d, 2)?.let { it / 100.0 } },
    Param("cap", "Ёмкость номинальная (вероятно)", "1FFE", "А·ч", 2, G_CAP) { d -> le16(d, 2)?.let { it / 100.0 } },
    Param("p0029", "Параметр 0029 (возможно SOH, не подтверждено)", "0029", "", 0, G_CAP) { d -> b(d) },
    Param("ah_out", "Отдано за всё время (вероятно)", "000F", "А·ч", 0, G_CAP) { d -> le24(d, 0)?.toDouble() },
    Param("ah_in", "Получено за всё время (вероятно)", "0010", "А·ч", 0, G_CAP) { d -> le24(d, 0)?.toDouble() },
)

/** Сводка по модулям: 7 групп по 8 идентификаторов начиная с 016C. */
const val MODULE_BASE = 0x016C
const val MODULE_COUNT = 7

/** Неизвестный параметр каждой ячейки: 90 идентификаторов 0040–0099. */
const val CELL_PARAM_BASE = 0x0040
const val CELL_PARAM_COUNT = 90

/** Подсказки для сканера: на что похож блок данных. */
object Hints {
    fun of(d: IntArray): String {
        val trimmed = d.toList().dropLastWhile { it == 0x20 || it == 0x00 || it == 0xFF }
        if (trimmed.size >= 3 && trimmed.all { it in 0x20..0x7E }) {
            return "   <-- текст: \"" + trimmed.map { it.toChar() }.joinToString("") + "\""
        }
        val hints = mutableListOf<String>()
        if (d.size >= 16) {
            for (offset in 0..1) {
                val idx = (offset until d.size - 1 step 2).toList()
                val le = idx.map { d[it + 1] * 256 + d[it] }
                val be = idx.map { d[it] * 256 + d[it + 1] }
                for ((name, arr) in listOf("LE" to le, "BE" to be)) {
                    val mv = arr.filter { it in 2500..3800 }
                    if (mv.size >= 8 && mv.size * 2 >= arr.size) {
                        hints += "похоже на напряжения ячеек в мВ ($name, сдвиг $offset, ${mv.size} шт., ${mv.min()}–${mv.max()})"
                    }
                }
            }
        }
        if (d.size >= 4) {
            val t = d.filter { it in 30..100 }
            if (t.size >= 4 && t.size * 10 >= d.size * 6) {
                hints += "возможно температуры (байт−40: ${t.min() - 40}…${t.max() - 40} °C)"
            }
        }
        return if (hints.isEmpty()) "" else "   <-- " + hints.distinct().joinToString("; ")
    }
}
