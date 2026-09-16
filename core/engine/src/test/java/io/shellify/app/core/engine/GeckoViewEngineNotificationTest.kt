package io.shellify.app.core.engine

import io.mockk.every
import io.mockk.slot
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test

class GeckoViewEngineNotificationTest {

    @Test
    fun `dispatchNotification reports shown result from callback`() {
        val cb = mockk<BrowserEngineCallback>(relaxed = true)
        val payload = NotificationPayload(title = "Hi", body = "Body", iconUrl = "icon", tag = "t1")
        var shown: Boolean? = null
        val resultCallback = slot<(Boolean) -> Unit>()
        every { cb.onNotificationReceived("Hi", "Body", "icon", "t1", capture(resultCallback)) } answers {
            resultCallback.captured.invoke(true)
        }

        dispatchNotification(payload, cb) { shown = it }

        assertEquals(true, shown)
        verify(exactly = 1) { cb.onNotificationReceived("Hi", "Body", "icon", "t1", any()) }
    }

    @Test
    fun `dispatchNotification with null title reports not shown`() {
        val cb = mockk<BrowserEngineCallback>(relaxed = true)
        val payload = NotificationPayload(title = null, body = "Body", iconUrl = "icon", tag = "t1")
        var shown: Boolean? = null

        dispatchNotification(payload, cb) { shown = it }

        assertEquals(false, shown)
        verify(exactly = 0) { cb.onNotificationReceived(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `dispatchNotification with null body and icon forwards values`() {
        val cb = mockk<BrowserEngineCallback>(relaxed = true)
        val payload = NotificationPayload(title = "OK", body = null, iconUrl = null, tag = "")

        dispatchNotification(payload, cb) {}

        verify(exactly = 1) { cb.onNotificationReceived("OK", null, null, "", any()) }
    }
}
