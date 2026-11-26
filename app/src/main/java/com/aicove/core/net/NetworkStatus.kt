package com.aicove.core.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程级网络可用性镜像。
 *
 * [NetworkConnectivityObserver] 在系统网络回调中更新它；不持有 Context 的纯 domain 层
 * （如重试策略）可直接读取，无需注入。默认视为可用，注册回调前不会误判为离线。
 */
data class NetworkSnapshot(val online: Boolean, val revision: Long)

object NetworkStatus {
    private val _state = MutableStateFlow(NetworkSnapshot(online = true, revision = 0))
    val state: StateFlow<NetworkSnapshot> = _state.asStateFlow()
    val isOnline: Boolean get() = _state.value.online
    val revision: Long get() = _state.value.revision

    @Synchronized
    fun update(online: Boolean, networkChanged: Boolean = false) {
        val current = _state.value
        val revision = if (current.online != online || networkChanged) current.revision + 1 else current.revision
        _state.value = NetworkSnapshot(online, revision)
    }
}
