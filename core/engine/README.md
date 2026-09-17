# `core:engine`

> Browser-engine abstraction for Android System WebView and Mozilla GeckoView, including optional Tor routing.

## Overview

`core:engine` isolates browser-specific APIs behind `BrowserEngine`. Feature code can select System WebView or GeckoView without duplicating navigation, popup, permission, storage, and lifecycle plumbing.

GeckoView is special in Shellify: Java/Kotlin API classes are compile-time dependencies, while the large native libraries are downloaded on demand from Mozilla Maven and loaded from app-private storage.

- Kotlin package: `io.shellify.app.core.engine`
- Convention plugin: `shellify.android.library`
- GeckoView release: `156.0.20260909172920`

## Key classes

| Class | Responsibility |
|---|---|
| `BrowserEngine` | Common browser lifecycle/navigation interface. |
| `BrowserEngineCallback` | Browser-to-host events for navigation, errors, popups, permissions, downloads, and notifications. |
| `SystemWebViewEngine` | Android System WebView implementation. |
| `GeckoViewEngine` | GeckoSession/GeckoView implementation. |
| `GeckoEngineManager` | Gecko runtime singleton, runtime download/update, integrity checks, storage operations, and ActivityDelegate ownership. |
| `GeckoActivityResultBridge` | Process-runtime FIDO/WebAuthn PendingIntent/result bridge. |
| `GeckoNativeLoader` | Loads only the native libraries belonging to the exact installed Gecko release and supported device ABI. |
| `GeckoNotificationCallback` | Host contract for Gecko WebNotification posting/closing outcomes. |
| `AdBlocker` / `AdBlockFilterCache` | Ad/tracker request blocking. |
| `TorManager` / `ProxyConfig` | Tor daemon lifecycle and proxy intent. |

## GeckoView version and ABI policy

The compile-time dependency and the runtime downloader must use the same exact release: `156.0.20260909172920`.

Mozilla publishes this release for three Android ABIs:

| Android ABI | Maven artifact |
|---|---|
| `arm64-v8a` | `geckoview-arm64-v8a` |
| `armeabi-v7a` | `geckoview-armeabi-v7a` |
| `x86_64` | `geckoview-x86_64` |

Legacy 32-bit `x86` is not published for GeckoView 156. `selectSupportedGeckoAbi()` chooses the first ABI in `Build.SUPPORTED_ABIS` that Mozilla actually publishes. If there is no match, installation/update/load fails closed; it must never substitute arm64 or another architecture.

`GECKO_SHA256_BY_ABI` pins Mozilla Maven's SHA-256 for all three current artifacts. The runtime installer verifies the downloaded AAR before extracting native code.

## Native-library replacement invariant

An app upgrade must never mix old Gecko native libraries with the new Java/API layer. Installation therefore:

1. downloads and verifies the target AAR;
2. extracts the selected ABI into a staging directory;
3. requires at least one `.so` in staging;
4. removes the complete live `gecko_engine/lib` tree;
5. moves/copies the complete staged tree into place;
6. records the new installed version only after replacement succeeds.

`GeckoNativeLoader` also refuses to load an installation whose recorded version is not the current `GECKO_VERSION`. This prevents an old GeckoView 140 native tree from being loaded immediately after the Shellify APK itself was upgraded.

## GeckoRuntime ownership and Android WebAuthn

GeckoView permits one `GeckoRuntime` per Android process. `GeckoEngineManager` creates it lazily and reuses that exact object regardless of the active `ProxyConfig`. Proxy intent remains applied through the existing process-level system-property mechanism before session use.

The singleton runtime always receives one `GeckoRuntime.ActivityDelegate`: `GeckoActivityResultBridge`.

This delegate is required for GeckoView WebAuthn/FIDO paths that ask the embedding app to launch a supplied Android `PendingIntent`. The bridge follows Mozilla's embedding contract:

- a currently eligible `WebViewActivity` launches the supplied `IntentSender`;
- the bridge retains the matching `GeckoResult<Intent>` and owner token;
- `RESULT_OK` completes that result with the exact returned `Intent`;
- non-OK/cancel and launch failures complete exceptionally;
- no eligible activity fails immediately instead of silently dropping the request;
- stopping an activity makes it ineligible for new launches but does not steal ownership of its in-flight result;
- destroying an activity fails and releases its remaining pending results;
- another Shellify document activity cannot consume a request code owned by the launcher.

The host registration is process-runtime state, not GeckoSession state. Do not attach competing ActivityDelegate implementations to individual sessions.

## Gecko WebNotification contract

`WebNotification.tag` is the only Gecko notification identity used by Shellify. Do not derive identity from `Parcel`, serialized object bytes, reflection, object identity, or private Gecko fields.

For GeckoView 156 the display handshake is mandatory:

- after Android actually posts the matching notification, call `WebNotification.show()`;
- if permission/channel/rate/DND/posting or a close/supersession prevents display, call `dismiss()`;
- when Gecko closes a notification, cancel the Android notification mapped by its public Gecko ID and call `dismiss()`;
- `show()` / `dismiss()` are completed on Android's main thread.

Shellify keeps a local generation counter only to reject stale asynchronous work for the same public Gecko ID; it is not a replacement identity. The mapping key remains the public Gecko tag plus the Shellify app identity.

## Session isolation and popups

Every GeckoSession carries the Shellify app's `contextId`. That is the storage/cookie isolation boundary used by the existing app model.

For `window.open()` / OAuth-style popups, `GeckoViewEngine` returns a new **unopened** child `GeckoSession` built from the parent settings. GeckoView opens the returned child. The inherited `contextId` keeps popup cookies/storage with the opener. `ContentDelegate.onCloseRequest` and `BrowserEngine.closeTopPopup()` tear the popup down.

This popup support does not bypass provider policies that reject embedded browsers. In particular, Google embedded-OAuth restrictions are outside the GeckoView passkey work.

## Notification permission integration

Gecko content permission requests are still attached through `NotificationDelegateFactory`. Shellify persists an allowed content notification permission in Gecko storage as before.

Multiple simultaneous first-time notification requests share one host permission decision. Every waiting Gecko callback receives that same result; one pending callback must not overwrite another.

## Tor and proxy behavior

`ProxyConfig` expresses the existing routing intent. `GeckoViewEngine.proxyConfigFor(app)` uses `ProxyConfig.Socks5("127.0.0.1", 9050)` for Tor-enabled apps and `ProxyConfig.None` otherwise.

The GeckoRuntime itself remains a process singleton. `GeckoEngineManager.getRuntime(proxyConfig)` updates the existing process-level SOCKS system properties before session use rather than attempting to create multiple runtimes, which GeckoView does not allow.

`TorManager` owns daemon lifecycle separately and exposes `StateFlow<TorState>` so callers can wait for a ready circuit before navigation.

## Build requirement

GeckoView 156's AAR metadata requires compile SDK `37.1`. Shellify therefore compiles Android modules against API 37 with minor API level 1 while retaining the existing `minSdk = 26` and `targetSdk = 36`.

## Ad/tracker blocking

System WebView request interception uses `AdBlocker` / `AdBlockFilterCache`. The tracker-rule set is independent from the normal ad-rule set and is enabled per app. GeckoView uses its `ContentBlocking` configuration/delegate for the Gecko path.

## Verification

The Gecko integration has focused tests for:

- supported/unsupported ABI selection and exact Mozilla hashes;
- stale-version load rejection and clean native-library replacement;
- singleton runtime ActivityDelegate installation;
- WebAuthn/FIDO activity launch, success, cancellation, launch failure, host detach, host replacement, and result ownership;
- public Gecko notification-ID forwarding;
- Android-post success/failure to Gecko `show()` / `dismiss()` acknowledgement;
- close-before-post, close-after-post, same-tag replacement, stale asynchronous work, and Android cancellation.

Repository-level verification remains `detekt`, `lintDebug`, `testDebugUnitTest`, and `assembleDebug`, plus the normal PR CI suites. The final behavioral check is on an Android 16 device: direct ChatGPT/OpenAI `Continue with passkey` must invoke Android's passkey/Credential Manager flow and complete with a valid credential.

## Configuration summary

| Item | Value |
|---|---|
| GeckoView | `156.0.20260909172920` |
| Compile SDK | `37.1` |
| Min SDK | `26` |
| Runtime Maven host | `https://maven.mozilla.org/maven2` |
| Supported runtime ABIs | `arm64-v8a`, `armeabi-v7a`, `x86_64` |
| Native path | `filesDir/gecko_engine/lib/$abi/` |
| Runtime count | exactly one per process |
| WebAuthn Android bridge | `GeckoRuntime.ActivityDelegate` / `GeckoActivityResultBridge` |
| Gecko notification identity | `WebNotification.tag` |
