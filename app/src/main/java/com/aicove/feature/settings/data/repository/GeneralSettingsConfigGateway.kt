package com.aicove.feature.settings.data.repository

import com.aicove.core.config.ConfigGateway
import com.aicove.core.config.ConfigValue
import com.aicove.core.config.ConfigValueType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ConfigGateway] 的实现：把配置项读写落到 [GeneralSettingsRepository]。
 *
 * 只认 [GeneralSettingsRepository] 里已有的开关与阈值——本实现不新增存储，
 * 只是给已有的那批设置开一个「AI 可访问」的入口。新增配置项要先在
 * [GeneralSettingsRepository] 里有对应的读写方法，再登记进 [com.aicove.core.config.ConfigCatalog]。
 */
@Singleton
class GeneralSettingsConfigGateway @Inject constructor(
    private val generalSettings: GeneralSettingsRepository
) : ConfigGateway {

    override suspend fun read(key: String): ConfigValue? = when (key) {
        "first_byte_timeout_sec" -> ConfigValue.Num(generalSettings.firstByteTimeoutSecSnapshot().toLong())
        "stream_idle_timeout_sec" -> ConfigValue.Num(generalSettings.streamIdleTimeoutSecSnapshot().toLong())
        "max_network_retries" -> ConfigValue.Num(generalSettings.maxNetworkRetries().toLong())
        "enter_to_send" -> ConfigValue.Flag(generalSettings.enterToSendSnapshot())
        "compaction_threshold_percent" -> ConfigValue.Num(generalSettings.compactionThresholdPercent().toLong())
        "soft_compaction_threshold_percent" ->
            ConfigValue.Num(generalSettings.softCompactionThresholdPercent().toLong())
        "sendfile_max_size_mb" -> ConfigValue.Num(generalSettings.sendFileMaxSizeMb().toLong())
        "goal_max_turns" -> ConfigValue.Num(generalSettings.goalMaxTurns().toLong())
        "goal_max_tokens" -> ConfigValue.Num(generalSettings.goalMaxTokens().toLong())
        "auto_remove_stale_models" -> ConfigValue.Flag(generalSettings.autoRemoveStaleModelsSnapshot())
        "delete_external_workspace_sessions" ->
            ConfigValue.Flag(generalSettings.deleteExternalWorkspaceSessionsSnapshot())
        else -> null
    }

    override suspend fun readAll(): Map<String, ConfigValue> =
        SUPPORTED_KEYS.mapNotNull { key -> read(key)?.let { key to it } }.toMap()

    override suspend fun write(key: String, value: ConfigValue): String? {
        // 类型必须与 schema 一致：模型把 30 传给布尔项、或把 true 传给整型项，一律拒绝。
        val type = when (key) {
            "first_byte_timeout_sec" -> ConfigValueType.INT
            "stream_idle_timeout_sec" -> ConfigValueType.INT
            "max_network_retries" -> ConfigValueType.INT
            "enter_to_send" -> ConfigValueType.BOOLEAN
            "compaction_threshold_percent" -> ConfigValueType.INT
            "soft_compaction_threshold_percent" -> ConfigValueType.INT
            "sendfile_max_size_mb" -> ConfigValueType.INT
            "goal_max_turns" -> ConfigValueType.INT
            "goal_max_tokens" -> ConfigValueType.INT
            "auto_remove_stale_models" -> ConfigValueType.BOOLEAN
            "delete_external_workspace_sessions" -> ConfigValueType.BOOLEAN
            else -> return "配置项 $key 尚未接入"
        }
        val text = value.asText(type) ?: return "配置项 $key 需要 ${type.name} 类型的值"

        return when (key) {
            "first_byte_timeout_sec" -> {
                generalSettings.setFirstByteTimeoutSec(text.toInt())
                null
            }
            "stream_idle_timeout_sec" -> {
                generalSettings.setStreamIdleTimeoutSec(text.toInt())
                null
            }
            "max_network_retries" -> {
                generalSettings.setMaxNetworkRetries(text.toInt())
                null
            }
            "enter_to_send" -> {
                generalSettings.setEnterToSend(text.toBoolean())
                null
            }
            "compaction_threshold_percent" -> {
                generalSettings.setCompactionThresholdPercent(text.toInt())
                null
            }
            "soft_compaction_threshold_percent" -> {
                generalSettings.setSoftCompactionThresholdPercent(text.toInt())
                null
            }
            "sendfile_max_size_mb" -> {
                generalSettings.setSendFileMaxSizeMb(text.toInt())
                null
            }
            "goal_max_turns" -> {
                generalSettings.setGoalMaxTurns(text.toInt())
                null
            }
            "goal_max_tokens" -> {
                generalSettings.setGoalMaxTokens(text.toInt())
                null
            }
            "auto_remove_stale_models" -> {
                generalSettings.setAutoRemoveStaleModels(text.toBoolean())
                null
            }
            "delete_external_workspace_sessions" -> {
                generalSettings.setDeleteExternalWorkspaceSessions(text.toBoolean())
                null
            }
            else -> "配置项 $key 尚未接入"
        }
    }

    private companion object {
        /** 本实现支持的键；与 [com.aicove.core.config.ConfigCatalog] 的清单一致。 */
        val SUPPORTED_KEYS = listOf(
            "first_byte_timeout_sec",
            "stream_idle_timeout_sec",
            "max_network_retries",
            "enter_to_send",
            "compaction_threshold_percent",
            "soft_compaction_threshold_percent",
            "sendfile_max_size_mb",
            "goal_max_turns",
            "goal_max_tokens",
            "auto_remove_stale_models",
            "delete_external_workspace_sessions"
        )
    }
}
