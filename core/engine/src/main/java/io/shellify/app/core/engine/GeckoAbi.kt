package io.shellify.app.core.engine

import java.io.File

internal val GECKO_ARTIFACT_BY_ABI = mapOf(
    "arm64-v8a" to "geckoview-arm64-v8a",
    "armeabi-v7a" to "geckoview-armeabi-v7a",
    "x86_64" to "geckoview-x86_64",
)

internal fun selectSupportedGeckoAbi(supportedAbis: Array<String>): String? =
    supportedAbis.firstOrNull { it in GECKO_ARTIFACT_BY_ABI }

internal val GECKO_SHA256_BY_ABI = mapOf(
    "arm64-v8a" to "416eea477aee090ac690ce4b56c558ea0acec882bf47520d0b0e2aaa11c65836",
    "armeabi-v7a" to "706f1122fb334ab662b6adcaf18916bd3bdc49887cab58d06548757187725ef7",
    "x86_64" to "46f97d82c9d4618deffdfa52d9c9d09b4e79a912e4df4191f515e7e3de9d3033",
)

internal fun hasCurrentGeckoInstallation(
    installed: Boolean,
    installedVersion: String?,
    expectedVersion: String,
    supportedAbis: Array<String>,
    filesDir: File,
): Boolean {
    if (!installed || installedVersion != expectedVersion) return false
    val abi = selectSupportedGeckoAbi(supportedAbis) ?: return false
    val libDir = File(filesDir, "gecko_engine/lib/$abi")
    return libDir.isDirectory && libDir.listFiles()?.any { it.extension == "so" } == true
}
