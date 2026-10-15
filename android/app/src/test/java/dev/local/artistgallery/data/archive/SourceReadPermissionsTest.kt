package dev.local.artistgallery.data.archive

import org.junit.Assert.*
import org.junit.Test

class SourceReadPermissionsTest {
    @Test fun existingPersistedReadAndReadWriteGrantsAreUntouched() {
        val grants = Grants(mutableMapOf("read" to READ, "both" to (READ or WRITE)))
        grants.permissions.acquire(listOf("read", "both")).close()
        assertTrue(grants.taken.isEmpty()); assertTrue(grants.released.isEmpty())
        assertEquals(mapOf("read" to READ, "both" to (READ or WRITE)), grants.flags)
    }

    @Test fun newlyAcquiredReadIsReleasedWhileExistingWriteRemains() {
        val grants = Grants(mutableMapOf("write" to WRITE))
        val held = grants.permissions.acquire(listOf("new", "write"))
        assertEquals(mapOf("new" to READ, "write" to (READ or WRITE)), grants.flags)
        held.close()
        assertEquals(listOf("new", "write"), grants.taken)
        assertEquals(listOf("new", "write"), grants.released)
        assertEquals(mapOf("write" to WRITE), grants.flags)
    }

    @Test fun duplicateUrisAreTakenAndReleasedOnlyOnceAndCloseIsIdempotent() {
        val grants = Grants()
        val held = grants.permissions.acquire(listOf("duplicate", "duplicate"))
        held.close(); held.close()
        assertEquals(listOf("duplicate"), grants.taken)
        assertEquals(listOf("duplicate"), grants.released)
        assertTrue(grants.flags.isEmpty())
    }

    @Test fun unsupportedProvidersKeepBestEffortBehaviorAndNeverReleaseUnacquiredReads() {
        val grants = Grants(mutableMapOf("denied" to WRITE)).apply {
            failures["denied"] = SecurityException("synthetic unsupported grant")
            failures["invalid"] = IllegalArgumentException("synthetic non-persistable source")
        }
        val held = grants.permissions.acquire(listOf("denied", "invalid", "supported"))
        assertEquals(READ, grants.flags["supported"])
        held.close()
        assertEquals(listOf("supported"), grants.released)
        assertEquals(mapOf("denied" to WRITE), grants.flags)
    }

    @Test fun unexpectedAcquisitionFailureReleasesPreviouslyAcquiredReads() {
        val grants = Grants(mutableMapOf("existing" to READ)).apply { failures["broken"] = IllegalStateException("synthetic acquisition failure") }
        val error = runCatching { grants.permissions.acquire(listOf("existing", "new", "broken")) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(listOf("new"), grants.released)
        assertEquals(mapOf("existing" to READ), grants.flags)
    }

    @Test fun revokedReadDuringReleaseDoesNotPreventOtherCleanup() {
        val grants = Grants().apply { releaseFailure = "revoked" }
        val held = grants.permissions.acquire(listOf("revoked", "other"))
        held.close(); held.close()
        assertEquals(listOf("revoked", "other"), grants.released)
        assertFalse(grants.flags.containsKey("other"))
    }

    @Test fun unavailablePersistedGrantListingDoesNotRiskReleasingAnExistingRead() {
        listOf(SecurityException("synthetic inaccessible grants"), IllegalArgumentException("synthetic unavailable grants")).forEach { error ->
            val permissions = SourceReadPermissions({ throw error }, { fail("Unknown existing grants must be preserved") }, { fail("No newly acquired grant") })
            permissions.acquire(listOf("existing")).close()
        }
    }

    private class Grants(val flags: MutableMap<String, Int> = mutableMapOf()) {
        val taken = mutableListOf<String>()
        val released = mutableListOf<String>()
        val failures = mutableMapOf<String, RuntimeException>()
        var releaseFailure: String? = null
        val permissions = SourceReadPermissions(
            { flags.filterValues { it and READ != 0 }.keys.toSet() },
            { uri -> failures[uri]?.let { throw it }; taken.add(uri); flags[uri] = flags.getOrDefault(uri, 0) or READ },
            { uri ->
                released.add(uri)
                if (uri == releaseFailure) throw SecurityException("synthetic revoked read")
                val remaining = flags.getOrDefault(uri, 0) and READ.inv()
                if (remaining == 0) flags.remove(uri) else flags[uri] = remaining
            },
        )
    }
    private companion object { const val READ = 1; const val WRITE = 2 }
}
