package com.aicove.core.lifecycle

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface BackgroundTaskLifecycleEvent {
    data object StopAll : BackgroundTaskLifecycleEvent
    data object AppForegrounded : BackgroundTaskLifecycleEvent
}

@Singleton
class BackgroundTaskLifecycleBus @Inject constructor() {
    private val _events = MutableSharedFlow<BackgroundTaskLifecycleEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<BackgroundTaskLifecycleEvent> = _events.asSharedFlow()

    fun stopAll() {
        _events.tryEmit(BackgroundTaskLifecycleEvent.StopAll)
    }

    fun appForegrounded() {
        _events.tryEmit(BackgroundTaskLifecycleEvent.AppForegrounded)
    }
}
