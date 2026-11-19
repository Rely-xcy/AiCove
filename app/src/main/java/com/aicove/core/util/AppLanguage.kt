package com.aicove.core.util

import android.content.Context
import java.util.Locale

fun Context.appLanguageTag(): String =
    resources.configuration.locales[0]?.toLanguageTag()?.takeIf { it.isNotBlank() }
        ?: Locale.getDefault().toLanguageTag()
