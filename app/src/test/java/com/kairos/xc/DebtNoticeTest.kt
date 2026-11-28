package com.kairos.xc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DebtNoticeTest {
    @Test
    fun leggeDebitoDaRegisterEHeartbeat() {
        val text = "Pagamento in sospeso · 18 €"
        assertEquals(text, DebtNotice.fromRegister(JSONObject("""{"debt":"$text"}""")))
        assertEquals(text, DebtNotice.fromHeartbeat(JSONObject("""{"dbt":"$text"}""")))
    }

    @Test
    fun campoAssentePulisceIlDebito() {
        assertEquals("", DebtNotice.fromRegister(JSONObject()))
        assertEquals("", DebtNotice.fromHeartbeat(JSONObject()))
    }
}
