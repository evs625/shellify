package io.shellify.app.presentation.webview

import android.app.NotificationChannel
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
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

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

    private data class NotificationKey(val appId: Long, val tag: String)

    private val activeNotificationIds = ConcurrentHashMap<NotificationKey, Int>()

    private val smallIcon: Int get() = R.drawable.ic_app_logo_fg

    private val isNightMode: Boolean
        get() = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

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
            data object DispatchFailed : Dropped
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

    suspend fun dispatch(
        app: WebApp,
        title: String,
        body: String?,
        iconUrl: String?,
        tag: String?,
    ): DispatchResult = try {
        dispatchInternal(app, title, body, iconUrl, tag)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Notification dispatch failed for app ${app.id}", e)
        DispatchResult.Dropped.DispatchFailed
    }

    private suspend fun dispatchInternal(
        app: WebApp,
        title: String,
        body: String?,
        iconUrl: String?,
        tag: String?,
    ): DispatchResult {
        // Gate 0: global notifications disabled
        if (!isGlobalNotificationsEnabled()) {
            Log.d(TAG, "Dropped: global notifications disabled")
            return DispatchResult.Dropped.GloballyDisabled
        }

        // Gate 1: permission denied
        if (app.notificationPermission == NotificationPermission.DENIED) {
            Log.d(TAG, "Dropped: permission denied for app ${app.id}")
            return DispatchResult.Dropped.PermissionDenied
        }

        // Gate 2: permission not asked yet — suppress until the in-app/OS permission flow completes.
        if (app.notificationPermission == NotificationPermission.NOT_ASKED) {
            Log.d(TAG, "Dropped: permission not asked for app ${app.id}")
            return DispatchResult.Dropped.NotAsked
        }

        // Gate 3: DND active
        if (isDndActive(app.dndStartHour, app.dndEndHour)) {
            Log.d(TAG, "Dropped: DND active for app ${app.id}")
            return DispatchResult.Dropped.DndActive
        }

        // Gate 4: rate limit
        val todayCount = countToday(app.id)
        if (todayCount >= RATE_LIMIT_PER_DAY) {
            Log.d(TAG, "Dropped: rate limit reached ($todayCount) for app ${app.id}")
            return DispatchResult.Dropped.RateLimited
        }

        // Gate 5: OS-level POST_NOTIFICATIONS permission
        if (!checkPostPermission(context)) {
            Log.d(TAG, "Dropped: OS POST_NOTIFICATIONS permission missing for app ${app.id}")
            return DispatchResult.Dropped.OsPermissionMissing
        }

        val category = app.categoryId?.let { getCategoryById?.invoke(it) }
        val groupId = if (category != null) "$GROUP_PREFIX${category.id}" else GROUP_ID_DEFAULT
        val groupName = if (category != null) category.name
            else context.getString(R.string.notification_channel_name_default)
        val channelId = NotificationChannelId.forApp(app.isolationId)
        val manager = notificationManagerProvider(context)

        manager.createNotificationChannelGroup(
            android.app.NotificationChannelGroup(groupId, groupName)
        )

        val channel = NotificationChannel(
            channelId,
            channelNameProvider(app.name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = channelDescProvider(app.name)
            group = groupId
        }
        manager.createNotificationChannel(channel)

        // Recreating a channel preserves the user's Android settings. A disabled channel makes
        // notify() a silent no-op, so report a drop instead of telling Gecko it was shown.
        if (manager.getNotificationChannel(channelId)?.importance == NotificationManager.IMPORTANCE_NONE) {
            Log.d(TAG, "Dropped: Android notification channel disabled for app ${app.id}")
            return DispatchResult.Dropped.ChannelDisabled
        }

        val safeTitle = title.take(MAX_TITLE_LEN)
        val safeBody = (body ?: "").take(MAX_BODY_LEN)
        val appIcon: Bitmap? = app.iconPath?.let { path ->
            runCatching { loadScaledBitmap(path) }.getOrNull()
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIcon)
            .setColor(if (isNightMode) Color.WHITE else Color.BLACK)
            .apply { appIcon?.let { setLargeIcon(it) } }
            .setContentTitle(safeTitle)
            .setContentText(safeBody)
            .setContentIntent(tapIntentProvider(app.id))
            .setAutoCancel(true)
            .build()

        @Suppress("MagicNumber")
        val notificationId = (app.id.toInt() shl 16) or (System.currentTimeMillis().toInt() and 0xFFFF)
        @Suppress("MissingPermission") // POST_NOTIFICATIONS declared in app manifest; gated above.
        manager.notify(notificationId, notification)

        // From this point the Android notification is displayed. Auxiliary bookkeeping must not
        // downgrade the Gecko display result if it fails.
        tag?.let { notificationTag ->
            val previousId = activeNotificationIds.put(NotificationKey(app.id, notificationTag), notificationId)
            if (previousId != null && previousId != notificationId) {
                runCatching { manager.cancel(previousId) }
                    .onFailure { Log.w(TAG, "Failed to cancel replaced notification $previousId", it) }
            }
        }

        try {
            saveNotification(
                PwaNotification(
                    appId = app.id,
                    title = safeTitle,
                    body = safeBody.ifEmpty { null },
                    iconUrl = iconUrl?.take(MAX_ICON_LEN),
                    timestamp = System.currentTimeMillis(),
                    isRead = false,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Notification posted but history persistence failed for app ${app.id}", e)
        }

        return DispatchResult.Posted(notificationId)
    }

    /** Cancels the Android notification corresponding to a Gecko WebNotification.close(). */
    fun cancelPostedNotification(app: WebApp, tag: String?) {
        val notificationTag = tag ?: return
        val notificationId = activeNotificationIds.remove(NotificationKey(app.id, notificationTag)) ?: return
        runCatching { notificationManagerProvider(context).cancel(notificationId) }
            .onFailure { Log.w(TAG, "Failed to cancel closed notification $notificationId", it) }
    }
}
