package com.aicove.core.plugin

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class ToolBeforeHookResult(
    val denied: Boolean = false,
    val denyReason: String? = null,
    val arguments: JsonObject? = null
)

data class ToolAfterHookResult(val data: JsonElement? = null)

data class HttpRequestHookResult(
    val url: String? = null,
    val headers: Map<String, String>? = null,
    val body: String? = null
)

enum class PermissionReviewDecision { ALLOW, DENY, PASS }

data class PermissionReviewHookResult(
    val decision: PermissionReviewDecision,
    val reason: String? = null
)
