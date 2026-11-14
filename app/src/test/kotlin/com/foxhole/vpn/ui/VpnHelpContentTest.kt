package com.foxhole.vpn.ui

import com.foxhole.vpn.R
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class VpnHelpContentTest {
    @Test fun `topic ids and context content do not depend on translated prose`() {
        val topics = vpnHelpTopics()
        assertEquals(8, topics.size)
        assertEquals(8, topics.map { it.id }.toSet().size)
        assertEquals(vpnHelpTopic("router").blocks, vpnRouterContextBlocks(R.string.router_help_setup_pair))
        val chips = vpnHelpTopic("protocols").blocks.filterIsInstance<HelpBlock.Chips>().single()
        assertTrue(chips.labels.contains("WireGuard"))
        assertFalse(chips.labels.any { it in setOf("Tor", "I2P", "Sentinel") })
    }

    @Test fun `router transfer uses the actual translated action`() {
        listOf("values", "values-ru", "values-de", "values-fr", "values-es", "values-ja", "values-zh-rCN", "values-ar").forEach { locale ->
            val strings = localizedStrings(locale)
            assertTrue("$locale actual action label missing", strings.getValue("help_router_transfer").contains(strings.getValue("vpn_profile_send_to_router")))
        }
    }

    @Test fun `inline installation label is unique and points to the official project`() {
        val links = vpnHelpTopic("router").blocks.filterIsInstance<HelpBlock.Items>().first().links
        assertEquals(3, links.size)
        val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
        listOf("values", "values-ru", "values-de", "values-fr", "values-es", "values-ja", "values-zh-rCN", "values-ar").forEach { locale ->
            val strings = localizedStrings(locale)
            links.forEach { (id, link) ->
                val full = strings.getValue(names.getValue(id))
                val label = strings.getValue(names.getValue(link.label))
                assertEquals("https://github.com/foxhole-team/foxhole-openwrt-client", link.url)
                val start = full.indexOf(label)
                assertTrue(start >= 0 && label.isNotBlank())
                assertEquals(-1, full.indexOf(label, start + label.length))
            }
        }
    }

    @Test fun `inspection and missing services never promise installation`() {
        fun ids(install: Boolean, inspect: Boolean) = vpnHelpTopic("router", install, inspect).blocks.flatMap(::resourceIds)
        assertTrue(ids(false, true).contains(R.string.help_router_install_unavailable))
        assertFalse(ids(false, true).contains(R.string.help_router_install_available))
        assertTrue(ids(false, false).contains(R.string.help_router_install_manual))
        assertTrue(ids(true, true).contains(R.string.help_router_install_available))
        assertFalse(ids(true, true).contains(R.string.help_router_install_unavailable))
    }

    @Test fun `website formats remain eight explicit LTR code examples`() {
        val rows = websiteHelpBlocks().filterIsInstance<HelpBlock.Comparison>().single().rows
        assertEquals(8, rows.size)
        assertTrue(rows.all { it.code })
        assertEquals(HelpText.Resource(R.string.site_mask_range_example), rows.last().label)
        assertTrue(applicationHelpBlocks().flatMap(::resourceIds).contains(R.string.help_wg_platform))
    }

    @Test fun `comparison cards adapt to narrow screens and large text`() {
        assertTrue(helpUsesVerticalRows(320f, 1f))
        assertTrue(helpUsesVerticalRows(360f, 1.5f))
        assertTrue(helpUsesVerticalRows(640f, 2f))
        assertFalse(helpUsesVerticalRows(720f, 1f))
    }

    @Test fun `all eight locales contain every structured text resource`() {
        val required = (vpnHelpTopics().flatMap { it.blocks } + websiteHelpBlocks() + vpnRouterHelpBlocks(false, false))
            .flatMap(::resourceIds).toSet()
        val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
        listOf("values", "values-ru", "values-de", "values-fr", "values-es", "values-ja", "values-zh-rCN", "values-ar").forEach { locale ->
            val available = localizedStrings(locale)
            required.forEach { id ->
                val name = requireNotNull(names[id])
                assertTrue("$locale missing $name", !available[name].isNullOrBlank())
            }
        }
    }

    private fun resourceIds(block: HelpBlock): List<Int> = when (block) {
        is HelpBlock.Heading -> listOf(block.text)
        is HelpBlock.Paragraph -> listOf(block.text)
        is HelpBlock.Items -> block.items + block.links.values.map { it.label }
        is HelpBlock.Notice -> listOf(block.text)
        is HelpBlock.Comparison -> listOfNotNull(block.title) + block.rows.flatMap { row ->
            listOf(row.label, row.detail).filterIsInstance<HelpText.Resource>().map { it.id }
        }
        is HelpBlock.Chips -> emptyList()
        is HelpBlock.Details -> listOf(block.title) + block.blocks.flatMap(::resourceIds)
        is HelpBlock.Link -> listOf(block.label)
    }

    private fun localizedStrings(locale: String): Map<String, String> {
        val root = File(requireNotNull(System.getProperty("user.dir")), "src/main/res/$locale")
        return root.listFiles().orEmpty().filter { it.extension == "xml" }.flatMap { file ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            (0 until nodes.length).map { index ->
                val node = nodes.item(index)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
            }
        }.toMap()
    }
}
