package org.jellyfin.androidtv.ui.settings.screen.profile

import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.UserProfileRepository
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.ProfilePicture
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.androidtv.util.apiclient.ServerRefusals
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.primaryImage
import org.jellyfin.androidtv.util.apiclient.refusalMessage
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.model.api.UserDto
import org.koin.compose.koinInject
import timber.log.Timber
import java.util.UUID

/**
 * The family's profiles, for a parent (an administrator) to change anyone's PIN or profile picture (Finly).
 */
@Composable
fun SettingsFamilyScreen() {
	val router = LocalRouter.current
	val context = LocalContext.current
	val api = koinInject<ApiClient>()
	val userProfileRepository = koinInject<UserProfileRepository>()

	var profiles by remember { mutableStateOf<List<UserDto>?>(null) }
	var error by remember { mutableStateOf<String?>(null) }

	LaunchedEffect(Unit) {
		try {
			profiles = userProfileRepository.getFamilyProfiles()
		} catch (err: ApiClientException) {
			Timber.e(err, "Unable to load the family's profiles")
			error = context.refusalMessage(ServerRefusals.take("/Users"), (err as? InvalidStatusException)?.status)
			profiles = emptyList()
		}
	}

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.profile).uppercase()) },
				headingContent = { Text(stringResource(R.string.family_profiles)) },
				captionContent = { Text(stringResource(R.string.family_profiles_description)) },
			)
		}

		error?.let { message -> item { Text(message, modifier = Modifier.padding(16.dp)) } }

		profiles?.let { loaded ->
			items(loaded.size) { index ->
				val profile = loaded[index]
				ListButton(
					leadingContent = {
						ProfilePicture(
							url = profile.primaryImage?.getUrl(api),
							modifier = Modifier.size(40.dp).clip(CircleShape),
						)
					},
					headingContent = { Text(profile.name.orEmpty()) },
					captionContent = {
						if (profile.policy?.isAdministrator == true) Text(stringResource(R.string.family_parent))
					},
					onClick = {
						router.push(Routes.PROFILE_FAMILY_USER, mapOf("userId" to profile.id.toString()))
					},
					modifier = Modifier.focusKey("family_${profile.id}"),
				)
			}
		}
	}
}

/**
 * One family member's profile, for a parent: their PIN and profile picture (Finly).
 */
@Composable
fun SettingsFamilyProfileScreen(userId: UUID) {
	val router = LocalRouter.current
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val api = koinInject<ApiClient>()
	val userProfileRepository = koinInject<UserProfileRepository>()

	var profile by remember { mutableStateOf<UserDto?>(null) }
	var error by remember { mutableStateOf<String?>(null) }
	// Bumped to load the profile again after a change
	var version by remember { mutableIntStateOf(0) }

	LaunchedEffect(userId, version) {
		try {
			profile = userProfileRepository.getProfile(userId)
			error = null
		} catch (err: ApiClientException) {
			Timber.e(err, "Unable to load the profile of user %s", userId)
			error = context.refusalMessage(ServerRefusals.take("/Users/$userId"), (err as? InvalidStatusException)?.status)
		}
	}

	SettingsColumn {
		item {
			ListSection(
				leadingContent = {
					ProfilePicture(
						url = profile?.primaryImage?.getUrl(api),
						modifier = Modifier.size(56.dp).clip(CircleShape),
					)
				},
				overlineContent = { Text(stringResource(R.string.family_profiles).uppercase()) },
				headingContent = { Text(profile?.name.orEmpty()) },
			)
		}

		error?.let { message -> item { Text(message, modifier = Modifier.padding(16.dp)) } }

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_user), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pin_profile)) },
				onClick = { router.push(Routes.PROFILE_FAMILY_USER_PIN, mapOf("userId" to userId.toString())) },
				modifier = Modifier.focusKey("family_pin"),
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_photo), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.profile_picture_change)) },
				captionContent = { Text(stringResource(R.string.family_picture_description)) },
				onClick = { router.push(Routes.PROFILE_FAMILY_USER_PICTURE, mapOf("userId" to userId.toString())) },
				modifier = Modifier.focusKey("family_picture"),
			)
		}

		if (profile?.primaryImageTag != null) item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.profile_picture_remove)) },
				onClick = {
					scope.launch {
						val message = try {
							userProfileRepository.removeProfilePicture(userId)
							version++
							context.getString(R.string.profile_picture_removed)
						} catch (err: ApiClientException) {
							Timber.e(err, "Unable to remove the profile picture of user %s", userId)
							context.refusalMessage(ServerRefusals.take("/Images/Primary"), (err as? InvalidStatusException)?.status)
						}
						Toast.makeText(context, message, Toast.LENGTH_LONG).show()
					}
				},
				modifier = Modifier.focusKey("family_remove_picture"),
			)
		}
	}
}
