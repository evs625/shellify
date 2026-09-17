package io.shellify.app.core.engine

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.StorageController
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

sealed class GeckoInstallState {
    data object NotInstalled : GeckoInstallState()
    data class Downloading(val progress: Float, val message: String) : GeckoInstallState()
    data object Installing : GeckoInstallState()
    data class Installed(val verified: Boolean) : GeckoInstallState()
    data class Error(val message: String) : GeckoInstallState()
}

class GeckoEngineManager(private val context: Context) {

    companion object {
        private const val TAG = "GeckoEngineManager"
        private const val PREFS_NAME = "gecko_engine"
        private const val KEY_INSTALLED = "installed"
        private const val KEY_VERSION = "version"
        private const val KEY_VERIFIED = "sha256_verified"
        private const val KEY_SHA256 = "sha256_hash"

        const val GECKO_VERSION = "156.0.20260909172920"
        private const val MAVEN_BASE = "https://maven.mozilla.org/maven2/org/mozilla/geckoview"
        internal fun hasCurrentInstallation(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_INSTALLED, false)) return false
            val installedVersion = prefs.getString(KEY_VERSION, null)
            if (installedVersion != GECKO_VERSION) return false
            return hasCurrentGeckoInstallation(
                installed = true,
                installedVersion = installedVersion,
                expectedVersion = GECKO_VERSION,
                supportedAbis = Build.SUPPORTED_ABIS,
                filesDir = context.filesDir,
            )
        }

    }

    // GeckoView enforces exactly ONE GeckoRuntime per process — a second GeckoRuntime.create()
    // throws IllegalStateException. A single runtime is created lazily on first use and reused for
    // every subsequent call. Proxy routing (SOCKS5 / direct) is controlled via JVM system properties
    // which are checked per-connection, so applying them before each session.open() is sufficient
    // without needing a separate runtime per ProxyConfig (T-02-20, WR-02-fix).
    @Volatile private var runtime: GeckoRuntime? = null
    private val activityResultBridge = GeckoActivityResultBridge()

    // Override in tests to supply mock GeckoRuntime instances without calling GeckoRuntime.create().
    internal var runtimeFactory: (ProxyConfig) -> GeckoRuntime = ::buildRuntime

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val _installState = MutableStateFlow<GeckoInstallState>(
        if (isInstalled()) GeckoInstallState.Installed(
            verified = prefs.getBoolean(
                KEY_VERIFIED,
                false
            )
        )
        else GeckoInstallState.NotInstalled
    )
    val installState: StateFlow<GeckoInstallState> = _installState.asStateFlow()

    private val _latestVersion = MutableStateFlow<String?>(null)
    val latestVersion: StateFlow<String?> = _latestVersion.asStateFlow()

    val updateAvailable: Boolean
        get() {
            val latest = _latestVersion.value ?: return false
            val installed = getInstalledVersion() ?: return false
            return isNewerVersion(candidate = latest, current = installed)
        }

    private fun isNewerVersion(candidate: String, current: String): Boolean {
        val c = candidate.split(".").mapNotNull { it.toLongOrNull() }
        val i = current.split(".").mapNotNull { it.toLongOrNull() }
        for (idx in 0 until maxOf(c.size, i.size)) {
            val cv = c.getOrElse(idx) { 0L }
            val iv = i.getOrElse(idx) { 0L }
            if (cv > iv) return true
            if (cv < iv) return false
        }
        return false
    }

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var _safeBrowsingEnabled: Boolean = false

    fun isSafeBrowsingEnabled(): Boolean = _safeBrowsingEnabled

    fun applySafeBrowsing(enabled: Boolean) {
        _safeBrowsingEnabled = enabled
        val level = if (enabled) ContentBlocking.SafeBrowsing.DEFAULT else ContentBlocking.SafeBrowsing.NONE
        runtime?.settings?.contentBlocking?.setSafeBrowsing(level)
    }

    fun isInstalled(): Boolean = hasCurrentInstallation(context)

    fun getInstalledVersion(): String? = prefs.getString(KEY_VERSION, null)
    fun getInstalledSha256(): String? = prefs.getString(KEY_SHA256, null)

    fun getInstalledSizeMb(): Int {
        val dir = File(context.filesDir, "gecko_engine")
        if (!dir.exists()) return 0
        val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return (bytes / (1024 * 1024)).toInt()
    }

    // GeckoRuntime lifecycle

    /**
     * Returns the single [GeckoRuntime] for this process.
     *
     * GeckoView allows exactly ONE [GeckoRuntime] per process; calling [GeckoRuntime.create] a
     * second time throws [IllegalStateException]. The runtime is created lazily on first call and
     * reused thereafter regardless of [proxyConfig].
     *
     * Proxy routing is managed via JVM system properties ([socksProxyHost] / [socksProxyPort])
     * which the JVM socket layer checks per-connection. They are applied on every [getRuntime]
     * call so the proxy is always correct for new connections opened immediately after.
     *
     * Callers that do not pass a [proxyConfig] get [ProxyConfig.None] (back-compat default).
     */
    fun getRuntime(proxyConfig: ProxyConfig = ProxyConfig.None): GeckoRuntime {
        // Apply proxy system properties on EVERY call — not just at creation — so the active
        // SOCKS5 / direct routing reflects the caller's intent for new socket connections.
        applyProxySystemProperties(proxyConfig)
        // Fast path: return the existing runtime without taking the lock.
        runtime?.let { return it }
        // Slow path: serialize creation so GeckoRuntime.create() is called at most once.
        return synchronized(this) {
            runtime ?: runtimeFactory(proxyConfig).also { created ->
                created.setActivityDelegate(activityResultBridge)
                runtime = created
            }
        }
    }

    @MainThread
    fun attachActivityHost(token: Any, host: GeckoActivityHost, isEligible: Boolean) {
        activityResultBridge.attachHost(token, host, isEligible)
    }

    @MainThread
    fun setActivityHostEligible(token: Any, isEligible: Boolean) {
        activityResultBridge.setHostEligible(token, isEligible)
    }

    @MainThread
    fun detachActivityHost(token: Any) {
        activityResultBridge.detachHost(token)
    }

    @MainThread
    fun onActivityResult(token: Any, requestCode: Int, resultCode: Int, data: Intent?): Boolean =
        activityResultBridge.onActivityResult(token, requestCode, resultCode, data)

    private fun applyProxySystemProperties(proxyConfig: ProxyConfig) {
        when (proxyConfig) {
            is ProxyConfig.Socks5 -> {
                System.setProperty("socksProxyHost", proxyConfig.host)
                System.setProperty("socksProxyPort", proxyConfig.port.toString())
            }
            else -> {
                System.clearProperty("socksProxyHost")
                System.clearProperty("socksProxyPort")
            }
        }
    }

    // Proxy system properties are applied by applyProxySystemProperties() before this factory
    // is invoked, so the runtime inherits the correct SOCKS5 / direct config at creation time.
    @Suppress("UnusedParameter")
    private fun buildRuntime(proxyConfig: ProxyConfig): GeckoRuntime {
        val safeBrowsingLevel = if (_safeBrowsingEnabled) ContentBlocking.SafeBrowsing.DEFAULT else ContentBlocking.SafeBrowsing.NONE
        val settings = GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(true)
            .contentBlocking(
                ContentBlocking.Settings.Builder()
                    .antiTracking(ContentBlocking.AntiTracking.DEFAULT)
                    .safeBrowsing(safeBrowsingLevel)
                    .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS)
                    .build()
            )
            .build()
        return GeckoRuntime.create(context.applicationContext, settings)
    }

    // Download and install

    suspend fun downloadAndInstall(): Boolean =
        withContext(Dispatchers.IO) {
            cancelRequested = false
            val abi = selectSupportedGeckoAbi(Build.SUPPORTED_ABIS)
            if (abi == null) {
                val supported = Build.SUPPORTED_ABIS.joinToString()
                Log.e(TAG, "No GeckoView artifact supports this device ABI set: $supported")
                _installState.value = GeckoInstallState.Error("Unsupported device ABI for GeckoView")
                return@withContext false
            }
            val artifact = GECKO_ARTIFACT_BY_ABI.getValue(abi)
            val url = "$MAVEN_BASE/$artifact/$GECKO_VERSION/$artifact-$GECKO_VERSION.aar"
            Log.i(TAG, "Downloading GeckoView from $url")

            try {
                _installState.value = GeckoInstallState.Downloading(0f, "Connecting…")
                val tempAar = File(context.cacheDir, "geckoview_temp.aar")

                val ok = downloadFile(url, tempAar) { p ->
                    if (!cancelRequested)
                        _installState.value =
                            GeckoInstallState.Downloading(p * 0.85f, "Downloading…")
                }

                if (cancelRequested) {
                    tempAar.delete()
                    _installState.value = GeckoInstallState.NotInstalled
                    return@withContext false
                }
                if (!ok) {
                    tempAar.delete()
                    _installState.value = GeckoInstallState.Error("Download failed")
                    return@withContext false
                }

                // Integrity verification uses the hash pinned to this exact compile-time release.
                _installState.value = GeckoInstallState.Downloading(0.9f, "Verifying...")
                val expectedHash = GECKO_SHA256_BY_ABI.getValue(abi)
                val actualHash = sha256(tempAar)
                if (actualHash != expectedHash) {
                    Log.e(TAG, "SHA-256 mismatch! expected=$expectedHash actual=$actualHash")
                    tempAar.delete()
                    _installState.value =
                        GeckoInstallState.Error("Integrity check failed - download may be corrupted or tampered")
                    return@withContext false
                }
                Log.i(TAG, "SHA-256 verified: $actualHash")
                _installState.value = GeckoInstallState.Installing
                val installed = extractAndReplaceSoFiles(tempAar, abi)
                tempAar.delete()

                if (!installed) {
                    _installState.value = GeckoInstallState.Error("GeckoView native library installation failed")
                    return@withContext false
                }

                prefs.edit()
                    .putBoolean(KEY_INSTALLED, true)
                    .putString(KEY_VERSION, GECKO_VERSION)
                    .putBoolean(KEY_VERIFIED, true)
                    .putString(KEY_SHA256, expectedHash)
                    .apply()
                _installState.value = GeckoInstallState.Installed(verified = true)
                Log.i(TAG, "GeckoView $GECKO_VERSION installed successfully (ABI=$abi, verified=true)")
                true
            } catch (e: CancellationException) {
                _installState.value = GeckoInstallState.NotInstalled
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Install failed", e)
                _installState.value = GeckoInstallState.Error(e.message ?: "Unknown error")
                false
            }
        }

    suspend fun checkForUpdate(): String? = withContext(Dispatchers.IO) {
        if (selectSupportedGeckoAbi(Build.SUPPORTED_ABIS) == null) {
            Log.w(TAG, "Skipping GeckoView update check: unsupported device ABI set")
            return@withContext null
        }
        _latestVersion.value = GECKO_VERSION
        GECKO_VERSION
    }

    suspend fun updateEngine(): Boolean = downloadAndInstall()

    fun clearDataForContext(isolationId: String) {
        val rt = runtime ?: return
        try {
            rt.storageController.clearDataForSessionContext(isolationId)
        } catch (e: Exception) {
            Log.w(TAG, "clearDataForContext failed: ${e.message}")
        }
    }

    fun cancelDownload() {
        cancelRequested = true
    }

    fun uninstall() {
        File(context.filesDir, "gecko_engine").deleteRecursively()
        prefs.edit().remove(KEY_INSTALLED).remove(KEY_VERSION).remove(KEY_VERIFIED)
            .remove(KEY_SHA256).apply()
        _installState.value = GeckoInstallState.NotInstalled
        runtime?.let {
            try { it.shutdown() } catch (_: Exception) { }
        }
        runtime = null
        Log.i(TAG, "GeckoView uninstalled")
    }

    // File helpers

    private fun downloadFile(url: String, dest: File, onProgress: (Float) -> Unit): Boolean {
        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        // Wrap response in .use{} so the connection is always returned to OkHttp's pool,
        // including on error paths. Without this the socket leaks on !isSuccessful (CR-07).
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "HTTP ${response.code} for $url")
                return@use false
            }
            val body = response.body ?: return@use false
            val total = body.contentLength()
            var read = 0L
            val buf = ByteArray(8192)
            FileOutputStream(dest).use { out ->
                body.byteStream().use { input ->
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        if (cancelRequested) return@use false
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress(read.toFloat() / total)
                    }
                }
            }
            dest.exists() && dest.length() > 0
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(8192)
        file.inputStream().use { input ->
            var n: Int
            while (input.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extractAndReplaceSoFiles(aarFile: File, abi: String): Boolean {
        val stagingRoot = File(context.cacheDir, "geckoview_lib_staging")
        if (stagingRoot.exists() && !stagingRoot.deleteRecursively()) return false
        val stagingAbi = File(stagingRoot, abi)
        if (!stagingAbi.mkdirs() && !stagingAbi.isDirectory) return false

        val prefix = "jni/$abi/"
        var count = 0
        ZipInputStream(aarFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.startsWith(prefix) && entry.name.endsWith(".so")) {
                    val name = entry.name.substringAfterLast("/")
                    FileOutputStream(File(stagingAbi, name)).use { zis.copyTo(it) }
                    count++
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        if (count == 0) {
            stagingRoot.deleteRecursively()
            return false
        }

        val liveRoot = File(context.filesDir, "gecko_engine/lib")
        val replaced = replaceGeckoLibraryDirectory(stagingRoot, liveRoot)
        Log.i(TAG, "Installed $count GeckoView native libraries for ABI=$abi: $replaced")
        return replaced
    }
}
