package com.foxhole.vpn.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Node
import java.io.File
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory

class FoxWidgetAssetsTest {
    @Test
    fun `simple power action has symmetric host padding and a centered header`() {
        listOf("widget_status.xml", "widget_status_compact.xml").forEach { layout ->
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(resourceRoot().resolve("layout/$layout"))
            val frames = document.getElementsByTagName("FrameLayout")
            val action = (0 until frames.length).map(frames::item).single {
                attribute(it, "id") == "@+id/widget_status_power_touch"
            }
            val touch = (0 until frames.length).map(frames::item).single {
                attribute(it, "id") == "@+id/widget_status_logo_touch"
            }
            val images = document.getElementsByTagName("ImageView")
            val logo = (0 until images.length).map(images::item).single {
                attribute(it, "id") == "@+id/widget_status_logo"
            }
            val surface = (0 until images.length).map(images::item).single {
                attribute(it, "id") == "@+id/widget_status_power_surface"
            }
            val ring = (0 until images.length).map(images::item).single {
                attribute(it, "id") == "@+id/widget_status_power_ring"
            }
            val row = action.parentNode
            assertEquals("LinearLayout", row.nodeName)
            assertEquals("@+id/widget_status_action_row", attribute(row, "id"))
            assertEquals("match_parent", attribute(row, "layout_width"))
            assertEquals("48dp", attribute(row, "layout_height"))
            assertEquals("horizontal", attribute(row, "orientation"))
            assertEquals("6dp", attribute(row, "layout_marginTop"))
            assertEquals(null, attribute(action, "layout_marginTop"))
            assertEquals("48dp", attribute(action, "layout_height"))
            assertEquals(null, attribute(action, "layout_weight"))
            assertEquals("@string/widget_connect", attribute(action, "contentDescription"))
            val children = (0 until row.childNodes.length).map(row.childNodes::item)
                .filter { it.nodeType == Node.ELEMENT_NODE }
            assertEquals(1, children.size)
            assertEquals(action, children[0])
            assertEquals("gone", attribute(surface, "visibility"))
            assertEquals("@drawable/widget_status_action_outline", attribute(ring, "src"))
            assertEquals(null, attribute(row, "paddingStart"))
            assertEquals(null, attribute(row, "paddingEnd"))
            assertEquals("match_parent", attribute(action, "layout_width"))
            assertEquals("center_horizontal", attribute(row, "gravity"))
            assertEquals(48f, dp(touch, "layout_width"), 0.001f)
            assertTrue(dp(logo, "layout_width") <= dp(touch, "layout_width"))
            val headers = document.getElementsByTagName("LinearLayout")
            val header = (0 until headers.length).map(headers::item).single {
                attribute(it, "id") == "@+id/widget_status_header"
            }
            assertEquals("wrap_content", attribute(header, "layout_width"))
            assertEquals("center_horizontal", attribute(header, "layout_gravity"))
            val group = row.parentNode
            assertEquals("match_parent", attribute(group, "layout_width"))
            assertEquals("center_horizontal", attribute(group, "layout_gravity"))
        }
    }

    @Test
    fun `connected animation uses the six original size png frames at 320ms`() {
        val root = resourceRoot()
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(root.resolve("layout/widget_fox_connected.xml"))
        val flipper = document.getElementsByTagName("ViewFlipper").item(0)
        assertEquals("true", flipper.attributes.getNamedItem("android:autoStart").nodeValue)
        assertEquals("320", flipper.attributes.getNamedItem("android:flipInterval").nodeValue)
        val images = document.getElementsByTagName("ImageView")
        assertEquals(6, images.length)
        (1..6).forEach { frame ->
            assertEquals(
                "@drawable/fhg_status_frame_$frame",
                images.item(frame - 1).attributes.getNamedItem("android:src").nodeValue,
            )
            val image = ImageIO.read(root.resolve("drawable-nodpi/fhg_status_frame_$frame.png"))
            assertEquals(627, image.width)
            assertEquals(627, image.height)
            assertTrue(image.colorModel.hasAlpha())
        }
    }

    private fun attribute(node: Node, name: String): String? =
        node.attributes.getNamedItem("android:$name")?.nodeValue

    private fun dp(node: Node, name: String): Float =
        checkNotNull(attribute(node, name)).removeSuffix("dp").toFloat()

    private fun resourceRoot(): File = listOf(File("src/main/res"), File("app/src/main/res"))
        .first { it.resolve("layout/widget_fox_connected.xml").isFile }
}
