package org.jellyfin.androidtv.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import timber.log.Timber
import java.util.UUID
import kotlin.math.roundToInt

/**
 * (Finly) The tuner's last signal reading for a channel. Strength and quality are percentages.
 */
@Serializable
data class ChannelSignal(
	@SerialName("Strength") val strength: Double? = null,
	@SerialName("Quality") val quality: Double? = null,
	@SerialName("SymbolQuality") val symbolQuality: Double? = null,
	@SerialName("MeasuredAt") val measuredAt: String? = null,
	@SerialName("IsLive") val isLive: Boolean = false,
	@SerialName("IsWeak") val isWeak: Boolean = false,
) {
	val strengthPercent: Int? get() = strength?.roundToInt()
	val qualityPercent: Int? get() = quality?.roundToInt()
}

/**
 * (Finly) Signal readings for every channel the server has one for, keyed by channel id without dashes in lower case.
 * Channels without a reading aren't listed.
 */
@Serializable
data class ChannelSignals(
	@SerialName("WeakQualityThreshold") val weakQualityThreshold: Double? = null,
	@SerialName("Channels") val channels: Map<String, ChannelSignal> = emptyMap(),
) {
	/** The reading for a channel, or null when there isn't one. */
	fun forChannel(channelId: UUID): ChannelSignal? = channels[channelSignalKey(channelId.toString())]

	companion object {
		val EMPTY = ChannelSignals()
	}
}

/** Channel ids are compared without dashes and in lower case. */
fun channelSignalKey(id: String) = id.replace("-", "").lowercase()

/**
 * (Finly) Asks the server how well the TV tuner receives each channel, so the guide can mark channels with a weak signal
 * and the player can show the signal of the channel being watched.
 *
 * Servers without the signal endpoint, or any other error, give null so callers simply show nothing.
 */
class ChannelSignalRepository(
	private val api: ApiClient,
) {
	/** The last reading of every channel that has one, or null when the server couldn't say. */
	suspend fun getAll(): ChannelSignals? = withContext(Dispatchers.IO) {
		try {
			val body = api.request(HttpMethod.GET, "/LiveTv/ChannelSignal").body
			parseAll(body.decodeToString())
		} catch (err: ApiClientException) {
			Timber.i(err, "Unable to load channel signals")
			null
		} catch (err: IllegalArgumentException) {
			// An unexpected body, kotlinx.serialization's SerializationException is an IllegalArgumentException
			Timber.w(err, "Unable to read channel signals")
			null
		}
	}

	/**
	 * The signal of one channel, read from the tuner when it is tuned to that channel. Null when the server has no reading
	 * for it or couldn't say.
	 */
	suspend fun get(channelId: UUID): ChannelSignal? = withContext(Dispatchers.IO) {
		val key = channelSignalKey(channelId.toString())
		try {
			val body = api.request(HttpMethod.GET, "/LiveTv/ChannelSignal/$key").body
			parseOne(body.decodeToString())
		} catch (err: InvalidStatusException) {
			if (err.status == 404) Timber.d("No signal reading for channel %s", key)
			else Timber.i(err, "Unable to load the signal of channel %s", key)
			null
		} catch (err: ApiClientException) {
			Timber.i(err, "Unable to load the signal of channel %s", key)
			null
		} catch (err: IllegalArgumentException) {
			Timber.w(err, "Unable to read the signal of channel %s", key)
			null
		}
	}

	companion object {
		private val json = Json { ignoreUnknownKeys = true }

		/** @throws IllegalArgumentException when the body isn't a signal list */
		fun parseAll(body: String): ChannelSignals {
			val signals = json.decodeFromString<ChannelSignals>(body)
			return signals.copy(channels = signals.channels.mapKeys { (id, _) -> channelSignalKey(id) })
		}

		/** @throws IllegalArgumentException when the body isn't a signal reading */
		fun parseOne(body: String): ChannelSignal = json.decodeFromString<ChannelSignal>(body)
	}
}
