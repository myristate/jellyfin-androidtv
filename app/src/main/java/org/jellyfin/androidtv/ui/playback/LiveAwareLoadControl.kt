package org.jellyfin.androidtv.ui.playback

import androidx.annotation.OptIn
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * Load control that starts Live TV sooner than recorded media.
 *
 * A live stream only arrives at real-time speed, so waiting for the usual 2.5 seconds of buffer before playing adds
 * 2.5 seconds to every channel change. For Live TV playback starts once a short buffer is ready, with a longer
 * buffer after a stall so a weak stream doesn't keep stuttering. Everything else is left to [delegate].
 *
 * The methods are forwarded explicitly: Kotlin's `by` delegation skips the interface's Java default methods, which
 * would then throw "not implemented".
 */
@OptIn(UnstableApi::class)
class LiveAwareLoadControl(
	private val delegate: DefaultLoadControl,
) : LoadControl {
	@Volatile
	var liveTv = false

	override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
		if (!liveTv) return delegate.shouldStartPlayback(parameters)

		val bufferedUs = Util.getPlayoutDurationForMediaDuration(parameters.bufferedDurationUs, parameters.playbackSpeed)
		val neededUs = if (parameters.rebuffering) LIVE_REBUFFER_US else LIVE_START_BUFFER_US
		return bufferedUs >= neededUs || delegate.shouldStartPlayback(parameters)
	}

	override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)

	override fun onTracksSelected(
		parameters: LoadControl.Parameters,
		trackGroups: TrackGroupArray,
		trackSelections: Array<out ExoTrackSelection?>,
	) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)

	override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)

	override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)

	override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)

	override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)

	override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)

	override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean = delegate.shouldContinueLoading(parameters)

	override fun shouldContinuePreloading(
		playerId: PlayerId,
		timeline: Timeline,
		mediaPeriodId: MediaSource.MediaPeriodId,
		bufferedDurationUs: Long,
	): Boolean = delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

	companion object {
		const val LIVE_START_BUFFER_US = 1_000_000L
		const val LIVE_REBUFFER_US = 2_500_000L
	}
}
