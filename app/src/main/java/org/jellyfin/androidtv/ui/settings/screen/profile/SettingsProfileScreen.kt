package org.jellyfin.androidtv.ui.settings.screen.profile

import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.AuthenticationRepository
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.data.repository.UserProfileRepository
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.ProfilePicture
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.ActivityDestinations
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.compat.SettingsViewModel
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.primaryImage
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinActivityViewModel
import timber.log.Timber

/**
 * The signed in user's own profile: picture, PIN and switching to another profile.
 */
@Composable
fun SettingsProfileScreen() {
	val router = LocalRouter.current
	val context = LocalContext.current
	val activity = LocalActivity.current
	val coroutineScope = rememberCoroutineScope()
	val api = koinInject<ApiClient>()
	val userRepository = koinInject<UserRepository>()
	val sessionRepository = koinInject<SessionRepository>()
	val authenticationRepository = koinInject<AuthenticationRepository>()
	val userProfileRepository = koinInject<UserProfileRepository>()
	val mediaManager = koinInject<MediaManager>()
	val settingsViewModel = koinActivityViewModel<SettingsViewModel>()

	val currentUser by userRepository.currentUser.collectAsState()
	val session by sessionRepository.currentSession.collectAsState()
	val user = currentUser ?: return
	val userName = user.name.orEmpty()

	SettingsColumn {
		item {
			ListSection(
				leadingContent = {
					ProfilePicture(
						url = user.primaryImage?.getUrl(api),
						modifier = Modifier.size(56.dp).clip(CircleShape),
					)
				},
				overlineContent = { Text(stringResource(R.string.profile).uppercase()) },
				headingContent = { Text(stringResource(R.string.signed_in_as, userName)) },
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_photo), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.profile_picture_change)) },
				captionContent = { Text(stringResource(R.string.profile_picture_change_description)) },
				onClick = { router.push(Routes.PROFILE_PICTURE) },
				modifier = Modifier.focusKey(Routes.PROFILE_PICTURE),
			)
		}

		if (user.primaryImageTag != null) item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.profile_picture_remove)) },
				onClick = {
					coroutineScope.launch {
						val message = try {
							userProfileRepository.removeProfilePicture()
							R.string.profile_picture_removed
						} catch (error: ApiClientException) {
							Timber.e(error, "Failed to remove profile picture")
							R.string.profile_picture_failed
						}
						Toast.makeText(context, message, Toast.LENGTH_LONG).show()
					}
				},
				modifier = Modifier.focusKey("remove_picture"),
			)
		}

		session?.let { currentSession ->
			item {
				val hasPin = authenticationRepository.hasPin(currentSession.serverId, currentSession.userId)

				ListButton(
					leadingContent = { Icon(painterResource(R.drawable.ic_user), contentDescription = null) },
					headingContent = { Text(stringResource(R.string.pin_profile)) },
					captionContent = {
						Text(stringResource(if (hasPin) R.string.pin_profile_enabled else R.string.pin_profile_disabled, userName))
					},
					onClick = { router.push(Routes.AUTHENTICATION_PIN) },
					modifier = Modifier.focusKey(Routes.AUTHENTICATION_PIN),
				)
			}
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_switch_users), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.profile_switch)) },
				captionContent = { Text(stringResource(R.string.profile_switch_description)) },
				onClick = {
					// Same as the user button in the toolbar
					settingsViewModel.hide()
					mediaManager.clearAudioQueue()
					sessionRepository.destroyCurrentSession()
					activity?.startActivity(ActivityDestinations.startup(activity))
					activity?.finishAfterTransition()
				},
				modifier = Modifier.focusKey("switch_profile"),
			)
		}
	}
}
