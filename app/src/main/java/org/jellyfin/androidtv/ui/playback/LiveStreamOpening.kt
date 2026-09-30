package org.jellyfin.androidtv.ui.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import timber.log.Timber

/**
 * App-wide scope for finishing Live TV stream opens that nobody waits for any more (Finly). Not tied to a screen, so a
 * stream opened after its screen was left can still be closed.
 */
internal val liveStreamScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Run [open] outside the caller's scope and wait for it. Opening a Live TV channel makes the server tune a tuner, which
 * carries on when the app stops waiting: if the caller is cancelled first (the highlight moved on, the guide or player
 * was left), the open still completes in [scope] and its result is handed to [release], which should close the stream.
 * Errors from [open] reach the caller as usual.
 */
suspend fun <T> openDetached(
	scope: CoroutineScope = liveStreamScope,
	release: suspend (T) -> Unit,
	open: suspend () -> T,
): T {
	val opening = scope.async { open() }
	try {
		return opening.await()
	} catch (err: CancellationException) {
		// The caller no longer wants the stream: let the open finish, then give it back
		scope.launch {
			val result = try {
				opening.await()
			} catch (openErr: Exception) {
				// Nothing was opened, or the app is going away
				return@launch
			}
			try {
				release(result)
			} catch (releaseErr: Exception) {
				if (releaseErr is CancellationException) throw releaseErr
				Timber.w(releaseErr, "Unable to release an abandoned stream")
			}
		}
		throw err
	}
}

/**
 * Close the live streams a playback info response opened, for when nobody will play them (Finly).
 */
suspend fun ApiClient.closeLiveStreams(response: PlaybackInfoResponse) {
	response.mediaSources.mapNotNull { it.liveStreamId }.distinct().forEach { liveStreamId ->
		Timber.i("Closing abandoned live stream %s", liveStreamId)
		runCatching { mediaInfoApi.closeLiveStream(liveStreamId) }
			.onFailure { Timber.w(it, "Unable to close abandoned live stream %s", liveStreamId) }
	}
}
