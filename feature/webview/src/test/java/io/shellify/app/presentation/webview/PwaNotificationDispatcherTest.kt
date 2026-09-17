package io.shellify.app.presentation.webview

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.shellify.app.domain.model.NotificationPermission
import io.shellify.app.domain.model.PwaNotification
import io.shellify.app.domain.model.WebApp
import io.shellify.app.domain.usecase.CountNotificationsTodayUseCase
import io.shellify.app.domain.usecase.IsDndActiveUseCase
import io.shellify.app.domain.usecase.SaveNotificationUseCase
import io.shellify.app.presentation.webview.PwaNotificationDispatcher.DispatchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PwaNotificationDispatcherTest {

    private lateinit var context: Context
    private val isDndActive = mockk<IsDndActiveUseCase>(relaxed = true)
    private val saveNotification = mockk<SaveNotificationUseCase>()
    private val countToday = mockk<CountNotificationsTodayUseCase>()
    private val mockManager = mockk<NotificationManagerCompat>(relaxed = true)

    private fun buildDispatcher(
        checkPostPermission: (Context) -> Boolean = { true },
        isGlobalNotificationsEnabled: () -> Boolean = { true },
    ) = PwaNotificationDispatcher(
        context = context,
        isGlobalNotificationsEnabled = isGlobalNotificationsEnabled,
        isDndActive = isDndActive,
        saveNotification = saveNotification,
        countToday = countToday,
        notificationManagerProvider = { mockManager },
        checkPostPermission = checkPostPermission,
        channelNameProvider = { name -> "$name notifications" },
        channelDescProvider = { name -> "Notifications from $name" },
    )

    private fun appWith(
        permission: NotificationPermission,
        isolationId: String = "test-iso",
        dndStart: Int = -1,
        dndEnd: Int = -1,
    ) = WebApp(
        id = 1L,
        name = "Test App",
        url = "https://example.com",
        isolationId = isolationId,
        notificationPermission = permission,
        dndStartHour = dndStart,
        dndEndHour = dndEnd,
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        every { mockManager.getNotificationChannel(any()) } returns null
    }

    @Test
    fun `dispatch with denied permission returns Dropped and does not post`() = runTest {
        val app = appWith(NotificationPermission.DENIED)
        val dispatcher = buildDispatcher()

        val result = dispatcher.dispatch(app, "Title", "Body", null, null)

        assertTrue(result is DispatchResult.Dropped.PermissionDenied)
        coVerify(exactly = 0) { mockManager.notify(any(), any()) }
        coVerify(exactly = 0) { saveNotification(any()) }
    }

    @Test
    fun `dispatch with not asked permission drops as NotAsked`() = runTest {
        val app = appWith(NotificationPermission.NOT_ASKED)
        val dispatcher = buildDispatcher()

        val result = dispatcher.dispatch(app, "Title", "Body", null, null)

        assertTrue(result is DispatchResult.Dropped.NotAsked)
        coVerify(exactly = 0) { mockManager.notify(any(), any()) }
        coVerify(exactly = 0) { saveNotification(any()) }
    }

    @Test
    fun `dispatch with DND active drops as DndActive`() = runTest {
        val app = appWith(NotificationPermission.GRANTED, dndStart = 22, dndEnd = 8)
        every { isDndActive(22, 8, any()) } returns true
        val dispatcher = buildDispatcher()

        val result = dispatcher.dispatch(app, "Title", "Body", null, null)

        assertTrue(result is DispatchResult.Dropped.DndActive)
        coVerify(exactly = 0) { mockManager.notify(any(), any()) }
        coVerify(exactly = 0) { saveNotification(any()) }
    }

    @Test
    fun `dispatch over rate limit drops as RateLimited`() = runTest {
        val app = appWith(NotificationPermission.GRANTED)
        every { isDndActive(any(), any(), any()) } returns false
        coEvery { countToday(app.id, any()) } returns 100
        val dispatcher = buildDispatcher()

        val result = dispatcher.dispatch(app, "Title", "Body", null, null)

        assertTrue(result is DispatchResult.Dropped.RateLimited)
        coVerify(exactly = 0) { mockManager.notify(any(), any()) }
        coVerify(exactly = 0) { saveNotification(any()) }
    }

    @Test
    fun `dispatch at 101 is also rate limited`() = runTest {
        val app = appWith(NotificationPermission.GRANTED)
        every { isDndActive(any(), any(), any()) } returns false
        coEvery { countToday(app.id, any()) } returns 101
        val dispatcher = buildDispatcher()

        val result = dispatcher.dispatch(app, "Title", "Body", null, null)

        assertTrue(result is DispatchResult.Dropped.RateLimited)
    }

    @Test
    fun `dispatch at 99 proceeds to post`() = runTest {
        val app = appWith(NotificationPermission.GRANTED)
        every { isDndActive(any(), any(), any()) } returns false
        coEvery { countToday(app.id, any()) } returns 99
        coEvery { saveNotification(any()) } returns 1L
        val dispatcher = buildDispatcher()

        val result = dispatcher.dispatch(app, "Title", "Body", null, null)

        assertTrue(result is DispatchResult.Posted)
        coVerify(exactly = 1) { mockManager.notify(any(), any()) }
    }

    @Test
    fun `dispatch with granted and under limit posts and saves`() = runTest {
        val app = appWith(NotificationPermission.GRANTED, isolationId = "abc123")
        every { isDndActive(any(), any(), any()) } returns false
        coEvery { countToday(app.id, any()) } returns 0
        val notificationSlot = slot<PwaNotification>()
        coEvery { saveNotification(capture(notificationSlot)) } returns 1L
        val dispatcher = buildDispatcher()

        val result = dispatcher.dispatch(app, "Hello", "World", null, null)

        assertTrue(result is DispatchResult.Posted)
        coVerify(exactly = 1) { mockManager.notify(any(), any()) }
        coVerify(exactly = 1) { saveNotification(any()) }
        assertNotNull(notificationSlot.captured)
        assertEquals("Hello", notificationSlot.captured.title)
        assertEquals("World", notificationSlot.captured.body)
    }

    @Test
    fun `dispatch title truncated to 256`() = runTest {
        val app = appWith(NotificationPermission.GRANTED)
        every { isDndActive(any(), any(), any()) } returns false
        coEvery { countToday(app.id, any()) } returns 0
        val notificationSlot = slot<PwaNotification>()
        coEvery { saveNotification(capture(notificationSlot)) } returns 1L
        val longTitle = "A".repeat(300)
        val dispatcher = buildDispatcher()

        dispatcher.dispatch(app, longTitle, "Body", null, null)

        assertEquals(256, notificationSlot.captured.title.length)
    }

    @Test
    fun `dispatch body truncated to 1024`() = runTest {
        val app = appWith(NotificationPermission.GRANTED)
        every { isDndActive(any(), any(), any()) } returns false
        coEvery { countToday(app.id, any()) } returns 0
        val notificationSlot = slot<PwaNotification>()
        coEvery { saveNotification(capture(notificationSlot)) } returns 1L
        val longBody = "B".repeat(1200)
        val dispatcher = buildDispatcher()

        dispatcher.dispatch(app, "Title", longBody, null, null)

        assertEquals(1024, notificationSlot.captured.body?.length)
    }

    @Test
    fun `dispatch channel id uses isolation id`() = runTest {
        val app = appWith(NotificationPermission.GRANTED, isolationId = "abc123")
        every { isDndActive(any(), any(), any()) } returns false
        coEvery { countToday(app.id, any()) } returns 0
        coEvery { saveNotification(any()) } returns 1L
        val dispatcher = buildDispatcher()

        val result = dispatcher.dispatch(app, "Title", null, null, null)

        assertTrue(result is DispatchResult.Posted)
        coVerify(exactly = 1) { mockManager.notify(any(), any()) }
    }

    @Test
    fun `dispatch when globally disabled drops as GloballyDisabled before any other gate`() = runTest {
        val app = appWith(NotificationPermission.GRANTED)
        every { isDndActive(any(), any(), any()) } returns false
        coEvery { countToday(app.id, any()) } returns 0
        val dispatcher = buildDispatcher(isGlobalNotificationsEnabled = { false })

        val result = dispatcher.dispatch(app, "Title", "Body", null, null)

        assertTrue(result is DispatchResult.Dropped.GloballyDisabled)
        coVerify(exactly = 0) { mockManager.notify(any(), any()) }
        coVerify(exactly = 0) { saveNotification(any()) }
    }

    @Test
    fun `dispatch with OS permission missing drops without posting`() = runTest {
        val app = appWith(NotificationPermission.GRANTED)
        every { isDndActive(any(), any(), any()) } returns false
        coEvery { countToday(app.id, any()) } returns 0
        val dispatcher = buildDispatcher(checkPostPermission = { false })

        val result = dispatcher.dispatch(app, "Title", "Body", null, null)

        assertTrue(result is DispatchResult.Dropped.OsPermissionMissing)
        coVerify(exactly = 0) { mockManager.notify(any(), any()) }
        coVerify(exactly = 0) { saveNotification(any()) }
    }
    @Test
    fun `disabled app channel reports Gecko notification not posted`() = runTest {
        val app = appWith(NotificationPermission.GRANTED)
        every { isDndActive(any(), any(), any()) } returns false
        coEvery { countToday(app.id, any()) } returns 0
        every { mockManager.getNotificationChannel(any()) } returns
            NotificationChannel("disabled", "Disabled", NotificationManager.IMPORTANCE_NONE)
        val dispatcher = buildDispatcher()
        val handle = dispatcher.beginGeckoNotification(app, "disabled-tag")

        val result = dispatcher.dispatchGecko(handle, app, "Title", "Body", null)

        assertTrue(result is DispatchResult.Dropped.ChannelDisabled)
        coVerify(exactly = 0) { mockManager.notify(any(), any()) }
    }

    @Test
    fun `Gecko close before post suppresses notification`() = runTest {
        val app = appWith(NotificationPermission.GRANTED)
        every { isDndActive(any(), any(), any()) } returns false
        coEvery { countToday(app.id, any()) } returns 0
        val dispatcher = buildDispatcher()
        val handle = dispatcher.beginGeckoNotification(app, "gecko-tag")
        dispatcher.closeGeckoNotification(app, "gecko-tag")

        val result = dispatcher.dispatchGecko(handle, app, "Title", "Body", null)

        assertTrue(result is DispatchResult.Dropped.ClosedBeforePost)
        coVerify(exactly = 0) { mockManager.notify(any(), any()) }
    }

    @Test
    fun `Gecko tag replacement reuses Android notification id`() = runTest {
        val app = appWith(NotificationPermission.GRANTED)
        every { isDndActive(any(), any(), any()) } returns false
        coEvery { countToday(app.id, any()) } returns 0
        coEvery { saveNotification(any()) } returns 1L
        val dispatcher = buildDispatcher()
        val ids = mutableListOf<Int>()
        every { mockManager.notify(capture(ids), any()) } returns Unit

        val first = dispatcher.beginGeckoNotification(app, "same-tag")
        val firstResult = dispatcher.dispatchGecko(first, app, "One", null, null)
        val second = dispatcher.beginGeckoNotification(app, "same-tag")
        val secondResult = dispatcher.dispatchGecko(second, app, "Two", null, null)

        assertTrue(firstResult is DispatchResult.Posted)
        assertTrue(secondResult is DispatchResult.Posted)
        assertEquals(2, ids.size)
        assertEquals(ids[0], ids[1])
    }

    @Test
    fun `closing active Gecko notification cancels mapped Android id`() = runTest {
        val app = appWith(NotificationPermission.GRANTED)
        every { isDndActive(any(), any(), any()) } returns false
        coEvery { countToday(app.id, any()) } returns 0
        coEvery { saveNotification(any()) } returns 1L
        val dispatcher = buildDispatcher()
        val handle = dispatcher.beginGeckoNotification(app, "close-me")
        val result = dispatcher.dispatchGecko(handle, app, "Title", null, null)
        val notificationId = (result as DispatchResult.Posted).notificationId

        dispatcher.closeGeckoNotification(app, "close-me")

        coVerify(exactly = 1) { mockManager.cancel(notificationId) }
    }

    @Test
    fun `superseded Gecko show cannot post after newer show begins`() = runTest {
        val app = appWith(NotificationPermission.GRANTED)
        every { isDndActive(any(), any(), any()) } returns false
        coEvery { countToday(app.id, any()) } returns 0
        val dispatcher = buildDispatcher()
        val old = dispatcher.beginGeckoNotification(app, "same-tag")
        dispatcher.beginGeckoNotification(app, "same-tag")

        val result = dispatcher.dispatchGecko(old, app, "Old", null, null)

        assertTrue(result is DispatchResult.Dropped.Superseded)
        coVerify(exactly = 0) { mockManager.notify(any(), any()) }
    }

    @Test
    fun `Gecko close while history save is suspended cannot acknowledge shown`() = runTest {
        val app = appWith(NotificationPermission.GRANTED)
        every { isDndActive(any(), any(), any()) } returns false
        coEvery { countToday(app.id, any()) } returns 0
        val saveStarted = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        coEvery { saveNotification(any()) } coAnswers {
            saveStarted.complete(Unit)
            releaseSave.await()
            1L
        }
        val dispatcher = buildDispatcher()
        val handle = dispatcher.beginGeckoNotification(app, "race-tag")

        val result = async { dispatcher.dispatchGecko(handle, app, "Title", "Body", null) }
        saveStarted.await()
        dispatcher.closeGeckoNotification(app, "race-tag")
        releaseSave.complete(Unit)

        assertTrue(result.await() is DispatchResult.Dropped.Superseded)
        coVerify(exactly = 1) { mockManager.cancel(any()) }
    }

}
