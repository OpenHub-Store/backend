package zed.rainxch.githubstore.mirrors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MirrorStatusRegistryTest {

    @Test
    fun `unknown id returns UNKNOWN sentinel`() {
        val r = MirrorStatusRegistry()
        val s = r.snapshot("never_seen")
        assertEquals(MirrorStatus.UNKNOWN, s.status)
        assertNull(s.latencyMs)
        assertNull(s.lastCheckedAt)
    }

    @Test
    fun `update writes status, latency, and timestamp`() {
        val r = MirrorStatusRegistry()
        r.update("ghfast_top", MirrorStatus.OK, 240)
        val s = r.snapshot("ghfast_top")
        assertEquals(MirrorStatus.OK, s.status)
        assertEquals(240, s.latencyMs)
        assertNotNull(s.lastCheckedAt)
    }

    @Test
    fun `non-DOWN update returns false (no warning to emit)`() {
        val r = MirrorStatusRegistry()
        assertFalse(r.update("x", MirrorStatus.OK, 100))
        assertFalse(r.update("x", MirrorStatus.DEGRADED, 2000))
    }

    @Test
    fun `consecutive DOWN cycles emit warning exactly once at the threshold`() {
        val r = MirrorStatusRegistry()
        // Cycles 1-6: no warn yet.
        repeat(6) { i ->
            assertFalse(r.update("flaky", MirrorStatus.DOWN, null), "cycle ${i + 1} should not warn")
        }
        // Cycle 7: warn fires for the first time.
        assertTrue(r.update("flaky", MirrorStatus.DOWN, null), "cycle 7 should warn")
        // Cycles 8+: already-warned flag suppresses further warns.
        assertFalse(r.update("flaky", MirrorStatus.DOWN, null))
        assertFalse(r.update("flaky", MirrorStatus.DOWN, null))
    }

    @Test
    fun `recovery after DOWN streak resets state and arms a future warning`() {
        val r = MirrorStatusRegistry()
        repeat(7) { r.update("recovers", MirrorStatus.DOWN, null) }
        // Recover.
        r.update("recovers", MirrorStatus.OK, 200)
        // After recovery, a fresh DOWN streak must restart the counter -- so
        // 6 more downs should NOT trigger the warning yet.
        repeat(6) {
            assertFalse(r.update("recovers", MirrorStatus.DOWN, null))
        }
        // 7th DOWN after recovery triggers the warning again.
        assertTrue(r.update("recovers", MirrorStatus.DOWN, null))
    }

    @Test
    fun `DEGRADED in middle of DOWN streak resets the counter`() {
        val r = MirrorStatusRegistry()
        repeat(5) { r.update("blip", MirrorStatus.DOWN, null) }
        r.update("blip", MirrorStatus.DEGRADED, 3000)
        // Streak reset; 6 more downs should not warn yet.
        repeat(6) {
            assertFalse(r.update("blip", MirrorStatus.DOWN, null))
        }
        assertTrue(r.update("blip", MirrorStatus.DOWN, null))
    }

    @Test
    fun `all() returns a snapshot of every tracked id`() {
        val r = MirrorStatusRegistry()
        r.update("a", MirrorStatus.OK, 100)
        r.update("b", MirrorStatus.DEGRADED, 2500)
        r.update("c", MirrorStatus.DOWN, null)
        val all = r.all()
        assertEquals(3, all.size)
        assertEquals(MirrorStatus.OK, all["a"]?.status)
        assertEquals(MirrorStatus.DEGRADED, all["b"]?.status)
        assertEquals(MirrorStatus.DOWN, all["c"]?.status)
    }

    @Test
    fun `presets list contains expected ids and types`() {
        val ids = MirrorPresets.ALL.map { it.id }.toSet()
        // Direct GitHub must always be present.
        assertTrue("direct" in ids)
        // The April 2026 catalog ships these 5 community mirrors.
        listOf("ghfast_top", "moeyy_xyz", "gh_proxy_com", "ghps_cc", "gh_99988866_xyz")
            .forEach { assertTrue(it in ids, "missing community mirror: $it") }
        // Direct must be officially-typed; everything else community.
        assertEquals(MirrorType.OFFICIAL, MirrorPresets.byId("direct")?.type)
        MirrorPresets.ALL.filter { it.id != "direct" }.forEach {
            assertEquals(MirrorType.COMMUNITY, it.type, "${it.id} should be community-typed")
        }
    }

    @Test
    fun `dropped mirrors are not in the catalog`() {
        // April 2026 research confirmed these are dead or GFW-blocked.
        // Regression guard: re-adding any of them is almost certainly a mistake.
        val ids = MirrorPresets.ALL.map { it.id }.toSet()
        listOf("ghproxy_com", "ghproxy_net", "mirror_ghproxy_com", "ghgo_xyz", "ghp_ci")
            .forEach { assertFalse(it in ids, "$it is known dead and must not ship") }
    }
}
