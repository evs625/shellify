package io.shellify.app.core.engine

import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.mozilla.geckoview.WebNotification

class GeckoWebNotificationDelegateTest {

    @Test
    fun `displayed notification calls Gecko show`() {
        val notification = mockk<WebNotification>(relaxed = true)

        acknowledgeWebNotification(notification, displayed = true)

        verify(exactly = 1) { notification.show() }
        verify(exactly = 0) { notification.dismiss() }
    }

    @Test
    fun `failed or closed notification calls Gecko dismiss`() {
        val notification = mockk<WebNotification>(relaxed = true)

        acknowledgeWebNotification(notification, displayed = false)

        verify(exactly = 0) { notification.show() }
        verify(exactly = 1) { notification.dismiss() }
    }
}
