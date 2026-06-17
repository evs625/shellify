package io.shellify.app.core.isolation

import android.os.Build
import android.webkit.WebView
import androidx.annotation.RequiresApi
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * API 33+ isolation: each PWA gets its own named WebView profile.
 * A profile has a completely separate cookie store, localStorage, IndexedDB, and cache.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
object WebViewProfileManager {

    fun applyProfile(webView: WebView, isolationId: String) {
        runCatching {
            val store = ProfileStore.getInstance()
            val profileName = "pwa_$isolationId"
            val profile = store.getOrCreateProfile(profileName)
            WebViewCompat.setProfile(webView, profileName)
            // Each profile owns a separate CookieManager. WebViewManager.configure() enabled
            // third-party cookies on the DEFAULT profile's manager before this switch, so they
            // must be re-enabled here on the profile actually backing the WebView. OAuth/SSO
            // (e.g. "Sign in with Google") spans origins and silently fails without this.
            profile.cookieManager.setAcceptCookie(true)
            profile.cookieManager.setAcceptThirdPartyCookies(webView, true)
        }
        // If ProfileStore is unavailable on this build (shouldn't happen on API 33+),
        // we silently fall back — CookieJarManager handles the API < 33 path.
    }

    /**
     * Persists the profile's cookies to disk so a session survives the WebView/Activity being
     * torn down. Without this a login made in one app may not be visible to another app sharing
     * the same profile (shared-space category) after a process restart.
     */
    suspend fun flush(isolationId: String) {
        val store = runCatching { ProfileStore.getInstance() }.getOrNull() ?: return
        runCatching {
            withContext(Dispatchers.IO) {
                store.getOrCreateProfile("pwa_$isolationId").cookieManager.flush()
            }
        }
    }

    suspend fun deleteProfile(isolationId: String) {
        val profileName = "pwa_$isolationId"
        val store = runCatching { ProfileStore.getInstance() }.getOrNull() ?: return
        runCatching {
            withContext(Dispatchers.IO) { store.deleteProfile(profileName) }
        }.onFailure {
            // Profile is in use by a live WebView — clear its data in-place.
            runCatching {
                val profile = store.getOrCreateProfile(profileName)
                profile.webStorage.deleteAllData()
                profile.cookieManager.removeAllCookies(null)
                profile.cookieManager.flush()
            }
        }
    }
}
