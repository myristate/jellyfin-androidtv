package org.jellyfin.androidtv.util.diagnostics

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.util.EventLogger
import timber.log.Timber

/**
 * media3's [EventLogger] (every load, track, decoder, dropped frame and error), written through Timber so the lines
 * also reach [DiagnosticsLog] and its reports.
 */
@OptIn(UnstableApi::class)
class TimberEventLogger : EventLogger() {
	override fun logd(msg: String) {
		Timber.tag(TAG).d(msg)
	}

	override fun loge(msg: String) {
		Timber.tag(TAG).e(msg)
	}

	private companion object {
		const val TAG = "EventLogger"
	}
}
