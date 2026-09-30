package org.jellyfin.androidtv.ui.startup.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.fragment.app.replace
import androidx.fragment.compose.content
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.model.ApiClientErrorLoginState
import org.jellyfin.androidtv.auth.model.AuthenticatedState
import org.jellyfin.androidtv.auth.model.AuthenticatingState
import org.jellyfin.androidtv.auth.model.InvalidPinState
import org.jellyfin.androidtv.auth.model.RequirePinState
import org.jellyfin.androidtv.auth.model.RequireSignInState
import org.jellyfin.androidtv.auth.model.Server
import org.jellyfin.androidtv.auth.model.ServerUnavailableState
import org.jellyfin.androidtv.auth.model.ServerVersionNotSupported
import org.jellyfin.androidtv.auth.model.User
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.ProfilePicture
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.composable.PinPad
import org.jellyfin.androidtv.ui.startup.StartupViewModel
import org.jellyfin.androidtv.util.createBundle
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.net.HttpURLConnection
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import kotlin.time.Duration.Companion.seconds

private data class PinEntryState(
	val message: String? = null,
	val enabled: Boolean = true,
)

@Composable
private fun UserPinScreen(
	userName: String,
	userImage: String?,
	state: PinEntryState,
	onPinEntered: (String) -> Unit,
	onCancel: () -> Unit,
) {
	Box(
		modifier = Modifier.fillMaxSize(),
		contentAlignment = Alignment.Center,
	) {
		Column(
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(16.dp),
		) {
			ProfilePicture(
				url = userImage,
				contentDescription = userName,
				modifier = Modifier.size(80.dp).clip(CircleShape),
			)

			PinPad(
				title = stringResource(R.string.pin_enter_for, userName),
				message = state.message,
				enabled = state.enabled,
				onPinEntered = onPinEntered,
			)

			Button(onClick = onCancel) {
				Text(stringResource(R.string.lbl_cancel))
			}
		}
	}
}

/**
 * Asks for the PIN of a PIN protected profile before signing in with its stored access token.
 */
class UserPinFragment : Fragment() {
	companion object {
		const val ARG_SERVER_ID = "server_id"
		const val ARG_USER_ID = "user_id"
	}

	private val startupViewModel: StartupViewModel by activityViewModel()

	private var pinState by mutableStateOf(PinEntryState())
	private var lockoutJob: Job? = null

	private val server: Server?
		get() = arguments?.getString(ARG_SERVER_ID)?.toUUIDOrNull()?.let(startupViewModel::getServer)

	private val user: User?
		get() {
			val userId = arguments?.getString(ARG_USER_ID)?.toUUIDOrNull() ?: return null
			return startupViewModel.users.value.find { it.id == userId }
		}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	) = content {
		val server = server
		val user = user

		JellyfinTheme {
			if (server != null && user != null) {
				UserPinScreen(
					userName = user.name,
					userImage = startupViewModel.getUserImage(server, user),
					state = pinState,
					onPinEntered = { pin -> onPinEntered(server, user, pin) },
					onCancel = { parentFragmentManager.popBackStack() },
				)
			}
		}
	}

	override fun onResume() {
		super.onResume()

		val server = server
		val user = user
		if (server == null || user == null) {
			parentFragmentManager.popBackStack()
			return
		}

		val lockout = startupViewModel.pinLockoutSeconds(server, user.id)
		if (lockout > 0) startLockout(lockout)
	}

	private fun onPinEntered(server: Server, user: User, pin: String) {
		pinState = pinState.copy(enabled = false)

		startupViewModel.authenticatePin(server, user, pin).onEach { state ->
			when (state) {
				AuthenticatingState -> Unit
				// The activity opens the next screen when the session changes
				AuthenticatedState -> Unit

				InvalidPinState -> {
					val lockout = startupViewModel.pinLockoutSeconds(server, user.id)
					if (lockout > 0) startLockout(lockout)
					else pinState = PinEntryState(message = getString(R.string.pin_wrong))
				}

				// The stored access token no longer works, the user needs to sign in again
				RequireSignInState, RequirePinState -> {
					pinState = PinEntryState()
					requireActivity().supportFragmentManager.commit {
						replace<UserLoginFragment>(R.id.content_view, null, createBundle {
							putString(UserLoginFragment.ARG_SERVER_ID, server.id.toString())
							putString(UserLoginFragment.ARG_USERNAME, user.name)
						})
						addToBackStack(null)
					}
				}

				ServerUnavailableState -> pinState = PinEntryState(message = getString(R.string.server_connection_failed))

				is ApiClientErrorLoginState -> {
					// The server refused to sign the profile in at all, for example outside its allowed hours (Finly)
					val refused = (state.error as? InvalidStatusException)?.status == HttpURLConnection.HTTP_FORBIDDEN
					pinState = PinEntryState(
						message = if (refused) getString(R.string.pin_sign_in_refused, user.name)
						else getString(R.string.server_connection_failed)
					)
				}

				is ServerVersionNotSupported -> pinState = PinEntryState(
					message = getString(
						R.string.server_issue_outdated_version,
						state.server.version,
						ServerRepository.recommendedServerVersion.toString()
					)
				)
			}
			// The view's scope: the lockout below updates the view, which is gone once the fragment's view is destroyed
		}.launchIn(viewLifecycleOwner.lifecycleScope)
	}

	private fun startLockout(seconds: Long) {
		lockoutJob?.cancel()
		lockoutJob = viewLifecycleOwner.lifecycleScope.launch {
			for (remaining in seconds downTo 1) {
				val message = if (remaining > 60) getString(R.string.pin_locked_out_minutes, ((remaining + 59) / 60).toInt())
				else getString(R.string.pin_locked_out, remaining.toInt())
				pinState = PinEntryState(message = message, enabled = false)
				delay(1.seconds)
			}
			pinState = PinEntryState()
		}
	}
}
