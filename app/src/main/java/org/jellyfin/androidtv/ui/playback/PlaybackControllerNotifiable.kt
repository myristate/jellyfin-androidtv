package org.jellyfin.androidtv.ui.playback

interface PlaybackControllerNotifiable {
	fun onCompletion()
	fun onError()
	fun onPrepared()
	fun onProgress()
	fun onPlaybackSpeedChange(newSpeed: Float)

	/** The player started ([buffering] true) or stopped waiting for data. */
	fun onBuffering(buffering: Boolean)
}
