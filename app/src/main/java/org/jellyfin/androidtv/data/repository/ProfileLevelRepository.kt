package org.jellyfin.androidtv.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.auth.repository.SessionRepository
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
 * Profile levels, and removing items from the signed in profile. Removed items and everything below them no longer show
 * up for the profile in any app. Removing from every profile on a level is for administrators, see
 * [KidsViewRepository].
 */
class ProfileLevelRepository(
	private val api: ApiClient,
	private val userRepository: UserRepository,
	sessionRepository: SessionRepository,
) {
	private val json = Json { ignoreUnknownKeys = true }
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	init {
		// What was loaded belongs to the signed in profile (Finly)
		scope.launch {
			sessionRepository.currentSession
				.map { it?.userId }
				.distinctUntilChanged()
				.drop(1)
				.collect { reset() }
		}
	}

	/** Forget the loaded levels, for example when another profile signs in. */
	fun reset() {
		levels = emptyList()
	}

	/** The levels, as last loaded by [refresh]. */
	@Volatile
	var levels: List<ProfileLevel> = emptyList()
		private set

	suspend fun refresh() = withContext(Dispatchers.IO) {
		try {
			levels = json.decodeFromString(api.request(HttpMethod.GET, "/ProfileLevels").body.decodeToString())
		} catch (err: ApiClientException) {
			Timber.w(err, "Unable to load profile levels")
		} catch (err: IllegalArgumentException) {
			// An unexpected body, kotlinx.serialization's SerializationException is an IllegalArgumentException
			Timber.w(err, "Unable to read profile levels")
		}
	}

	/** Remove an item from the signed in profile. */
	suspend fun hideFromMe(itemId: UUID): Boolean {
		val userId = userRepository.currentUser.value?.id ?: return false
		return post("/Users/$userId/HiddenItems/$itemId")
	}

	private suspend fun post(path: String) = withContext(Dispatchers.IO) {
		try {
			api.request(HttpMethod.POST, path)
			true
		} catch (err: ApiClientException) {
			Timber.e(err, "Unable to remove item: $path")
			false
		}
	}
}
