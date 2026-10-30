package com.foxhole.vpn

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

internal class AppLanguageResourcesTest {
    private val qualifiers = listOf("values-ru", "values-de", "values-fr", "values-es", "values-ja", "values-zh-rCN")
    private val placeholders = Regex("""%(?:[1-9]\d*\$)?[-#+ 0,(<]*\d*(?:\.\d+)?(?:[tT][a-zA-Z]|[bBhHsScCdoxXeEfgGaA%n])""")

    @Test fun `all seven app string resource sets preserve keys and format placeholders`() {
        val res = root().resolve("app/src/main/res")
        val defaults = entries(res.resolve("values"))
        assertTrue(defaults.size >= 480)
        qualifiers.forEach { qualifier ->
            val translated = entries(res.resolve(qualifier))
            assertEquals("Missing resource in $qualifier", defaults.keys, translated.keys)
            defaults.forEach { (key, value) ->
                assertEquals("Format differs for $qualifier:$key", placeholders.findAll(value).map { it.value }.sorted().toList(),
                    placeholders.findAll(translated.getValue(key)).map { it.value }.sorted().toList())
            }
        }
    }

    @Test fun `platform discovery lists exactly the seven app languages`() {
        val config = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(root().resolve("app/src/main/res/xml/locale_config.xml"))
        val locales = config.getElementsByTagName("locale")
        assertEquals(listOf("en", "ru", "de", "fr", "es", "ja", "zh-CN"), (0 until locales.length).map {
            (locales.item(it) as Element).getAttribute("android:name")
        })
        assertTrue(root().resolve("app/src/main/AndroidManifest.xml").readText().contains("android:localeConfig=\"@xml/locale_config\""))
    }

    private fun entries(folder: File): Map<String, String> = buildMap {
        folder.listFiles { file -> file.extension == "xml" }.orEmpty().forEach { file ->
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val children = document.documentElement.childNodes
            for (index in 0 until children.length) {
                val element = children.item(index) as? Element ?: continue
                if (element.tagName != "string" || element.getAttribute("translatable") == "false") continue
                val key = element.tagName + ":" + element.getAttribute("name")
                check(put(key, element.textContent) == null) { "Duplicate resource $key" }
            }
        }
    }

    private fun root(): File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { it.resolve("app/src/main/res").isDirectory }
}
