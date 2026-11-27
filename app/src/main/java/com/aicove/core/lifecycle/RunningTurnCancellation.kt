package com.aicove.core.lifecycle

fun interface RunningTurnCancellation {
    suspend fun cancelRunningTurns()
}
