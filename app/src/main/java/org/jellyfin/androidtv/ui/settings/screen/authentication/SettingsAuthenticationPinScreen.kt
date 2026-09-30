package org.jellyfin.androidtv.ui.settings.screen.authentication

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.ProfilePin
import org.jellyfin.androidtv.auth.repository.AuthenticationRepository
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.composable.PinPad
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject

/**
 * Set, change or remove the PIN of the profile that is currently signed in.
 */
@Composable
fun SettingsAuthenticationPinScreen() {
	val context = LocalContext.current
	val sessionRepository = koinInject<SessionRepository>()
	val userRepository = koinInject<UserRepository>()
	val authenticationRepository = koinInject<AuthenticationRepository>()

	val session by sessionRepository.currentSession.collectAsState()
	val user by userRepository.currentUser.collectAsState()
	val currentSession = session ?: return

	var hasPin by remember(currentSession) {
		mutableStateOf(authenticationRepository.hasPin(currentSession.serverId, currentSession.userId))
	}
	var editing by remember { mutableStateOf(false) }
	var firstPin by remember { mutableStateOf<String?>(null) }
	var message by remember { mutableStateOf<String?>(null) }
	// Changing or removing an existing PIN first asks for the current one, set while waiting for it
	var confirmingRemove by remember { mutableStateOf<Boolean?>(null) }

	fun savePin(pin: String?) {
		val saved = authenticationRepository.setPin(currentSession.serverId, currentSession.userId, pin)
		if (saved) hasPin = pin != null

		val toast = when {
			!saved -> R.string.pin_save_failed
			pin == null -> R.string.pin_removed
			else -> R.string.pin_saved
		}
		Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
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

		val confirming = confirmingRemove
		if (confirming != null) item {
			val wrongMessage = stringResource(R.string.pin_wrong)
			PinPad(
				title = stringResource(R.string.pin_current),
				message = message,
				modifier = Modifier
					.fillMaxWidth()
					.padding(vertical = 8.dp),
				onPinEntered = { pin ->
					if (authenticationRepository.checkPin(currentSession.serverId, currentSession.userId, pin)) {
						confirmingRemove = null
						message = null
						if (confirming) savePin(null) else editing = true
					} else {
						val lockout = authenticationRepository.pinLockoutSeconds(currentSession.serverId, currentSession.userId)
						message = if (lockout > 0) context.getString(R.string.pin_locked_out, lockout.toInt()) else wrongMessage
					}
				},
			)
		} else if (editing) item {
			val newPinTitle = stringResource(R.string.pin_new, ProfilePin.LENGTH)
			val confirmTitle = stringResource(R.string.pin_confirm)
			val mismatchMessage = stringResource(R.string.pin_mismatch)

			PinPad(
				title = if (firstPin == null) newPinTitle else confirmTitle,
				message = message,
				modifier = Modifier
					.fillMaxWidth()
					.padding(vertical = 8.dp),
				onPinEntered = { pin ->
					when (firstPin) {
						null -> {
							firstPin = pin
							message = null
						}

						pin -> {
							savePin(pin)
							editing = false
						}

						else -> {
							firstPin = null
							message = mismatchMessage
						}
					}
				},
			)
		} else {
			item {
				ListButton(
					leadingContent = { Icon(painterResource(R.drawable.ic_user), contentDescription = null) },
					headingContent = { Text(stringResource(if (hasPin) R.string.pin_change else R.string.pin_set)) },
					onClick = {
						firstPin = null
						message = null
						if (hasPin) confirmingRemove = false else editing = true
					},
					modifier = Modifier.focusKey("set_pin"),
				)
			}

			if (hasPin) item {
				ListButton(
					leadingContent = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
					headingContent = { Text(stringResource(R.string.pin_remove)) },
					onClick = {
						message = null
						confirmingRemove = true
					},
					modifier = Modifier.focusKey("remove_pin"),
				)
			}
		}
	}
}
