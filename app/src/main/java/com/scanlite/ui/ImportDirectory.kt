package com.scanlite.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/** Owns an import directory until the session has been published to the UI. */
internal suspend fun <T> withImportDirectory(directory: File, block: suspend (commit: () -> Unit) -> T): T {
    var committed = false
    try {
        return block { committed = true }
    } finally {
        if (!committed) withContext(NonCancellable + Dispatchers.IO) { directory.deleteRecursively() }
    }
}
