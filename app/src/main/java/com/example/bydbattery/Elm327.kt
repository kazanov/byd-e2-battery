package com.example.bydbattery

import android.bluetooth.BluetoothSocket
import java.io.IOException

/** Простой синхронный клиент ELM327 поверх Bluetooth SPP. */
class Elm327(private val socket: BluetoothSocket) {

    private val input = socket.inputStream
    private val output = socket.outputStream

    /** Отправляет команду и ждёт приглашения '>'. Возвращает ответ без эха и мусора. */
    @Synchronized
    fun send(cmd: String, timeoutMs: Long = 3000): String {
        while (input.available() > 0) input.read() // сбрасываем остатки прошлых ответов

        output.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        output.flush()

        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val c = input.read()
                if (c < 0) throw IOException("Адаптер закрыл соединение")
                val ch = c.toChar()
                if (ch == '>') return clean(sb.toString(), cmd)
                if (ch != '\u0000') sb.append(ch)
            } else {
                Thread.sleep(10)
            }
        }
        val partial = clean(sb.toString(), cmd)
        return if (partial.isEmpty()) "TIMEOUT" else "$partial\nTIMEOUT"
    }

    private fun clean(raw: String, cmd: String): String {
        val lines = raw.replace('\r', '\n').split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()
        if (lines.isNotEmpty() && lines[0].replace(" ", "").equals(cmd.replace(" ", ""), true)) {
            lines.removeAt(0)
        }
        return lines.joinToString("\n")
    }

    fun close() {
        try { input.close() } catch (_: Exception) {}
        try { output.close() } catch (_: Exception) {}
        try { socket.close() } catch (_: Exception) {}
    }
}

/** Результат разбора ответа UDS. */
sealed class UdsResult {
    class Ok(val data: IntArray, val rawHex: String) : UdsResult()
    class Error(val message: String, val nrc: Int? = null) : UdsResult()
}

object Uds {
    private val HEX = Regex("^[0-9A-Fa-f]+$")

    /** Байты ответа при ATH0/ATCAF1 (однокадровый или "0: … 1: …"), либо текст ошибки адаптера. */
    private fun bytesOf(raw: String): Pair<List<Int>?, String> {
        val lines = raw.split('\n').map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("SEARCHING", true) && !it.equals("OK", true) }
            // "7F xx 78" = ЭБУ просит подождать, настоящий ответ идёт следующей строкой
            .filter { val h = it.replace(" ", "").uppercase(); !(h.length == 6 && h.startsWith("7F") && h.endsWith("78")) }
        if (lines.isEmpty()) return null to "пустой ответ"

        val multiFrame = lines.any { it.contains(':') }
        val hex = StringBuilder()
        for (line in lines) {
            var s = line.replace(" ", "")
            val colon = s.indexOf(':')
            if (colon in 1..2) {
                s = s.substring(colon + 1)
            } else if (multiFrame && s.length <= 3) {
                continue
            }
            if (!HEX.matches(s) || s.length % 2 != 0) return null to lines.joinToString(" ")
            hex.append(s)
        }
        val bytes = hex.chunked(2).map { it.toInt(16) }
        return if (bytes.isEmpty()) null to "нет данных" else bytes to ""
    }

    private fun pretty(bytes: List<Int>) = bytes.joinToString(" ") { "%02X".format(it) }

    private fun negative(bytes: List<Int>): UdsResult.Error {
        val nrc = bytes.getOrNull(2)
        val nrcHex = nrc?.let { "%02X".format(it) } ?: "??"
        return UdsResult.Error("отказ блока: ${nrcText(nrc)} (NRC $nrcHex)", nrc)
    }

    fun nrcText(nrc: Int?): String = when (nrc) {
        0x10 -> "общий отказ"
        0x11 -> "сервис не поддерживается"
        0x12 -> "подфункция не поддерживается"
        0x13 -> "неверная длина запроса"
        0x22 -> "условия не выполнены"
        0x31 -> "нет такого параметра"
        0x33 -> "доступ защищён"
        0x7E, 0x7F -> "недоступно в текущей сессии"
        else -> "код отказа"
    }

    /** Разбор ответа на "22 <did>". */
    fun parse(raw: String, did: String): UdsResult {
        val (bytes, err) = bytesOf(raw)
        if (bytes == null) return UdsResult.Error(err)
        if (bytes[0] == 0x7F) return negative(bytes)
        val hi = did.substring(0, 2).toInt(16)
        val lo = did.substring(2, 4).toInt(16)
        if (bytes.size < 3 || bytes[0] != 0x62 || bytes[1] != hi || bytes[2] != lo) {
            return UdsResult.Error("неожиданный ответ: ${pretty(bytes)}")
        }
        return UdsResult.Ok(bytes.drop(3).toIntArray(), pretty(bytes))
    }

    /** Разбор ответа на произвольный сервис: возвращает данные после байта (sid + 0x40). */
    fun parseService(raw: String, sid: Int): UdsResult {
        val (bytes, err) = bytesOf(raw)
        if (bytes == null) return UdsResult.Error(err)
        if (bytes[0] == 0x7F) return negative(bytes)
        if (bytes[0] != sid + 0x40) return UdsResult.Error("неожиданный ответ: ${pretty(bytes)}")
        return UdsResult.Ok(bytes.drop(1).toIntArray(), pretty(bytes))
    }
}

/** Сборка ISO-TP сообщений из строк ELM327 при включённых заголовках (ATH1). */
object CanFrames {
    fun assemble(raw: String): Map<Int, IntArray> {
        val buf = LinkedHashMap<Int, MutableList<Int>>()
        val len = HashMap<Int, Int>()
        for (line in raw.split('\n')) {
            val t = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (t.size < 2 || t[0].length != 3) continue
            val id = t[0].toIntOrNull(16) ?: continue
            val bytes = t.drop(1).mapNotNull { if (it.length == 2) it.toIntOrNull(16) else null }
            if (bytes.size != t.size - 1 || bytes.isEmpty()) continue
            val pci = bytes[0]
            when (pci shr 4) {
                0 -> {
                    val n = pci and 0xF
                    buf[id] = bytes.drop(1).take(n).toMutableList()
                    len[id] = n
                }
                1 -> {
                    if (bytes.size < 2) continue
                    len[id] = ((pci and 0xF) shl 8) or bytes[1]
                    buf[id] = bytes.drop(2).toMutableList()
                }
                2 -> buf[id]?.addAll(bytes.drop(1))
            }
        }
        return buf.mapValues { (id, l) -> l.take(len[id] ?: l.size).toIntArray() }
    }
}
