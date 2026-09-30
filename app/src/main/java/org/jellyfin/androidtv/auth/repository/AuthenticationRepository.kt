package org.jellyfin.androidtv.auth.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import org.jellyfin.sdk.api.client.HttpMethod
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

	/**
	 * Whether the server asks this user for a PIN, as last reported by the server.
	 */
	fun hasPin(serverId: UUID, userId: UUID): Boolean

	/**
	 * Ask the server how the signed in user signs in, which also refreshes [hasPin].
	 */
	suspend fun getSignInOptions(): SignInOptions?

	/**
	 * Set the signed in user's PIN on the server, or remove it with a null [pin]. The server needs [current], the user's
	 * current PIN or password, when they have either.
	 */
	suspend fun setPin(current: String, pin: String?): PinChangeResult

	/**
	 * Seconds until the PIN of a stored user may be tried again after too many wrong attempts, 0 when it can be tried.
	 */
	fun pinLockoutSeconds(serverId: UUID, userId: UUID): Long
}

/**
 * How a user signs in: with a password, a PIN (on this network), both or neither.
 */
data class SignInOptions(
	val hasPassword: Boolean,
	val hasPin: Boolean,
)

enum class PinChangeResult {
	SAVED,
	WRONG_CURRENT,
	FAILED,
}

@Serializable
internal data class UpdateUserPinBody(
	@SerialName("CurrentPw") val currentPw: String?,
	@SerialName("NewPin") val newPin: String?,
	@SerialName("ResetPin") val resetPin: Boolean,
)

private const val PIN_ATTEMPTS_BEFORE_LOCKOUT = 5

// Lockout after the 5th wrong PIN, then after each further one
private val PIN_LOCKOUT_SECONDS = longArrayOf(30, 60, 120, 300, 900, 1800)

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
		// Either the server's user list or what the server said last is enough to ask for the PIN (Finly)
		val hasPin = (user is PublicUser && user.hasPin) || authStoreUser?.serverPin == true
		return when {
			// A profile with a PIN always asks for it, also when this device has a stored access token
			hasPin -> flowOf(RequirePinState)
			// Try login with access token
			authStoreUser?.accessToken != null -> authenticateToken(server, user.withToken(authStoreUser.accessToken))
			// A user without a password can sign in straight away
			user is PublicUser && !user.hasPassword -> authenticateCredential(server, user.name, "")
			// Require login
			else -> flowOf(RequireSignInState)
		}
	}

	/**
	 * Sign in with the user's PIN. The server checks it, like a password, and throttles wrong PINs; the same count is
	 * kept here so the PIN pad can show how long to wait.
	 */
	private fun authenticatePin(server: Server, user: User, pin: String) = flow {
		// Locked after too many wrong attempts: don't even ask the server
		if (pinLockoutSeconds(server.id, user.id) > 0) return@flow emit(InvalidPinState)

		val api = jellyfin.createApi(server.address, deviceInfo = defaultDeviceInfo.forUser(user.name))
		val result = try {
			api.userApi.authenticateUserByName(user.name, pin).content
		} catch (err: TimeoutException) {
			Timber.e(err, "Failed to connect to server trying to sign in ${user.id} with a PIN")
			return@flow emit(ServerUnavailableState)
		} catch (err: InvalidStatusException) {
			when (err.status) {
				HttpURLConnection.HTTP_UNAUTHORIZED -> {
					Timber.w("Wrong PIN entered for user ${user.id}")
					recordPinFailure(server.id, user.id, serverLocked = false)
					emit(InvalidPinState)
				}

				// The server refuses PINs for a while after too many wrong ones
				HttpURLConnection.HTTP_FORBIDDEN -> {
					Timber.w("Server refused PIN sign in for user ${user.id}")
					recordPinFailure(server.id, user.id, serverLocked = true)
					emit(InvalidPinState)
				}

				else -> emit(ApiClientErrorLoginState(err))
			}
			return@flow
		} catch (err: ApiClientException) {
			Timber.e(err, "Unable to sign in ${user.id} with a PIN")
			return@flow emit(ApiClientErrorLoginState(err))
		}

		emitAll(authenticateAuthenticationResult(server, result))

		// Stored now, also for a profile signed in to for the first time on this device
		authenticationStore.getUser(server.id, user.id)?.let { stored ->
			authenticationStore.putUser(server.id, user.id, stored.copy(serverPin = true, pinFailures = 0, pinLockedUntil = 0))
		}
	}.flowOn(Dispatchers.IO)

	/**
	 * Count a wrong PIN the same way as the server: after [PIN_ATTEMPTS_BEFORE_LOCKOUT] the PIN is locked for a while,
	 * and each further wrong PIN locks it for longer. When the server already refuses PINs ([serverLocked]), lock for at
	 * least the first period. Stored with the user, so restarting the app doesn't reset it.
	 */
	private fun recordPinFailure(serverId: UUID, userId: UUID, serverLocked: Boolean) {
		val storeUser = authenticationStore.getUser(serverId, userId) ?: return
		val failures = if (serverLocked) maxOf(storeUser.pinFailures + 1, PIN_ATTEMPTS_BEFORE_LOCKOUT) else storeUser.pinFailures + 1
		val lockouts = failures - PIN_ATTEMPTS_BEFORE_LOCKOUT
		val lockedUntil = if (lockouts < 0) 0L
		else System.currentTimeMillis() + PIN_LOCKOUT_SECONDS[lockouts.coerceAtMost(PIN_LOCKOUT_SECONDS.lastIndex)] * 1000
		authenticationStore.putUser(serverId, userId, storeUser.copy(pinFailures = failures, pinLockedUntil = lockedUntil))
	}

	override fun pinLockoutSeconds(serverId: UUID, userId: UUID): Long {
		val lockedUntil = authenticationStore.getUser(serverId, userId)?.pinLockedUntil ?: return 0
		return ((lockedUntil - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
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
		// The user just signed in with their PIN, password or Quick Connect, so a PIN isn't asked for again (Finly)
		val result = setActiveSession(user, server, unlocked = true)
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
		// The server says the profile has a PIN (Finly)
		this == SessionSwitchResult.PIN_REQUIRED -> RequirePinState
		else -> RequireSignInState
	}

	private suspend fun setActiveSession(user: User, server: Server, unlocked: Boolean = false): SessionSwitchResult {
		val result = sessionRepository.switchCurrentSession(server.id, user.id, unlocked)

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
		authenticationStore.getUser(serverId, userId)?.serverPin == true

	override suspend fun getSignInOptions(): SignInOptions? = withContext(Dispatchers.IO) {
		val session = sessionRepository.currentSession.value ?: return@withContext null
		try {
			val user = Json.parseToJsonElement(userApiClient.request(HttpMethod.GET, "/Users/Me").body.decodeToString()).jsonObject
			val options = SignInOptions(
				hasPassword = user["HasConfiguredPassword"]?.jsonPrimitive?.booleanOrNull ?: true,
				hasPin = user["HasPin"]?.jsonPrimitive?.booleanOrNull ?: false,
			)
			authenticationStore.rememberServerPin(session.serverId, session.userId, options.hasPin)
			options
		} catch (err: ApiClientException) {
			Timber.w(err, "Unable to get sign in options")
			null
		} catch (err: IllegalArgumentException) {
			// An unexpected body, kotlinx.serialization's SerializationException is an IllegalArgumentException
			Timber.w(err, "Unable to read sign in options")
			null
		}
	}

	override suspend fun setPin(current: String, pin: String?): PinChangeResult = withContext(Dispatchers.IO) {
		val session = sessionRepository.currentSession.value ?: return@withContext PinChangeResult.FAILED
		try {
			userApiClient.request(
				method = HttpMethod.POST,
				pathTemplate = "/Users/Pin",
				queryParameters = mapOf("userId" to session.userId),
				requestBody = UpdateUserPinBody(currentPw = current, newPin = pin, resetPin = pin == null),
			)
			authenticationStore.rememberServerPin(session.serverId, session.userId, pin != null)
			PinChangeResult.SAVED
		} catch (err: InvalidStatusException) {
			Timber.w(err, "Unable to change the PIN")
			if (err.status == HttpURLConnection.HTTP_FORBIDDEN) PinChangeResult.WRONG_CURRENT else PinChangeResult.FAILED
		} catch (err: ApiClientException) {
			Timber.w(err, "Unable to change the PIN")
			PinChangeResult.FAILED
		}
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
