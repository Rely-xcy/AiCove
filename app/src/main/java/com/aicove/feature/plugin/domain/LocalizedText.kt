package com.aicove.feature.plugin.domain

import java.util.Locale

/**
 * 面向使用者展示的插件文本：清单里写成单一字符串（对所有语言生效），
 * 或语言标签到文本的映射（可含 `default` 键）。
 *
 * 取值优先匹配语言、书写系统与地区，再回退通用文本；中文变体可回退到对应简繁文本。
 */
data class LocalizedText(val values: Map<String, String>) {

    init {
        require(values.isNotEmpty()) { "LocalizedText 至少需要一项文本" }
    }

    fun resolve(languageTag: String): String {
        val tag = normalize(languageTag)
        val locale = Locale.forLanguageTag(tag)
        val language = locale.language
        val script = locale.script.lowercase(Locale.ROOT)
        val region = locale.country.lowercase(Locale.ROOT)
        val chineseFallback = if (language == "zh") {
            if (script == "hant" || (script != "hans" && region in setOf("tw", "hk", "mo"))) "zh-tw" else "zh-cn"
        } else null
        return values[tag]
            ?: script.takeIf { it.isNotEmpty() }?.let { values["$language-$it"] }
            ?: region.takeIf { it.isNotEmpty() }?.let { values["$language-$it"] }
            ?: values[language]
            ?: chineseFallback?.let(values::get)
            ?: values[DEFAULT_KEY]
            ?: values["en"]
            ?: values.values.first()
    }

    companion object {
        const val DEFAULT_KEY = "default"

        fun single(text: String) = LocalizedText(mapOf(DEFAULT_KEY to text))

        /** 标签统一为小写、`-` 分隔，`zh_CN` 与 `zh-cn` 视为同一个。 */
        fun normalize(tag: String): String = tag.trim().lowercase(Locale.ROOT).replace('_', '-')
    }
}
