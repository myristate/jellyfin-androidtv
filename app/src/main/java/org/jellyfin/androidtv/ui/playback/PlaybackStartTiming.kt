package org.jellyfin.androidtv.ui.playback

import android.os.SystemClock
import timber.log.Timber

/**
 * Logs how long each step of starting playback takes, from the request to the first frame on screen.
 * Filter logcat on the "PlaybackTiming" tag to follow a start, for example when tuning Live TV channels.
 */
object PlaybackStartTiming {
	private const val TAG = "PlaybackTiming"

	private var startedAt = 0L
	private var label = ""
	private val events = mutableListOf<String>()

	@JvmStatic
	fun start(label: String) {
		startedAt = SystemClock.elapsedRealtime()
		this.label = label
		synchronized(events) {
			events.clear()
			events.add("$label: start")
		}
		Timber.tag(TAG).i("%s: start", label)
	}

	@JvmStatic
	fun mark(event: String) {
		if (startedAt == 0L) return
		val elapsed = SystemClock.elapsedRealtime() - startedAt
		synchronized(events) { events.add("$label: $event +${elapsed}ms") }
		Timber.tag(TAG).i("%s: %s +%dms", label, event, elapsed)
	}

	/** The steps of the latest start, for diagnostics reports. */
	@JvmStatic
	fun events(): List<String> = synchronized(events) { events.toList() }

	/** Log the final step and stop timing, later events are ignored until the next start. */
	@JvmStatic
	fun finish(event: String) {
		mark(event)
		startedAt = 0L
	}
}
