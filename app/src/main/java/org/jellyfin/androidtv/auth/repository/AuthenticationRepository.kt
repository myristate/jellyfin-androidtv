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
import org.jellyfin.androidtv.util.apiclient.ServerRefusals
import org.jellyfin.androidtv.util.apiclient.ServerRefusal
import org.jellyfin.androidtv.auth.model.RefusedLoginState
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
	 * Ask the server how a user signs in, the signed in user when [userId] is null, which also refreshes [hasPin] for a
	 * user stored on this device.
	 */
	suspend fun getSignInOptions(userId: UUID? = null): SignInOptions?

	/**
	 * Set a user's PIN on the server, or remove it with a null [pin] (finly/SIGN-IN.md). For the signed in user ([userId]
	 * null) the server needs [current]: their current PIN (home network only) or password, or for a first PIN their
	 * password. An administrator changing someone else's gives none.
	 */
	suspend fun setPin(current: String?, pin: String?, userId: UUID? = null): PinChangeResult

	/**
	 * Turn PIN sign in back on for a user after too many wrong PINs, for an administrator.
	 */
	suspend fun unlockPin(userId: UUID): PinChangeResult

	/**
	 * Seconds until the PIN of a stored user may be tried again after too many wrong attempts, 0 when it can be tried.
	 */
	fun pinLockoutSeconds(serverId: UUID, userId: UUID): Long
}

/**
 * How a user signs in (finly/SIGN-IN.md): whether they have a password, have a PIN at all ([pinProtected]), can enter
 * it from here ([pinUsable], home network and not turned off) and whether PIN sign in is turned off after too many
 * wrong PINs.
 */
data class SignInOptions(
	val hasPassword: Boolean,
	val pinProtected: Boolean,
	val pinUsable: Boolean,
	val pinSignInDisabled: Boolean,
)

sealed interface PinChangeResult {
	data object Saved : PinChangeResult

	/** The server refused or couldn't be reached ([status] null), show [org.jellyfin.androidtv.util.apiclient.refusalMessage]. */
	data class Refused(val refusal: ServerRefusal?, val status: Int?) : PinChangeResult
}

@Serializable
internal data class UpdateUserPinBody(
	@SerialName("CurrentPw") val currentPw: String?,
	@SerialName("NewPin") val newPin: String?,
	@SerialName("ResetPin") val resetPin: Boolean,
)

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
		// Either the server's user list or what the server said last is enough to know there is a PIN (Finly)
		val pinProtected = (user is PublicUser && user.hasPin) || authStoreUser?.serverPin == true
		return when {
			// A profile with a PIN always asks for it, also when this device has a stored access token
			pinProtected && (user !is PublicUser || user.hasPin) -> flowOf(RequirePinState)
			// The PIN can't be entered right now (away from home, or turned off after too many wrong PINs): the password
			// then, never the stored access token
			pinProtected -> flowOf(RequireSignInState)
			// Try login with access token
			authStoreUser?.accessToken != null -> authenticateToken(server, user.withToken(authStoreUser.accessToken))
			// A user without a password can sign in straight away
			user is PublicUser && !user.hasPassword -> authenticateCredential(server, user.name, "")
			// Require login
			else -> flowOf(RequireSignInState)
		}
	}

	/**
	 * Sign in with the user's PIN. The server checks it, like a password, and throttles wrong PINs; it says how long to
	 * wait, which is kept so the PIN pad can count down. Nothing is counted here: a parent unlocking the PIN or setting a
	 * new one elsewhere then works straight away.
	 */
	private fun authenticatePin(server: Server, user: User, pin: String) = flow {
		val api = jellyfin.createApi(server.address, deviceInfo = defaultDeviceInfo.forUser(user.name))
		val result = try {
			api.userApi.authenticateUserByName(user.name, pin).content
		} catch (err: TimeoutException) {
			Timber.e(err, "Failed to connect to server trying to sign in ${user.id} with a PIN")
			return@flow emit(ServerUnavailableState)
		} catch (err: InvalidStatusException) {
			val refusal = ServerRefusals.take("/Users/AuthenticateByName")
			when (err.status) {
				HttpURLConnection.HTTP_UNAUTHORIZED -> {
					Timber.w("Wrong PIN entered for user ${user.id}")
					emit(InvalidPinState)
				}

				// A wrong PIN is a 401 like a wrong password. A 403 means the server refused to sign the profile in at
				// all, and says why (Finly): PIN sign in paused or turned off after too many wrong PINs, outside its
				// allowed hours, or turned off.
				HttpURLConnection.HTTP_FORBIDDEN -> {
					Timber.w("Server refused to sign in user ${user.id} with a PIN: ${refusal?.reason}, retry after ${refusal?.retryAfterSeconds} s")
					rememberPinLockout(server.id, user.id, refusal)
					emit(RefusedLoginState(refusal, err.status))
				}

				else -> {
					Timber.e(err, "Unable to sign in ${user.id} with a PIN, status ${err.status}")
					emit(ApiClientErrorLoginState(err))
				}
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
	 * Keep how long the server said to wait after too many wrong PINs, so the PIN pad can count down, also after the
	 * app restarts.
	 */
	private fun rememberPinLockout(serverId: UUID, userId: UUID, refusal: ServerRefusal?) {
		val seconds = refusal?.retryAfterSeconds?.takeIf { refusal.reason == ServerRefusal.PIN_LOCKED_OUT } ?: return
		val storeUser = authenticationStore.getUser(serverId, userId) ?: return
		authenticationStore.putUser(serverId, userId, storeUser.copy(pinLockedUntil = System.currentTimeMillis() + seconds * 1000))
	}

	private fun resetPinFailures(serverId: UUID, userId: UUID) {
		val storeUser = authenticationStore.getUser(serverId, userId) ?: return
		if (storeUser.pinFailures != 0 || storeUser.pinLockedUntil != 0L) {
			authenticationStore.putUser(serverId, userId, storeUser.copy(pinFailures = 0, pinLockedUntil = 0))
		}
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
			val refusal = ServerRefusals.take("/Users/AuthenticateByName")
			Timber.e(err, "Unable to sign in as $username, status ${err.status}, reason ${refusal?.reason}")
			// The server rejected the username or password, don't report it as a connection problem
			when (err.status) {
				HttpURLConnection.HTTP_UNAUTHORIZED -> emit(RequireSignInState)
				// Refused for a reason to tell the person (Finly), such as a password needed away from home
				HttpURLConnection.HTTP_FORBIDDEN -> emit(RefusedLoginState(refusal, err.status))
				else -> emit(ApiClientErrorLoginState(err))
			}
			return@flow
		} catch (err: ApiClientException) {
			Timber.e(err, "Unable to sign in as $username")
			emit(ApiClientErrorLoginState(err))
			return@flow
		}

		// Signing in with the password lets the server take PINs again, start counting afresh here too (Finly)
		result.user?.id?.let { userId -> resetPinFailures(server.id, userId) }

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
		if (result == SessionSwitchResult.FAILED && user is PublicUser && !user.hasPassword && !user.hasPin) {
			// (Finly) The stored access token was signed out, for example by a parent changing the profile's PIN: a
			// profile without a password or PIN just signs in again
			Timber.i("Stored access token of user ${user.id} was rejected, signing in again without a password")
			emitAll(authenticateCredential(server, user.name, ""))
		} else if (result != SessionSwitchResult.SWITCHED) {
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

	override suspend fun getSignInOptions(userId: UUID?): SignInOptions? = withContext(Dispatchers.IO) {
		val session = sessionRepository.currentSession.value ?: return@withContext null
		val targetId = userId ?: session.userId
		try {
			val path = if (targetId == session.userId) "/Users/Me" else "/Users/$targetId"
			val user = Json.parseToJsonElement(userApiClient.request(HttpMethod.GET, path).body.decodeToString()).jsonObject
			fun flag(name: String) = user[name]?.jsonPrimitive?.booleanOrNull
			val hasPin = flag("HasPin") ?: false
			val options = SignInOptions(
				hasPassword = flag("HasConfiguredPassword") ?: true,
				// PinProtected: HasPin is also false when the PIN only can't be used from here
				pinProtected = flag("PinProtected") ?: hasPin,
				pinUsable = hasPin,
				pinSignInDisabled = flag("PinSignInDisabled") ?: false,
			)
			Timber.i("Sign in options of user %s: %s", targetId, options)
			authenticationStore.rememberServerPin(session.serverId, targetId, options.pinProtected)
			options
		} catch (err: ApiClientException) {
			Timber.w(err, "Unable to get sign in options of user %s", targetId)
			null
		} catch (err: IllegalArgumentException) {
			// An unexpected body, kotlinx.serialization's SerializationException is an IllegalArgumentException
			Timber.w(err, "Unable to read sign in options of user %s", targetId)
			null
		}
	}

	override suspend fun setPin(current: String?, pin: String?, userId: UUID?): PinChangeResult = withContext(Dispatchers.IO) {
		val session = sessionRepository.currentSession.value ?: return@withContext PinChangeResult.Refused(null, null)
		val targetId = userId ?: session.userId
		val action = if (pin == null) "remove" else "set"

		try {
			userApiClient.request(
				method = HttpMethod.POST,
				pathTemplate = "/Users/Pin",
				queryParameters = mapOf("userId" to targetId),
				requestBody = UpdateUserPinBody(currentPw = current, newPin = pin, resetPin = pin == null),
			)
			Timber.i("PIN of user %s: %s done", targetId, action)
			// A user stored on this device: its PIN flag, and the wrong PINs are forgotten by the server
			authenticationStore.rememberServerPin(session.serverId, targetId, pin != null)
			resetPinFailures(session.serverId, targetId)
			PinChangeResult.Saved
		} catch (err: InvalidStatusException) {
			val refusal = ServerRefusals.take("/Users/Pin")
			Timber.w(err, "Unable to %s the PIN of user %s: status %d, reason %s", action, targetId, err.status, refusal?.reason)
			PinChangeResult.Refused(refusal, err.status)
		} catch (err: ApiClientException) {
			Timber.w(err, "Unable to %s the PIN of user %s, the server can't be reached", action, targetId)
			PinChangeResult.Refused(null, null)
		}
	}

	override suspend fun unlockPin(userId: UUID): PinChangeResult = withContext(Dispatchers.IO) {
		val session = sessionRepository.currentSession.value ?: return@withContext PinChangeResult.Refused(null, null)
		try {
			userApiClient.request(
				method = HttpMethod.POST,
				pathTemplate = "/Users/Pin/Unlock",
				queryParameters = mapOf("userId" to userId),
			)
			Timber.i("PIN sign in of user %s unlocked", userId)
			resetPinFailures(session.serverId, userId)
			PinChangeResult.Saved
		} catch (err: InvalidStatusException) {
			val refusal = ServerRefusals.take("/Users/Pin/Unlock")
			Timber.w(err, "Unable to unlock the PIN of user %s: status %d, reason %s", userId, err.status, refusal?.reason)
			PinChangeResult.Refused(refusal, err.status)
		} catch (err: ApiClientException) {
			Timber.w(err, "Unable to unlock the PIN of user %s, the server can't be reached", userId)
			PinChangeResult.Refused(null, null)
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
