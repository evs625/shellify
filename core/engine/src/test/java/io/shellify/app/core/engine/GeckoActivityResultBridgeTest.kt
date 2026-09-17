package io.shellify.app.core.engine

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.geckoview.GeckoResult

class GeckoActivityResultBridgeTest {

    private class RecordingHost(
        private val failure: Exception? = null,
    ) : GeckoActivityHost {
        val launches = mutableListOf<Pair<PendingIntent, Int>>()

        override fun startPendingIntent(pendingIntent: PendingIntent, requestCode: Int) {
            failure?.let { throw it }
            launches += pendingIntent to requestCode
        }
    }

    @Test
    fun `no eligible host completes exceptionally`() {
        val result = mockk<GeckoResult<Intent>>(relaxed = true)
        val bridge = GeckoActivityResultBridge { result }

        assertSame(result, bridge.onStartActivityForResult(mockk()))

        verify(exactly = 1) { result.completeExceptionally(any<IllegalStateException>()) }
    }

    @Test
    fun `result is delivered only to launching host`() {
        val result = mockk<GeckoResult<Intent>>(relaxed = true)
        val bridge = GeckoActivityResultBridge { result }
        val owner = Any()
        val other = Any()
        val host = RecordingHost()
        bridge.attachHost(owner, host, isEligible = true)
        val pendingIntent = mockk<PendingIntent>()
        val data = mockk<Intent>()

        bridge.onStartActivityForResult(pendingIntent)
        val requestCode = host.launches.single().second

        assertFalse(bridge.onActivityResult(other, requestCode, Activity.RESULT_OK, data))
        verify(exactly = 0) { result.complete(any()) }
        assertTrue(bridge.onActivityResult(owner, requestCode, Activity.RESULT_OK, data))
        verify(exactly = 1) { result.complete(data) }
    }

    @Test
    fun `non ok result completes exceptionally`() {
        val result = mockk<GeckoResult<Intent>>(relaxed = true)
        val bridge = GeckoActivityResultBridge { result }
        val owner = Any()
        val host = RecordingHost()
        bridge.attachHost(owner, host, isEligible = true)

        bridge.onStartActivityForResult(mockk())
        val requestCode = host.launches.single().second

        assertTrue(bridge.onActivityResult(owner, requestCode, Activity.RESULT_CANCELED, null))
        verify(exactly = 1) { result.completeExceptionally(any<IllegalStateException>()) }
    }

    @Test
    fun `launch failure completes exceptionally and releases request`() {
        val failure = IllegalStateException("launch failed")
        val result = mockk<GeckoResult<Intent>>(relaxed = true)
        val bridge = GeckoActivityResultBridge { result }
        val owner = Any()
        bridge.attachHost(owner, RecordingHost(failure), isEligible = true)

        bridge.onStartActivityForResult(mockk())

        verify(exactly = 1) { result.completeExceptionally(failure) }
    }

    @Test
    fun `destroyed host fails its pending result`() {
        val result = mockk<GeckoResult<Intent>>(relaxed = true)
        val bridge = GeckoActivityResultBridge { result }
        val owner = Any()
        val host = RecordingHost()
        bridge.attachHost(owner, host, isEligible = true)
        bridge.onStartActivityForResult(mockk())
        val requestCode = host.launches.single().second

        bridge.detachHost(owner)

        verify(exactly = 1) { result.completeExceptionally(any<IllegalStateException>()) }
        assertFalse(bridge.onActivityResult(owner, requestCode, Activity.RESULT_OK, mockk()))
    }

    @Test
    fun `most recently eligible host gets new requests while prior host keeps ownership`() {
        val firstResult = mockk<GeckoResult<Intent>>(relaxed = true)
        val secondResult = mockk<GeckoResult<Intent>>(relaxed = true)
        val results = ArrayDeque(listOf(firstResult, secondResult))
        val bridge = GeckoActivityResultBridge { results.removeFirst() }
        val firstToken = Any()
        val secondToken = Any()
        val firstHost = RecordingHost()
        val secondHost = RecordingHost()

        bridge.attachHost(firstToken, firstHost, isEligible = true)
        bridge.onStartActivityForResult(mockk())
        val firstRequestCode = firstHost.launches.single().second

        bridge.attachHost(secondToken, secondHost, isEligible = true)
        bridge.onStartActivityForResult(mockk())
        val secondRequestCode = secondHost.launches.single().second

        assertFalse(bridge.onActivityResult(secondToken, firstRequestCode, Activity.RESULT_OK, mockk()))
        assertTrue(bridge.onActivityResult(firstToken, firstRequestCode, Activity.RESULT_OK, mockk()))
        assertTrue(bridge.onActivityResult(secondToken, secondRequestCode, Activity.RESULT_OK, mockk()))
        verify(exactly = 1) { firstResult.complete(any()) }
        verify(exactly = 1) { secondResult.complete(any()) }
    }
    @Test
    fun `stopped host keeps ownership of already launched result`() {
        val result = mockk<GeckoResult<Intent>>(relaxed = true)
        val bridge = GeckoActivityResultBridge { result }
        val owner = Any()
        val host = RecordingHost()
        bridge.attachHost(owner, host, isEligible = true)
        bridge.onStartActivityForResult(mockk())
        val requestCode = host.launches.single().second
        val data = mockk<Intent>()

        bridge.setHostEligible(owner, false)

        assertTrue(bridge.onActivityResult(owner, requestCode, Activity.RESULT_OK, data))
        verify(exactly = 1) { result.complete(data) }
    }

}
