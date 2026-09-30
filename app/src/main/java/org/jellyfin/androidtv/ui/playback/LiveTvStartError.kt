package org.jellyfin.androidtv.ui.playback

import org.jellyfin.androidtv.data.compat.PlaybackException
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import java.io.InterruptedIOException

/**
 * Why a Live TV channel couldn't be opened (Finly), and whether trying again can help.
 */
enum class LiveTvStartError(val retry: Boolean) {
	/** The server tuned the channel and found nothing being broadcast (503), trying again won't help. */
	OFF_AIR(false),

	/** Every tuner is in use (409), one may come free in a moment. */
	TUNERS_BUSY(true),

	/** The server or the tuner failed (5xx, such as 502 or 504). */
	SERVER(true),

	/** The server took too long to answer. */
	TIMEOUT(true),

	/**
	 * Anything else, such as a profile not allowed to watch (a parental block), no stream the TV can play or a request
	 * the server refused: trying again gives the same answer, so the real reason is shown straight away.
	 */
	OTHER(false);

	companion object {
		private const val HTTP_CONFLICT = 409
		private const val HTTP_UNAVAILABLE = 503

		/**
		 * Classify an error from opening a channel. The server's X-Finly-Reason and Retry-After headers can't be read
		 * through the SDK, so a 503 is taken as off air, which is what Finly's server uses it for.
		 */
		@JvmStatic
		fun classify(error: Throwable?): LiveTvStartError {
			// PlaybackManager hands errors over wrapped, find the one that says what happened
			val cause = generateSequence(error) { it.cause }
				.take(MAX_CAUSES)
				.firstOrNull { it is ApiClientException || it is PlaybackException || it is InterruptedIOException }
				?: return OTHER

			return when (cause) {
				is PlaybackException -> OTHER
				is TimeoutException -> TIMEOUT
				// A socket timeout the SDK didn't wrap as one
				is InterruptedIOException -> TIMEOUT
				is InvalidStatusException -> when (cause.status) {
					HTTP_UNAVAILABLE -> OFF_AIR
					HTTP_CONFLICT -> TUNERS_BUSY
					in 500..599 -> SERVER
					else -> OTHER
				}

				else -> if (cause.cause is InterruptedIOException) TIMEOUT else OTHER
			}
		}

		private const val MAX_CAUSES = 8
	}
}
