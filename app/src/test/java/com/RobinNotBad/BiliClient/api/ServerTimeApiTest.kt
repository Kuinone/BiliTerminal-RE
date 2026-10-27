package com.RobinNotBad.BiliClient.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerTimeApiTest {

    @Test
    fun parse_realResponse() {
        // 26.10.27 curl 实测原文
        val json = JSONObject("""{"code":0,"message":"0","ttl":1,"data":{"now":1793096557}}""")
        assertEquals(1_793_096_557_000L, ServerTimeApi.parseNowMillis(json))
    }

    @Test
    fun parse_rejectsBadResponses() {
        assertNull(ServerTimeApi.parseNowMillis(null))
        assertNull(ServerTimeApi.parseNowMillis(JSONObject("""{"code":-412,"data":{"now":1793096557}}""")))
        assertNull(ServerTimeApi.parseNowMillis(JSONObject("""{"code":0}""")))
        assertNull(ServerTimeApi.parseNowMillis(JSONObject("""{"code":0,"data":{}}""")))
        assertNull(ServerTimeApi.parseNowMillis(JSONObject("""{"code":0,"data":{"now":0}}""")))
    }
}
