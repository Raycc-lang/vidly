package org.raycc.vidly.native

import android.content.Context
import android.os.Build
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.raycc.vidly.BuildConfig
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

// Lightweight, self-hosted crash reporting (no GMS / Firebase / NDK).
//
// Java/Kotlin crashes are caught via an UncaughtExceptionHandler and written to
// a pending file on disk. Native crashes (SIGSEGV/SIGABRT from WebRTC) kill the
// process before any handler runs, so on each launch we also scan the logcat
// crash buffer for native crash signatures. All pending reports are uploaded to
// the server on the next launch, then deleted once accepted.
object CrashReporter {
    private const val PENDING_DIR = "crashes"
    private const val PREFS_NAME = "vidly_crash_prefs"
    private const val KEY_LAST_NATIVE_HASH = "last_native_hash"
    private const val JSON = "application/json; charset=utf-8"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writePendingReport(appContext, buildJavaReport(throwable)) }
            previous?.uncaughtException(thread, throwable)
        }
        // Native crashes from the previous session only surface in logcat.
        runCatching { captureNativeCrash(appContext) }
        // Upload anything left over from previous sessions.
        runCatching { uploadPending(appContext) }
    }

    private fun buildJavaReport(throwable: Throwable): JSONObject {
        return baseReport("java").apply {
            put("exceptionType", throwable.javaClass.name)
            put("message", throwable.message ?: "")
            put("stacktrace", android.util.Log.getStackTraceString(throwable))
        }
    }

    private fun baseReport(kind: String): JSONObject {
        return JSONObject().apply {
            put("kind", kind)
            put("timestamp", iso8601(Date()))
            put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("hardware", Build.HARDWARE)
            put("androidVersion", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            put("appVersion", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        }
    }

    private fun captureNativeCrash(context: Context): Unit {
        val lines = try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-b", "crash"))
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            return
        }
        if (lines.isBlank()) return
        val pattern = Regex("F DEBUG|SIG[A-Z]+|signal \\d+|backtrace:|Fatal signal")
        val matched = lines.lineSequence().filter { pattern.containsMatchIn(it) }.toList()
        if (matched.isEmpty()) return

        // Dedupe: skip if the crash buffer is byte-identical to the one we last
        // uploaded. Note: the crash buffer is cumulative, so a new crash changes
        // the hash and is reported (it may re-include earlier frames merged in).
        val logcat = matched.joinToString("\n")
        val hash = Integer.toHexString(logcat.hashCode())
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_LAST_NATIVE_HASH, null) == hash) return
        prefs.edit().putString(KEY_LAST_NATIVE_HASH, hash).apply()

        // Pull the backtrace frames (logged under the F DEBUG tag) into the
        // stacktrace field for quick scanning; full context stays in `logcat`.
        val backtrace = matched.filter { it.contains(Regex("#\\d{2}\\s+pc")) }.joinToString("\n")
        val report = baseReport("native").apply {
            put("exceptionType", "NativeCrash")
            put("message", matched.firstOrNull { it.contains("signal", ignoreCase = true) } ?: "Native crash detected")
            put("stacktrace", backtrace)
            put("logcat", logcat)
        }
        writePendingReport(context, report)
    }

    private fun writePendingReport(context: Context, report: JSONObject) {
        val dir = File(context.cacheDir, PENDING_DIR).apply { mkdirs() }
        val name = "${System.currentTimeMillis()}-${randomSuffix()}.json"
        File(dir, name).writeText(report.toString())
    }

    private fun uploadPending(context: Context) {
        val dir = File(context.cacheDir, PENDING_DIR)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        val url = NativeSignalingClient.httpUrlFor("crash")
        for (file in files) {
            val body = runCatching { file.readText() }.getOrNull() ?: continue
            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON.toMediaType()))
                .build()
            http.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = Unit
                override fun onResponse(call: Call, response: Response) {
                    response.use { if (it.isSuccessful) file.delete() }
                }
            })
        }
    }

    private fun iso8601(date: Date): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(date)
    }

    private fun randomSuffix(): String =
        java.util.UUID.randomUUID().toString().take(8)
}
