package com.aicove.core.branding

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class BrandLinksTest {
    @Test
    fun helpAndAgreementLinksOpenAiCovePagesInTheAppLanguage() {
        assertLinks("values", "")
        assertLinks("values-en", "en/")
    }

    private fun assertLinks(resourceFolder: String, localePath: String) {
        val resourcePath = "src/main/res/$resourceFolder/strings.xml"
        val file = listOf(File(resourcePath), File("app/$resourcePath")).firstOrNull { it.isFile }
        checkNotNull(file) { "Cannot locate $resourcePath" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val strings = factory.newDocumentBuilder().parse(file).getElementsByTagName("string")
        val actual = (0 until strings.length).map { strings.item(it) as Element }
            .groupBy({ it.getAttribute("name") }, { it.textContent.trim() })

        val base = "https://rely-xcy.github.io/AiCove/$localePath"
        val expected = mapOf(
            "settings_user_guide_url" to base,
            "agreement_terms_url" to "${base}terms",
            "agreement_privacy_url" to "${base}privacy"
        )
        expected.forEach { (key, url) ->
            assertTrue("Missing string $key in $resourceFolder", actual.containsKey(key))
            assertEquals("Invalid destination for $key in $resourceFolder", listOf(url), actual[key])
        }
    }
}
