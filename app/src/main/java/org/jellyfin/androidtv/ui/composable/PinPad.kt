package org.jellyfin.androidtv.ui.composable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.ProfilePin
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.LocalTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button

private val digitKeys = mapOf(
	Key.Zero to '0', Key.NumPad0 to '0',
	Key.One to '1', Key.NumPad1 to '1',
	Key.Two to '2', Key.NumPad2 to '2',
	Key.Three to '3', Key.NumPad3 to '3',
	Key.Four to '4', Key.NumPad4 to '4',
	Key.Five to '5', Key.NumPad5 to '5',
	Key.Six to '6', Key.NumPad6 to '6',
	Key.Seven to '7', Key.NumPad7 to '7',
	Key.Eight to '8', Key.NumPad8 to '8',
	Key.Nine to '9', Key.NumPad9 to '9',
)


/**
 * A number pad for entering a [ProfilePin], usable with the D-pad or the number keys on a remote. [onPinEntered]
 * is called as soon as the last digit is entered, after which the pad is cleared for the next attempt. While not
 * [enabled] input is ignored, the buttons stay focusable so the remote does not lose focus.
 */
@Composable
fun PinPad(
	title: String,
	onPinEntered: (pin: String) -> Unit,
	modifier: Modifier = Modifier,
	message: String? = null,
	enabled: Boolean = true,
) {
	var digits by remember { mutableStateOf("") }
	val color = LocalTextStyle.current.color.takeOrElse { Color.White }

	fun type(digit: Char) {
		if (!enabled || digits.length >= ProfilePin.LENGTH) return
		digits += digit
		if (digits.length == ProfilePin.LENGTH) {
			val pin = digits
			digits = ""
			onPinEntered(pin)
		}
	}

	fun deleteDigit() {
		if (enabled) digits = digits.dropLast(1)
	}

	Column(
		modifier = modifier.onDigitKeys(onDigit = ::type, onDelete = ::deleteDigit),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Text(
			text = title,
			color = color,
			textAlign = TextAlign.Center,
			style = LocalTextStyle.current.copy(fontSize = 20.sp),
		)

		PinDots(filled = digits.length, color = color)

		Text(
			text = message.orEmpty(),
			color = color,
			textAlign = TextAlign.Center,
			style = LocalTextStyle.current.copy(fontSize = 14.sp),
		)

		PinKeys(onDigit = ::type, onDelete = ::deleteDigit)
	}
}

private fun Modifier.onDigitKeys(onDigit: (Char) -> Unit, onDelete: () -> Unit) = onPreviewKeyEvent { event ->
	if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

	val digit = digitKeys[event.key]
	when {
		digit != null -> onDigit(digit)
		event.key == Key.Backspace || event.key == Key.Delete -> onDelete()
		else -> return@onPreviewKeyEvent false
	}
	true
}

@Composable
private fun PinDots(filled: Int, color: Color) = Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
	repeat(ProfilePin.LENGTH) { index ->
		Box(
			modifier = Modifier
				.size(14.dp)
				.border(2.dp, color, CircleShape)
				.background(if (index < filled) color else Color.Transparent, CircleShape)
		)
	}
}

@Composable
private fun PinKeys(onDigit: (Char) -> Unit, onDelete: () -> Unit) {
	val focusRequester = remember { FocusRequester() }

	for (row in listOf("123", "456", "789")) {
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			for (digit in row) {
				PinPadButton(
					onClick = { onDigit(digit) },
					modifier = if (digit == '1') Modifier.focusRequester(focusRequester) else Modifier,
				) {
					Text(digit.toString())
				}
			}
		}
	}

	Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
		Spacer(Modifier.size(56.dp))
		PinPadButton(onClick = { onDigit('0') }) {
			Text("0")
		}
		PinPadButton(onClick = onDelete) {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_backspace),
				contentDescription = stringResource(R.string.pin_delete_digit),
				modifier = Modifier.size(20.dp),
			)
		}
	}

	LaunchedEffect(focusRequester) {
		focusRequester.requestFocus()
	}
}

@Composable
private fun PinPadButton(
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	content: @Composable () -> Unit,
) = Button(
	onClick = onClick,
	modifier = modifier.size(56.dp),
) {
	content()
}
