package org.jellyfin.androidtv.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.auth.store.AuthenticationStore
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.FileInfo
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageFormat
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import java.io.ByteArrayOutputStream

/**
 * Changes the profile of the signed in user. A TV has no photo gallery or camera, so a profile picture is picked
 * from the posters in the user's own libraries, which also keeps a kid's choice to what their profile can see.
 */
interface UserProfileRepository {
	suspend fun getPictureChoices(): List<BaseItemDto>
	suspend fun setProfilePicture(item: BaseItemDto)
	suspend fun removeProfilePicture()
}

class UserProfileRepositoryImpl(
	private val api: ApiClient,
	private val userRepository: UserRepository,
	private val sessionRepository: SessionRepository,
	private val authenticationStore: AuthenticationStore,
) : UserProfileRepository {
	private companion object {
		const val PICTURE_CHOICES_LIMIT = 400
		const val PICTURE_SOURCE_WIDTH = 600
		const val PICTURE_SIZE = 400
		const val PICTURE_QUALITY = 90
	}

	override suspend fun getPictureChoices(): List<BaseItemDto> = withContext(Dispatchers.IO) {
		api.itemsApi.getItems(
			includeItemTypes = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
			recursive = true,
			imageTypes = setOf(ImageType.PRIMARY),
			sortBy = setOf(ItemSortBy.SORT_NAME),
			limit = PICTURE_CHOICES_LIMIT,
			enableTotalRecordCount = false,
		).content.items
	}

	override suspend fun setProfilePicture(item: BaseItemDto) {
		val userId = sessionRepository.currentSession.value?.userId ?: return

		val picture = withContext(Dispatchers.IO) {
			val poster by api.imageApi.getItemImage(
				itemId = item.id,
				imageType = ImageType.PRIMARY,
				maxWidth = PICTURE_SOURCE_WIDTH,
				format = ImageFormat.JPG,
			)

			// The server expects the image base64 encoded
			Base64.encode(poster.toSquareJpeg(), Base64.NO_WRAP)
		}

		withContext(Dispatchers.IO) {
			api.imageApi.postUserImage(userId, FileInfo(picture, "image/jpeg"))
		}

		refreshCurrentUser()
	}

	override suspend fun removeProfilePicture() {
		val userId = sessionRepository.currentSession.value?.userId ?: return

		withContext(Dispatchers.IO) {
			api.imageApi.deleteUserImage(userId)
		}

		refreshCurrentUser()
	}

	/**
	 * Crop to the middle of a poster because profile pictures are shown as circles.
	 */
	private fun ByteArray.toSquareJpeg(): ByteArray {
		val source = BitmapFactory.decodeByteArray(this, 0, size) ?: return this
		val side = minOf(source.width, source.height)
		val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
		val scaled = Bitmap.createScaledBitmap(square, PICTURE_SIZE, PICTURE_SIZE, true)

		return ByteArrayOutputStream().use { output ->
			scaled.compress(Bitmap.CompressFormat.JPEG, PICTURE_QUALITY, output)
			output.toByteArray()
		}
	}

	/**
	 * Show the new picture in the toolbar straight away and on the user picker next time.
	 */
	private suspend fun refreshCurrentUser() {
		val session = sessionRepository.currentSession.value ?: return
		val user = withContext(Dispatchers.IO) { api.userApi.getCurrentUser().content }
		userRepository.setCurrentUser(user)

		authenticationStore.getUser(session.serverId, session.userId)?.let { storedUser ->
			authenticationStore.putUser(session.serverId, session.userId, storedUser.copy(imageTag = user.primaryImageTag))
		}
	}
}
