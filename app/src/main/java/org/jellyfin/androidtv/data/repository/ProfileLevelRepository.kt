package org.jellyfin.androidtv.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.exception.ApiClientException
import timber.log.Timber
import java.util.UUID

/**
 * A kind of profile such as Child, Teen or Adult, defined on Finly's server.
 */
@Serializable
data class ProfileLevel(
	@SerialName("Id") val id: String,
	@SerialName("Name") val name: String,
	@SerialName("IsRestricted") val isRestricted: Boolean = false,
)

/**
 * Profile levels, and removing items from the signed in profile or from every profile on a level. Removed items and
 * everything below them no longer show up for those profiles in any app.
 */
class ProfileLevelRepository(
	private val api: ApiClient,
	private val userRepository: UserRepository,
) {
	private val json = Json { ignoreUnknownKeys = true }

	/** The levels, as last loaded by [refresh]. */
	@Volatile
	var levels: List<ProfileLevel> = emptyList()
		private set

	/** The level the signed in profile is on, as last loaded by [refresh]. */
	@Volatile
	var ownLevelId: String? = null
		private set

	suspend fun refresh() = withContext(Dispatchers.IO) {
		try {
			levels = json.decodeFromString(api.request(HttpMethod.GET, "/ProfileLevels").body.decodeToString())
			val me = Json.parseToJsonElement(api.request(HttpMethod.GET, "/Users/Me").body.decodeToString()).jsonObject
			ownLevelId = me["Policy"]?.jsonObject?.get("ProfileLevelId")?.jsonPrimitive?.contentOrNull?.normalized()
		} catch (err: ApiClientException) {
			Timber.w(err, "Unable to load profile levels")
		}
	}

	/**
	 * The levels the signed in profile may remove items from: every restricted level for administrators, otherwise
	 * the profile's own level when it is restricted.
	 */
	fun offeredLevels(): List<ProfileLevel> {
		val isAdministrator = userRepository.currentUser.value?.policy?.isAdministrator == true
		return levels.filter { it.isRestricted && (isAdministrator || it.id.normalized() == ownLevelId) }
	}

	/** Remove an item from the signed in profile. */
	suspend fun hideFromMe(itemId: UUID): Boolean {
		val userId = userRepository.currentUser.value?.id ?: return false
		return post("/Users/$userId/HiddenItems/$itemId")
	}

	/** Remove an item from every profile on a level. */
	suspend fun hideFromLevel(itemId: UUID, levelId: String): Boolean = post("/Items/$itemId/HideFromLevel/$levelId")

	private suspend fun post(path: String) = withContext(Dispatchers.IO) {
		try {
			api.request(HttpMethod.POST, path)
			true
		} catch (err: ApiClientException) {
			Timber.e(err, "Unable to remove item: $path")
			false
		}
	}

	private fun String.normalized() = replace("-", "").lowercase()
}
