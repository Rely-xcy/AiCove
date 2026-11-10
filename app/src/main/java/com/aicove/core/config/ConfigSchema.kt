package com.aicove.core.config

/**
 * 配置项的元信息：AI 通过 `config` 工具能读写哪些设置、各自什么含义。
 *
 * 为什么把 schema 放 core 而不是 settings：配置通道的**定义**（有哪些项、哪些敏感）
 * 要同时被两个方向用到——
 * - `feature/agent` 侧的 `config` 工具要按它生成工具描述、校验入参；
 * - `feature/settings` 侧的实现要按它读写 DataStore。
 * 放 core 才不会让 agent 反向依赖 settings（那会形成 feature 间的循环依赖，
 * 架构门禁的 layering 规则正是拦这个）。
 */
data class ConfigSchema(
    /** 配置项键名，如 `first_byte_timeout_sec`。 */
    val key: String,
    /** 值类型：读回的 JSON 类型与写入时的校验都按它走。 */
    val type: ConfigValueType,
    /** 一句话说明这个设置是干什么的（给模型看）。 */
    val description: String,
    /** 默认值；null 表示该项没有默认值（读不到时返回 null）。 */
    val defaultValue: String? = null,
    /**
     * 是否敏感：敏感项写入前必须经用户确认。
     *
     * 「敏感」指改动会明显影响用户数据或设备状态（如删数据、换模型），
     * 不是指含密码的项——那些根本不在这个通道里。
     */
    val sensitive: Boolean = false,
    /**
     * 是否可回滚：可回滚的项写入前留一份旧值，用户后悔了能一键还原。
     */
    val revertible: Boolean = true
)

enum class ConfigValueType {
    STRING, INT, LONG, BOOLEAN
}

/**
 * 当前可被 AI 读写的配置项清单。
 *
 * 刻意**只收通用偏好**：超时、重试、压缩阈值这类「改了对系统有影响但用户可接受」的项。
 * 不收的：凭据类（模型 Key、口令、令牌——那些走各自的凭据页，且要加密）、
 * 供应商与连接配置（结构复杂，改坏了要重配）、主题与语言（纯外观，改动没意义）。
 *
 * 加新项要同时在 settings 侧实现读写，否则工具会报「该项尚未接入」。
 */
object ConfigCatalog {

    val schemas: List<ConfigSchema> = listOf(
        ConfigSchema(
            key = "first_byte_timeout_sec",
            type = ConfigValueType.INT,
            description = "等待模型首个字节的超时秒数。服务端卡住时按此断开重试。",
            defaultValue = "300"
        ),
        ConfigSchema(
            key = "stream_idle_timeout_sec",
            type = ConfigValueType.INT,
            description = "流式响应中两个数据块之间的最大间隔秒数。超过则判定连接停滞并重试。",
            defaultValue = "300"
        ),
        ConfigSchema(
            key = "max_network_retries",
            type = ConfigValueType.INT,
            description = "网络请求失败后的重试次数（不含首次请求）。0 表示不重试。",
            defaultValue = "6"
        ),
        ConfigSchema(
            key = "enter_to_send",
            type = ConfigValueType.BOOLEAN,
            description = "回车是否直接发送消息。false 时回车换行，由发送按钮发送。",
            defaultValue = "false"
        ),
        ConfigSchema(
            key = "compaction_threshold_percent",
            type = ConfigValueType.INT,
            description = "上下文占用达到该百分比时触发压缩（折叠早期对话为摘要）。",
            defaultValue = "85"
        ),
        ConfigSchema(
            key = "soft_compaction_threshold_percent",
            type = ConfigValueType.INT,
            description = "上下文占用达到该百分比时先精简历史里的超长工具输出（不调模型、无感）。",
            defaultValue = "40"
        ),
        ConfigSchema(
            key = "sendfile_max_size_mb",
            type = ConfigValueType.INT,
            description = "单个上传文件的大小上限（MB）。",
            defaultValue = "100"
        ),
        ConfigSchema(
            key = "goal_max_turns",
            type = ConfigValueType.INT,
            description = "自主循环（goal）模式下单次任务最多执行的轮数。",
            defaultValue = "50"
        ),
        ConfigSchema(
            key = "goal_max_tokens",
            type = ConfigValueType.INT,
            description = "自主循环（goal）模式下单次任务最多消耗的 token。",
            defaultValue = "100000"
        ),
        ConfigSchema(
            key = "auto_remove_stale_models",
            type = ConfigValueType.BOOLEAN,
            description = "拉取模型列表成功后，是否自动移除远端已不存在的本地模型。",
            defaultValue = "true"
        ),
        ConfigSchema(
            key = "delete_external_workspace_sessions",
            type = ConfigValueType.BOOLEAN,
            description = "移除外部工作区时，是否一并删除该工作区的聊天记录。",
            defaultValue = "false",
            // 会删用户数据：写入前必须确认，且不参与自动回滚。
            sensitive = true,
            revertible = false
        )
    )

    private val byKey = schemas.associateBy { it.key }

    fun find(key: String): ConfigSchema? = byKey[key]
}
