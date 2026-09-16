package io.shellify.app.core.engine

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeckoEngineManagerAbiTest {

    @Test
    fun `arm64 is selected for Android phones`() {
        assertEquals("arm64-v8a", GeckoEngineManager.selectSupportedAbi(arrayOf("arm64-v8a", "armeabi-v7a")))
    }

    @Test
    fun `first published ABI is selected when earlier ABI is unsupported`() {
        assertEquals("x86_64", GeckoEngineManager.selectSupportedAbi(arrayOf("x86", "x86_64")))
    }

    @Test
    fun `legacy x86 alone is unsupported`() {
        assertNull(GeckoEngineManager.selectSupportedAbi(arrayOf("x86")))
    }

    @Test
    fun `stale Gecko install metadata is rejected before native preload`() {
        val context = mockContextWithVersion("140.0.20250707120347")
        assertFalse(GeckoEngineManager.hasCurrentInstallMetadata(context))
    }

    @Test
    fun `current Gecko install metadata permits native preload`() {
        val context = mockContextWithVersion(GeckoEngineManager.GECKO_VERSION)
        assertTrue(GeckoEngineManager.hasCurrentInstallMetadata(context))
    }

    private fun mockContextWithVersion(version: String): Context {
        val prefs = mockk<SharedPreferences>()
        every { prefs.getBoolean("installed", false) } returns true
        every { prefs.getString("version", null) } returns version
        return mockk { every { getSharedPreferences("gecko_engine", Context.MODE_PRIVATE) } returns prefs }
    }
}
