package io.shellify.app.core.engine

import java.io.File

/** Replaces the live Gecko native-library tree only after a complete staging tree exists. */
internal fun replaceGeckoLibraryDirectory(stagingRoot: File, liveRoot: File): Boolean {
    if (!stagingRoot.isDirectory) return false
    if (liveRoot.exists() && !liveRoot.deleteRecursively()) return false
    if (liveRoot.exists()) return false
    if (liveRoot.parentFile?.mkdirs() == false && liveRoot.parentFile?.isDirectory != true) return false

    if (stagingRoot.renameTo(liveRoot)) return true

    val copied = runCatching {
        stagingRoot.copyRecursively(liveRoot, overwrite = false)
    }.getOrDefault(false)
    if (!copied) {
        liveRoot.deleteRecursively()
        return false
    }
    if (!stagingRoot.deleteRecursively()) {
        liveRoot.deleteRecursively()
        return false
    }
    return true
}
