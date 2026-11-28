package com.kairos.xc

import org.json.JSONObject

internal object DebtNotice {
    fun fromRegister(json: JSONObject): String = json.optString("debt", "").take(160)
    fun fromHeartbeat(json: JSONObject): String = json.optString("dbt", "").take(160)
}
