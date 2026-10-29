package com.example.bydbattery

import android.util.AtomicFile
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Accessed only by the existing serial worker. No Bluetooth or file I/O on the UI thread. */
class HistoryStore(private val dir: File) {
    private var active: ChargeSession? = null
    private var startMono = 0L
    private var linked = false
    private var checkpointMono = 0L
    private var initialized = false
    var problem: String? = null; private set

    private fun initialize() {
        if (initialized) return
        check(dir.isDirectory || dir.mkdirs()) { "Не удалось создать папку истории" }
        // A process restart never silently resumes an old charge session.
        for (f in files("charge_")) {
            val s = readCharge(readJson(f)) ?: continue
            if (s.endedAt == null) {
                s.endedAt = s.startedAt + (s.points.lastOrNull()?.elapsedMs ?: 0)
                s.durationMs = s.points.lastOrNull()?.elapsedMs ?: 0L
                s.endReason = "Приложение было закрыто; восстановлена последняя сохранённая точка"
                writeCharge(s)
            }
        }
        initialized = true
    }

    private fun files(prefix: String) = dir.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".json") }
        ?.sortedByDescending { it.lastModified() }.orEmpty()

    private fun readJson(file: File): JSONObject? = try {
        JSONObject(AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
    } catch (ex: Exception) {
        problem = "Не удалось прочитать ${file.name}: ${ex.message}"
        null
    }

    private fun write(name: String, json: JSONObject) {
        val file = AtomicFile(File(dir, name))
        val stream = file.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (ex: Exception) {
            file.failWrite(stream)
            throw ex
        }
    }

    fun start(title: String, wall: Long, mono: Long) {
        initialize()
        check(active == null) { "Сначала завершите текущую зарядку" }
        val s = ChargeSession(UUID.randomUUID().toString(), title, wall)
        writeCharge(s)
        active = s; startMono = mono; checkpointMono = mono; linked = false
    }

    fun pause() { linked = false }

    fun add(point: ChargePoint, mono: Long): Boolean {
        val session = active ?: return false
        session.points += point.copy(elapsedMs = mono - startMono, linked = linked)
        linked = point.powerKw != null
        if (session.points.size >= 25_000) {
            stop(session.startedAt + mono - startMono, "Достигнут предел 25 000 измерений", mono)
        } else if (mono - checkpointMono >= 30_000) {
            writeCharge(session); checkpointMono = mono
        }
        return true
    }

    fun stop(wall: Long, reason: String = "Завершено пользователем", mono: Long = SystemClock.elapsedRealtime()) {
        val s = active ?: return
        s.endedAt = wall; s.endReason = reason; s.durationMs = (mono - startMono).coerceAtLeast(0)
        try { writeCharge(s) } catch (ex: Exception) {
            s.endedAt = null; s.endReason = null; s.durationMs = null
            throw ex
        }
        active = null; linked = false
    }

    fun checkpoint() { active?.let { writeCharge(it) } }
    fun current(): ChargeSession? = active

    fun charges(): List<ChargeSession> {
        initialize()
        return (files("charge_").mapNotNull { f -> readCharge(readJson(f)) }.filter { it.id != active?.id } + listOfNotNull(active))
            .sortedByDescending { it.startedAt }
    }

    fun snapshots(): List<ParameterSnapshot> {
        initialize()
        return files("snapshot_").mapNotNull { f -> readSnapshot(readJson(f)) }.sortedByDescending { it.startedAt }
    }

    fun saveSnapshot(s: ParameterSnapshot) { initialize(); write("snapshot_${s.id}.json", snapshotJson(s)) }
    fun deleteCharge(id: String) { check(active?.id != id); AtomicFile(File(dir, "charge_$id.json")).delete() }
    fun deleteSnapshot(id: String) { AtomicFile(File(dir, "snapshot_$id.json")).delete() }

    private fun writeCharge(s: ChargeSession) = write("charge_${s.id}.json", chargeJson(s))

    private fun nullable(o: JSONObject, key: String): Double? = if (o.isNull(key)) null else o.getDouble(key).takeIf { it.isFinite() }
    private fun value(v: Any?): Any = v ?: JSONObject.NULL

    fun chargeJson(s: ChargeSession): JSONObject = JSONObject().put("schema", 1).put("id", s.id).put("title", s.title)
        .put("startedAt", s.startedAt).put("endedAt", value(s.endedAt)).put("endReason", value(s.endReason))
        .put("durationMs", value(s.durationMs))
        .put("points", JSONArray().apply {
            s.points.forEach { p -> put(JSONObject().put("ms", p.elapsedMs).put("soc", value(p.soc))
                .put("powerKw", value(p.powerKw)).put("tempC", value(p.tempC)).put("dvMv", value(p.dvMv)).put("linked", p.linked)) }
        })

    internal fun readCharge(o: JSONObject?): ChargeSession? {
        if (o == null) return null
        return try {
        require(o.getInt("schema") == 1)
        ChargeSession(o.getString("id"), o.getString("title"), o.getLong("startedAt"),
            if (o.isNull("endedAt")) null else o.getLong("endedAt"), if (o.isNull("endReason")) null else o.getString("endReason"),
            mutableListOf<ChargePoint>().apply {
                val points = o.getJSONArray("points")
                for (i in 0 until points.length()) { val p = points.getJSONObject(i)
                    add(ChargePoint(p.getLong("ms"), nullable(p, "soc"), nullable(p, "powerKw"), nullable(p, "tempC"), nullable(p, "dvMv"), p.getBoolean("linked")))
                }
            }, if (o.isNull("durationMs")) null else o.getLong("durationMs"))
    } catch (ex: Exception) { problem = "Повреждена запись зарядки: ${ex.message}"; null }
    }

    fun snapshotJson(s: ParameterSnapshot): JSONObject = JSONObject().put("schema", 1).put("id", s.id).put("title", s.title)
        .put("requestId", s.requestId).put("responseId", s.responseId).put("startedAt", s.startedAt).put("endedAt", s.endedAt)
        .put("partial", s.partial).put("readings", JSONArray().apply {
            s.readings.forEach { r -> put(JSONObject().put("did", r.did).put("hex", value(r.hex)).put("decoded", value(r.decoded)).put("error", value(r.error))) }
        })

    internal fun readSnapshot(o: JSONObject?): ParameterSnapshot? {
        if (o == null) return null
        return try {
        require(o.getInt("schema") == 1)
        ParameterSnapshot(o.getString("id"), o.getString("title"), o.getInt("requestId"), o.getInt("responseId"),
            o.getLong("startedAt"), o.getLong("endedAt"), o.getBoolean("partial"), buildList {
                val arr = o.getJSONArray("readings")
                for (i in 0 until arr.length()) { val r = arr.getJSONObject(i)
                    add(SnapshotReading(r.getString("did"), if (r.isNull("hex")) null else r.getString("hex"),
                        if (r.isNull("decoded")) null else r.getString("decoded"), if (r.isNull("error")) null else r.getString("error")))
                }
            })
    } catch (ex: Exception) { problem = "Повреждён снимок: ${ex.message}"; null }
    }
}
