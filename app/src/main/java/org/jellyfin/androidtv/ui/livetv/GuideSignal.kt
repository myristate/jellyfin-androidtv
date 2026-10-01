package org.jellyfin.androidtv.ui.livetv

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.children
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.ChannelSignal
import org.jellyfin.androidtv.data.repository.ChannelSignalRepository
import org.jellyfin.androidtv.data.repository.ChannelSignals
import org.jellyfin.androidtv.ui.GuideChannelHeader
import org.koin.java.KoinJavaComponent

/**
 * (Finly) Show the weak signal mark on a guide channel when the tuner receives it weakly, otherwise hide it. Channels
 * without a reading show nothing.
 */
fun ImageView.showWeakSignal(signal: ChannelSignal?) {
	if (signal == null || !signal.isWeak) {
		visibility = View.GONE
		contentDescription = null
		return
	}

	val quality = signal.qualityPercent
	val strength = signal.strengthPercent
	contentDescription = if (quality != null && strength != null) {
		context.getString(R.string.signal_weak_description, quality, strength)
	} else {
		context.getString(R.string.signal_weak)
	}
	visibility = View.VISIBLE
}

/**
 * (Finly) Marks the guide's channels that the tuner receives weakly. The readings are asked for once each time the
 * guide loads its channels with [refresh], and channel headers added afterwards are marked with [apply].
 *
 * @param scope a scope that ends with the guide's view, so a slow answer isn't applied to a closed guide
 * @param channels the guide's column of channel headers
 */
class GuideSignalBadges(
	private val scope: CoroutineScope,
	private val channels: ViewGroup,
) {
	private val repository by lazy { KoinJavaComponent.get<ChannelSignalRepository>(ChannelSignalRepository::class.java) }

	private var signals = ChannelSignals.EMPTY
	private var job: Job? = null

	/** Ask the server for the latest readings and mark the channels showing. */
	fun refresh() {
		job?.cancel()
		job = scope.launch {
			// Older servers don't have the readings, the guide then shows no marks
			signals = repository.getAll() ?: ChannelSignals.EMPTY
			channels.children.filterIsInstance<GuideChannelHeader>().forEach(::apply)
		}
	}

	/** Mark a channel header from the readings already loaded. */
	fun apply(header: GuideChannelHeader) {
		header.setSignal(header.channel?.id?.let(signals::forChannel))
	}
}
