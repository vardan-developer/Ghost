package com.tg.ghosta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeTreeTest {

    private fun node(
        id: String,
        text: String? = null,
        desc: String? = null,
        className: String = "android.widget.TextView",
        clickable: Boolean = false,
        editable: Boolean = false,
        viewId: String? = null,
        children: List<NodeData> = emptyList()
    ) = NodeData(
        id, text, desc, className, "0,0,100,100",
        clickable, true, editable, viewId, children
    )

    private val tree = node(
        "root",
        children = listOf(
            node("root/0", text = "Start recording", clickable = true),
            node(
                "root/1",
                children = listOf(
                    node("root/1/0", text = "Cancel", clickable = true),
                    node("root/1/1", text = "While using the app", clickable = true),
                    node("root/1/2", viewId = "com.android.systemui:id/pin_entry", editable = true)
                )
            ),
            node("root/2", desc = "Allow")
        )
    )

    @Test
    fun `flatten includes root and all descendants`() {
        assertEquals(7, NodeTree.flatten(tree).size)
    }

    @Test
    fun `find by text is case-insensitive substring`() {
        val hits = NodeTree.find(tree, "while using", null)
        assertEquals(1, hits.size)
        assertEquals("root/1/1", hits[0].id)
    }

    @Test
    fun `find matches content description too`() {
        val hits = NodeTree.find(tree, "allow", null)
        assertEquals(1, hits.size)
        assertEquals("root/2", hits[0].id)
    }

    @Test
    fun `find by viewId`() {
        val hits = NodeTree.find(tree, null, "pin_entry")
        assertEquals(1, hits.size)
        assertTrue(hits[0].editable)
    }

    @Test
    fun `find with empty query returns nothing`() {
        assertTrue(NodeTree.find(tree, "  ", null).isEmpty())
        assertTrue(NodeTree.find(tree, null, null).isEmpty())
    }

    @Test
    fun `resolve follows index paths`() {
        assertEquals("root/1/0", NodeTree.resolve(tree, "root/1/0")?.id)
        assertEquals("root", NodeTree.resolve(tree, "root")?.id)
    }

    @Test
    fun `resolve rejects bad paths`() {
        assertEquals(null, NodeTree.resolve(tree, "root/9"))
        assertEquals(null, NodeTree.resolve(tree, "root/x"))
        assertEquals(null, NodeTree.resolve(tree, "other/0"))
        assertEquals(null, NodeTree.resolve(tree, null))
    }

    @Test
    fun `toJson emits valid nested JSON`() {
        val json = NodeTree.toJson(tree)
        val parsed = JsonParser.parse(json)
        @Suppress("UNCHECKED_CAST")
        val root = parsed as Map<String, Any?>
        assertEquals("root", root["id"])
        assertEquals("Start recording", (root["children"] as List<*>)[0].let {
            @Suppress("UNCHECKED_CAST")
            (it as Map<String, Any?>)["text"]
        })
        // shallow map has no children key
        assertFalse(NodeTree.toShallowMap(tree).containsKey("children"))
    }

    @Test
    fun `json escaping`() {
        val json = Json.stringify(mapOf("t" to "a\"b\\c\nd\te"))
        assertEquals("{\"t\":\"a\\\"b\\\\c\\nd\\te\"}", json)
        val back = JsonParser.parse("{\"t\":\"a\\\"b\\\\c\\nd\\te\"}")
        @Suppress("UNCHECKED_CAST")
        assertEquals("a\"b\\c\nd\te", (back as Map<String, Any?>)["t"])
    }

    @Test
    fun `parser handles numbers and booleans`() {
        val v = JsonParser.parse("{\"a\":540,\"b\":1.5,\"c\":false,\"d\":null,\"e\":[1,2]}")
        @Suppress("UNCHECKED_CAST")
        val m = v as Map<String, Any?>
        assertEquals(540L, m["a"])
        assertEquals(1.5, m["b"])
        assertEquals(false, m["c"])
        assertEquals(null, m["d"])
        assertEquals(listOf(1L, 2L), m["e"])
    }
}
