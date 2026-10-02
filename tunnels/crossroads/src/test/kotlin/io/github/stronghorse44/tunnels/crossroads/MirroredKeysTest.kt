package io.github.stronghorse44.tunnels.crossroads

import io.github.stronghorse44.tunnels.crossrules.CrossKeys
import io.github.stronghorse44.tunnels.deepmode.DeepKeys
import io.github.stronghorse44.tunnels.dns.TrafficKeys
import io.github.stronghorse44.tunnels.model.TunnelCatalog
import io.github.stronghorse44.tunnels.permrules.PermissionKeys
import io.github.stronghorse44.tunnels.timeline.TimelineKeys
import io.github.stronghorse44.tunnels.trackers.ApkKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.concurrent.TimeUnit

/** CrossKeys.Sources mirrors keys owned by Android modules; a rename there must fail here, not silently empty a join. */
class MirroredKeysTest {
    private val s = CrossKeys.Sources

    @Test
    fun sourceTunnelIdsMatchTheirOwners() {
        assertEquals(PermissionKeys.TUNNEL_ID, s.PERMISSIONS)
        assertEquals(ApkKeys.TUNNEL_ID, s.APK)
        assertEquals(TrafficKeys.TUNNEL_ID, s.TRAFFIC)
        assertEquals(TimelineKeys.TUNNEL_ID, s.TIMELINE)
        assertEquals(DeepKeys.TUNNEL_ID, s.DEEP)
        assertEquals(TunnelCatalog.CROSSROADS, CrossKeys.TUNNEL_ID)
        s.ALL.forEach { assertNotNull("$it is in the catalog", TunnelCatalog.byId(it)) }
    }

    @Test
    fun timelineKeysMatch() {
        assertEquals(TimelineKeys.LABEL, s.TIMELINE_LABEL)
        assertEquals(TimelineKeys.SYSTEM, s.TIMELINE_SYSTEM)
        assertEquals(TimelineKeys.FIRST_INSTALL, s.TIMELINE_FIRST_INSTALL)
        assertEquals(TimelineKeys.LAST_USED, s.TIMELINE_LAST_USED)
        assertEquals(TimelineKeys.NEVER, s.TIMELINE_NEVER)
    }

    @Test
    fun deepModeKeysAndAgesMatch() {
        assertEquals(DeepKeys.CAMERA, s.DEEP_CAMERA)
        assertEquals(DeepKeys.RECORD_AUDIO, s.DEEP_RECORD_AUDIO)
        assertEquals(DeepKeys.AGE_TODAY, s.DEEP_AGE_TODAY)
        assertEquals(DeepKeys.lastKey(DeepKeys.CAMERA), s.deepLastKey(DeepKeys.CAMERA))
        for (days in listOf(0L, 1L, 2L, 17L, 29L, 30L, 400L)) {
            val age = DeepKeys.coarseAge(TimeUnit.DAYS.toMillis(days) + 1_000)
            assertEquals(age, DeepKeys.ageDays(age), s.deepAgeDays(age))
        }
        assertEquals(DeepKeys.ageDays(DeepKeys.AGE_NEVER), s.deepAgeDays(DeepKeys.AGE_NEVER))
        assertEquals(DeepKeys.ageDays(DeepKeys.AGE_OLD), s.deepAgeDays(DeepKeys.AGE_OLD))
    }
}
