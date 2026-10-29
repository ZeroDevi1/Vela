package com.vela.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerProbePlanTest {
    @Test
    fun peachNeverProbesEmbyOrJellyfin() {
        val plan = serverProbePlan("https://example.test/peach", ServerType.PEACH)
        assertTrue(plan.mediaUrls.isEmpty())
        assertTrue(plan.probePeach)
    }

    @Test
    fun selectedMediaProtocolNeverFallsBackToPeach() {
        for (type in listOf(ServerType.EMBY, ServerType.JELLYFIN)) {
            val plan = serverProbePlan("https://example.test/media", type)
            assertTrue(plan.mediaUrls.isNotEmpty())
            assertFalse(plan.probePeach)
        }
    }

    @Test
    fun existingCallersKeepAutomaticDiscovery() {
        val legacy = serverProbePlan("https://example.test", null)
        assertTrue(legacy.mediaUrls.isNotEmpty())
        assertTrue(legacy.probePeach)
        assertEquals(legacy, serverProbePlan("https://example.test", ServerType.UNKNOWN))
    }
}
