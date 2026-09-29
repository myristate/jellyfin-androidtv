package org.jellyfin.androidtv.ui.settings.screen.profile

import android.widget.ImageView
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.UserProfileRepository
import org.jellyfin.androidtv.ui.base.CircularProgressIndicator
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.design.Tokens
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.koin.compose.koinInject
import timber.log.Timber

private const val POSTER_ASPECT_RATIO = 2f / 3f
private const val POSTER_MAX_HEIGHT = 300
private const val POSTER_COLUMNS = 3

/**
 * Pick a poster from the user's own libraries as their profile picture.
 */
@Composable
fun SettingsProfilePictureScreen() {
	val router = LocalRouter.current
	val context = LocalContext.current
	val coroutineScope = rememberCoroutineScope()
	val api = koinInject<ApiClient>()
	val userProfileRepository = koinInject<UserProfileRepository>()

	var choices by remember { mutableStateOf<List<BaseItemDto>?>(null) }
	var saving by remember { mutableStateOf(false) }

	LaunchedEffect(userProfileRepository) {
		choices = try {
			userProfileRepository.getPictureChoices()
		} catch (error: ApiClientException) {
			Timber.e(error, "Failed to load profile picture choices")
			emptyList()
		}
	}

	fun choose(item: BaseItemDto) {
		if (saving) return
		saving = true

		coroutineScope.launch {
			val changed = try {
				userProfileRepository.setProfilePicture(item)
				true
			} catch (error: ApiClientException) {
				Timber.e(error, "Failed to set profile picture")
				false
			}

			val message = if (changed) R.string.profile_picture_changed else R.string.profile_picture_failed
			Toast.makeText(context, message, Toast.LENGTH_LONG).show()
			saving = false
			if (changed) router.back()
		}
	}

	LazyVerticalGrid(
		columns = GridCells.Fixed(POSTER_COLUMNS),
		modifier = Modifier.padding(Tokens.Space.spaceSm),
		horizontalArrangement = Arrangement.spacedBy(Tokens.Space.spaceXs),
		verticalArrangement = Arrangement.spacedBy(Tokens.Space.spaceXs),
	) {
		item(span = { GridItemSpan(maxLineSpan) }) {
			ListSection(
				overlineContent = { Text(stringResource(R.string.profile).uppercase()) },
				headingContent = { Text(stringResource(R.string.profile_picture_change)) },
				captionContent = { Text(stringResource(R.string.profile_picture_change_description)) },
			)
		}

		val loadedChoices = choices
		when {
			loadedChoices == null || saving -> item(span = { GridItemSpan(maxLineSpan) }) {
				Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
					CircularProgressIndicator()
				}
			}

			loadedChoices.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
				Text(stringResource(R.string.profile_picture_none), modifier = Modifier.padding(16.dp))
			}

			else -> items(loadedChoices, key = { it.id }) { item ->
				PictureChoice(
					url = item.itemImages[ImageType.PRIMARY]?.getUrl(api, maxHeight = POSTER_MAX_HEIGHT),
					name = item.name.orEmpty(),
					onClick = { choose(item) },
				)
			}
		}
	}
}

@Composable
private fun PictureChoice(
	url: String?,
	name: String,
	onClick: () -> Unit,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val shape = JellyfinTheme.shapes.small

	Box(
		modifier = Modifier
			.aspectRatio(POSTER_ASPECT_RATIO)
			.clip(shape)
			.border(3.dp, if (focused) Color.White else Color.Transparent, shape)
			.clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
	) {
		AsyncImage(
			url = url,
			aspectRatio = POSTER_ASPECT_RATIO,
			scaleType = ImageView.ScaleType.CENTER_CROP,
			modifier = Modifier.fillMaxWidth(),
		)

		if (focused) Text(
			text = name,
			maxLines = 1,
			color = Color.White,
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.padding(4.dp),
		)
	}
}
