package com.scanlite.ui

import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportDirectoryTest {
    @Test fun cancellationBeforeCommitRemovesTemporaryPages() = runBlocking {
        val directory = Files.createTempDirectory("scanlite-import-").toFile()
        try {
            val page = directory.resolve("page.jpg").apply { writeText("test page") }
            val ready = CompletableDeferred<Unit>()
            val job = async(start = CoroutineStart.UNDISPATCHED) {
                withImportDirectory(directory) {
                    ready.complete(Unit)
                    awaitCancellation()
                }
            }
            ready.await()
            job.cancelAndJoin()
            assertFalse(page.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun cancellationAfterCommitPreservesPublishedPages() = runBlocking {
        val directory = Files.createTempDirectory("scanlite-import-").toFile()
        try {
            val page = directory.resolve("page.jpg").apply { writeText("test page") }
            val ready = CompletableDeferred<Unit>()
            val job = async(start = CoroutineStart.UNDISPATCHED) {
                withImportDirectory(directory) { commit ->
                    commit()
                    ready.complete(Unit)
                    awaitCancellation()
                }
            }
            ready.await()
            job.cancelAndJoin()
            assertTrue(page.isFile)
            assertTrue(page.readText() == "test page")
        } finally { directory.deleteRecursively() }
    }
}
