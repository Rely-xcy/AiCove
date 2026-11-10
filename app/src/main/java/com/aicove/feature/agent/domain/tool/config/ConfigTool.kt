package com.aicove.feature.agent.domain.tool.config

import com.aicove.core.config.ConfigCatalog
import com.aicove.core.config.ConfigGateway
import com.aicove.core.config.ConfigValue
import com.aicove.core.config.ConfigValueType
import com.aicove.core.util.FileLogger
import com.aicove.feature.agent.domain.model.AgentContext
import com.aicove.feature.agent.domain.tool.AbstractContextualTool
import com.aicove.feature.agent.domain.tool.ParameterType
import com.aicove.feature.agent.domain.tool.ToolCapability
import com.aicove.feature.agent.domain.tool.ToolParameter
import com.aicove.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicove.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 配置通道工具：让 AI 能读写 App 的设置项。
 *
 * 只依赖 core 层的 [ConfigGateway] 接口，实现由 settings 侧提供——这样工具与实现分处两个
 * feature 而不互相 import（直接依赖 settings 的仓库类会形成 agent ↔ settings 循环依赖，
 * 架构门禁的 layering 规则正是拦这个）。
 *
 * 能改哪些项、哪些敏感，全在 [ConfigCatalog] 里声明，工具与实现都按它走。
 */
@Singleton
class ConfigTool @Inject constructor(
    private val gateway: ConfigGateway
) : AbstractContextualTool() {

    private companion object {
        const val TAG = "ConfigTool"
    }

    override val name = "config"

    override val description =
        "读取或写入 App 的设置项（超时、重试、压缩阈值等通用偏好）。\n" +
            "操作：\n" +
            "- read: 读一项（需 key）\n" +
            "- read_all: 读全部可访问项\n" +
            "- write: 写一项（需 key 与 value）\n" +
            "- list: 列出可改的配置项、类型与含义\n" +
            "改动会影响 App 行为（例如调大超时会让失败重试更久），改前想清楚；" +
            "标了「敏感」的项必须先经用户确认，会被拒绝并提示改用提问确认。"

    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE

    override val useWhen =
        "用户要求调整 App 的设置（超时、重试次数、压缩阈值、回车发送等），或需要先看清当前配置再决定怎么做；" +
            "先 action=list 确认有哪些项，再 read 或 write"
    override val avoidWhen =
        "改供应商/模型/密钥（那些有专用设置页且要加密）、改主题与语言（纯外观）、" +
            "或想改不在 list 里的项——那些一律拒，不要绕着试"
    override val successCriteria =
        "write 返回「已把 X 设为 Y」；read/read_all 返回的值与设置页里看到的一致"

    override val capabilities = setOf(
        ToolCapability.READ_AGENT_CONFIG,
        ToolCapability.MODIFY_AGENT_CONFIG
    )

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "操作类型：read/read_all/write/list",
            required = true,
            enum = listOf("read", "read_all", "write", "list")
        ),
        "key" to ToolParameter(
            name = "key",
            type = ParameterType.STRING,
            description = "配置项键名；用 action=list 看有哪些",
            required = false
        ),
        "value" to ToolParameter(
            name = "value",
            type = ParameterType.OBJECT,
            description = "写入时的值；整型项传数字，布尔项传 true/false，字符串项传文本",
            required = false
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("缺少必需参数：action", "MISSING_ACTION")

        return try {
            when (action) {
                "read" -> handleRead(args)
                "read_all" -> handleReadAll()
                "write" -> handleWrite(args)
                "list" -> handleList()
                else -> ToolResult.Error("不支持的操作：$action", "UNSUPPORTED_ACTION")
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "配置操作失败: ${e.message}", e)
            ToolResult.Error("配置操作失败：${e.message}")
        }
    }

    private suspend fun handleRead(args: Map<String, JsonElement>): ToolResult {
        val key = args["key"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("缺少必需参数：key", "MISSING_KEY")
        val schema = ConfigCatalog.find(key)
            ?: return ToolResult.Error(
                "没有名为 $key 的配置项；用 action=list 看有哪些可改的项。",
                "KEY_NOT_FOUND"
            )
        val value = gateway.read(key)
            ?: return ToolResult.Error("配置项 $key 尚未接入读取", "NOT_IMPLEMENTED")
        return ToolResult.Success(JsonPrimitive(value.asText(schema.type) ?: ""))
    }

    private suspend fun handleReadAll(): ToolResult {
        val values = gateway.readAll()
        if (values.isEmpty()) return ToolResult.Success(JsonPrimitive("{}"))
        val obj = JsonObject(
            values.mapNotNull { (key, value) ->
                val schema = ConfigCatalog.find(key) ?: return@mapNotNull null
                val text = value.asText(schema.type) ?: return@mapNotNull null
                key to JsonPrimitive(text)
            }.toMap()
        )
        return ToolResult.Success(obj)
    }

    private suspend fun handleWrite(args: Map<String, JsonElement>): ToolResult {
        val key = args["key"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("缺少必需参数：key", "MISSING_KEY")
        val schema = ConfigCatalog.find(key)
            ?: return ToolResult.Error(
                "没有名为 $key 的配置项；用 action=list 看有哪些可改的项。",
                "KEY_NOT_FOUND"
            )
        // 敏感项不直接写：会改用户数据或设备状态的项必须先经用户确认。
        if (schema.sensitive) {
            return ToolResult.Error(
                "配置项 $key 属于敏感项（${schema.description}），需要用户确认后才能改。" +
                    "请先用 askUserQuestion 之类的方式征得用户同意，再重新调用。",
                "NEEDS_CONFIRMATION"
            )
        }
        val raw = args["value"]
            ?: return ToolResult.Error("缺少必需参数：value", "MISSING_VALUE")
        val value = toConfigValue(raw, schema.type)
            ?: return ToolResult.Error(
                "配置项 $key 需要 ${schema.type.name} 类型的值，收到的是 ${raw::class.simpleName}",
                "INVALID_TYPE"
            )
        val failure = gateway.write(key, value)
            ?: return ToolResult.Success(JsonPrimitive("已把 $key 设为 ${value.asText(schema.type)}"))
        return ToolResult.Error("写入 $key 失败：$failure", "WRITE_FAILED")
    }

    private fun handleList(): ToolResult {
        val lines = ConfigCatalog.schemas.map { schema ->
            val flag = if (schema.sensitive) "（敏感，需用户确认）" else ""
            "- ${schema.key} [${schema.type.name}]${flag}：${schema.description}"
        }
        return ToolResult.Success(JsonPrimitive(lines.joinToString("\n")))
    }

    /** 把工具入参里的原始值转成 [ConfigValue]，类型不符返回 null。 */
    private fun toConfigValue(raw: JsonElement, type: ConfigValueType): ConfigValue? = when (type) {
        ConfigValueType.STRING -> (raw as? JsonPrimitive)?.contentOrNull?.let { ConfigValue.Str(it) }
        ConfigValueType.INT -> (raw as? JsonPrimitive)?.intOrNull?.let { ConfigValue.Num(it.toLong()) }
        ConfigValueType.LONG -> (raw as? JsonPrimitive)?.longOrNull?.let { ConfigValue.Num(it) }
        ConfigValueType.BOOLEAN -> (raw as? JsonPrimitive)?.booleanOrNull?.let { ConfigValue.Flag(it) }
    }
}
