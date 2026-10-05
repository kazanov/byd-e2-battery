package com.example.bydbattery

import java.io.BufferedWriter
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Запись данных BMS в CSV.
 * Формат под русский Excel: разделитель ";", десятичная запятая, UTF-8 с BOM.
 * Все методы synchronized: запись идёт из рабочего потока, старт/стоп — из UI.
 */
class Recorder(private val dir: File) {

    companion object {
        /** Ключ значения → заголовок столбца. */
        val COLUMNS: List<Pair<String, String>> = listOf(
            "soc_d" to "Заряд приборка, %",
            "soc" to "Заряд BMS, %",
            "volt" to "Напряжение, В",
            "amp" to "Ток, А (- заряд)",
            "power_kw" to "Мощность, кВт",
            "temp" to "Температура батареи, °C",
            "cmin_v" to "Мин. ячейка, В",
            "cmin_n" to "Мин. ячейка, №",
            "cmax_v" to "Макс. ячейка, В",
            "cmax_n" to "Макс. ячейка, №",
            "dv_mv" to "ΔV, мВ",
            "tmin" to "Мин. темп., °C",
            "tmin_n" to "Мин. темп., датчик",
            "tmax" to "Макс. темп., °C",
            "tmax_n" to "Макс. темп., датчик",
            "dt_c" to "ΔT, °C",
            "cap_act" to "Ёмкость факт., А·ч",
            "cap" to "Ёмкость ном., А·ч",
            "soh" to "SOH, %",
            "p0029" to "Параметр 0029",
            "ah_out" to "Отдано всего, А·ч",
            "ah_in" to "Получено всего, А·ч",
            "e_left_kwh" to "Осталось, кВт·ч",
            "e_in_kwh" to "Получено с сброса, кВт·ч",
            "e_out_kwh" to "Отдано с сброса, кВт·ч",
            "odo" to "Пробег, км (вероятно)",
            "speed" to "Скорость, км/ч",
            "v12_adapter" to "12 В по адаптеру",
            "mcu_v" to "Инвертор: напряжение, В (вероятно)",
            "mcu_t" to "Инвертор: макс. темп., °C (вероятно)",
            "mcu_ph" to "Инвертор 1FF2: макс. темп., °C (вероятно)",
            "obc_v" to "7E4: напряжение, В (вероятно)",
            "obc_t" to "7E4: макс. темп., °C (вероятно)",
            "dcdc_v" to "793: напряжение, В (вероятно)",
            "v12a" to "793 0004: 12 В? ",
            "v12b" to "793 0005: 12 В? ",
        )
    }

    private var writer: BufferedWriter? = null
    var file: File? = null; private set
    var rows = 0; private set
    var startMs = 0L; private set
    private var intervalMs = 1000L
    private var lastWriteMs = 0L
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    val active: Boolean @Synchronized get() = writer != null

    @Synchronized
    fun start(intervalMs: Long): File {
        stop()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(dir, "byd_log_$stamp.csv")
        val w = f.bufferedWriter(Charsets.UTF_8)
        w.write("\uFEFF")
        w.write("Время;" + COLUMNS.joinToString(";") { it.second })
        w.newLine()
        w.flush()
        writer = w
        file = f
        rows = 0
        startMs = System.currentTimeMillis()
        lastWriteMs = 0L
        this.intervalMs = intervalMs
        return f
    }

    /** Пишет строку, если прошёл интервал. Возвращает true, если строка записана. */
    @Synchronized
    fun maybeWrite(now: Long, row: Map<String, Double?>): Boolean {
        val w = writer ?: return false
        if (lastWriteMs != 0L && now - lastWriteMs < intervalMs - 200) return false
        lastWriteMs = now
        val sb = StringBuilder(timeFmt.format(Date(now)))
        for ((key, _) in COLUMNS) {
            sb.append(';')
            row[key]?.let { sb.append(num(it)) }
        }
        w.write(sb.toString())
        w.newLine()
        rows++
        if (rows % 10 == 0) w.flush()
        return true
    }

    private fun num(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return ""
        return BigDecimal(v).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros()
            .toPlainString().replace('.', ',')
    }

    @Synchronized
    fun flush() {
        try { writer?.flush() } catch (_: Exception) {}
    }

    /** Закрывает файл и возвращает его (или null, если запись не шла). */
    @Synchronized
    fun stop(): File? {
        val w = writer ?: return null
        try { w.flush(); w.close() } catch (_: Exception) {}
        writer = null
        return file
    }
}
