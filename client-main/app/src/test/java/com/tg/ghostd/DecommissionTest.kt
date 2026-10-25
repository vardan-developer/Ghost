package com.tg.ghostd

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Pure tests for the Remove Device command: wire protocol, response
 * validation and the narrow artifact cleanup. Android activity starts and
 * the two-app handshake itself need the emulator/device checks in BACKLOG.
 */
class DecommissionTest {

    // ------------------------------------------------------------ protocol

    @Test
    fun `prepare and confirm requests carry the package and session`() {
        val prepare = Decommission.prepareRequest(Decommission.SELF_PACKAGE, "sess-1")
        assertEquals("decommission", prepare.getString("cmd"))
        assertEquals("prepare", prepare.getString("action"))
        assertEquals("com.tg.ghostd", prepare.getString("package"))
        assertEquals("sess-1", prepare.getString("session"))

        val confirm = Decommission.confirmedRequest(Decommission.SELF_PACKAGE, "sess-1")
        assertEquals("decommission", confirm.getString("cmd"))
        assertEquals("confirmed", confirm.getString("action"))
        assertEquals("com.tg.ghostd", confirm.getString("package"))
        assertEquals("sess-1", confirm.getString("session"))
    }

    @Test
    fun `cancel request uses the sidekick cancel verb`() {
        val cancel = Decommission.cancelRequest(Decommission.SELF_PACKAGE, "sess-1")
        assertEquals("cancel", cancel.getString("action"))
        assertEquals("com.tg.ghostd", cancel.getString("package"))
        assertEquals("sess-1", cancel.getString("session"))
    }

    @Test
    fun `a blank session cancel is the unconditional reset sent before prepare`() {
        // ghostd clears any stale handshake before every new prepare. No
        // session field means "reset whatever is active", never a
        // normal-disconnect signal and never a specific attempt.
        for (session in listOf(null, "", "   ")) {
            val reset = Decommission.cancelRequest(Decommission.SELF_PACKAGE, session)
            assertEquals("decommission", reset.getString("cmd"))
            assertEquals("cancel", reset.getString("action"))
            assertEquals("com.tg.ghostd", reset.getString("package"))
            assertFalse(
                "unconditional reset must not carry a session: $session",
                reset.has("session")
            )
        }
    }

    @Test
    fun `a blocked sidekick is reported with its distinct code`() {
        val blocked = JSONObject().put("ok", false)
            .put("code", "self_uninstall_blocked")
            .put("error", "grant Display over other apps")
        assertTrue(Decommission.isSelfUninstallBlocked(blocked))
        assertFalse(
            Decommission.isSelfUninstallBlocked(
                JSONObject().put("ok", false).put("error", "not armed")
            )
        )
        assertFalse(Decommission.isSelfUninstallBlocked(null))
    }

    // ------------------------------------------------------------- outcome

    @Test
    fun `opening a sheet is never reported as success`() {
        val artifacts = JSONObject().put("complete", true)
        val waiting = Decommission.awaitingConfirmation(artifacts, 120_000L)
        assertFalse("a sheet left open is not removal", waiting.getBoolean("ok"))
        assertEquals("awaiting_confirmation", waiting.getString("stage"))
        assertTrue(waiting.getBoolean("awaiting_confirmation"))
        assertTrue(waiting.getBoolean("uninstall_requested"))
        assertEquals(120_000L, waiting.getLong("waited_ms"))
        assertTrue(
            waiting.getString("error").contains("retry destroy")
        )

        val replaced = Decommission.replacedResult(artifacts, 5_000L)
        assertTrue(replaced.getBoolean("ok"))
        assertTrue(replaced.getBoolean("replaced"))
        assertEquals("replaced", replaced.getString("stage"))
        assertFalse(replaced.has("awaiting_confirmation"))
    }

    @Test
    fun `the replacement wait is bounded to 120 seconds`() {
        assertEquals(120_000L, Decommission.REPLACEMENT_WAIT_MS)
        assertFalse(Decommission.replacementWaitElapsed(0L, 119_999L))
        assertTrue(Decommission.replacementWaitElapsed(0L, 120_000L))
        assertTrue(Decommission.replacementWaitElapsed(1_000L, 121_000L))
    }

    @Test
    fun `a response is accepted only for the exact stage and package`() {
        val prepared = JSONObject().put("ok", true)
            .put("stage", "prepared").put("package", "com.tg.ghostd")
        assertTrue(Decommission.stageMatches(prepared, Decommission.RESPONSE_PREPARED))
        assertFalse(Decommission.stageMatches(prepared, Decommission.RESPONSE_CONFIRMED))

        val wrongPackage = JSONObject().put("ok", true)
            .put("stage", "prepared").put("package", "com.tg.ghosta")
        assertFalse(
            "a success for another package must not be accepted",
            Decommission.stageMatches(wrongPackage, Decommission.RESPONSE_PREPARED)
        )

        val rejected = JSONObject().put("ok", false)
            .put("stage", "prepared").put("package", "com.tg.ghostd")
            .put("error", "not armed")
        assertFalse(Decommission.stageMatches(rejected, Decommission.RESPONSE_PREPARED))

        assertFalse(Decommission.stageMatches(null, Decommission.RESPONSE_PREPARED))
    }

    @Test
    fun `sidekick errors are surfaced actionably`() {
        assertEquals(
            "sidekick link: refused",
            Decommission.sidekickError(
                JSONObject().put("ok", false).put("error", "sidekick link: refused")
            )
        )
        assertEquals("no response", Decommission.sidekickError(null))
        assertEquals("no response", Decommission.sidekickError(JSONObject().put("ok", false)))
    }

    @Test
    fun `failure reports ok false plus stage and error`() {
        val f = Decommission.failure("sidekick_prepare", "not armed")
        assertFalse(f.getBoolean("ok"))
        assertEquals("sidekick_prepare", f.getString("stage"))
        assertEquals("not armed", f.getString("error"))
    }

    // ------------------------------------------------------------- cleanup

    @Test
    fun `artifact list is limited to staged ghostd and ghosta copies`() {
        val cache = File("/tmp/example-cache")
        val paths = Decommission.artifactFiles(cache).map { it.absolutePath }
        assertTrue(paths.contains("/sdcard/Download/ghostd.apk"))
        assertTrue(paths.contains("/sdcard/Download/ghostd.apk.part"))
        assertTrue(paths.contains("/sdcard/Download/ghosta.apk"))
        assertTrue(paths.contains("/sdcard/Download/ghosta.apk.part"))
        assertTrue(paths.contains(File(cache, "ghosta-bundled.apk").absolutePath))
        assertEquals(5, paths.size)
    }

    @Test
    fun `cleanup deletes only the listed files and reports failures`() {
        val dir = Files.createTempDirectory("ghostd-destroy").toFile()
        try {
            val staged = File(dir, "ghostd.apk").apply { writeText("apk") }
            val sidekick = File(dir, "ghosta.apk").apply { writeText("apk") }
            val unrelated = File(dir, "photo.jpg").apply { writeText("keep me") }
            val missing = File(dir, "already-gone.apk")
            // A non-empty directory cannot be deleted by File.delete(): use it
            // to prove failures are reported, not silently swallowed.
            val busy = File(dir, "busy").apply {
                mkdir()
                File(this, "child").writeText("x")
            }

            val res = Decommission.cleanup(listOf(staged, sidekick, missing, busy))
            assertFalse(res.getBoolean("complete"))
            assertFalse("deleted staged ghostd copy", staged.exists())
            assertFalse("deleted staged ghosta copy", sidekick.exists())
            assertTrue("unrelated user file must be untouched", unrelated.exists())
            assertTrue("non-empty directory must remain", busy.exists())

            val removed = res.getJSONArray("removed")
            assertEquals(2, removed.length())
            val failed = res.getJSONArray("failed")
            assertEquals(1, failed.length())
            assertEquals(busy.absolutePath, failed.getJSONObject(0).getString("path"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `cleanup of nothing left behind is complete`() {
        val dir = Files.createTempDirectory("ghostd-destroy-empty").toFile()
        try {
            val res = Decommission.cleanup(
                listOf(File(dir, "ghostd.apk"), File(dir, "ghosta.apk"))
            )
            assertTrue(res.getBoolean("complete"))
            assertEquals(0, res.getJSONArray("removed").length())
            assertEquals(0, res.getJSONArray("failed").length())
        } finally {
            dir.deleteRecursively()
        }
    }
}
