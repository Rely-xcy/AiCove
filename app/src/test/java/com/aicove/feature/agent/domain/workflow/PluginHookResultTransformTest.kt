package com.aicove.feature.agent.domain.workflow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [toolResultSuccessData] / [replaceToolResultData] 的单元测试。
 *
 * 测试覆盖：
 * - 成功结果：data 字段被正确提取与替换
 * - 失败结果：toolResultSuccessData 返回 null，不触发改写
 * - 格式异常：返回 null，不抛异常
 */
class PluginHookResultTransformTest {

    private val successRaw = buildJsonObject {
        put("status", "success")
        put("data", buildJsonObject { put("message", "hello") })
    }.toString()

    private val errorRaw = buildJsonObject {
        put("status", "error")
        put("message", "something went wrong")
        put("code", "ERR_X")
    }.toString()

    @Test
    fun `toolResultSuccessData returns data for success result`() {
        val data = toolResultSuccessData(successRaw)
        assertNotNull(data)
        assertEquals("hello", data?.jsonObject?.get("message")?.jsonPrimitive?.content)
    }

    @Test
    fun `toolResultSuccessData returns null for error result`() {
        assertNull(toolResultSuccessData(errorRaw))
    }

    @Test
    fun `toolResultSuccessData returns null for malformed json`() {
        assertNull(toolResultSuccessData("not json"))
    }

    @Test
    fun `toolResultSuccessData returns null when data key missing`() {
        val noData = buildJsonObject { put("status", "success") }.toString()
        assertNull(toolResultSuccessData(noData))
    }

    @Test
    fun `replaceToolResultData rewrites data and preserves other fields`() {
        val newData = JsonPrimitive("replaced")
        val result = replaceToolResultData(successRaw, newData)
        val parsed = Json.parseToJsonElement(result).jsonObject

        assertEquals("success", parsed["status"]?.jsonPrimitive?.content)
        assertEquals("replaced", parsed["data"]?.jsonPrimitive?.content)
    }

    @Test
    fun `replaceToolResultData preserves status on error raw`() {
        val newData = JsonPrimitive("masked")
        val result = replaceToolResultData(errorRaw, newData)
        val parsed = Json.parseToJsonElement(result).jsonObject

        assertEquals("error", parsed["status"]?.jsonPrimitive?.content)
        assertEquals("masked", parsed["data"]?.jsonPrimitive?.content)
        assertEquals("ERR_X", parsed["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `replaceToolResultData roundtrip preserves structure`() {
        val original = buildJsonObject {
            put("status", "success")
            put("data", buildJsonObject {
                put("files", buildJsonObject { put("count", JsonPrimitive(3)) })
                put("message", JsonPrimitive("ok"))
            })
        }.toString()
        val newData = buildJsonObject {
            put("files", buildJsonObject { put("count", JsonPrimitive(5)) })
            put("message", JsonPrimitive("done"))
        }
        val result = replaceToolResultData(original, newData)
        val parsed = Json.parseToJsonElement(result).jsonObject

        assertEquals("success", parsed["status"]?.jsonPrimitive?.content)
        assertEquals(5L, parsed["data"]?.jsonObject?.get("count")?.jsonPrimitive?.longOrNull)
        assertEquals("done", parsed["data"]?.jsonObject?.get("message")?.jsonPrimitive?.content)
    }
}
