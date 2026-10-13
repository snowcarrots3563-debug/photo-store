package dev.local.artistgallery.data.deleting

import dev.local.artistgallery.data.DELETE_BATCH_SIZE
import dev.local.artistgallery.data.MediaAssetEntity
import dev.local.artistgallery.data.MediaLibraryRepository
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaDeleteCoordinatorTest {
    @Test fun thousandAssetsUseFourBatchesAndSerialFilesAfterMarking() = runTest {
        val ids = (0 until 1000).map(Int::toString)
        val fake = Fake(ids)
        var inFlight = 0
        var maximum = 0
        val removed = mutableListOf<String>()
        val callbacks = mutableListOf<Set<String>>()
        val result = deleteMediaCopies(fake.repository, ids, { key ->
            inFlight++
            maximum = maxOf(maximum, inFlight)
            val id = key.removePrefix("key-")
            assertEquals("PENDING_DELETE", fake.assets[id]!!.state)
            removed += id
            yield()
            inFlight--
            true
        }) { success, unavailable -> assertTrue(unavailable.isEmpty()); callbacks += success }
        assertEquals(listOf(256, 256, 256, 232), fake.marks.map(List<String>::size))
        assertEquals(4, fake.finishes.size)
        assertEquals(ids, removed)
        assertEquals(1, maximum)
        assertEquals(ids.toSet(), result.deletedIds)
        assertEquals(ids.toSet(), callbacks.flatten().toSet())
        assertTrue(result.failedIds.isEmpty())
        assertTrue(fake.assets.isEmpty())
        assertEquals(0, fake.singleCalls)
    }

    @Test fun duplicateMissingPendingAndOtherStateKeepDistinctOutcomes() = runTest {
        val fake = Fake(listOf("ready", "pending", "blocked"))
        fake.assets["pending"] = asset("pending", "PENDING_DELETE")
        fake.assets["blocked"] = asset("blocked", "BROKEN")
        val removed = mutableListOf<String>()
        val result = deleteMediaCopies(fake.repository, listOf("ready", "missing", "pending", "ready", "blocked"), {
            removed += it
            true
        }) { _, _ -> }
        assertEquals(listOf("key-ready", "key-pending"), removed)
        assertEquals(setOf("ready", "pending"), result.deletedIds)
        assertEquals(setOf("missing", "blocked"), result.unavailableIds)
        assertEquals("BROKEN", fake.assets["blocked"]!!.state)
        assertEquals(listOf("ready", "missing", "pending", "blocked"), fake.marks.single())
    }

    @Test fun emptyRequestDoesNotTouchRepositoryFilesOrCallback() = runTest {
        val fake = Fake(emptyList())
        val result = deleteMediaCopies(fake.repository, emptyList(), { error("unexpected file") }) { _, _ -> error("unexpected callback") }
        assertEquals(DeleteCopiesResult(emptySet(), emptySet(), emptySet()), result)
        assertTrue(fake.marks.isEmpty())
        assertTrue(fake.finishes.isEmpty())
    }

    @Test fun unavailableOnlyBatchReportsWithoutDeletingFiles() = runTest {
        val fake = Fake(emptyList())
        val reported = mutableListOf<Set<String>>()
        val result = deleteMediaCopies(fake.repository, listOf("gone"), { error("unexpected file") }) { success, unavailable ->
            assertTrue(success.isEmpty())
            reported += unavailable
        }
        assertEquals(setOf("gone"), result.unavailableIds)
        assertEquals(listOf(setOf("gone")), reported)
    }

    @Test fun falseAndExceptionRestoreFailedAssetsAndKeepSuccessfulDeletion() = runTest {
        val fake = Fake(listOf("success", "false", "exception"))
        val result = deleteMediaCopies(fake.repository, fake.assets.keys.toList(), {
            when (it) {
                "key-false" -> false
                "key-exception" -> throw IOException("unlink failed")
                else -> true
            }
        }) { _, _ -> }
        assertEquals(setOf("success"), result.deletedIds)
        assertEquals(setOf("false", "exception"), result.failedIds)
        assertNull(fake.assets["success"])
        assertEquals("READY", fake.assets["false"]!!.state)
        assertEquals("READY", fake.assets["exception"]!!.state)
    }

    @Test fun markFailureDoesNotDeleteFilesOrReportSuccess() = runTest {
        val fake = Fake(listOf("first"))
        fake.markFailure = IllegalStateException("mark failed")
        val failure = try {
            deleteMediaCopies(fake.repository, listOf("first"), { error("unexpected file") }) { _, _ -> error("unexpected callback") }
            null
        } catch (error: IllegalStateException) { error }
        assertEquals("mark failed", failure?.message)
        assertEquals("READY", fake.assets["first"]!!.state)
        assertTrue(fake.finishes.isEmpty())
    }

    @Test fun finishFailureLeavesAllMarkedAssetsPendingAndDoesNotReportCompletion() = runTest {
        val fake = Fake(listOf("success", "failure"))
        fake.finishFailureAt = 1
        val reported = mutableListOf<Set<String>>()
        val failure = try {
            deleteMediaCopies(fake.repository, fake.assets.keys.toList(), { it == "key-success" }) { success, _ -> reported += success }
            null
        } catch (error: IllegalStateException) { error }
        assertNotNull(failure)
        assertTrue(reported.isEmpty())
        assertEquals(setOf("PENDING_DELETE"), fake.assets.values.map { it.state }.toSet())
    }

    @Test fun laterBatchFailureKeepsEarlierCommittedCallbackAndNeverStartsThirdBatch() = runTest {
        val ids = (0 until 600).map(Int::toString)
        val fake = Fake(ids)
        fake.finishFailureAt = 2
        val completed = linkedSetOf<String>()
        try {
            deleteMediaCopies(fake.repository, ids, { true }) { success, _ -> completed += success }
            fail("expected finish failure")
        } catch (_: IllegalStateException) { }
        assertEquals(ids.take(256).toSet(), completed)
        assertEquals(2, fake.marks.size)
        assertEquals("PENDING_DELETE", fake.assets["256"]!!.state)
        assertEquals("READY", fake.assets["512"]!!.state)
    }

    @Test fun cancellationBeforeExecutionDoesNotMarkOrUnlink() = runTest {
        val fake = Fake(listOf("first"))
        val job = launch {
            deleteMediaCopies(fake.repository, listOf("first"), { error("unexpected file") }) { _, _ -> error("unexpected callback") }
        }
        job.cancel()
        runCurrent()
        job.join()
        assertTrue(fake.marks.isEmpty())
        assertTrue(fake.finishes.isEmpty())
    }

    @Test fun cancellationDuringUnknownFileKeepsItAndUntouchedFilesPending() = runTest {
        val fake = Fake(listOf("first", "unknown", "untouched"))
        val entered = CompletableDeferred<Unit>()
        val reported = linkedSetOf<String>()
        var propagated: CancellationException? = null
        val job = launch {
            try {
                deleteMediaCopies(fake.repository, fake.assets.keys.toList(), { key ->
                    if (key == "key-unknown") { entered.complete(Unit); awaitCancellation() }
                    true
                }) { success, _ -> reported += success }
            } catch (cancelled: CancellationException) { propagated = cancelled; throw cancelled }
        }
        runCurrent()
        assertTrue(entered.isCompleted)
        job.cancel()
        runCurrent()
        job.join()
        assertNotNull(propagated)
        assertEquals(setOf("first"), reported)
        assertNull(fake.assets["first"])
        assertEquals("PENDING_DELETE", fake.assets["unknown"]!!.state)
        assertEquals("PENDING_DELETE", fake.assets["untouched"]!!.state)
        assertTrue(fake.finishes.single().second.isEmpty())
    }

    @Test fun cancellationAfterKnownFailureRestoresThatFailureAndStopsNextBatch() = runTest {
        val ids = (0 until 300).map(Int::toString)
        val fake = Fake(ids)
        val reported = linkedSetOf<String>()
        try {
            deleteMediaCopies(fake.repository, ids, { key ->
                when (key) {
                    "key-0" -> false
                    "key-1" -> true
                    else -> throw CancellationException("stop")
                }
            }) { success, _ -> reported += success }
            fail("expected cancellation")
        } catch (cancelled: CancellationException) { assertEquals("stop", cancelled.message) }
        assertEquals(setOf("1"), reported)
        assertEquals("READY", fake.assets["0"]!!.state)
        assertEquals("PENDING_DELETE", fake.assets["2"]!!.state)
        assertEquals("READY", fake.assets["256"]!!.state)
        assertEquals(1, fake.marks.size)
    }

    @Test fun cancellationDuringSuccessfulFinishStillCommitsAndReportsBeforePropagating() = runTest {
        val fake = Fake(listOf("first"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = object : MediaLibraryRepository by fake.repository {
            override suspend fun finishAssetDeletes(deletedIds: List<String>, failedIds: List<String>) {
                entered.complete(Unit)
                release.await()
                fake.repository.finishAssetDeletes(deletedIds, failedIds)
            }
        }
        val reported = linkedSetOf<String>()
        var propagated = false
        val job = launch {
            try { deleteMediaCopies(repository, listOf("first"), { true }) { success, _ -> reported += success } }
            catch (cancelled: CancellationException) { propagated = true; throw cancelled }
        }
        runCurrent()
        assertTrue(entered.isCompleted)
        job.cancel()
        release.complete(Unit)
        runCurrent()
        job.join()
        assertTrue(propagated)
        assertEquals(setOf("first"), reported)
        assertNull(fake.assets["first"])
    }

    @Test fun failedFinishDoesNotReplaceOriginalCancellation() = runTest {
        val fake = Fake(listOf("success", "unknown"))
        fake.finishFailureAt = 1
        val cancellation = CancellationException("original stop")
        val reported = mutableListOf<Set<String>>()
        val failure = try {
            deleteMediaCopies(fake.repository, fake.assets.keys.toList(), {
                if (it == "key-unknown") throw cancellation
                true
            }) { success, _ -> reported += success }
            null
        } catch (error: CancellationException) { error }
        assertSame(cancellation, failure)
        assertEquals("finish failed", failure!!.suppressed.single().message)
        assertTrue(reported.isEmpty())
        assertEquals(setOf("PENDING_DELETE"), fake.assets.values.map { it.state }.toSet())
    }

    @Test fun failedFinishWhileCancellationArrivesStillPropagatesCancellation() = runTest {
        val fake = Fake(listOf("first"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = object : MediaLibraryRepository by fake.repository {
            override suspend fun finishAssetDeletes(deletedIds: List<String>, failedIds: List<String>) {
                entered.complete(Unit)
                release.await()
                throw IOException("finish after cancellation")
            }
        }
        var failure: CancellationException? = null
        val job = launch {
            try { deleteMediaCopies(repository, listOf("first"), { true }) { _, _ -> error("unexpected callback") } }
            catch (cancelled: CancellationException) { failure = cancelled; throw cancelled }
        }
        runCurrent()
        assertTrue(entered.isCompleted)
        job.cancel()
        release.complete(Unit)
        runCurrent()
        job.join()
        assertNotNull(failure)
        assertEquals("finish after cancellation", failure!!.suppressed.single().message)
        assertEquals("PENDING_DELETE", fake.assets["first"]!!.state)
    }

    @Test fun cancellationBeforeFirstUnlinkLeavesCommittedMarkForRecovery() = runTest {
        val fake = Fake(listOf("first"))
        val marked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = object : MediaLibraryRepository by fake.repository {
            override suspend fun markAssetsForDelete(assetIds: List<String>): List<MediaAssetEntity> {
                val result = fake.repository.markAssetsForDelete(assetIds)
                marked.complete(Unit)
                release.await()
                return result
            }
        }
        val job = launch { deleteMediaCopies(repository, listOf("first"), { error("unexpected file") }) { _, _ -> error("unexpected callback") } }
        runCurrent()
        assertTrue(marked.isCompleted)
        job.cancel()
        runCurrent()
        job.join()
        assertEquals("PENDING_DELETE", fake.assets["first"]!!.state)
        assertTrue(fake.finishes.isEmpty())
    }

    @Test fun legacySingleAssetRepositoryHasCompatibleDefaultBatchMethods() = runTest {
        val fake = Fake(listOf("ready", "pending", "failed"), allowSingles = true)
        fake.assets["pending"] = asset("pending", "PENDING_DELETE")
        val legacy = object : MediaLibraryRepository by fake.repository {
            override suspend fun markAssetsForDelete(assetIds: List<String>): List<MediaAssetEntity> =
                super<MediaLibraryRepository>.markAssetsForDelete(assetIds)
            override suspend fun finishAssetDeletes(deletedIds: List<String>, failedIds: List<String>) =
                super<MediaLibraryRepository>.finishAssetDeletes(deletedIds, failedIds)
        }
        val result = deleteMediaCopies(legacy, listOf("ready", "pending", "failed", "missing"), { it != "key-failed" }) { _, _ -> }
        assertEquals(setOf("ready", "pending"), result.deletedIds)
        assertEquals(setOf("failed"), result.failedIds)
        assertEquals(setOf("missing"), result.unavailableIds)
        assertEquals("READY", fake.assets["failed"]!!.state)
        assertTrue(fake.singleCalls > 0)
        assertTrue(fake.marks.isEmpty())
    }

    private class Fake(ids: List<String>, private val allowSingles: Boolean = false) {
        val assets = ids.associateWith { asset(it) }.toMutableMap()
        val marks = mutableListOf<List<String>>()
        val finishes = mutableListOf<Pair<Set<String>, Set<String>>>()
        var markFailure: RuntimeException? = null
        var finishFailureAt: Int? = null
        var singleCalls = 0
        @Suppress("UNCHECKED_CAST")
        val repository = Proxy.newProxyInstance(MediaLibraryRepository::class.java.classLoader,
            arrayOf(MediaLibraryRepository::class.java)) { _, method, args ->
            when (method.name) {
                "markAssetsForDelete" -> {
                    val requested = (args!![0] as List<String>).toList()
                    require(requested.size <= DELETE_BATCH_SIZE)
                    marks += requested
                    markFailure?.let { throw it }
                    requested.mapNotNull { id -> assets[id]?.takeIf { it.state in setOf("READY", "PENDING_DELETE") }?.let {
                        it.copy(state = "PENDING_DELETE").also { pending -> assets[id] = pending }
                    } }
                }
                "finishAssetDeletes" -> {
                    val deleted = (args!![0] as List<String>).toSet()
                    val failed = (args[1] as List<String>).toSet()
                    finishes += deleted to failed
                    if (finishFailureAt == finishes.size) throw IllegalStateException("finish failed")
                    deleted.forEach(assets::remove)
                    failed.forEach { id -> assets[id]?.let { assets[id] = it.copy(state = "READY") } }
                    Unit
                }
                else -> {
                    check(allowSingles) { "unexpected single-asset call: ${method.name}" }
                    singleCalls++
                    val id = args!![0] as String
                    when (method.name) {
                        "getAsset" -> assets[id]
                        "markAssetForDelete" -> assets[id]?.takeIf { it.state == "READY" }?.copy(state = "PENDING_DELETE")?.also { assets[id] = it }
                        "completeAssetDelete" -> { assets.remove(id); Unit }
                        "restoreAssetAfterDeleteFailure" -> { assets[id]?.let { assets[id] = it.copy(state = "READY") }; Unit }
                        else -> error("unexpected repository method: ${method.name}")
                    }
                }
            }
        } as MediaLibraryRepository
    }

    private companion object {
        fun asset(id: String, state: String = "READY") =
            MediaAssetEntity(id, "$id.png", "IMAGE", 8000, null, null, id, "key-$id", state, 1)
    }
}
