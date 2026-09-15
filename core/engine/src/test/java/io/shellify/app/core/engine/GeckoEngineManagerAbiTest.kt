package io.shellify.app.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
