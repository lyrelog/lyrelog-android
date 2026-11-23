package com.lyrelog

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.lyrelog.core.Names
import com.lyrelog.core.Resource
import java.util.Locale

/** What the app and device say about themselves, read once at start. */
internal object DeviceContext {
    const val SDK = "lyrelog-android/${Lyrelog.VERSION}"

    /** `resource.sdk`: this SDK, after the wrapper's name when one sends through it. */
    fun sdk(wrapper: String?): String {
        val name = wrapper.orEmpty().filterNot { it.isWhitespace() || it.isISOControl() }.take(64)
        return if (name.isEmpty()) SDK else "$name $SDK"
    }

    fun resource(context: Context, configuration: Configuration): Resource {
        val (versionName, versionCode) = packageVersion(context)
        return Resource(
            appId = context.packageName,
            appVersion = configuration.appVersion ?: versionName,
            appBuild = versionCode,
            osName = "android",
            osVersion = Build.VERSION.RELEASE ?: "",
            deviceModel = Defaults.deviceModel(Build.MANUFACTURER, Build.MODEL),
            locale = Defaults.locale(Locale.getDefault()),
            sdk = sdk(configuration.wrapper),
            environment = configuration.environment?.let(Names::clean) ?: Defaults.environment(isDebuggable(context)),
            platform = configuration.platform?.let(Names::clean) ?: "android",
        )
    }

    fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun packageVersion(context: Context): Pair<String, String> = try {
        val manager = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            manager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(context.packageName, 0)
        }
        val code = if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toString()
        }
        (info.versionName ?: "") to code
    } catch (e: Exception) {
        "" to ""
    }

    fun isEmulator(): Boolean = Defaults.isEmulator(Build.HARDWARE, Build.PRODUCT, Build.FINGERPRINT)
}

/** The pure part of the defaults, unit-tested without a device. */
internal object Defaults {
    fun environment(debuggable: Boolean) = if (debuggable) "development" else "production"

    /** "Google Pixel 9", "samsung SM-S921B": the manufacturer once, then the model. */
    fun deviceModel(manufacturer: String?, model: String?): String {
        val maker = manufacturer.orEmpty().trim()
        val name = model.orEmpty().trim()
        return when {
            name.isEmpty() -> maker
            maker.isEmpty() || name.startsWith(maker, ignoreCase = true) -> name
            else -> "$maker $name"
        }
    }

    /** `es_ES`, like the other SDKs (`-` would be `_`); just the language when there's no country. */
    fun locale(locale: Locale): String {
        val language = locale.language.orEmpty()
        val country = locale.country.orEmpty()
        return if (country.isEmpty()) language else "${language}_$country"
    }

    /** The Android emulator (where `10.0.2.2` is the host machine). */
    fun isEmulator(hardware: String?, product: String?, fingerprint: String?): Boolean {
        val h = hardware.orEmpty()
        val p = product.orEmpty()
        val f = fingerprint.orEmpty()
        return h == "ranchu" || h == "goldfish" || p.startsWith("sdk_") || p.contains("_sdk_") ||
            f.startsWith("generic") || f.contains("emulator")
    }
}
