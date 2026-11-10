package com.aicove.feature.agent.domain.tool.config

import com.aicove.core.config.ConfigCatalog
import com.aicove.core.config.ConfigGateway
import com.aicove.core.config.ConfigValue
import com.aicove.core.config.ConfigValueType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置工具的口径：清单可读、类型要对、敏感项要拦。
 *
 * 工具本身不碰存储（只依赖 [ConfigGateway] 接口），所以这里给一个内存实现就能测完整逻辑，
 * 不需要 Android 环境。
 */
class ConfigToolTest {

    /** 内存版网关：记录写入，读回最后写入的值。 */
    private class FakeGateway : ConfigGateway {
        val written = mutableMapOf<String, ConfigValue>()
        override suspend fun read(key: String): ConfigValue? = written[key]
        override suspend fun readAll(): Map<String, ConfigValue> = written.toMap()
        override suspend fun write(key: String, value: ConfigValue): String? {
            written[key] = value
            return null
        }
    }

    private fun tool() = ConfigTool(FakeGateway())

    @Test
    fun `list 列出全部可改项`() = runTest {
        val result = tool().executeWithContext(mapOf("action" to JsonPrimitive("list")), ctx())
        val text = (result as com.aicove.feature.agent.domain.tool.ToolResult.Success).data.toString()
        for (schema in ConfigCatalog.schemas) {
            assertTrue("清单应包含 ${schema.key}", text.contains(schema.key))
        }
    }

    @Test
    fun `写入整型项成功`() = runTest {
        val result = tool().executeWithContext(
            mapOf(
                "action" to JsonPrimitive("write"),
                "key" to JsonPrimitive("first_byte_timeout_sec"),
                "value" to JsonPrimitive(600)
            ),
            ctx()
        )
        assertTrue(result is com.aicove.feature.agent.domain.tool.ToolResult.Success)
    }

    @Test
    fun `类型不匹配被拒`() = runTest {
        // 布尔项传数字：必须报 INVALID_TYPE，不能静默当成 true。
        val result = tool().executeWithContext(
            mapOf(
                "action" to JsonPrimitive("write"),
                "key" to JsonPrimitive("enter_to_send"),
                "value" to JsonPrimitive(1)
            ),
            ctx()
        )
        val error = result as com.aicove.feature.agent.domain.tool.ToolResult.Error
        assertEquals("INVALID_TYPE", error.code)
    }

    @Test
    fun `未知配置项被拒`() = runTest {
        val result = tool().executeWithContext(
            mapOf("action" to JsonPrimitive("read"), "key" to JsonPrimitive("api_key")),
            ctx()
        )
        val error = result as com.aicove.feature.agent.domain.tool.ToolResult.Error
        assertEquals("KEY_NOT_FOUND", error.code)
    }

    @Test
    fun `敏感项写入被拦下要求确认`() = runTest {
        // 删工作区聊天记录这类项会动用户数据，工具不能直接写。
        val result = tool().executeWithContext(
            mapOf(
                "action" to JsonPrimitive("write"),
                "key" to JsonPrimitive("delete_external_workspace_sessions"),
                "value" to JsonPrimitive(true)
            ),
            ctx()
        )
        val error = result as com.aicove.feature.agent.domain.tool.ToolResult.Error
        assertEquals("NEEDS_CONFIRMATION", error.code)
    }

    @Test
    fun `schema 清单里敏感项只有会动用户数据的那一个`() {
        val sensitive = ConfigCatalog.schemas.filter { it.sensitive }.map { it.key }
        assertEquals(listOf("delete_external_workspace_sessions"), sensitive)
    }

    @Test
    fun `每个 schema 都能被 find 命中`() {
        for (schema in ConfigCatalog.schemas) {
            assertNotNull("find 应命中 ${schema.key}", ConfigCatalog.find(schema.key))
        }
        assertNull(ConfigCatalog.find("no_such_key"))
    }

    @Test
    fun `值类型与 schema 对应`() {
        val expected = mapOf(
            "first_byte_timeout_sec" to ConfigValueType.INT,
            "enter_to_send" to ConfigValueType.BOOLEAN,
            "auto_remove_stale_models" to ConfigValueType.BOOLEAN
        )
        for ((key, type) in expected) {
            assertEquals(type, ConfigCatalog.find(key)?.type)
        }
    }

    private fun ctx() = com.aicove.feature.agent.domain.model.AgentContext(
        currentFile = null,
        selectedCode = null,
        projectRoot = "/ws",
        language = null,
        sessionId = "s1"
    )
}
