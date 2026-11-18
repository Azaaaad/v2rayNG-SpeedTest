package com.v2ray.ang.core

import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ThroughputTestConfigTest {

    @Test
    fun replacesEveryInboundWithOneLoopbackHttpInbound() {
        val config = """
            {"inbounds":[{"tag":"socks","port":10808,"protocol":"socks"},{"tag":"tun","protocol":"tun"}],
             "outbounds":[{"tag":"proxy","protocol":"vless"}]}
        """.trimIndent()

        val result = requireNotNull(CoreConfigManager.withLoopbackHttpInbound(config, 18080))

        val root = JsonParser.parseString(result).asJsonObject
        val inbounds = root.getAsJsonArray("inbounds")
        assertEquals(1, inbounds.size())
        val inbound = inbounds[0].asJsonObject
        assertEquals(CoreConfigManager.THROUGHPUT_TEST_INBOUND_TAG, inbound["tag"].asString)
        assertEquals(AppConfig.LOOPBACK, inbound["listen"].asString)
        assertEquals(18080, inbound["port"].asInt)
        assertEquals("http", inbound["protocol"].asString)
        assertEquals("proxy", root.getAsJsonArray("outbounds")[0].asJsonObject["tag"].asString)
    }

    @Test
    fun addsInboundWhenConfigHasNone() {
        val result = requireNotNull(CoreConfigManager.withLoopbackHttpInbound("""{"outbounds":[]}""", 1))

        assertEquals(1, JsonParser.parseString(result).asJsonObject.getAsJsonArray("inbounds").size())
    }

    @Test
    fun acceptsCustomConfigWithComments() {
        val config = """
            {
              // custom profiles may carry comments that Xray accepts
              "inbounds": [],
              "outbounds": [{"tag": "proxy"}]
            }
        """.trimIndent()

        val result = requireNotNull(CoreConfigManager.withLoopbackHttpInbound(config, 65535))

        assertEquals(65535, JsonParser.parseString(result).asJsonObject.getAsJsonArray("inbounds")[0].asJsonObject["port"].asInt)
    }

    @Test
    fun rejectsInvalidPort() {
        assertNull(CoreConfigManager.withLoopbackHttpInbound("""{"inbounds":[]}""", 0))
        assertNull(CoreConfigManager.withLoopbackHttpInbound("""{"inbounds":[]}""", 65536))
    }

    @Test
    fun rejectsMalformedOrNonObjectContent() {
        assertNull(CoreConfigManager.withLoopbackHttpInbound("", 18080))
        assertNull(CoreConfigManager.withLoopbackHttpInbound("[]", 18080))
        assertNull(CoreConfigManager.withLoopbackHttpInbound("""{"inbounds":[""", 18080))
    }
}
