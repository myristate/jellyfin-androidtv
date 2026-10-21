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
import org.jellyfin.androidtv.auth.repository.AuthenticationRepository
import org.jellyfin.androidtv.auth.repository.PIN_LENGTH
import org.jellyfin.androidtv.auth.repository.PinChangeResult
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.auth.repository.SignInOptions
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.auth.repository.isPinFormat
import org.jellyfin.androidtv.data.repository.UserProfileRepository
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
import org.jellyfin.androidtv.util.apiclient.ServerRefusal
import org.jellyfin.androidtv.util.apiclient.refusalMessage
import org.koin.compose.koinInject
import timber.log.Timber
import java.util.UUID

private sealed interface PinStep {
	data object Menu : PinStep

	/** Asking for the current PIN before changing ([remove] false) or removing it. */
	data class CurrentPin(val remove: Boolean) : PinStep

	/** Asking for the password: for a first PIN, or in place of the current PIN. */
	data class CurrentPassword(val remove: Boolean) : PinStep

	/** Asking for the new PIN, then again to confirm it. [current] is null for a parent changing someone else's. */
	data class NewPin(val current: String?, val first: String?) : PinStep

	data object Saving : PinStep
}

/**
 * Set, change or remove a profile's PIN (finly/SIGN-IN.md): the signed in user's own when [userId] is null, otherwise,
 * for a parent, someone else's. The PIN is kept on the server, so it works on every device and app. Changing or
 * removing your own needs your current PIN (or password), a first PIN your password; a parent needs neither for someone
 * else's, and can turn PIN sign in back on after too many wrong PINs.
 */
@Composable
fun SettingsAuthenticationPinScreen(userId: UUID? = null) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val sessionRepository = koinInject<SessionRepository>()
	val userRepository = koinInject<UserRepository>()
	val authenticationRepository = koinInject<AuthenticationRepository>()
	val userProfileRepository = koinInject<UserProfileRepository>()

	val session by sessionRepository.currentSession.collectAsState()
	val currentUser by userRepository.currentUser.collectAsState()
	val currentSession = session ?: return
	val targetId = userId ?: currentSession.userId
	val forSomeoneElse = targetId != currentSession.userId

	var otherName by remember(targetId) { mutableStateOf<String?>(null) }
	LaunchedEffect(targetId) {
		if (forSomeoneElse) otherName = runCatching { userProfileRepository.getProfile(targetId).name }
			.onFailure { Timber.w(it, "Unable to load the profile of user %s", targetId) }
			.getOrNull()
	}
	val userName = if (forSomeoneElse) otherName else currentUser?.name

	// Start from what the server last said and ask it again, the PIN may have changed on another device
	val storedPin = authenticationRepository.hasPin(currentSession.serverId, targetId)
	var options by remember(targetId) {
		mutableStateOf(SignInOptions(hasPassword = true, pinProtected = storedPin, pinUsable = storedPin, pinSignInDisabled = false))
	}
	suspend fun reloadOptions() {
		authenticationRepository.getSignInOptions(userId)?.let { options = it }
	}
	LaunchedEffect(targetId) { reloadOptions() }

	var step by remember { mutableStateOf<PinStep>(PinStep.Menu) }
	var message by remember { mutableStateOf<String?>(null) }
	val askParentMessage = stringResource(R.string.pin_ask_parent)

	fun save(current: String?, pin: String?, retryStep: PinStep) {
		step = PinStep.Saving
		scope.launch {
			when (val result = authenticationRepository.setPin(current, pin, userId)) {
				PinChangeResult.Saved -> {
					step = PinStep.Menu
					message = null
					Toast.makeText(context, if (pin == null) R.string.pin_removed else R.string.pin_saved, Toast.LENGTH_SHORT).show()
					reloadOptions()
				}

				is PinChangeResult.Refused -> {
					message = context.refusalMessage(result.refusal, result.status)
					// A wrong or throttled current PIN or password can be tried again, anything else goes back to the menu
					step = when (result.refusal?.reason) {
						ServerRefusal.WRONG_PIN, ServerRefusal.WRONG_PASSWORD,
						ServerRefusal.TOO_MANY_ATTEMPTS, ServerRefusal.PIN_LOCKED_OUT -> retryStep

						else -> PinStep.Menu
					}
				}
			}
		}
	}

	fun start(remove: Boolean) {
		message = null
		step = when {
			// A parent changing someone else's
			forSomeoneElse -> if (remove) {
				save(null, null, PinStep.Menu)
				PinStep.Saving
			} else PinStep.NewPin(current = null, first = null)

			// Changing or removing your own: the current PIN, or the password when the PIN can't be used from here
			options.pinProtected && options.pinUsable -> PinStep.CurrentPin(remove)
			options.pinProtected && options.hasPassword -> PinStep.CurrentPassword(remove)
			options.pinProtected -> {
				message = askParentMessage
				PinStep.Menu
			}

			// A first PIN needs the password, a profile without one just sets it
			options.hasPassword -> PinStep.CurrentPassword(remove)
			else -> PinStep.NewPin(current = "", first = null)
		}
	}

	fun onCurrentEntered(current: String, entryStep: PinStep, remove: Boolean) {
		message = null
		if (remove) save(current, null, entryStep) else step = PinStep.NewPin(current, first = null)
	}

	fun unlock() {
		step = PinStep.Saving
		scope.launch {
			when (val result = authenticationRepository.unlockPin(targetId)) {
				PinChangeResult.Saved -> {
					message = null
					Toast.makeText(context, R.string.pin_unlocked, Toast.LENGTH_SHORT).show()
					reloadOptions()
				}

				is PinChangeResult.Refused -> message = context.refusalMessage(result.refusal, result.status)
			}
			step = PinStep.Menu
		}
	}

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(userName?.uppercase().orEmpty()) },
				headingContent = { Text(stringResource(R.string.pin_profile)) },
				captionContent = {
					val description = if (forSomeoneElse) R.string.pin_profile_description_other else R.string.pin_profile_description
					Text(stringResource(description, SessionRepository.PIN_LOCK_TIMEOUT.toMinutes().toInt()))
				},
			)
		}

		if (options.pinSignInDisabled) item {
			Text(stringResource(R.string.pin_unlock_description), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
		}

		val menuMessage = message
		if (menuMessage != null && step == PinStep.Menu) item {
			Text(menuMessage, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
		}

		when (val current = step) {
			PinStep.Menu, PinStep.Saving -> {
				item {
					ListButton(
						leadingContent = { Icon(painterResource(R.drawable.ic_user), contentDescription = null) },
						headingContent = { Text(stringResource(if (options.pinProtected) R.string.pin_change else R.string.pin_set)) },
						enabled = current == PinStep.Menu,
						onClick = { start(remove = false) },
						modifier = Modifier.focusKey("set_pin"),
					)
				}

				if (options.pinProtected) item {
					ListButton(
						leadingContent = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
						headingContent = { Text(stringResource(R.string.pin_remove)) },
						enabled = current == PinStep.Menu,
						onClick = { start(remove = true) },
						modifier = Modifier.focusKey("remove_pin"),
					)
				}

				// Only a parent can turn PIN sign in back on
				if (options.pinSignInDisabled && forSomeoneElse) item {
					ListButton(
						leadingContent = { Icon(painterResource(R.drawable.ic_check), contentDescription = null) },
						headingContent = { Text(stringResource(R.string.pin_unlock)) },
						enabled = current == PinStep.Menu,
						onClick = { unlock() },
						modifier = Modifier.focusKey("unlock_pin"),
					)
				}
			}

			is PinStep.CurrentPin -> {
				item {
					PinPad(
						title = stringResource(R.string.pin_current),
						message = message,
						modifier = Modifier
							.fillMaxWidth()
							.padding(vertical = 8.dp),
						onPinEntered = { pin -> onCurrentEntered(pin, current, current.remove) },
					)
				}

				if (options.hasPassword) item {
					ListButton(
						headingContent = { Text(stringResource(R.string.pin_use_password)) },
						onClick = {
							message = null
							step = PinStep.CurrentPassword(current.remove)
						},
						modifier = Modifier.focusKey("use_password"),
					)
				}
			}

			is PinStep.CurrentPassword -> item {
				PasswordEntry(
					title = stringResource(if (options.pinProtected) R.string.pin_current_password_change else R.string.pin_current_password),
					message = message,
					onDone = { password -> onCurrentEntered(password, current, current.remove) },
				)
			}

			is PinStep.NewPin -> item {
				val newPinTitle = stringResource(R.string.pin_new, PIN_LENGTH)
				val confirmTitle = stringResource(R.string.pin_confirm)
				val mismatchMessage = stringResource(R.string.pin_mismatch)
				// Where a refused current PIN or password is asked again
				val given = current.current
				val retryStep = when {
					given == null -> PinStep.Menu
					options.pinProtected && options.pinUsable && isPinFormat(given) -> PinStep.CurrentPin(remove = false)
					else -> PinStep.CurrentPassword(remove = false)
				}

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

							pin -> save(current.current, pin, retryStep)

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
 * Asks for the user's password: for a first PIN, or in place of the current PIN.
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
