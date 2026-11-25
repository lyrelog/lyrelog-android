<p align="center">
  <a href="https://lyrelog.com">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://lyrelog.com/readme/android-dark.png">
      <img alt="Lyrelog for Android &amp; JVM" src="https://lyrelog.com/readme/android-light.png" width="600">
    </picture>
  </a>
</p>

<p align="center">
  <a href="https://docs.lyrelog.com/sdks/android"><b>Android docs</b></a> ·
  <a href="https://docs.lyrelog.com/sdks/jvm"><b>JVM docs</b></a> ·
  <a href="https://docs.lyrelog.com/quickstart">Quickstart</a> ·
  <a href="https://lyrelog.com">lyrelog.com</a>
</p>

Structured logs from your Android app to [Lyrelog](https://lyrelog.com/platforms/android), a hosted log service.
Kotlin, Android 7.0 (API 24) and later, no dependencies beyond the Kotlin standard library.

The same repository has the [JVM server SDK](https://lyrelog.com/platforms/jvm), `com.lyrelog:lyrelog-jvm`, for Java and Kotlin on
servers (Spring, Ktor, plain JVM), built on the same engine: see [On a server](#on-a-server-java-and-kotlin-on-the-jvm).

## Install

Not published yet. Once it is, from Maven Central:

```kotlin
dependencies {
    implementation("com.lyrelog:lyrelog-android:1.1.1")
}
```

The library adds the `INTERNET` permission to your app's manifest.

## Use

```kotlin
import com.lyrelog.Configuration
import com.lyrelog.Consent
import com.lyrelog.Lyrelog

class TilefallApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // ingest-only key: it can append events, never read them
        Lyrelog.start(this, Configuration("lyr_ing_...") {
            consent = Consent.GRANTED        // or PENDING until the person chooses
        })                                   // endpoint: https://ingest.lyrelog.com unless you set one
    }
}

Lyrelog.setUser(playerId)                   // optional; attached to every following event
Lyrelog.info("level_cleared", attrs = mapOf("level" to 12, "moves" to 31))
Lyrelog.error("sync_failed", category = "sync", attrs = mapOf("code" to 503), payload = mapOf("response" to raw))

try { submitScore() } catch (e: IOException) {
    Lyrelog.captureError(e, category = "leaderboard")   // the `exception` event
}

// One logger per area of the app, like a log tag.
val store = Lyrelog.logger("store")
store.warning("purchase_retry", mapOf("attempt" to 2))
```

- `message`: a stable event name (`level_cleared`), not prose.
- `category`: the area of the app (`game`, `store`, `sync`); the dashboard filters by it.
- `attrs`: small fields to filter and chart by: ids, durations, codes, flags. Strings, numbers,
  booleans, null, lists (sent as strings) and nested maps up to 4 levels.
- `payload`: the big stuff you only read when you open one event: responses, traces. Any map,
  list or value.

`start` returns `true` when it started Lyrelog (`false` when it was already running, or couldn't
start); `Lyrelog.isStarted` and `Lyrelog.consent` say what runs. `setUser` and `setConsent` may
be called before `start`; they apply when it runs (and that consent wins over
`Configuration.consent`).

`Configuration("lyr_ing_...") { ... }` sets only what you need; `config.copy { ... }` changes a
copy. From Java every function is static (`Lyrelog.info("x")`, overloads for the optional
arguments) and the configuration has a builder:

```java
Lyrelog.start(this, Configuration.builder("lyr_ing_...").consent(Consent.PENDING).build());
```

Only `com.lyrelog` is API. `com.lyrelog.core` is Lyrelog's engine, shared with its other
modules: Kotlin refuses it without `@OptIn(InternalLyrelogApi::class)`, and it changes without
notice.

## Errors, crashes and screens

**Errors**: `Lyrelog.captureError(e)` (or `logger.captureError(e)`) logs the `exception` event
every Lyrelog SDK sends: level `error`, `error_type` (the class name) and `error_message`
attributes, and a payload with the description, up to 60 frames, the causes (up to 8, 20 frames
each) and suppressed exceptions. Frames are as the VM reports them: run R8's `retrace` with your
mapping file when the app is minified.

**Crashes**: an uncaught exception is written to disk on the spot, on the thread that is dying,
together with any event still on its way to the disk, and then handed to the handler that was
there before (the system's crash dialog, or Crashlytics if it installed one first). Nothing is
sent then: the process is about to end. The next launch sends it as an `app_crashed` event, level
critical, area `diagnostics` (like the Swift SDK's crash reports, so both platforms' crashes sit
together in the dashboard), with `error_type`, `error_message`, `handled: false` and
`mechanism: uncaught_exception`, the frames in the payload, at the time it happened. On by default
(`captureCrashes`).

**ANRs and native crashes** come from Android's own records of why the app's earlier processes
ended (`ApplicationExitInfo`, Android 11+), read on the next launch at the time they happened: an
ANR the system ended is `app_hung` (level error, `mechanism: anr`) with the main thread's frames;
a native (C/C++) crash is `app_crashed` (`mechanism: native`) with the signal, the abort message,
and from Android 12 the crashed thread's frames, each library's build id and `native_function`,
your app's frame nearest the top. JVM crashes aren't read again there.

**Readable crashes in release builds**: upload each release's symbols once with the
[command-line tool](https://docs.lyrelog.com/cli), `lyrelog symbols upload app/build`. It finds
the R8 mapping (renamed classes and methods back) and your native libraries' debug information
(C and C++ frames as functions, files and lines; Gradle keeps the unstripped `.so` files under
`build/intermediates`), and uploads only what the project doesn't have yet. An ANR the app recovered
from (the person waited) isn't recorded by Android, so it isn't reported.

**Screens**: say where the person is and every event that follows carries it as `screen`
(`attachScreen`), plus a `screen_viewed` event with the screen before and the time spent on it.

```kotlin
override fun onResume() {
    super.onResume()
    Lyrelog.screen("Settings")
}
```

`automaticScreens = true` does it for every Activity, named by its class (`SettingsActivity`).
It is off by default: Compose and fragment apps have many screens in one Activity, and a name you
choose reads better on the dashboard.

## What it does for you

| | |
|---|---|
| Never blocks | `log` copies the event and returns; one background writer puts it on disk. The writer never waits on the network. |
| Keeps events across launches | Events wait in `noBackupFilesDir/lyrelog` until the server confirms them, capped at 20 MB and 7 days (oldest first). What reached the disk survives a crash, a kill or days offline; events still on their way to the writer (milliseconds) are written by the crash handler, or lost if the process is killed outright. The folder is never in a backup, so a restored phone doesn't resend another device's events. |
| Batches | Up to 500 events or 512 KiB per request (never more than 2,000 or 4 MiB, the server's limits), gzip. An event over 1 MiB once encoded is dropped and counted. |
| When it sends | Every 15 s while an Activity is started (`flushIntervalMillis`, at least 1 s), and once when the app goes to the background (the last Activity stops; a rotation doesn't count). A process started without an Activity (a push, a job) sends nothing on a timer until one starts. In the background nothing runs on a timer: what's left goes on the next launch or the next time the app comes to the front. `Lyrelog.flushInBackground()` sends now; `Lyrelog.flush()` waits for it (never on the main thread). |
| Retries | Backoff from 1 s doubling to 5 min with full jitter; the server's `Retry-After` is always honoured, also by a flush, up to an hour (a longer one waits an hour). After 3 failed uploads in a row one warning goes to logcat, and again only after an upload went through. A batch the server keeps failing goes to the back, then is dropped. A refused batch (4xx) is dropped; a revoked key stops sending until the next launch and keeps the data. When the organization's monthly quota is used up (a `429` with `plan` and `limit`), the batch waits for its `Retry-After` without counting as a failure (no backoff) and one warning goes to logcat, again only after a batch has been accepted in between. Uploads never follow redirects. |
| Drops are visible | Events lost to the caps are reported in a `lyrelog_events_dropped` warning (category `lyrelog`) with the count. The SDK's own problems (a wrong endpoint, a 404, rejected lines, an endpoint that doesn't answer) go once per launch to logcat, tag `Lyrelog`, at debug level. |
| Consent | `PENDING` keeps events on the device unsent; `GRANTED` sends them and those kept while pending; `DENIED` deletes everything queued and the install id, and records nothing until consent changes (including events logged a moment before the denial and not yet written). Nothing is ever uploaded unless consent is granted, including what an earlier launch left. |
| Context | App id (package name), version name, version code, `android`, the Android version, the device (`Google Pixel 9`), locale, environment, platform `android`, a session id (new after 30 min idle) and an install id. |
| Redacts secrets | Before queueing, any field in `attrs` or `payload` (at any depth, inside lists too) whose name, lowercased and without `-`, `_`, `.` or spaces, is or ends with `authorization`, `cookie`, `password`, `passwd`, `secret`, `token`, `apikey`, `privatekey`, `credential(s)`, `accesskey` or `signature` is sent as `"[redacted]"`: `X-Api-Key`, `csrf_token`, `Set-Cookie` match, `token_count` and `max_tokens` don't. It runs before anything is cut, so no secret survives in a truncated preview. Only names are checked, never free text such as a message or an exception's message. |
| Limits | Fits every event to the server's limits before queueing (long strings cut on a character boundary, extra or colliding attributes counted in `lyrelog_dropped_attrs`, big payloads summarised). |

Every knob is in `Configuration`.

### Endpoint

The default is `https://ingest.lyrelog.com`. If you pass one, a pasted `.../v1/events:batch` or
`.../v1` is cut back to the origin. Only `https` is used; plain `http` only to `localhost`,
`127.0.0.1` or `::1`, and on the emulator to `10.0.2.2` (your computer, for a local Lyrelog
stack: `http://10.0.2.2:8090`). Android also blocks plain http unless the app allows it: allow
`10.0.2.2` in a network security config of the debug build only, as the sample does. On
Android 17 an app targeting API 37 also needs the runtime permission
`android.permission.ACCESS_LOCAL_NETWORK` to reach a private address like `10.0.2.2` (without
it the connection just times out); declare it in the debug manifest only and grant it
(`adb shell pm grant <your.app> android.permission.ACCESS_LOCAL_NETWORK`), as the sample does.
The hosted ingest is a public https address and needs none of this. Any other endpoint is
refused: nothing is sent and the events stay on the device.

### Environment

`Configuration.environment` names it; `null` detects it: `development` when the app is
debuggable (`android:debuggable`, every debug build), `production` otherwise. Any name works
(`"internal"`, `"beta"`): lowercase letters, digits, `-` and `_`, up to 32.

### Redaction

`Configuration.redactedKeys` replaces the list (`null`, the default, is
`Configuration.DEFAULT_REDACTED_KEYS`, the same in every Lyrelog SDK); an empty list turns
redaction off. To add a name, keep the defaults:

```kotlin
redactedKeys = Configuration.DEFAULT_REDACTED_KEYS + "pin"
```

### Stopping

`Lyrelog.stop()` sends what was logged (waiting at most 2 seconds) and stops; `start` can be
called again afterwards. It blocks, so call it off the main thread. `Lyrelog.stopNow()` stops at
once without sending: what was logged is written to disk and goes with the next launch.

`Lyrelog.flushToDisk()` waits (up to 1 second) until everything logged so far is on disk, and
sends nothing: for a fatal error your own handler records just before the process ends (the
React Native and Flutter SDKs use it for JavaScript and Dart crashes). It then goes with the
next upload, of this launch or the next.

## Verified events

With **Key Attestation** (on by default, `attest = false` turns it off) a real
device proves its events come from your app: once per install the SDK makes a key in the phone's
secure hardware (StrongBox or the TEE), Android attests it with Google's certificates, and the
server checks the chain, that the boot is verified and the bootloader locked, and that the app is
your package signed with your certificate. Then every batch carries a token (24 hours, renewed in
the background by signing a fresh challenge) and its events are stored with
`verified_by = key_attestation`. It never delays or holds an event.

Set it up in the project's settings (Verified events → Android): the package name and the
SHA-256 fingerprint of the signing certificate (Play Console → App integrity → App signing key
certificate; for builds you sign yourself, `./gradlew signingReport`). Until it's set up the SDK
turns it off for the launch and says so once in logcat.

Events stay unverified, and arrive as before, from emulators (no secure hardware Google attests;
the SDK doesn't try), rooted devices and unlocked bootloaders (the server refuses them; the SDK
tries again a day later), and builds signed with a certificate the project doesn't list. So
before turning on "accept only verified events" for `android`, check in the explorer that your
real users' events arrive verified. Events your own servers send with a server key
(`lyr_srv_...`) are verified too.

## On a server (Java and Kotlin on the JVM)

`com.lyrelog:lyrelog-jvm` (Java 11 and later) runs the same engine as the Android library
(`lyrelog-core`: the queue, batching, retries, redaction, the wire format), set up for a server
the way the Go and Python SDKs are. Not published yet either.

```kotlin
dependencies {
    implementation("com.lyrelog:lyrelog-jvm:1.1.1")
    implementation("com.lyrelog:lyrelog-jvm-logback:1.1.1")   // optional: SLF4J and Logback records
}
```

Its classes are in `com.lyrelog.jvm` (`Level`, `Logger` stay in `com.lyrelog`), so the jars
work on the Java module path: `com.lyrelog.core`, `com.lyrelog.jvm` and `com.lyrelog.logback`.

```kotlin
import com.lyrelog.jvm.Configuration
import com.lyrelog.jvm.Lyrelog
import com.lyrelog.jvm.LyrelogUser

fun main() {
    Lyrelog.start(Configuration(System.getenv("LYRELOG_KEY")) { platform = "api" })
    Lyrelog.info("checkout_done", attrs = mapOf("plan" to "pro"), user = customerId)
    Lyrelog.userScope(customerId).use { handle(request) }   // the user of everything this thread logs
    Lyrelog.logger("billing").captureError(e)
}

suspend fun handle(call: Call) = withContext(LyrelogUser(call.userId)) {   // kotlinx-coroutines
    Lyrelog.info("checkout_done")                                         // carries the user after any suspension
}
```

```java
Lyrelog.start(Configuration.builder(System.getenv("LYRELOG_KEY")).platform("api").build());
try (Lyrelog.UserScope scope = Lyrelog.userScope(customerId)) {
    Lyrelog.info("checkout_done", "billing", Map.of("plan", "pro"));
    Lyrelog.info("checkout_priced", Map.of("plan", "pro"));      // no category
}
```

How it differs from the Android library:

| | |
|---|---|
| Key | A server key, `lyr_srv_...`: its events are stored verified (`verified_by = server`). A `null` or blank key starts nothing, and every call does nothing. |
| Context | Platform `jvm`; environment from `Configuration.environment`, else `LYRELOG_ENVIRONMENT`, else `production` (an invalid variable is said once and replaced); `release` and `appId` default to the started jar's `Implementation-Version` and `Implementation-Title` (else its file name, else the main class); `os_name` and `os_version` from the JVM. No device, locale, screens, consent or Key Attestation. |
| Ids | An install id and one session id per process, never written to disk (as in Go and Python). |
| Users | `user` on each event, `Lyrelog.userScope(id)` for a thread (a request) or `LyrelogUser(id)` for a coroutine (`withContext`; kotlinx-coroutines is the application's own, Lyrelog doesn't bring it), `setUser` for the process; in that order of precedence. Reactive code (Reactor, RxJava) passes `user` on each event. |
| Queue | A temporary directory per process (deleted on close; one left by a process killed without closing is deleted by the next start, its events unsent), or `storageDirectory`, kept for the next start and locked to one process. Same caps: 20 MB and 7 days. |
| Sending | Every 5 s (at least 1 s). A JVM shutdown hook closes (sends what's left, up to `closeTimeoutMillis`, 5 s). It runs beside your framework's own shutdown hooks, so it keeps recording until nothing was logged for half a second (at most half the timeout), then sends: what Spring logs while its context closes is kept, and the exit never waits longer than the timeout. For a shutdown that logs for longer (a server draining slow requests), set `shutdownHook = false` and call `Lyrelog.close()` as the last step of your own shutdown (Spring: a `SmartLifecycle` with the lowest phase). `Lyrelog.flush()` before a serverless handler returns. `start` returns `true` when it started; `Lyrelog.isStarted` says whether it runs. |
| Uncaught exceptions | Off by default; `captureUncaught = true` records them as `exception` with `handled: false`, `mechanism: uncaught_exception` and `thread`, then runs the previous handler. |
| Diagnostics | To standard error, once per process, or to `Configuration.diagnostics` (a `Consumer<String>`). |
| Logback | `lyrelog-jvm-logback`: `<appender class="com.lyrelog.logback.LyrelogAppender"/>`. The logger name is the category, MDC and SLF4J 2 key-value pairs the attributes (`user_id` the user), the exception `error_type`, `error_message` and `payload.exception`. With `<apiKey>` it starts Lyrelog itself and closes it when Logback stops (an unset `${LYRELOG_KEY}` starts nothing). Records from the SDK's own threads are ignored, also behind an `AsyncAppender`, so it can't loop. Logback is the application's own (1.3 or later). |

The full page is the docs site's "Java and Kotlin (JVM)".

## Privacy

For the Play Console's data safety form, declare what your app sends through Lyrelog:

- **App info and performance**: Crash logs, Diagnostics (App functionality, Analytics if you use
  it that way). Collected, not shared.
- **Device or other IDs**: the install id, a random UUID that is not the advertising id, is not
  backed up and is deleted on a denial. Not linked to the person unless you also call `setUser`.
- **App activity > App interactions**: if you log screens or taps (`screen_viewed`, your own events).
- Anything else you put in `attrs` or `payload` (emails, purchases...) under its own category.

Data is encrypted in transit (https). The server adds the country of the sending address; the
address itself is not stored.

## Sample

`sample/` is Tilefall, a made-up puzzle game: drop tiles, an undo that fails (a handled error),
a crash button, and a settings screen with the consent choice. Put a key in `local.properties`:

```properties
lyrelog.key=lyr_ing_...
lyrelog.endpoint=http://10.0.2.2:8090
```

and `./gradlew :sample:installDebug`. On Android 17 the debug build asks for the local network
permission when its endpoint is plain http (or `adb shell pm grant com.example.tilefall
android.permission.ACCESS_LOCAL_NETWORK`).

## Tests

```bash
./gradlew :core:test :jvm:test :jvm-logback:test       # the JVM tests, no Android SDK needed
LYRELOG_E2E_URL=http://127.0.0.1:8090 LYRELOG_E2E_KEY=lyr_ing_... ./gradlew :core:test --tests '*EndToEnd*'
LYRELOG_E2E_URL=http://127.0.0.1:8090 LYRELOG_E2E_KEY=lyr_srv_... ./gradlew :jvm:test --tests '*EndToEnd*'
./gradlew publishToMavenLocal                              # every artifact to ~/.m2, version 1.1.1
./gradlew :android:testDebugUnitTest :sample:assembleDebug   # with an Android SDK
```

## Licence

MIT.
