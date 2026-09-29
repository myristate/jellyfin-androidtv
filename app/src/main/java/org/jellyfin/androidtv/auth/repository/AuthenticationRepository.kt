package org.jellyfin.androidtv.auth.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import org.jellyfin.androidtv.auth.ProfilePin
import org.jellyfin.androidtv.auth.model.ApiClientErrorLoginState
import org.jellyfin.androidtv.auth.model.AuthenticateMethod
import org.jellyfin.androidtv.auth.model.AuthenticatedState
import org.jellyfin.androidtv.auth.model.AuthenticatingState
import org.jellyfin.androidtv.auth.model.AuthenticationStoreUser
import org.jellyfin.androidtv.auth.model.AutomaticAuthenticateMethod
import org.jellyfin.androidtv.auth.model.CredentialAuthenticateMethod
import org.jellyfin.androidtv.auth.model.InvalidPinState
import org.jellyfin.androidtv.auth.model.LoginState
import org.jellyfin.androidtv.auth.model.PinAuthenticateMethod
import org.jellyfin.androidtv.auth.model.PrivateUser
import org.jellyfin.androidtv.auth.model.PublicUser
import org.jellyfin.androidtv.auth.model.QuickConnectAuthenticateMethod
import org.jellyfin.androidtv.auth.model.RequirePinState
import org.jellyfin.androidtv.auth.model.RequireSignInState
import org.jellyfin.androidtv.auth.model.Server
import org.jellyfin.androidtv.auth.model.ServerUnavailableState
import org.jellyfin.androidtv.auth.model.ServerVersionNotSupported
import org.jellyfin.androidtv.auth.model.User
import org.jellyfin.androidtv.auth.store.AuthenticationPreferences
import org.jellyfin.androidtv.auth.store.AuthenticationStore
import org.jellyfin.androidtv.util.apiclient.JellyfinImage
import org.jellyfin.androidtv.util.apiclient.JellyfinImageSource
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.primaryImage
import org.jellyfin.androidtv.util.sdk.forUser
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.authenticateWithQuickConnect
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.UserDto
import timber.log.Timber
import java.net.HttpURLConnection
import java.time.Instant
import java.util.UUID

/**
 * Repository to manage authentication of the user in the app.
 */
interface AuthenticationRepository {
	fun authenticate(server: Server, method: AuthenticateMethod): Flow<LoginState>
	fun logout(user: User): Boolean
	fun getUserImageUrl(server: Server, user: User): String?

	fun hasPin(serverId: UUID, userId: UUID): Boolean

	/**
	 * Set the PIN that protects a stored user on this device, or remove it with a null [pin].
	 */
	fun setPin(serverId: UUID, userId: UUID, pin: String?): Boolean
}

class AuthenticationRepositoryImpl(
	private val jellyfin: Jellyfin,
	private val sessionRepository: SessionRepository,
	private val authenticationStore: AuthenticationStore,
	private val userApiClient: ApiClient,
	private val authenticationPreferences: AuthenticationPreferences,
	private val defaultDeviceInfo: DeviceInfo,
) : AuthenticationRepository {
	override fun authenticate(server: Server, method: AuthenticateMethod): Flow<LoginState> {
		return when (method) {
			is AutomaticAuthenticateMethod -> authenticateAutomatic(server, method.user)
			is PinAuthenticateMethod -> authenticatePin(server, method.user, method.pin)
			is CredentialAuthenticateMethod -> authenticateCredential(server, method.username, method.password)
			is QuickConnectAuthenticateMethod -> authenticateQuickConnect(server, method.secret)
		}
	}

	private fun authenticateAutomatic(server: Server, user: User): Flow<LoginState> {
		Timber.i("Authenticating user ${user.id}")

		// Automatic logic is disabled when the always authenticate preference is enabled
		if (authenticationPreferences[AuthenticationPreferences.alwaysAuthenticate]) return flowOf(RequireSignInState)

		val authStoreUser = authenticationStore.getUser(server.id, user.id)
		return when {
			// Ask for the PIN before using the stored access token
			authStoreUser?.accessToken != null && authStoreUser.pinHash != null -> flowOf(RequirePinState)
			// Try login with access token
			authStoreUser?.accessToken != null -> authenticateToken(server, user.withToken(authStoreUser.accessToken))
			// A user without a password can sign in straight away
			user is PublicUser && !user.hasPassword -> authenticateCredential(server, user.name, "")
			// Require login
			else -> flowOf(RequireSignInState)
		}
	}

	private fun authenticatePin(server: Server, user: User, pin: String): Flow<LoginState> {
		val authStoreUser = authenticationStore.getUser(server.id, user.id)
		val pinHash = authStoreUser?.pinHash
		val accessToken = authStoreUser?.accessToken

		return when {
			accessToken == null -> flowOf(RequireSignInState)
			pinHash != null && !ProfilePin.verify(pin, pinHash) -> {
				Timber.w("Wrong PIN entered for user ${user.id}")
				flowOf(InvalidPinState)
			}

			else -> authenticateToken(server, user.withToken(accessToken))
		}
	}

	private fun authenticateCredential(server: Server, username: String, password: String) = flow {
		val api = jellyfin.createApi(server.address, deviceInfo = defaultDeviceInfo.forUser(username))
		val result = try {
			val response = api.userApi.authenticateUserByName(username, password)
			response.content
		} catch (err: TimeoutException) {
			Timber.e(err, "Failed to connect to server trying to sign in $username")
			emit(ServerUnavailableState)
			return@flow
		} catch (err: InvalidStatusException) {
			Timber.e(err, "Unable to sign in as $username")
			// The server rejected the username or password, don't report it as a connection problem
			if (err.status == HttpURLConnection.HTTP_UNAUTHORIZED) emit(RequireSignInState)
			else emit(ApiClientErrorLoginState(err))
			return@flow
		} catch (err: ApiClientException) {
			Timber.e(err, "Unable to sign in as $username")
			emit(ApiClientErrorLoginState(err))
			return@flow
		}

		emitAll(authenticateAuthenticationResult(server, result))
	}.flowOn(Dispatchers.IO)

	private fun authenticateQuickConnect(server: Server, secret: String) = flow {
		val api = jellyfin.createApi(server.address, deviceInfo = defaultDeviceInfo)
		val result = try {
			val response = api.userApi.authenticateWithQuickConnect(secret)
			response.content
		} catch (err: TimeoutException) {
			Timber.e(err, "Failed to connect to server")
			emit(ServerUnavailableState)
			return@flow
		} catch (err: ApiClientException) {
			Timber.e(err, "Unable to sign in with Quick Connect secret")
			emit(ApiClientErrorLoginState(err))
			return@flow
		}

		emitAll(authenticateAuthenticationResult(server, result))
	}.flowOn(Dispatchers.IO)

	private fun authenticateAuthenticationResult(server: Server, result: AuthenticationResult) = flow {
		val accessToken = result.accessToken ?: return@flow emit(RequireSignInState)
		val userInfo = result.user ?: return@flow emit(RequireSignInState)
		val user = PrivateUser(
			id = userInfo.id,
			serverId = server.id,
			name = userInfo.name!!,
			accessToken = result.accessToken,
			imageTag = userInfo.primaryImage?.tag,
			lastUsed = Instant.now().toEpochMilli(),
		)

		authenticateFinish(server, userInfo, accessToken)
		val result = setActiveSession(user, server)
		if (result == SessionSwitchResult.SWITCHED) {
			emit(AuthenticatedState)
		} else {
			Timber.w("Failed to set active session after authenticating")
			emit(result.toFailedLoginState(server))
		}
	}.flowOn(Dispatchers.IO)

	private fun authenticateToken(server: Server, user: User) = flow {
		emit(AuthenticatingState)

		val result = setActiveSession(user, server)
		if (result != SessionSwitchResult.SWITCHED) {
			emit(result.toFailedLoginState(server))
		} else try {
			// Update user info
			val userInfo by userApiClient.userApi.getCurrentUser()
			authenticateFinish(server, userInfo, user.accessToken.orEmpty())
			emit(AuthenticatedState)
		} catch (err: TimeoutException) {
			Timber.e(err, "Failed to connect to server")
			emit(ServerUnavailableState)
			return@flow
		} catch (err: ApiClientException) {
			Timber.e(err, "Unable to get current user data")
			emit(ApiClientErrorLoginState(err))
		}
	}.flowOn(Dispatchers.IO)

	private suspend fun authenticateFinish(server: Server, userInfo: UserDto, accessToken: String) {
		val currentUser = authenticationStore.getUser(server.id, userInfo.id)

		val updatedUser = currentUser?.copy(
			name = userInfo.name!!,
			lastUsed = Instant.now().toEpochMilli(),
			imageTag = userInfo.primaryImage?.tag,
			accessToken = accessToken,
		) ?: AuthenticationStoreUser(
			name = userInfo.name!!,
			imageTag = userInfo.primaryImage?.tag,
			accessToken = accessToken,
		)
		authenticationStore.putUser(server.id, userInfo.id, updatedUser)
	}

	private fun SessionSwitchResult.toFailedLoginState(server: Server) = when {
		!server.versionSupported -> ServerVersionNotSupported(server)
		// Don't ask to sign in again when the stored access token could still be valid
		this == SessionSwitchResult.SERVER_UNAVAILABLE -> ServerUnavailableState
		else -> RequireSignInState
	}

	private suspend fun setActiveSession(user: User, server: Server): SessionSwitchResult {
		val result = sessionRepository.switchCurrentSession(server.id, user.id)

		if (result == SessionSwitchResult.SWITCHED) {
			// Update last use in store
			authenticationStore.getServer(server.id)?.let { storedServer ->
				authenticationStore.putServer(server.id, storedServer.copy(lastUsed = Instant.now().toEpochMilli()))
			}

			authenticationStore.getUser(server.id, user.id)?.let { storedUser ->
				authenticationStore.putUser(server.id, user.id, storedUser.copy(lastUsed = Instant.now().toEpochMilli()))
			}
		}

		return result
	}

	override fun hasPin(serverId: UUID, userId: UUID): Boolean =
		authenticationStore.getUser(serverId, userId)?.pinHash != null

	override fun setPin(serverId: UUID, userId: UUID, pin: String?): Boolean {
		val authStoreUser = authenticationStore.getUser(serverId, userId) ?: return false
		val pinHash = pin?.let(ProfilePin::hash)

		return authenticationStore.putUser(serverId, userId, authStoreUser.copy(pinHash = pinHash))
	}

	override fun logout(user: User): Boolean {
		val authStoreUser = authenticationStore
			.getUser(user.serverId, user.id)
			?.copy(accessToken = null)

		return if (authStoreUser != null) authenticationStore.putUser(user.serverId, user.id, authStoreUser)
		else false
	}

	override fun getUserImageUrl(server: Server, user: User): String? = user.imageTag?.let { primaryImageTag ->
		JellyfinImage(
			item = user.id,
			source = JellyfinImageSource.USER,
			type = ImageType.PRIMARY,
			tag = primaryImageTag,
			blurHash = null,
			aspectRatio = null,
			index = null
		)
	}?.getUrl(jellyfin.createApi(server.address))
}
