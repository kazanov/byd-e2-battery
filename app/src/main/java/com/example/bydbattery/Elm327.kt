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
        // убираем эхо команды, если ATE0 ещё не применился
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

/** Результат разбора ответа на запрос UDS 0x22 (Read Data By Identifier). */
sealed class UdsResult {
    class Ok(val data: IntArray, val rawHex: String) : UdsResult()
    class Error(val message: String, val nrc: Int? = null) : UdsResult()
}

object Uds {
    private val HEX = Regex("^[0-9A-Fa-f]+$")

    /**
     * Разбирает ответ ELM327 (ATH0, ATCAF1) на запрос "22 <did>".
     * Поддерживает однокадровые ответы и многокадровый формат "0: ... 1: ...".
     */
    fun parse(raw: String, did: String): UdsResult {
        val lines = raw.split('\n').map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("SEARCHING", true) && !it.equals("OK", true) }
            // "7F 22 78" = ЭБУ просит подождать, настоящий ответ идёт следующей строкой
            .filter { it.replace(" ", "").uppercase() != "7F2278" }
        if (lines.isEmpty()) return UdsResult.Error("пустой ответ")

        val multiFrame = lines.any { it.contains(':') }
        val hex = StringBuilder()
        for (line in lines) {
            var s = line.replace(" ", "")
            val colon = s.indexOf(':')
            if (colon in 1..2) {
                s = s.substring(colon + 1)
            } else if (multiFrame && s.length <= 3) {
                continue // строка с длиной сообщения, например "00A"
            }
            if (!HEX.matches(s) || s.length % 2 != 0) {
                return UdsResult.Error(lines.joinToString(" "))
            }
            hex.append(s)
        }

        val bytes = hex.chunked(2).map { it.toInt(16) }
        if (bytes.isEmpty()) return UdsResult.Error("нет данных")
        val pretty = bytes.joinToString(" ") { "%02X".format(it) }

        if (bytes[0] == 0x7F) {
            val nrc = bytes.getOrNull(2)
            val nrcHex = nrc?.let { "%02X".format(it) } ?: "??"
            return UdsResult.Error("отказ ЭБУ, NRC=$nrcHex ($pretty)", nrc)
        }
        val didHi = did.substring(0, 2).toInt(16)
        val didLo = did.substring(2, 4).toInt(16)
        if (bytes.size < 3 || bytes[0] != 0x62 || bytes[1] != didHi || bytes[2] != didLo) {
            return UdsResult.Error("неожиданный ответ: $pretty")
        }
        return UdsResult.Ok(bytes.drop(3).toIntArray(), pretty)
    }
}
