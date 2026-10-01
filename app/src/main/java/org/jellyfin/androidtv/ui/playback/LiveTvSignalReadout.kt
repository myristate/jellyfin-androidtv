package org.jellyfin.androidtv.ui.playback

import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.text.inSpans
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.ChannelSignal
import org.jellyfin.androidtv.data.repository.ChannelSignalRepository
import org.koin.java.KoinJavaComponent
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * (Finly) A line in the player's overlay with the tuner's signal for the live TV channel being watched, like
 * "Signal 84% · Quality 100%". It is read from the tuner every 10 seconds while the overlay shows, see [start] and [stop],
 * and hidden when the server has no reading, can't be reached or doesn't know about signals.
 *
 * @param lifecycleOwner the player's view lifecycle, readings stop while it is stopped and end with it
 * @param liveChannelId the live TV channel playing, or null when what plays isn't live TV
 */
class LiveTvSignalReadout(
	private val lifecycleOwner: LifecycleOwner,
	private val view: TextView,
	private val liveChannelId: () -> UUID?,
) {
	private val repository by lazy { KoinJavaComponent.get<ChannelSignalRepository>(ChannelSignalRepository::class.java) }

	private var job: Job? = null
	private var shownChannelId: UUID? = null

	/** Start showing the signal, for when the overlay shows. */
	fun start() {
		if (job?.isActive == true) return
		job = lifecycleOwner.lifecycleScope.launch {
			lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				while (true) {
					update()
					delay(INTERVAL)
				}
			}
		}
	}

	/** Stop reading the signal, for when the overlay hides. */
	fun stop() {
		job?.cancel()
		job = null
	}

	/** Read the signal again straight away when it is showing, for when the channel changes. */
	fun restart() {
		if (job == null) return
		hide()
		stop()
		start()
	}

	private suspend fun update() {
		val channelId = liveChannelId()
		if (channelId == null) {
			hide()
			return
		}
		// Don't leave another channel's reading up while asking
		if (channelId != shownChannelId) hide()

		val signal = repository.get(channelId)
		// The channel may have changed while asking
		if (signal == null || liveChannelId() != channelId) hide()
		else show(channelId, signal)
	}

	private fun show(channelId: UUID, signal: ChannelSignal) {
		val strength = signal.strengthPercent
		val quality = signal.qualityPercent
		if (strength == null || quality == null) {
			hide()
			return
		}

		val context = view.context
		view.text = SpannableStringBuilder().apply {
			append(context.getString(R.string.signal_readout, strength, quality))
			if (signal.isWeak) {
				append(" · ")
				inSpans(ForegroundColorSpan(ContextCompat.getColor(context, R.color.finly_signal_weak))) {
					append(context.getString(R.string.signal_weak))
				}
			}
		}
		view.visibility = View.VISIBLE
		shownChannelId = channelId
	}

	private fun hide() {
		view.visibility = View.GONE
		shownChannelId = null
	}

	private companion object {
		val INTERVAL = 10.seconds
	}
}
