package io.shellify.app.core.engine

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.MainThread
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import java.lang.ref.WeakReference

/** Host surface required by GeckoView when WebAuthn falls back to an Android FIDO activity. */
interface GeckoActivityHost {
    fun startPendingIntent(pendingIntent: PendingIntent, requestCode: Int)
}

/**
 * Process-scoped owner for GeckoRuntime activity-result requests.
 *
 * The runtime outlives individual WebViewActivity instances, so requests are bound to the exact
 * host token that launched them. A stopped host becomes ineligible for new launches but keeps
 * ownership of an in-flight result until it is delivered or the host is destroyed.
 */
internal class GeckoActivityResultBridge(
    private val resultFactory: () -> GeckoResult<Intent> = { GeckoResult() },
) : GeckoRuntime.ActivityDelegate {

    private data class HostEntry(
        val token: Any,
        val host: WeakReference<GeckoActivityHost>,
        var isEligible: Boolean,
    )

    private data class PendingResult(
        val ownerToken: Any,
        val result: GeckoResult<Intent>,
    )

    private val hosts = mutableListOf<HostEntry>()
    private val pendingResults = mutableMapOf<Int, PendingResult>()
    private var nextRequestCode = REQUEST_CODE_FIRST

    @MainThread
    fun attachHost(token: Any, host: GeckoActivityHost, isEligible: Boolean) {
        hosts.removeAll { it.token === token || it.host.get() == null }
        hosts += HostEntry(token, WeakReference(host), isEligible)
    }

    @MainThread
    fun setHostEligible(token: Any, isEligible: Boolean) {
        val index = hosts.indexOfFirst { it.token === token }
        if (index < 0) return
        val entry = hosts[index]
        entry.isEligible = isEligible
        if (isEligible) {
            hosts.removeAt(index)
            hosts += entry
        }
    }

    @MainThread
    fun detachHost(token: Any) {
        hosts.removeAll { it.token === token || it.host.get() == null }
        failPendingFor(token, IllegalStateException("Gecko activity host was destroyed before result delivery"))
    }

    @MainThread
    override fun onStartActivityForResult(pendingIntent: PendingIntent): GeckoResult<Intent> {
        val result = resultFactory()
        val hostEntry = currentHost()
        val host = hostEntry?.host?.get()
        if (hostEntry == null || host == null) {
            result.completeExceptionally(IllegalStateException("No eligible WebViewActivity is available for GeckoView"))
            return result
        }

        val requestCode = allocateRequestCode()
        if (requestCode == null) {
            result.completeExceptionally(IllegalStateException("No GeckoView activity-result request codes are available"))
            return result
        }

        pendingResults[requestCode] = PendingResult(hostEntry.token, result)
        try {
            host.startPendingIntent(pendingIntent, requestCode)
        } catch (e: Exception) {
            pendingResults.remove(requestCode)
            result.completeExceptionally(e)
        }
        return result
    }

    @MainThread
    fun onActivityResult(ownerToken: Any, requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        val pending = pendingResults[requestCode] ?: return false
        if (pending.ownerToken !== ownerToken) return false
        pendingResults.remove(requestCode)
        if (resultCode == Activity.RESULT_OK) {
            pending.result.complete(data)
        } else {
            pending.result.completeExceptionally(IllegalStateException("Gecko activity returned resultCode=$resultCode"))
        }
        return true
    }

    private fun currentHost(): HostEntry? {
        hosts.removeAll { it.host.get() == null }
        return hosts.lastOrNull { it.isEligible }
    }

    private fun allocateRequestCode(): Int? {
        repeat(REQUEST_CODE_COUNT) {
            val candidate = nextRequestCode
            nextRequestCode = if (candidate == REQUEST_CODE_LAST) REQUEST_CODE_FIRST else candidate + 1
            if (candidate !in pendingResults) return candidate
        }
        return null
    }

    private fun failPendingFor(ownerToken: Any, error: Throwable) {
        val owned = pendingResults.filterValues { it.ownerToken === ownerToken }.keys.toList()
        owned.forEach { requestCode ->
            pendingResults.remove(requestCode)?.result?.completeExceptionally(error)
        }
    }

    companion object {
        private const val REQUEST_CODE_FIRST = 0x4700
        private const val REQUEST_CODE_LAST = 0x47FF
        private const val REQUEST_CODE_COUNT = REQUEST_CODE_LAST - REQUEST_CODE_FIRST + 1
    }
}
