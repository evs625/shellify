package io.shellify.app.core.engine

/** Host callbacks that complete GeckoView's WebNotification display lifecycle. */
interface GeckoNotificationCallback {
    fun onGeckoNotificationReceived(
        title: String,
        body: String?,
        iconUrl: String?,
        tag: String,
        onDisplayed: (Boolean) -> Unit,
    )

    fun onGeckoNotificationClosed(tag: String)
}
