package org.jellyfin.androidtv.ui.livetv

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.annotation.OptIn
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.playback.LiveAwareLoadControl
import org.jellyfin.androidtv.ui.playback.LiveTvExtractorsFactory
import org.jellyfin.androidtv.ui.playback.PlaybackStartTiming
import org.jellyfin.androidtv.ui.playback.liveStreamScope
import org.jellyfin.androidtv.ui.playback.openDetached
import org.jellyfin.androidtv.util.profile.createDeviceProfile
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.ServerVersion
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.koin.java.KoinJavaComponent
import timber.log.Timber
import java.util.UUID

/**
 * Plays a small, muted live preview of the highlighted channel in the Live TV guide.
 *
 * A channel is previewed once it stays highlighted for [DWELL_MS]. The preview opens the channel on the server like
 * normal playback, so pressing select to watch it full screen shares the already tuned stream and starts almost at
 * once. Only one channel is previewed at a time, and its stream is closed as soon as the highlight moves on or the
 * guide is left; the server keeps a closed tuner stream around briefly, which is what makes the switch to full
 * screen instant. A preview stops by itself after [MAX_PREVIEW_MS], so a guide left open doesn't hold a tuner for hours.
 */
@OptIn(UnstableApi::class)
class GuideLivePreview(
	private val context: Context,
	private val scope: LifecycleCoroutineScope,
	private val playerView: PlayerView,
	private val placeholder: View,
	private val offAirMessage: View,
) {
	private val api = KoinJavaComponent.get<ApiClient>(ApiClient::class.java)
	private val userPreferences = KoinJavaComponent.get<UserPreferences>(UserPreferences::class.java)
	private val handler = Handler(Looper.getMainLooper())

	private var player: ExoPlayer? = null
	private var pendingChannel: UUID? = null
	private var previewChannel: UUID? = null
	private var liveStreamId: String? = null
	private var openJob: Job? = null

	private val startPending = Runnable { pendingChannel?.let(::start) }

	// Stop without forgetting the highlighted channel: moving the highlight or coming back to the guide previews again
	private val stopIdle = Runnable {
		Timber.i("Stopping guide preview of %s after %d minutes", previewChannel, MAX_PREVIEW_MS / 60_000)
		stop()
	}

	/** The highlighted channel changed, preview it if the highlight stays there. */
	fun onChannelHighlighted(channelId: UUID?) {
		if (channelId == pendingChannel) return
		pendingChannel = channelId
		handler.removeCallbacks(startPending)
		if (channelId == null) {
			stop()
		} else if (channelId != previewChannel) {
			handler.postDelayed(startPending, DWELL_MS)
		}
	}

	/** Stop previewing, for example when the guide is paused or the channel is opened full screen. */
	fun stop() {
		handler.removeCallbacks(startPending)
		handler.removeCallbacks(stopIdle)
		openJob?.cancel()
		openJob = null
		player?.let {
			it.stop()
			it.clearMediaItems()
		}
		playerView.visibility = View.INVISIBLE
		placeholder.visibility = View.VISIBLE
		offAirMessage.visibility = View.GONE
		closeLiveStream()
		previewChannel = null
	}

	/** Preview the last highlighted channel again, for example when coming back to the guide. */
	fun resume() {
		handler.removeCallbacks(startPending)
		if (pendingChannel != null && previewChannel == null) handler.postDelayed(startPending, DWELL_MS)
	}

	fun release() {
		stop()
		pendingChannel = null
		player?.release()
		player = null
		playerView.player = null
	}

	private fun start(channelId: UUID) {
		stop()
		previewChannel = channelId
		PlaybackStartTiming.start("Guide preview $channelId")
		handler.postDelayed(stopIdle, MAX_PREVIEW_MS)

		openJob = scope.launch {
			val source = try {
				// The server tunes the channel while this runs. When the highlight moves on or the guide is left before
				// it answers, the open still finishes and its stream is closed, so no tuner is left busy.
				openDetached(release = { opened: MediaSourceInfo? -> opened?.liveStreamId?.let { closeLiveStreamNow(it) } }) {
					api.mediaInfoApi.getPostedPlaybackInfo(
						itemId = channelId,
						data = PlaybackInfoDto(
							deviceProfile = createDeviceProfile(context, userPreferences, KoinJavaComponent.get(ServerVersion::class.java)),
							enableDirectPlay = true,
							enableDirectStream = false,
							enableTranscoding = false,
							autoOpenLiveStream = true,
						)
					).content.mediaSources.firstOrNull()
				}
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to open guide preview for %s", channelId)
				// The server tuned the channel and found nothing being broadcast, say so instead of an empty preview
				if (err is InvalidStatusException && err.status == 503 && previewChannel == channelId) {
					offAirMessage.visibility = View.VISIBLE
				}
				null
			}

			if (source == null || previewChannel != channelId) {
				source?.liveStreamId?.let(::closeLiveStream)
				return@launch
			}

			liveStreamId = source.liveStreamId
			if (!source.supportsDirectPlay) {
				// Previews only use the tuner's own stream, a transcode would be too slow and costly
				Timber.i("Guide preview not available for %s, the channel can't be played directly", channelId)
				closeLiveStream()
				return@launch
			}

			PlaybackStartTiming.mark("preview stream info")
			val url = api.videosApi.getVideoStreamUrl(
				itemId = channelId,
				container = source.container,
				mediaSourceId = source.id,
				static = true,
				tag = source.eTag,
				liveStreamId = source.liveStreamId,
			)

			val preview = player ?: createPlayer().also { player = it }
			preview.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
			preview.prepare()
			preview.playWhenReady = true
		}
	}

	private fun createPlayer(): ExoPlayer {
		val renderersFactory = DefaultRenderersFactory(context)
			.setEnableDecoderFallback(true)
			.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
		val timestampSearchBytes = TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES * 3
		val extractors = LiveTvExtractorsFactory(
			DefaultExtractorsFactory().setTsExtractorTimestampSearchBytes(timestampSearchBytes),
			timestampSearchBytes,
		) { true }
		val dataSourceFactory = DefaultDataSource.Factory(context, KoinJavaComponent.get(HttpDataSource.Factory::class.java))

		return ExoPlayer.Builder(context)
			.setRenderersFactory(renderersFactory)
			.setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, extractors))
			.setLoadControl(LiveAwareLoadControl(DefaultLoadControl()).apply { liveTv = true })
			.build()
			.apply {
				// Silent, and without decoding sound at all
				volume = 0f
				trackSelectionParameters = trackSelectionParameters.buildUpon()
					.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
					.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
					.build()
				addListener(object : Player.Listener {
					override fun onRenderedFirstFrame() {
						PlaybackStartTiming.finish("preview first frame")
						placeholder.visibility = View.INVISIBLE
						playerView.visibility = View.VISIBLE
					}

					override fun onPlayerError(error: PlaybackException) {
						Timber.w(error, "Guide preview error %s", error.errorCodeName)
						stop()
					}
				})
				playerView.player = this
				playerView.useController = false
			}
	}

	private fun closeLiveStream() {
		liveStreamId?.let(::closeLiveStream)
		liveStreamId = null
	}

	private fun closeLiveStream(id: String) {
		// Not tied to the guide's lifecycle, the close has to reach the server even when the guide is being left
		liveStreamScope.launch(Dispatchers.IO) { closeLiveStreamNow(id) }
	}

	private suspend fun closeLiveStreamNow(id: String) {
		runCatching { api.mediaInfoApi.closeLiveStream(id) }
			.onFailure { Timber.w(it, "Unable to close guide preview stream %s", id) }
	}

	companion object {
		/** How long a channel has to stay highlighted before it is previewed. */
		const val DWELL_MS = 800L

		/** How long a channel is previewed while the highlight stays on it. */
		const val MAX_PREVIEW_MS = 2 * 60_000L
	}
}
