package com.example.bydbattery

/**
 * Параметры батареи. Запросы идут в BMS по адресу из поля "Адрес BMS" (по умолчанию 7E7).
 * d[0] — первый байт данных после "62 XX XX".
 *
 * Идентификаторы и формулы взяты из открытых профилей WiCAN для BYD на e-Platform 3.0
 * (Atto 3, Dolphin, Seal). Для BYD e2 они не проверены — смотрите сырые ответы в логе.
 */
data class Param(
    val key: String,
    val title: String,
    val did: String,
    val unit: String,
    val decimals: Int,
    val decode: (IntArray) -> Double?
)

private fun le16(d: IntArray, i: Int): Int? =
    if (d.size >= i + 2) d[i + 1] * 256 + d[i] else null

val PARAMS = listOf(
    Param("soc_d", "Заряд (как на приборке)", "0005", "%", 0) { d -> d.getOrNull(0)?.toDouble() },
    Param("soc", "Заряд BMS (точный)", "1FFC", "%", 2) { d -> le16(d, 0)?.let { it / 100.0 } },
    Param("cap", "Ёмкость батареи", "1FFE", "А·ч", 1) { d -> le16(d, 2)?.let { it / 100.0 } },
    Param("volt", "Напряжение ВВБ", "0008", "В", 0) { d -> le16(d, 0)?.toDouble() },
    Param("amp", "Ток ВВБ", "0009", "А", 1) { d -> le16(d, 0)?.let { it * 0.1 - 500 } },
    Param("temp", "Температура ВВБ", "0032", "°C", 0) { d -> d.getOrNull(0)?.let { it - 40.0 } },
)
