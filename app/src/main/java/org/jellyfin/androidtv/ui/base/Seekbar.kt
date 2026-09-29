package org.jellyfin.androidtv.ui.base

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.times
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Immutable
data class SeekbarColors(
	val backgroundColor: Color,
	val bufferColor: Color,
	val progressColor: Color,
	val knobColor: Color,
)

object SeekbarDefaults {
	@ReadOnlyComposable
	@Composable
	fun colors(
		backgroundColor: Color = JellyfinTheme.colorScheme.rangeControlBackground,
		bufferColor: Color = JellyfinTheme.colorScheme.seekbarBuffer,
		progressColor: Color = JellyfinTheme.colorScheme.rangeControlFill,
		knobColor: Color = JellyfinTheme.colorScheme.rangeControlKnob,
	) = SeekbarColors(
		backgroundColor = backgroundColor,
		bufferColor = bufferColor,
		progressColor = progressColor,
		knobColor = knobColor,
	)
}

@Composable
fun Seekbar(
	modifier: Modifier = Modifier,
	interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
	progress: Duration = Duration.ZERO,
	buffer: Duration = Duration.ZERO,
	duration: Duration = Duration.ZERO,
	seekForwardAmount: Duration = duration / 100,
	seekRewindAmount: Duration = duration / 100,
	onScrubbing: ((scrubbing: Boolean) -> Unit)? = null,
	onSeek: ((progress: Duration) -> Unit)? = null,
	enabled: Boolean = true,
	colors: SeekbarColors = SeekbarDefaults.colors(),
) {
	val durationMs = duration.inWholeMilliseconds.toFloat().coerceAtLeast(1f)
	val progressPercentage = progress.inWholeMilliseconds.toFloat() / durationMs
	val bufferPercentage = buffer.inWholeMilliseconds.toFloat() / durationMs
	val seekForwardPercentage = seekForwardAmount.inWholeMilliseconds.toFloat() / durationMs
	val seekRewindPercentage = seekRewindAmount.inWholeMilliseconds.toFloat() / durationMs

	Seekbar(
		modifier = modifier,
		interactionSource = interactionSource,
		progress = progressPercentage,
		buffer = bufferPercentage,
		seekForwardAmount = seekForwardPercentage,
		seekRewindAmount = seekRewindPercentage,
		onScrubbing = onScrubbing,
		onSeek = if (onSeek == null) null else { progress -> onSeek(progress.toDouble() * duration) },
		enabled = enabled,
		colors = colors,
	)
}

@Composable
fun Seekbar(
	modifier: Modifier = Modifier,
	interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
	progress: Float = 0f,
	buffer: Float = 0f,
	seekForwardAmount: Float = 0.01f,
	seekRewindAmount: Float = 0.01f,
	onScrubbing: ((scrubbing: Boolean) -> Unit)? = null,
	onSeek: ((progress: Float) -> Unit)? = null,
	enabled: Boolean = true,
	colors: SeekbarColors = SeekbarDefaults.colors(),
) {
	val coroutineScope = rememberCoroutineScope()
	val focused by interactionSource.collectIsFocusedAsState()
	var progressOverride by remember { mutableStateOf<Float?>(null) }
	val visibleProgress = progressOverride ?: progress
	val knobAlpha by animateFloatAsState(if (focused) 1f else 0f)
	var scrubCancelJob by remember { mutableStateOf<Job?>(null) }
	// Read the latest callbacks without restarting a gesture that is in progress
	val currentOnSeek by rememberUpdatedState(onSeek)
	val currentOnScrubbing by rememberUpdatedState(onScrubbing)
	val acceptsTouch = enabled && onSeek != null

	Box(
		modifier = modifier
			.onKeyEvent {
				if (!enabled) return@onKeyEvent false

				val isForward = it.key == Key.DirectionRight
				val isRewind = it.key == Key.DirectionLeft
				val isScrubbing = isForward || isRewind
				val isKeyUp = it.type == KeyEventType.KeyUp
				val isKeyDown = it.type == KeyEventType.KeyDown

				val newProgress = when {
					isKeyDown && isForward -> (visibleProgress + seekForwardAmount).coerceAtMost(1f)
					isKeyDown && isRewind -> (visibleProgress - seekRewindAmount).coerceAtLeast(0f)
					else -> visibleProgress
				}

				if (isScrubbing && isKeyDown && onScrubbing != null) {
					scrubCancelJob?.cancel()
					onScrubbing(true)
				}

				if (visibleProgress != newProgress) {
					progressOverride = newProgress
					if (onSeek != null) onSeek(newProgress)
				}

				if (isScrubbing && isKeyUp && onScrubbing != null) {
					scrubCancelJob?.cancel()
					scrubCancelJob = coroutineScope.launch {
						delay(300.milliseconds)
						onScrubbing(false)
						progressOverride = null
					}
				}

				return@onKeyEvent isScrubbing
			}
			// A tap or mouse click seeks to that point, dragging scrubs like holding left or right on the remote
			.pointerInput(acceptsTouch) {
				if (!acceptsTouch) return@pointerInput
				detectTapGestures { offset ->
					val newProgress = (offset.x / size.width).coerceIn(0f, 1f)
					scrubCancelJob?.cancel()
					progressOverride = newProgress
					currentOnSeek?.invoke(newProgress)
					scrubCancelJob = coroutineScope.launch {
						delay(300.milliseconds)
						progressOverride = null
					}
				}
			}
			.pointerInput(acceptsTouch) {
				if (!acceptsTouch) return@pointerInput
				fun endScrub() {
					scrubCancelJob?.cancel()
					scrubCancelJob = coroutineScope.launch {
						delay(300.milliseconds)
						currentOnScrubbing?.invoke(false)
						progressOverride = null
					}
				}
				detectHorizontalDragGestures(
					onDragStart = {
						scrubCancelJob?.cancel()
						currentOnScrubbing?.invoke(true)
					},
					onDragEnd = { endScrub() },
					onDragCancel = { endScrub() },
				) { change, _ ->
					change.consume()
					val newProgress = (change.position.x / size.width).coerceIn(0f, 1f)
					progressOverride = newProgress
					currentOnSeek?.invoke(newProgress)
				}
			}
			.focusable(interactionSource = interactionSource, enabled = enabled)
			.drawWithContent {
				val barCornerRadius = CornerRadius(size.minDimension, size.minDimension)

				// Background bar
				drawRoundRect(
					color = colors.backgroundColor,
					cornerRadius = barCornerRadius,
				)

				// Buffer bar
				if (buffer > 0f) {
					drawRoundRect(
						color = colors.bufferColor,
						size = size.copy(
							width = buffer * size.width,
						),
						cornerRadius = barCornerRadius,
					)
				}

				// Progress bar
				if (visibleProgress > 0f) {
					drawRoundRect(
						color = colors.progressColor,
						size = size.copy(
							width = visibleProgress * size.width,
						),
						cornerRadius = barCornerRadius,
					)
				}

				// Progress knob
				drawCircle(
					color = colors.knobColor,
					alpha = knobAlpha,
					center = center.copy(
						x = visibleProgress * size.width,
					),
					radius = size.minDimension * 2,
				)
			}
	)
}
