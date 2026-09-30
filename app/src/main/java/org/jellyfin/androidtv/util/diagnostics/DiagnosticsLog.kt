package org.jellyfin.androidtv.util.diagnostics

import android.os.Build
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.clientLogApi
import org.koin.java.KoinJavaComponent
import timber.log.Timber
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Keeps the most recent log lines in memory and uploads them to the server when something goes wrong, so problems on
 * a TV without a debugging cable (for example a channel that won't start) can be read in the server's log folder.
 */
object DiagnosticsLog {
	private const val MAX_LINES = 400
	private const val MIN_REPORT_INTERVAL_MS = 60_000L

	private val lines = ArrayDeque<String>(MAX_LINES)
	private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
	private var lastReportAt = 0L

	/** Timber tree recording every log line into the ring buffer. */
	val tree: Timber.Tree = object : Timber.Tree() {
		override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
			val level = when (priority) {
				Log.VERBOSE -> 'V'
				Log.DEBUG -> 'D'
				Log.INFO -> 'I'
				Log.WARN -> 'W'
				Log.ERROR -> 'E'
				else -> 'A'
			}
			val line = buildString {
				append(LocalDateTime.now().format(timeFormat)).append(' ').append(level).append(' ')
				// Stream URLs in the log carry the access token, and these lines are uploaded to the server
				append(tag ?: "-").append(": ").append(LogRedaction.redact(message))
				if (t != null) append('\n').append(Log.getStackTraceString(t).lineSequence().take(8).joinToString("\n"))
			}
			synchronized(lines) {
				if (lines.size == MAX_LINES) lines.removeFirst()
				lines.addLast(line)
			}
		}
	}

	fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

	/**
	 * Upload a report with the recent log to the server. Reports are rate limited so a failing channel that is retried
	 * doesn't flood the server.
	 */
	fun report(title: String, details: List<String> = emptyList()) {
		val now = System.currentTimeMillis()
		synchronized(this) {
			if (now - lastReportAt < MIN_REPORT_INTERVAL_MS) return
			lastReportAt = now
		}

		val document = buildString {
			appendLine("Finly TV diagnostics: $title")
			appendLine("Time: ${LocalDateTime.now()}")
			appendLine("App: ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
			appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
			if (details.isNotEmpty()) {
				appendLine()
				details.forEach(::appendLine)
			}
			appendLine()
			appendLine("Recent log:")
			snapshot().forEach(::appendLine)
		}

		val api = KoinJavaComponent.get<ApiClient>(ApiClient::class.java)
		ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
			runCatching { api.clientLogApi.logFile(document).content.fileName }
				.onSuccess { Timber.i("Uploaded diagnostics report %s", it) }
				.onFailure { Timber.w(it, "Unable to upload diagnostics report") }
		}
	}
}
