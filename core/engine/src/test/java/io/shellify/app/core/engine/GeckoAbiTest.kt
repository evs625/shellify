package io.shellify.app.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class GeckoAbiTest {

    @Test
    fun `selects first ABI that Mozilla publishes`() {
        val selected = selectSupportedGeckoAbi(arrayOf("x86", "arm64-v8a", "x86_64"))

        assertEquals("arm64-v8a", selected)
    }

    @Test
    fun `unsupported x86 only device has no fallback`() {
        assertNull(selectSupportedGeckoAbi(arrayOf("x86")))
    }

    @Test
    fun `supported ABI hashes match GeckoView 156 artifacts`() {
        assertEquals(
            "416eea477aee090ac690ce4b56c558ea0acec882bf47520d0b0e2aaa11c65836",
            GECKO_SHA256_BY_ABI["arm64-v8a"],
        )
        assertEquals(
            "706f1122fb334ab662b6adcaf18916bd3bdc49887cab58d06548757187725ef7",
            GECKO_SHA256_BY_ABI["armeabi-v7a"],
        )
        assertEquals(
            "46f97d82c9d4618deffdfa52d9c9d09b4e79a912e4df4191f515e7e3de9d3033",
            GECKO_SHA256_BY_ABI["x86_64"],
        )
        assertEquals(setOf("arm64-v8a", "armeabi-v7a", "x86_64"), GECKO_SHA256_BY_ABI.keys)
    }

    @Test
    fun `library replacement removes stale old ABI tree`() {
        val root = Files.createTempDirectory("gecko-libs-test").toFile()
        try {
            val liveRoot = root.resolve("live/lib")
            val stale = liveRoot.resolve("x86/libold.so")
            stale.parentFile?.mkdirs()
            stale.writeText("old")

            val stagingRoot = root.resolve("staging")
            val fresh = stagingRoot.resolve("arm64-v8a/libxul.so")
            fresh.parentFile?.mkdirs()
            fresh.writeText("new")

            assertTrue(replaceGeckoLibraryDirectory(stagingRoot, liveRoot))
            assertTrue(liveRoot.resolve("arm64-v8a/libxul.so").isFile)
            assertFalse(liveRoot.resolve("x86/libold.so").exists())
            assertFalse(stagingRoot.exists())
        } finally {
            root.deleteRecursively()
        }
    }
    @Test
    fun `stale installed Gecko version is not loadable`() {
        val root = Files.createTempDirectory("gecko-install-state").toFile()
        try {
            val lib = root.resolve("gecko_engine/lib/arm64-v8a/libxul.so")
            lib.parentFile?.mkdirs()
            lib.writeText("old")

            assertFalse(
                hasCurrentGeckoInstallation(
                    installed = true,
                    installedVersion = "140.0.20250707120347",
                    expectedVersion = GeckoEngineManager.GECKO_VERSION,
                    supportedAbis = arrayOf("arm64-v8a"),
                    filesDir = root,
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `current Gecko version with matching ABI library is loadable`() {
        val root = Files.createTempDirectory("gecko-install-state").toFile()
        try {
            val lib = root.resolve("gecko_engine/lib/arm64-v8a/libxul.so")
            lib.parentFile?.mkdirs()
            lib.writeText("current")

            assertTrue(
                hasCurrentGeckoInstallation(
                    installed = true,
                    installedVersion = GeckoEngineManager.GECKO_VERSION,
                    expectedVersion = GeckoEngineManager.GECKO_VERSION,
                    supportedAbis = arrayOf("arm64-v8a"),
                    filesDir = root,
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }

}
