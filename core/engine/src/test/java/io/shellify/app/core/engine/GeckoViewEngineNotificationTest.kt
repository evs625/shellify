package io.shellify.app.core.engine

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeckoViewEngineNotificationTest {

    private fun callbackPair(): Pair<BrowserEngineCallback, GeckoNotificationCallback> {
        val browser = mockk<BrowserEngineCallback>(relaxed = true)
        val gecko = mockk<GeckoNotificationCallback>(relaxed = true)
        val combined = object : BrowserEngineCallback by browser, GeckoNotificationCallback by gecko {}
        return combined to gecko
    }

    @Test
    fun `dispatch uses public Gecko tag and forwards display result`() {
        val (callback, gecko) = callbackPair()
        var displayed = false
        every {
            gecko.onGeckoNotificationReceived("Hi", "Body", "icon", "gecko-tag", any())
        } answers {
            lastArg<(Boolean) -> Unit>().invoke(true)
        }

        dispatchGeckoNotification(
            NotificationPayload("Hi", "Body", "icon", "gecko-tag"),
            callback,
        ) { displayed = it }

        assertTrue(displayed)
        verify(exactly = 1) {
            gecko.onGeckoNotificationReceived("Hi", "Body", "icon", "gecko-tag", any())
        }
    }

    @Test
    fun `missing title fails display without invoking host`() {
        val (callback, gecko) = callbackPair()
        var displayed = true

        dispatchGeckoNotification(
            NotificationPayload(null, "Body", "icon", "gecko-tag"),
            callback,
        ) { displayed = it }

        assertFalse(displayed)
        verify(exactly = 0) {
            gecko.onGeckoNotificationReceived(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `host without Gecko notification contract fails closed`() {
        val callback = mockk<BrowserEngineCallback>(relaxed = true)
        var displayed = true

        dispatchGeckoNotification(
            NotificationPayload("Hi", null, null, "gecko-tag"),
            callback,
        ) { displayed = it }

        assertFalse(displayed)
    }
}
