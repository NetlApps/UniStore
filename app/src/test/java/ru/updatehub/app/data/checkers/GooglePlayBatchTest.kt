package ru.updatehub.app.data.checkers

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GooglePlayBatchTest {

    /**
     * Builds the same ds:5 shape Play returns: the package id lives in
     * [1][3]["12"], the title in [1][2][0][0] and the version in
     * [1][2][140][0][0][0].
     */
    private fun playRoot(packageName: String?, title: String?, version: String?): String {
        val details = JSONArray()
        details.put(JSONArray().put(title ?: ""))
        if (version != null) {
            while (details.length() < 140) details.put(JSONObject.NULL)
            details.put(
                JSONArray().put(
                    JSONArray().put(
                        JSONArray().put(version)
                    )
                )
            )
        } else {
            details.put(JSONObject.NULL)
        }

        val meta = JSONObject()
        if (packageName != null) {
            meta.put("12", JSONArray().put(JSONArray().put(packageName).put(7)))
        }

        val root = JSONArray()
        root.put(JSONArray().put(JSONArray()))
        root.put(
            JSONArray()
                .put(JSONObject.NULL)
                .put(JSONObject.NULL)
                .put(details)
                .put(meta)
        )
        return root.toString()
    }

    private fun entry(payload: String?): JSONArray = JSONArray()
        .put("wrb.fr")
        .put("Ws7gDc")
        .put(payload ?: JSONObject.NULL)

    private fun response(vararg entries: JSONArray): String {
        val outer = JSONArray()
        entries.forEach(outer::put)
        return ")]}'\n\n$outer"
    }

    @Test
    fun `maps packages by id instead of response order`() {
        val body = response(
            entry(playRoot("org.mozilla.firefox", "Firefox", "159.0")),
            entry(playRoot("com.fsck.k9", "K-9 Mail", "24.0"))
        )

        val parsed = parsePlayBatch(body)

        assertEquals(2, parsed.size)
        assertEquals(
            PlayBatchEntry.Found("159.0", "Firefox"),
            parsed["org.mozilla.firefox"]
        )
        assertEquals(
            PlayBatchEntry.Found("24.0", "K-9 Mail"),
            parsed["com.fsck.k9"]
        )
    }

    @Test
    fun `marks a card without version as hidden`() {
        val body = response(
            entry(playRoot("org.telegram.messenger", "Telegram", null))
        )

        val parsed = parsePlayBatch(body)

        assertEquals(PlayBatchEntry.Hidden, parsed["org.telegram.messenger"])
    }

    @Test
    fun `skips null payloads for missing apps`() {
        val body = response(
            entry(null),
            entry(playRoot("com.fsck.k9", "K-9 Mail", "24.0"))
        )

        val parsed = parsePlayBatch(body)

        // The null block cannot be attributed to a package, so it stays
        // uncached and the per-app check reports "not found" itself.
        assertEquals(1, parsed.size)
        assertTrue(parsed.containsKey("com.fsck.k9"))
    }

    @Test
    fun `ignores entries without a readable package id`() {
        val body = response(
            entry(playRoot(null, "Unknown", "1.0")),
            entry(playRoot("com.fsck.k9", "K-9 Mail", "24.0"))
        )

        val parsed = parsePlayBatch(body)

        assertEquals(setOf("com.fsck.k9"), parsed.keys)
    }

    @Test
    fun `rejects a page without Play data`() {
        assertTrue(parsePlayBatch("<html>blocked</html>").isEmpty())
    }

    @Test
    fun `batch body carries every package in one rpc group`() {
        val body = playBatchBody(
            listOf("com.fsck.k9", "org.mozilla.firefox")
        )
        val decoded = java.net.URLDecoder.decode(
            body.removePrefix("f.req="),
            "UTF-8"
        )

        assertTrue(decoded.startsWith("[[["))
        assertTrue(decoded.endsWith("]]]"))
        assertEquals(2, Regex("\"Ws7gDc\"").findAll(decoded).count())
        assertTrue(decoded.contains("com.fsck.k9"))
        assertTrue(decoded.contains("org.mozilla.firefox"))
        // The field mask must stay in every RPC, not only the first one.
        assertEquals(
            2,
            Regex("\\[1,9,10,11,13,14,19,20,38").findAll(decoded).count()
        )
    }
}
