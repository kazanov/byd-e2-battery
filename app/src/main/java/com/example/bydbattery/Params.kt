package com.example.bydbattery

/**
 * Параметры батареи. Запросы идут в BMS по адресу из поля "Адрес BMS" (по умолчанию 7E7).
 * d[0] — первый байт данных после "62 XX XX".
 * Проверено на BYD e2: адрес 7E7, все запросы ниже отвечают.
 */
data class Param(
    val key: String,
    val title: String,
    val did: String,
    val unit: String,
    val decimals: Int,
    val decode: (IntArray) -> Double?
)

fun le16(d: IntArray, i: Int): Int? =
    if (d.size >= i + 2) d[i + 1] * 256 + d[i] else null

val PARAMS = listOf(
    Param("soc_d", "Заряд (как на приборке)", "0005", "%", 0) { d -> d.getOrNull(0)?.toDouble() },
    Param("soc", "Заряд BMS (точный)", "1FFC", "%", 2) { d -> le16(d, 0)?.let { it / 100.0 } },
    Param("cap_act", "Ёмкость фактическая (гипотеза)", "1FFC", "А·ч", 2) { d -> le16(d, 2)?.let { it / 100.0 } },
    Param("cap", "Ёмкость номинальная (?)", "1FFE", "А·ч", 2) { d -> le16(d, 2)?.let { it / 100.0 } },
    Param("unk_1ffe", "Неизвестное число из 1FFE", "1FFE", "", 0) { d -> le16(d, 0)?.toDouble() },
    Param("volt", "Напряжение ВВБ", "0008", "В", 0) { d -> le16(d, 0)?.toDouble() },
    Param("amp", "Ток ВВБ", "0009", "А", 1) { d -> le16(d, 0)?.let { it * 0.1 - 500 } },
    Param("temp", "Температура ВВБ", "0032", "°C", 0) { d -> d.getOrNull(0)?.let { it - 40.0 } },
)

/** Подсказки для сканера: на что похож блок данных. */
object Hints {
    fun of(d: IntArray): String {
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
