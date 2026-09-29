package org.jellyfin.androidtv.ui.livetv

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.RelativeLayout

/**
 * Let a tap or mouse click on a guide cell do what the select button does on the focused cell. The guide handles the
 * remote at screen level using the focused cell, and a tap doesn't move focus, so taps are handled separately here
 * instead of making the cell clickable, which would take the select and long press events away from the remote.
 */
@SuppressLint("ClickableViewAccessibility")
fun RelativeLayout.handleGuideCellTouches(guide: LiveTvGuide) {
	val cell = this
	// Guide cells are created on a background thread, touch events are always handled on the main thread
	val listener = object : GestureDetector.SimpleOnGestureListener() {
		override fun onDown(e: MotionEvent): Boolean = true

		override fun onSingleTapUp(e: MotionEvent): Boolean {
			guide.onGuideCellClicked(cell)
			return true
		}

		override fun onLongPress(e: MotionEvent) {
			guide.onGuideCellLongClicked(cell)
		}
	}
	val detector = GestureDetector(context, listener, Handler(Looper.getMainLooper()))

	setOnTouchListener { _, event -> detector.onTouchEvent(event) }
}
