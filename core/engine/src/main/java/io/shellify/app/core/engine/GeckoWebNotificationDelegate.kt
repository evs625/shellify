package io.shellify.app.core.engine

import android.os.Handler
import android.os.Looper
import org.mozilla.geckoview.WebNotification
import org.mozilla.geckoview.WebNotificationDelegate
import java.util.concurrent.atomic.AtomicBoolean

/** Builds the runtime-scoped GeckoView notification delegate used by foreground/background hosts. */
fun createGeckoWebNotificationDelegate(cb: BrowserEngineCallback): WebNotificationDelegate {
    val mainHandler = Handler(Looper.getMainLooper())
    return createGeckoWebNotificationDelegate(cb) { notification, displayed ->
        val completion = Runnable { acknowledgeWebNotification(notification, displayed) }
        if (Looper.myLooper() == Looper.getMainLooper()) completion.run() else mainHandler.post(completion)
    }
}

internal fun createGeckoWebNotificationDelegate(
    cb: BrowserEngineCallback,
    complete: (WebNotification, Boolean) -> Unit,
): WebNotificationDelegate = object : WebNotificationDelegate {
    override fun onShowNotification(notification: WebNotification) {
        val completed = AtomicBoolean(false)
        dispatchGeckoNotification(
            NotificationPayload(notification.title, notification.text, notification.imageUrl, notification.tag),
            cb,
        ) { displayed ->
            if (completed.compareAndSet(false, true)) {
                complete(notification, displayed)
            }
        }
    }

    override fun onCloseNotification(notification: WebNotification) {
        (cb as? GeckoNotificationCallback)?.onGeckoNotificationClosed(notification.tag)
        complete(notification, false)
    }
}


internal fun acknowledgeWebNotification(notification: WebNotification, displayed: Boolean) {
    if (displayed) notification.show() else notification.dismiss()
}
