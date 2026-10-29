package com.example.bydbattery

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class HistorySerializationTest {
    private val store = HistoryStore(File("unused-codec-test-directory"))

    @Test fun chargeRoundTripPreservesMissingValuesAndBreaks() {
        val session = ChargeSession("test", "Зарядка дома", 1000, 8000, "Завершено", mutableListOf(
            ChargePoint(1000, 50.25, 7.1, 23.0, 4.0, false),
            ChargePoint(4000, null, null, null, null, false)), 7000)
        val encoded = store.chargeJson(session).toString()
        assertEquals(session, store.readCharge(JSONObject(encoded)))
        assertEquals(session.id, store.readCharge(JSONObject(encoded))!!.id)
    }

    @Test fun activeSessionHasNoFakeEndTime() {
        val s = ChargeSession("active", "AC", 1000)
        assertEquals(s, store.readCharge(JSONObject(store.chargeJson(s).toString())))
    }

    @Test fun snapshotRoundTripPreservesErrorsAndRawBytes() {
        val s = ParameterSnapshot("test", "Климат включён", 0x7E7, 0x7EF, 1000, 2000, true,
            listOf(SnapshotReading("0005", "32", "Заряд: 50 %", null), SnapshotReading("0009", null, null, "TIMEOUT")))
        assertEquals(s, store.readSnapshot(JSONObject(store.snapshotJson(s).toString())))
    }

    @Test fun corruptOrUnsupportedFilesAreReported() {
        assertNull(store.readCharge(JSONObject().put("schema", 99)))
        assertNotNull(store.problem)
        assertNull(store.readSnapshot(JSONObject().put("schema", 1)))
    }
}
