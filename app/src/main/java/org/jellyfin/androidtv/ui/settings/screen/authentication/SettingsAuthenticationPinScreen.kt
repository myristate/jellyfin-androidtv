package org.jellyfin.androidtv.ui.settings.screen.authentication

import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.ProfilePin
import org.jellyfin.androidtv.auth.repository.AuthenticationRepository
import org.jellyfin.androidtv.auth.repository.PinChangeResult
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.auth.repository.SignInOptions
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.LocalTextStyle
import org.jellyfin.androidtv.ui.base.ProvideTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.composable.PinPad
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject

private sealed interface PinStep {
	data object Menu : PinStep

	/** Asking for the current PIN or password before changing ([remove] false) or removing the PIN. */
	data class Current(val remove: Boolean) : PinStep

	/** Asking for the new PIN, then again to confirm it. */
	data class NewPin(val current: String, val first: String?) : PinStep

	data object Saving : PinStep
}

/**
 * Set, change or remove the PIN of the profile that is currently signed in. The PIN is kept on the server, so it works
 * on every device and app; the server wants the current PIN or password for any change.
 */
@Composable
fun SettingsAuthenticationPinScreen() {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val sessionRepository = koinInject<SessionRepository>()
	val userRepository = koinInject<UserRepository>()
	val authenticationRepository = koinInject<AuthenticationRepository>()

	val session by sessionRepository.currentSession.collectAsState()
	val user by userRepository.currentUser.collectAsState()
	val currentSession = session ?: return

	// Start from what the server last said and ask it again, the PIN may have changed on another device
	var options by remember(currentSession) {
		mutableStateOf(SignInOptions(hasPassword = true, hasPin = authenticationRepository.hasPin(currentSession.serverId, currentSession.userId)))
	}
	LaunchedEffect(currentSession) {
		authenticationRepository.getSignInOptions()?.let { options = it }
	}

	var step by remember { mutableStateOf<PinStep>(PinStep.Menu) }
	var message by remember { mutableStateOf<String?>(null) }
	val wrongCurrentMessage = stringResource(R.string.pin_current_wrong)

	fun save(current: String, pin: String?) {
		step = PinStep.Saving
		scope.launch {
			when (authenticationRepository.setPin(current, pin)) {
				PinChangeResult.SAVED -> {
					options = options.copy(hasPin = pin != null)
					step = PinStep.Menu
					message = null
					Toast.makeText(context, if (pin == null) R.string.pin_removed else R.string.pin_saved, Toast.LENGTH_SHORT).show()
				}

				PinChangeResult.WRONG_CURRENT -> {
					step = PinStep.Current(remove = pin == null)
					message = wrongCurrentMessage
				}

				PinChangeResult.FAILED -> {
					step = PinStep.Menu
					message = null
					Toast.makeText(context, R.string.pin_save_failed, Toast.LENGTH_SHORT).show()
				}
			}
		}
	}

	fun start(remove: Boolean) {
		message = null
		step = when {
			options.hasPin || options.hasPassword -> PinStep.Current(remove)
			remove -> {
				save("", null)
				PinStep.Saving
			}

			else -> PinStep.NewPin(current = "", first = null)
		}
	}

	fun onCurrentEntered(remove: Boolean, current: String) {
		message = null
		if (remove) save(current, null) else step = PinStep.NewPin(current, first = null)
	}

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(user?.name?.uppercase().orEmpty()) },
				headingContent = { Text(stringResource(R.string.pin_profile)) },
				captionContent = {
					Text(stringResource(R.string.pin_profile_description, SessionRepository.PIN_LOCK_TIMEOUT.toMinutes().toInt()))
				},
			)
		}

		when (val current = step) {
			PinStep.Menu, PinStep.Saving -> {
				item {
					ListButton(
						leadingContent = { Icon(painterResource(R.drawable.ic_user), contentDescription = null) },
						headingContent = { Text(stringResource(if (options.hasPin) R.string.pin_change else R.string.pin_set)) },
						enabled = current == PinStep.Menu,
						onClick = { start(remove = false) },
						modifier = Modifier.focusKey("set_pin"),
					)
				}

				if (options.hasPin) item {
					ListButton(
						leadingContent = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
						headingContent = { Text(stringResource(R.string.pin_remove)) },
						enabled = current == PinStep.Menu,
						onClick = { start(remove = true) },
						modifier = Modifier.focusKey("remove_pin"),
					)
				}
			}

			is PinStep.Current -> item {
				if (options.hasPin) {
					PinPad(
						title = stringResource(R.string.pin_current),
						message = message,
						modifier = Modifier
							.fillMaxWidth()
							.padding(vertical = 8.dp),
						onPinEntered = { pin -> onCurrentEntered(current.remove, pin) },
					)
				} else {
					PasswordEntry(
						title = stringResource(R.string.pin_current_password),
						message = message,
						onDone = { password -> onCurrentEntered(current.remove, password) },
					)
				}
			}

			is PinStep.NewPin -> item {
				val newPinTitle = stringResource(R.string.pin_new, ProfilePin.LENGTH)
				val confirmTitle = stringResource(R.string.pin_confirm)
				val mismatchMessage = stringResource(R.string.pin_mismatch)

				PinPad(
					title = if (current.first == null) newPinTitle else confirmTitle,
					message = message,
					modifier = Modifier
						.fillMaxWidth()
						.padding(vertical = 8.dp),
					onPinEntered = { pin ->
						when (current.first) {
							null -> {
								step = current.copy(first = pin)
								message = null
							}

							pin -> save(current.current, pin)

							else -> {
								step = current.copy(first = null)
								message = mismatchMessage
							}
						}
					},
				)
			}
		}
	}
}

/**
 * Asks for the user's password, for setting a first PIN on a profile that has a password.
 */
@Composable
private fun PasswordEntry(
	title: String,
	message: String?,
	onDone: (password: String) -> Unit,
) {
	var password by remember { mutableStateOf("") }
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val color = if (focused) JellyfinTheme.colorScheme.inputFocused to JellyfinTheme.colorScheme.onInputFocused
	else JellyfinTheme.colorScheme.input to JellyfinTheme.colorScheme.onInput

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(vertical = 8.dp, horizontal = 16.dp),
	) {
		Text(title, modifier = Modifier.padding(bottom = 8.dp))
		if (message != null) Text(message, modifier = Modifier.padding(bottom = 8.dp))

		ProvideTextStyle(LocalTextStyle.current.copy(color = color.second, fontSize = 16.sp)) {
			BasicTextField(
				value = password,
				onValueChange = { password = it },
				singleLine = true,
				interactionSource = interactionSource,
				visualTransformation = PasswordVisualTransformation(),
				keyboardOptions = KeyboardOptions.Default.copy(
					keyboardType = KeyboardType.Password,
					imeAction = ImeAction.Done,
					showKeyboardOnFocus = true,
				),
				keyboardActions = KeyboardActions { if (password.isNotEmpty()) onDone(password) },
				textStyle = LocalTextStyle.current,
				cursorBrush = SolidColor(color.first),
				decorationBox = { innerTextField ->
					Column(
						modifier = Modifier
							.border(2.dp, color.first, RoundedCornerShape(percent = 30))
							.padding(12.dp)
							.fillMaxWidth()
					) { innerTextField() }
				},
				modifier = Modifier
					.fillMaxWidth()
					.focusKey("pin_password"),
			)
		}

		Button(
			onClick = { if (password.isNotEmpty()) onDone(password) },
			modifier = Modifier.padding(top = 12.dp),
		) {
			Text(stringResource(R.string.pin_continue))
		}
	}
}
