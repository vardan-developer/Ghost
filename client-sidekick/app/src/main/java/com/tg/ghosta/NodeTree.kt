package com.tg.ghosta

/**
 * Pure (JVM-testable) snapshot of an accessibility node.
 * `id` is the index path from the root ("root", "root/0", "root/0/3", ...)
 * so a node found in a dump can be tapped later by id without holding
 * live AccessibilityNodeInfo references.
 */
data class NodeData(
    val id: String,
    val text: String?,
    val desc: String?,
    val className: String?,
    /** "left,top,right,bottom" in screen coords */
    val bounds: String?,
    val clickable: Boolean,
    val focusable: Boolean,
    val editable: Boolean,
    /** viewIdResourceName, e.g. "com.android.systemui:id/btn_ok" */
    val viewId: String?,
    val children: List<NodeData> = emptyList()
)

object NodeTree {

    /** JSON-serializable map of a node (recursive, children included). */
    fun toMap(n: NodeData): Map<String, Any?> = linkedMapOf(
        "id" to n.id,
        "text" to n.text,
        "desc" to n.desc,
        "class" to n.className,
        "bounds" to n.bounds,
        "clickable" to n.clickable,
        "focusable" to n.focusable,
        "editable" to n.editable,
        "viewId" to n.viewId,
        "children" to n.children.map { toMap(it) }
    )

    /** Same as [toMap] but children chopped off — used for `find` results. */
    fun toShallowMap(n: NodeData): Map<String, Any?> = linkedMapOf(
        "id" to n.id,
        "text" to n.text,
        "desc" to n.desc,
        "class" to n.className,
        "bounds" to n.bounds,
        "clickable" to n.clickable,
        "focusable" to n.focusable,
        "editable" to n.editable,
        "viewId" to n.viewId
    )

    fun toJson(root: NodeData): String = Json.stringify(toMap(root))

    /** Depth-first flatten of the tree (root included). */
    fun flatten(root: NodeData): List<NodeData> {
        val out = ArrayList<NodeData>()
        fun walk(n: NodeData) {
            out.add(n)
            n.children.forEach(::walk)
        }
        walk(root)
        return out
    }

    /**
     * Search the tree by substring (case-insensitive).
     * `text` matches against text/contentDescription; `id` matches against
     * the view's resource id (viewId) — mirroring "ui find --text/--id".
     */
    fun find(root: NodeData, text: String?, viewId: String?): List<NodeData> {
        val query = (text ?: viewId ?: return emptyList()).trim().lowercase()
        if (query.isEmpty()) return emptyList()
        return flatten(root).filter { n ->
            if (text != null) {
                (n.text != null && n.text!!.lowercase().contains(query)) ||
                    (n.desc != null && n.desc!!.lowercase().contains(query))
            } else {
                n.viewId != null && n.viewId!!.lowercase().contains(query)
            }
        }
    }

    /** Resolve an id path like "root/0/3" inside a tree; null if not found. */
    fun resolve(root: NodeData, id: String?): NodeData? {
        if (id == null) return null
        var node: NodeData = root
        if (id == root.id) return node
        val rest = id.removePrefix(root.id).removePrefix("/")
        if (rest == id) return null // not rooted at our root id
        for (seg in rest.split('/')) {
            if (seg.isEmpty()) continue
            val idx = seg.toIntOrNull() ?: return null
            if (idx < 0 || idx >= node.children.size) return null
            node = node.children[idx]
        }
        return node
    }
}
