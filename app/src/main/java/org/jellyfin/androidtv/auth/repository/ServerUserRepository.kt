package org.jellyfin.androidtv.auth.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jellyfin.androidtv.auth.model.PrivateUser
import org.jellyfin.androidtv.auth.model.PublicUser
import org.jellyfin.androidtv.auth.model.Server
import org.jellyfin.androidtv.auth.store.AuthenticationStore
import org.jellyfin.androidtv.util.sdk.toPublicUser
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.api.UserDto
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.util.UUID

/**
 * Repository to maintain users for servers.
 * Authentication is done using the [AuthenticationRepository].
 */
interface ServerUserRepository {
	//server
	fun getStoredServerUsers(server: Server): List<PrivateUser>

	/**
	 * @return the users shown on the server's login screen, or null when the server could not be reached.
	 */
	suspend fun getPublicServerUsers(server: Server): List<PublicUser>?

	fun deleteStoredUser(user: PrivateUser)
}

class ServerUserRepositoryImpl(
	private val jellyfin: Jellyfin,
	private val authenticationStore: AuthenticationStore,
) : ServerUserRepository {
	override fun getStoredServerUsers(server: Server) = authenticationStore.getUsers(server.id)
		?.mapNotNull { (userId, userInfo) ->
			val authInfo = authenticationStore.getUser(server.id, userId)
			PrivateUser(
				id = userId,
				serverId = server.id,
				name = userInfo.name,
				accessToken = authInfo?.accessToken,
				imageTag = userInfo.imageTag,
				lastUsed = userInfo.lastUsed,
				hasPin = userInfo.serverPin,
			)
		}
		?.sortedWith(compareByDescending<PrivateUser> { it.lastUsed }.thenBy { it.name })
		.orEmpty()

	override suspend fun getPublicServerUsers(server: Server): List<PublicUser>? {
		// Create a fresh API because the shared one might be authenticated for a different server
		val api = jellyfin.createApi(server.address)

		return try {
			val response = withContext(Dispatchers.IO) {
				api.request(HttpMethod.GET, "/Users/Public")
			}
			val pinUsers = response.pinUserIds()
			val users = response.createContent<List<UserDto>>()
				.mapNotNull { dto -> dto.toPublicUser()?.copy(hasPin = dto.id in pinUsers) }

			// Remember which stored users have a PIN, for when they are picked without the server's list at hand
			users.forEach { user ->
				val stored = authenticationStore.getUser(server.id, user.id)
				if (stored != null && stored.serverPin != user.hasPin) {
					authenticationStore.putUser(server.id, user.id, stored.copy(serverPin = user.hasPin))
				}
			}

			users
		} catch (err: ApiClientException) {
			Timber.e(err, "Unable to retrieve public users")

			null
		}
	}

	// Finly's server says which users sign in with a PIN on this network, a field the SDK doesn't know
	private fun RawResponse.pinUserIds(): Set<UUID> = runCatching {
		Json.parseToJsonElement(body.decodeToString()).jsonArray.mapNotNull { element ->
			val user = element.jsonObject
			if (user["HasPin"]?.jsonPrimitive?.booleanOrNull != true) null
			else user["Id"]?.jsonPrimitive?.contentOrNull?.toUUIDOrNull()
		}.toSet()
	}.getOrDefault(emptySet())

	override fun deleteStoredUser(user: PrivateUser) {
		// Remove user info from store
		authenticationStore.removeUser(user.serverId, user.id)
	}
}
