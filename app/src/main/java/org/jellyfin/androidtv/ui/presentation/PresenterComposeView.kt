package org.jellyfin.androidtv.ui.presentation

import android.content.Context
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AbstractComposeView

/**
 * A ComposeView for display-only content in leanback presenters. Leanback sets the click listeners on the view itself,
 * so touch and mouse input is kept away from the composed content. Otherwise an Android view inside the content, like
 * an image, would take the touch and the click listener would never be called.
 */
class PresenterComposeView(context: Context) : AbstractComposeView(context) {
	private var composable by mutableStateOf<(@Composable () -> Unit)?>(null)

	override val shouldCreateCompositionOnAttachedToWindow: Boolean
		get() = composable != null

	fun setContent(content: @Composable () -> Unit) {
		composable = content
		if (isAttachedToWindow) createComposition()
	}

	@Composable
	override fun Content() {
		composable?.invoke()
	}

	override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true
}
