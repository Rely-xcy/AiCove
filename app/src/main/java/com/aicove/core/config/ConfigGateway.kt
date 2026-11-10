package com.aicove.core.config

/**
 * 配置通道的读写入口。
 *
 * 放在 core 是为了断开 feature 之间的依赖：`feature/agent` 的 `config` 工具依赖这个接口，
 * `feature/settings` 提供实现。工具与实现分处两个 feature，若工具直接依赖 settings 的仓库类，
 * 就会形成 agent ↔ settings 的循环（架构门禁的 layering 规则正是拦这个）。
 *
 * 实现方要遵守三条：
 * 1. 只认 [ConfigCatalog] 里列出的键——不在清单里的一律拒绝，不给「顺手加一个」的口子；
 * 2. 写入前按 schema 校验类型与范围；
 * 3. 敏感项（[ConfigSchema.sensitive]）的写入必须先经用户确认，确认由上层工具负责，
 *    实现只负责落盘。
 */
interface ConfigGateway {

    /** 读一个配置项；该项未接入或读不到时返回 null。 */
    suspend fun read(key: String): ConfigValue?

    /** 读全部已接入的配置项；未接入的键不出现在结果里。 */
    suspend fun readAll(): Map<String, ConfigValue>

    /**
     * 写一个配置项。
     *
     * @return 失败原因；null 表示写成功。
     */
    suspend fun write(key: String, value: ConfigValue): String?
}

/**
 * 配置项的值。
 *
 * 用一个 sealed 而不是 Any：类型信息由 [ConfigSchema.type] 决定，
 * 这里只承载值本身，读写双方对不上类型时由 schema 拦住。
 */
sealed class ConfigValue {
    data class Str(val value: String) : ConfigValue()
    data class Num(val value: Long) : ConfigValue()
    data class Flag(val value: Boolean) : ConfigValue()

    /** 按 schema 的类型转成可读文本；类型不匹配时返回 null（调用方应视为非法值）。 */
    fun asText(type: ConfigValueType): String? = when (type) {
        ConfigValueType.STRING -> (this as? Str)?.value
        ConfigValueType.INT, ConfigValueType.LONG -> (this as? Num)?.value?.toString()
        ConfigValueType.BOOLEAN -> (this as? Flag)?.value?.toString()
    }

    companion object {
        /** 从 JSON 原始值构造；类型不符时返回 null。 */
        fun of(text: String?, number: Long?, flag: Boolean?): ConfigValue? = when {
            flag != null -> Flag(flag)
            number != null -> Num(number)
            text != null -> Str(text)
            else -> null
        }
    }
}
