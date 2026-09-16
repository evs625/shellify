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
import java.util.concurrent.atomic.AtomicInteger
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

    private data class SourceKey(val appId: Long, val sourceNotificationId: String)
    private data class TagKey(val appId: Long, val tag: String)

    private sealed interface LifecycleState {
        data object Pending : LifecycleState
        data object Closed : LifecycleState
        data class Posted(val notificationId: Int, val tagKey: TagKey?) : LifecycleState
    }

    private val lifecycleLock = Any()
    private val lifecycleStates = mutableMapOf<SourceKey, LifecycleState>()
    private val sourceByNotificationId = mutableMapOf<Int, SourceKey>()
    private val notificationIdsByTag = mutableMapOf<TagKey, Int>()
    private val notificationIdSequence = AtomicInteger((System.currentTimeMillis() and Int.MAX_VALUE.toLong()).toInt())

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
            data object ClosedBeforePost : Dropped
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

    /** Registers the web-engine notification synchronously before asynchronous dispatch starts. */
    fun beginNotification(app: WebApp, sourceNotificationId: String?) {
        val sourceKey = sourceNotificationId?.let { SourceKey(app.id, it) } ?: return
        synchronized(lifecycleLock) {
            lifecycleStates.putIfAbsent(sourceKey, LifecycleState.Pending)
        }
    }

    /** Clears a pending notification when its permission flow is abandoned without a retry. */
    fun abandonPendingNotification(app: WebApp, sourceNotificationId: String?) {
        val sourceKey = sourceNotificationId?.let { SourceKey(app.id, it) } ?: return
        synchronized(lifecycleLock) {
            if (lifecycleStates[sourceKey] !is LifecycleState.Posted) {
                lifecycleStates.remove(sourceKey)
            }
        }
    }

    suspend fun dispatch(
        app: WebApp,
        title: String,
        body: String?,
        iconUrl: String?,
        tag: String?,
        sourceNotificationId: String? = null,
    ): DispatchResult = try {
        dispatchInternal(app, title, body, iconUrl, tag, sourceNotificationId)
    } catch (e: CancellationException) {
        abandonPendingNotification(app, sourceNotificationId)
        throw e
    } catch (e: Exception) {
        abandonPendingNotification(app, sourceNotificationId)
        Log.e(TAG, "Notification dispatch failed for app ${app.id}", e)
        DispatchResult.Dropped.DispatchFailed
    }

    private suspend fun dispatchInternal(
        app: WebApp,
        title: String,
        body: String?,
        iconUrl: String?,
        tag: String?,
        sourceNotificationId: String?,
    ): DispatchResult {
        val sourceKey = sourceNotificationId?.let { SourceKey(app.id, it) }
        if (sourceKey != null) {
            synchronized(lifecycleLock) {
                when (lifecycleStates[sourceKey]) {
                    LifecycleState.Closed -> {
                        lifecycleStates.remove(sourceKey)
                        return DispatchResult.Dropped.ClosedBeforePost
                    }
                    null -> lifecycleStates[sourceKey] = LifecycleState.Pending
                    else -> Unit
                }
            }
        }

        fun drop(result: DispatchResult.Dropped, keepPending: Boolean = false): DispatchResult {
            if (!keepPending && sourceKey != null) {
                synchronized(lifecycleLock) {
                    if (lifecycleStates[sourceKey] !is LifecycleState.Posted) {
                        lifecycleStates.remove(sourceKey)
                    }
                }
            }
            return result
        }

        if (!isGlobalNotificationsEnabled()) {
            Log.d(TAG, "Dropped: global notifications disabled")
            return drop(DispatchResult.Dropped.GloballyDisabled)
        }
        if (app.notificationPermission == NotificationPermission.DENIED) {
            Log.d(TAG, "Dropped: permission denied for app ${app.id}")
            return drop(DispatchResult.Dropped.PermissionDenied)
        }
        if (app.notificationPermission == NotificationPermission.NOT_ASKED) {
            Log.d(TAG, "Dropped: permission not asked for app ${app.id}")
            return drop(DispatchResult.Dropped.NotAsked, keepPending = true)
        }
        if (isDndActive(app.dndStartHour, app.dndEndHour)) {
            Log.d(TAG, "Dropped: DND active for app ${app.id}")
            return drop(DispatchResult.Dropped.DndActive)
        }

        val todayCount = countToday(app.id)
        if (todayCount >= RATE_LIMIT_PER_DAY) {
            Log.d(TAG, "Dropped: rate limit reached ($todayCount) for app ${app.id}")
            return drop(DispatchResult.Dropped.RateLimited)
        }
        if (!checkPostPermission(context)) {
            Log.d(TAG, "Dropped: OS POST_NOTIFICATIONS permission missing for app ${app.id}")
            return drop(DispatchResult.Dropped.OsPermissionMissing)
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

        if (manager.getNotificationChannel(channelId)?.importance == NotificationManager.IMPORTANCE_NONE) {
            Log.d(TAG, "Dropped: Android notification channel disabled for app ${app.id}")
            return drop(DispatchResult.Dropped.ChannelDisabled)
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

        val notificationId = notificationIdSequence.getAndUpdate { current ->
            if (current == Int.MAX_VALUE) 1 else current + 1
        }
        val tagKey = tag?.takeIf { it.isNotEmpty() }?.let { TagKey(app.id, it) }
        var replacedNotificationId: Int? = null

        synchronized(lifecycleLock) {
            if (sourceKey != null && lifecycleStates[sourceKey] == LifecycleState.Closed) {
                lifecycleStates.remove(sourceKey)
                return DispatchResult.Dropped.ClosedBeforePost
            }

            @Suppress("MissingPermission") // POST_NOTIFICATIONS declared in app manifest; gated above.
            manager.notify(notificationId, notification)

            if (sourceKey != null) {
                lifecycleStates[sourceKey] = LifecycleState.Posted(notificationId, tagKey)
                sourceByNotificationId[notificationId] = sourceKey
            }
            if (tagKey != null) {
                replacedNotificationId = notificationIdsByTag.put(tagKey, notificationId)
                replacedNotificationId?.let { replacedId ->
                    if (replacedId != notificationId) {
                        sourceByNotificationId.remove(replacedId)?.let { previousSource ->
                            lifecycleStates.remove(previousSource)
                        }
                    }
                }
            }
        }

        replacedNotificationId?.takeIf { it != notificationId }?.let { replacedId ->
            runCatching { manager.cancel(replacedId) }
                .onFailure { Log.w(TAG, "Failed to cancel replaced notification $replacedId", it) }
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

    /** Cancels the Android notification corresponding to a web-engine close callback. */
    fun cancelPostedNotification(app: WebApp, tag: String?, sourceNotificationId: String? = null) {
        val sourceKey = sourceNotificationId?.let { SourceKey(app.id, it) }
        val tagKey = tag?.takeIf { it.isNotEmpty() }?.let { TagKey(app.id, it) }
        var notificationId: Int? = null

        synchronized(lifecycleLock) {
            if (sourceKey != null) {
                when (val state = lifecycleStates[sourceKey]) {
                    LifecycleState.Pending -> {
                        lifecycleStates[sourceKey] = LifecycleState.Closed
                        return
                    }
                    LifecycleState.Closed -> return
                    is LifecycleState.Posted -> {
                        notificationId = state.notificationId
                        lifecycleStates.remove(sourceKey)
                        sourceByNotificationId.remove(state.notificationId)
                        state.tagKey?.let { postedTag ->
                            if (notificationIdsByTag[postedTag] == state.notificationId) {
                                notificationIdsByTag.remove(postedTag)
                            }
                        }
                    }
                    null -> return
                }
            } else if (tagKey != null) {
                notificationId = notificationIdsByTag.remove(tagKey)
                notificationId?.let { id ->
                    sourceByNotificationId.remove(id)?.let { source -> lifecycleStates.remove(source) }
                }
            } else {
                return
            }
        }

        notificationId?.let { id ->
            runCatching { notificationManagerProvider(context).cancel(id) }
                .onFailure { Log.w(TAG, "Failed to cancel closed notification $id", it) }
        }
    }

}
