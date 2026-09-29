package org.jellyfin.androidtv.ui.livetv

import android.widget.RelativeLayout
import java.time.LocalDateTime
import java.util.UUID

interface LiveTvGuide {
	fun displayChannels(start: Int, max: Int)
	fun getCurrentLocalStartDate(): LocalDateTime
	fun showProgramOptions()
	fun setSelectedProgram(programView: RelativeLayout)
	fun refreshFavorite(channelId: UUID)

	/**
	 * A tap or mouse click on a program cell or channel header, does the same as the select button on it.
	 */
	fun onGuideCellClicked(cell: RelativeLayout)

	/**
	 * A long press with touch or the mouse, does the same as a long press of the select button.
	 */
	fun onGuideCellLongClicked(cell: RelativeLayout)
}
