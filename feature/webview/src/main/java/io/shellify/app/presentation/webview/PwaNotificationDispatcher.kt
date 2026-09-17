package io.shellify.app.presentation.webview

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.shellify.app.domain.model.NotificationChannelId
import io.shellify.app.domain.model.NotificationPermission
import io.shellify.app.domain.model.PwaNotification
import io.shellify.app.domain.model.WebApp
import io.shellify.app.domain.usecase.CountNotificationsTodayUseCase
import io.shellify.app.domain.usecase.GetCategoryByIdUseCase
import io.shellify.app.domain.usecase.IsDndActiveUseCase
import io.shellify.app.domain.usecase.SaveNotificationUseCase
import io.shellify.core.ui.R

class PwaNotificationDispatcher(
    private val context: Context,
    private val isGlobalNotificationsEnabled: () -> Boolean = { true },
    private val isDndActive: IsDndActiveUseCase,
    private val saveNotification: SaveNotificationUseCase,
    private val countToday: CountNotificationsTodayUseCase,
    private val getCategoryById: GetCategoryByIdUseCase? = null,
    private val notificationManagerProvider: (Context) -> NotificationManagerCompat = { NotificationManagerCompat.from(it) },
    private val checkPostPermission: (Context) -> Boolean = { ctx ->
        android.os.Build.VERSION.SDK_INT < POST_NOTIFICATIONS_SDK_INT ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    },
    // Injected for testability — Robolectric library module tests cannot resolve cross-module resource IDs
    private val channelNameProvider: (appName: String) -> String = { name ->
        context.getString(R.string.notification_channel_name, name)
    },
    private val channelDescProvider: (appName: String) -> String = { name ->
        context.getString(R.string.notification_channel_description, name)
    },
    private val tapIntentProvider: (appId: Long) -> PendingIntent? = { appId ->
        runCatching {
            PendingIntent.getActivity(
                context,
                (appId and 0x7FFFFFFFL).toInt(), // mask to positive Int range — Long IDs can overflow
                WebViewActivity.launchIntent(context, appId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }.getOrNull()
    },
) {

    private val smallIcon: Int get() = R.drawable.ic_app_logo_fg

    private val isNightMode: Boolean
        get() = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    internal class GeckoNotificationHandle internal constructor(
        val appId: Long,
        val tag: String,
        val generation: Long,
    )

    private data class GeckoNotificationKey(val appId: Long, val tag: String)

    private sealed interface GeckoNotificationState {
        val generation: Long
        data class Pending(override val generation: Long, val reuseNotificationId: Int?) : GeckoNotificationState
        data class Active(override val generation: Long, val notificationId: Int) : GeckoNotificationState
        data class Closed(override val generation: Long) : GeckoNotificationState
    }

    private val geckoNotificationLock = Any()
    private val geckoNotificationStates = mutableMapOf<GeckoNotificationKey, GeckoNotificationState>()
    private var nextGeckoGeneration = 0L

    sealed interface DispatchResult {
        data class Posted(val notificationId: Int) : DispatchResult
        sealed interface Dropped : DispatchResult {
            data object GloballyDisabled : Dropped
            data object PermissionDenied : Dropped
            data object NotAsked : Dropped
            data object DndActive : Dropped
            data object RateLimited : Dropped
            data object OsPermissionMissing : Dropped
            data object ChannelDisabled : Dropped
            data object ClosedBeforePost : Dropped
            data object Superseded : Dropped
            data object PostFailed : Dropped
        }
    }

    companion object {
        const val RATE_LIMIT_PER_DAY = 100
        const val MAX_TITLE_LEN = 256
        const val MAX_BODY_LEN = 1024
        const val MAX_ICON_LEN = 2048
        private const val GROUP_PREFIX = "pwa_group_cat_"
        private const val GROUP_ID_DEFAULT = "pwa_group_cat_default"

        fun channelId(isolationId: String) = NotificationChannelId.forApp(isolationId)
        private const val TAG = "PwaNotifDispatcher"
        @Suppress("MagicNumber")
        private const val POST_NOTIFICATIONS_SDK_INT = 33
        @Suppress("MagicNumber")
        private const val LARGE_ICON_MAX_PX = 256
    }

    private fun loadScaledBitmap(path: String): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        val raw = maxOf(opts.outWidth, opts.outHeight)
        if (raw <= 0) return null
        opts.inSampleSize = generateSequence(1) { it * 2 }.first { raw / it <= LARGE_ICON_MAX_PX }
        opts.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(path, opts)
    }

    internal fun beginGeckoNotification(app: WebApp, tag: String): GeckoNotificationHandle =
        synchronized(geckoNotificationLock) {
            val key = GeckoNotificationKey(app.id, tag)
            val reusableId = (geckoNotificationStates[key] as? GeckoNotificationState.Active)?.notificationId
            val generation = ++nextGeckoGeneration
            geckoNotificationStates[key] = GeckoNotificationState.Pending(generation, reusableId)
            GeckoNotificationHandle(app.id, tag, generation)
        }

    internal fun closeGeckoNotification(app: WebApp, tag: String) {
        val key = GeckoNotificationKey(app.id, tag)
        val manager = notificationManagerProvider(context)
        synchronized(geckoNotificationLock) {
            when (val state = geckoNotificationStates[key]) {
                is GeckoNotificationState.Active -> {
                    runCatching { manager.cancel(state.notificationId) }
                    geckoNotificationStates.remove(key)
                }
                is GeckoNotificationState.Pending -> {
                    geckoNotificationStates[key] = GeckoNotificationState.Closed(state.generation)
                }
                is GeckoNotificationState.Closed, null -> Unit
            }
        }
    }

    internal fun finishGeckoNotification(handle: GeckoNotificationHandle) {
        val key = GeckoNotificationKey(handle.appId, handle.tag)
        synchronized(geckoNotificationLock) {
            val state = geckoNotificationStates[key] ?: return@synchronized
            if (state.generation == handle.generation && state !is GeckoNotificationState.Active) {
                geckoNotificationStates.remove(key)
            }
        }
    }

    suspend fun dispatch(
        app: WebApp,
        title: String,
        body: String?,
        iconUrl: String?,
        tag: String?,
    ): DispatchResult = dispatchInternal(app, title, body, iconUrl, tag, geckoHandle = null)

    internal suspend fun dispatchGecko(
        handle: GeckoNotificationHandle,
        app: WebApp,
        title: String,
        body: String?,
        iconUrl: String?,
    ): DispatchResult = dispatchInternal(app, title, body, iconUrl, handle.tag, handle)

    private suspend fun dispatchInternal(
        app: WebApp,
        title: String,
        body: String?,
        iconUrl: String?,
        tag: String?,
        geckoHandle: GeckoNotificationHandle?,
    ): DispatchResult {
        dropReason(app)?.let { return it }
        val target = prepareChannel(app)
        if (geckoHandle != null && !target.isEnabled) return DispatchResult.Dropped.ChannelDisabled
        val safeTitle = title.take(MAX_TITLE_LEN)
        val safeBody = (body ?: "").take(MAX_BODY_LEN)
        val notification = buildNotification(app, target.channelId, safeTitle, safeBody)
        val result = postNotification(target.manager, app, notification, geckoHandle)
        if (result is DispatchResult.Posted) {
            if (geckoHandle == null) {
                saveHistory(app, safeTitle, safeBody, iconUrl)
            } else {
                saveGeckoHistory(app, safeTitle, safeBody, iconUrl, geckoHandle.tag)
            }
        }
        return if (result is DispatchResult.Posted && geckoHandle != null) {
            confirmGeckoPostStillActive(geckoHandle, result)
        } else {
            result
        }
    }

    private suspend fun dropReason(app: WebApp): DispatchResult.Dropped? {
        if (!isGlobalNotificationsEnabled()) return DispatchResult.Dropped.GloballyDisabled
        if (app.notificationPermission == NotificationPermission.DENIED) {
            return DispatchResult.Dropped.PermissionDenied
        }
        if (app.notificationPermission == NotificationPermission.NOT_ASKED) {
            return DispatchResult.Dropped.NotAsked
        }
        if (isDndActive(app.dndStartHour, app.dndEndHour)) return DispatchResult.Dropped.DndActive
        if (countToday(app.id) >= RATE_LIMIT_PER_DAY) return DispatchResult.Dropped.RateLimited
        if (!checkPostPermission(context)) return DispatchResult.Dropped.OsPermissionMissing
        return null
    }

    private data class ChannelTarget(
        val manager: NotificationManagerCompat,
        val channelId: String,
        val isEnabled: Boolean,
    )

    private suspend fun prepareChannel(app: WebApp): ChannelTarget {
        val category = app.categoryId?.let { getCategoryById?.invoke(it) }
        val groupId = if (category != null) "$GROUP_PREFIX${category.id}" else GROUP_ID_DEFAULT
        val groupName = category?.name ?: context.getString(R.string.notification_channel_name_default)
        val channelId = NotificationChannelId.forApp(app.isolationId)
        val manager = notificationManagerProvider(context)
        manager.createNotificationChannelGroup(NotificationChannelGroup(groupId, groupName))
        val channel = NotificationChannel(
            channelId,
            channelNameProvider(app.name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = channelDescProvider(app.name)
            group = groupId
        }
        manager.createNotificationChannel(channel)
        val isEnabled = manager.getNotificationChannel(channelId)?.importance != NotificationManager.IMPORTANCE_NONE
        return ChannelTarget(manager, channelId, isEnabled)
    }

    private fun buildNotification(
        app: WebApp,
        channelId: String,
        safeTitle: String,
        safeBody: String,
    ): Notification {
        val appIcon = app.iconPath?.let { path -> runCatching { loadScaledBitmap(path) }.getOrNull() }
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIcon)
            .setColor(if (isNightMode) Color.WHITE else Color.BLACK)
            .apply { appIcon?.let { setLargeIcon(it) } }
            .setContentTitle(safeTitle)
            .setContentText(safeBody)
            .setContentIntent(tapIntentProvider(app.id))
            .setAutoCancel(true)
            .build()
    }

    private fun postNotification(
        manager: NotificationManagerCompat,
        app: WebApp,
        notification: Notification,
        handle: GeckoNotificationHandle?,
    ): DispatchResult {
        if (handle == null) {
            val notificationId = newNotificationId(app.id)
            notifyAndroidUnchecked(manager, notificationId, notification)
            return DispatchResult.Posted(notificationId)
        }
        return postGeckoNotification(manager, app, notification, handle)
    }

    private fun postGeckoNotification(
        manager: NotificationManagerCompat,
        app: WebApp,
        notification: Notification,
        handle: GeckoNotificationHandle,
    ): DispatchResult = synchronized(geckoNotificationLock) {
        val key = GeckoNotificationKey(handle.appId, handle.tag)
        val state = geckoNotificationStates[key]
        if (state == null || state.generation != handle.generation) {
            return@synchronized DispatchResult.Dropped.Superseded
        }
        if (state is GeckoNotificationState.Closed) {
            geckoNotificationStates.remove(key)
            return@synchronized DispatchResult.Dropped.ClosedBeforePost
        }
        val notificationId = when (state) {
            is GeckoNotificationState.Pending -> state.reuseNotificationId ?: newNotificationId(app.id)
            is GeckoNotificationState.Active -> state.notificationId
            is GeckoNotificationState.Closed -> error("handled above")
        }
        if (!notifyAndroid(manager, notificationId, notification)) {
            geckoNotificationStates.remove(key)
            return@synchronized DispatchResult.Dropped.PostFailed
        }
        geckoNotificationStates[key] = GeckoNotificationState.Active(handle.generation, notificationId)
        DispatchResult.Posted(notificationId)
    }

    @Suppress("MissingPermission")
    private fun notifyAndroid(manager: NotificationManagerCompat, notificationId: Int, notification: Notification): Boolean =
        runCatching { manager.notify(notificationId, notification) }
            .onFailure { Log.w(TAG, "Android notification post failed", it) }
            .isSuccess

    @Suppress("MagicNumber")
    private fun newNotificationId(appId: Long): Int =
        (appId.toInt() shl 16) or (System.currentTimeMillis().toInt() and 0xFFFF)

    private fun confirmGeckoPostStillActive(
        handle: GeckoNotificationHandle,
        posted: DispatchResult.Posted,
    ): DispatchResult = synchronized(geckoNotificationLock) {
        val state = geckoNotificationStates[GeckoNotificationKey(handle.appId, handle.tag)]
        if (state is GeckoNotificationState.Active &&
            state.generation == handle.generation &&
            state.notificationId == posted.notificationId
        ) {
            posted
        } else {
            DispatchResult.Dropped.Superseded
        }
    }

    private suspend fun saveHistory(
        app: WebApp,
        safeTitle: String,
        safeBody: String,
        iconUrl: String?,
    ) {
        saveNotification(historyEntry(app, safeTitle, safeBody, iconUrl))
    }

    private suspend fun saveGeckoHistory(
        app: WebApp,
        safeTitle: String,
        safeBody: String,
        iconUrl: String?,
        tag: String,
    ) {
        runCatching { saveNotification(historyEntry(app, safeTitle, safeBody, iconUrl)) }
            .onFailure { Log.w(TAG, "Notification history save failed for Gecko tag=$tag", it) }
    }

    private fun historyEntry(
        app: WebApp,
        safeTitle: String,
        safeBody: String,
        iconUrl: String?,
    ): PwaNotification = PwaNotification(
        appId = app.id,
        title = safeTitle,
        body = safeBody.ifEmpty { null },
        iconUrl = iconUrl?.take(MAX_ICON_LEN),
        timestamp = System.currentTimeMillis(),
        isRead = false,
    )

    @Suppress("MissingPermission")
    private fun notifyAndroidUnchecked(
        manager: NotificationManagerCompat,
        notificationId: Int,
        notification: Notification,
    ) {
        manager.notify(notificationId, notification)
    }

}
