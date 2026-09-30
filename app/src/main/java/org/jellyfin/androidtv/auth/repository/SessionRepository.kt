package org.jellyfin.androidtv.auth.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.auth.model.Server
import org.jellyfin.androidtv.auth.store.AuthenticationPreferences
import org.jellyfin.androidtv.auth.store.AuthenticationStore
import org.jellyfin.androidtv.preference.PreferencesRepository
import org.jellyfin.androidtv.preference.TelemetryPreferences
import org.jellyfin.androidtv.preference.constant.UserSelectBehavior.DISABLED
import org.jellyfin.androidtv.preference.constant.UserSelectBehavior.LAST_USER
import org.jellyfin.androidtv.preference.constant.UserSelectBehavior.SPECIFIC_USER
import org.jellyfin.androidtv.util.sdk.forUser
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.clientLogApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.net.HttpURLConnection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

data class Session(
	val userId: UUID,
	val serverId: UUID,
	val accessToken: String,
)

enum class SessionRepositoryState {
	READY,
	RESTORING_SESSION,
	SWITCHING_SESSION,
}

enum class SessionSwitchResult {
	SWITCHED,

	/**
	 * The server could not be reached, the stored access token may still be valid.
	 */
	SERVER_UNAVAILABLE,

	/**
	 * The server rejected the session or is not supported.
	 */
	FAILED,
}

interface SessionRepository {
	val currentSession: StateFlow<Session?>
	val state: StateFlow<SessionRepositoryState>

	suspend fun restoreSession(destroyOnly: Boolean)
	suspend fun switchCurrentSession(serverId: UUID, userId: UUID): SessionSwitchResult
	fun destroyCurrentSession()

	/**
	 * Remember when the app went to the background or its screensaver started, used by [lockIfNeeded].
	 */
	fun onBackgrounded()

	/**
	 * Sign out of a PIN protected profile when the app was in the background for longer than [PIN_LOCK_TIMEOUT].
	 * @return true when the session was signed out.
	 */
	fun lockIfNeeded(): Boolean

	companion object {
		val PIN_LOCK_TIMEOUT: Duration = Duration.ofMinutes(5)

		/**
		 * How long to keep retrying to restore a session while the server is unreachable, for example while the
		 * network is still connecting after the device woke up.
		 */
		val RESTORE_RETRY_TIMEOUT: Duration = Duration.ofSeconds(20)
	}
}

class SessionRepositoryImpl(
	private val authenticationPreferences: AuthenticationPreferences,
	private val authenticationStore: AuthenticationStore,
	private val userApiClient: ApiClient,
	private val preferencesRepository: PreferencesRepository,
	private val defaultDeviceInfo: DeviceInfo,
	private val userRepository: UserRepository,
	private val serverRepository: ServerRepository,
	private val telemetryPreferences: TelemetryPreferences,
) : SessionRepository {
	private val currentSessionMutex = Mutex()
	private val _currentSession = MutableStateFlow<Session?>(null)
	override val currentSession = _currentSession.asStateFlow()
	private val _state = MutableStateFlow(SessionRepositoryState.READY)
	override val state = _state.asStateFlow()
	private var backgroundedAt: Instant? = null

	private companion object {
		const val RESTORE_RETRY_MAX_DELAY_SECONDS = 4
	}

	override suspend fun restoreSession(destroyOnly: Boolean): Unit = withContext(NonCancellable) {
		currentSessionMutex.withLock {
			Timber.i("Restoring session")

			_state.value = SessionRepositoryState.RESTORING_SESSION

			val alwaysAuthenticate = authenticationPreferences[AuthenticationPreferences.alwaysAuthenticate]
			val autoLoginBehavior = authenticationPreferences[AuthenticationPreferences.autoLoginUserBehavior]

			when {
				alwaysAuthenticate -> destroyCurrentSession()
				autoLoginBehavior == DISABLED -> destroyCurrentSession()
				// Leaving the app does not lock a PIN protected profile straight away, see lockIfNeeded()
				autoLoginBehavior == LAST_USER && !destroyOnly -> restoreUserSession(createLastUserSession())
				autoLoginBehavior == SPECIFIC_USER && !destroyOnly -> {
					val serverId = authenticationPreferences[AuthenticationPreferences.autoLoginServerId].toUUIDOrNull()
					val userId = authenticationPreferences[AuthenticationPreferences.autoLoginUserId].toUUIDOrNull()
					if (serverId != null && userId != null) restoreUserSession(createUserSession(serverId, userId))
				}
			}

			_state.value = SessionRepositoryState.READY
		}
	}

	private suspend fun restoreUserSession(session: Session?) {
		if (session == null) return

		// A PIN protected profile is never signed in to automatically, the user picker asks for the PIN instead
		if (authenticationStore.getUser(session.serverId, session.userId)?.serverPin == true) {
			Timber.i("Not restoring session for PIN protected user ${session.userId}")
			return
		}

		// Keep trying while the server is unreachable, the network may still be connecting after the device woke up
		val deadline = Instant.now() + SessionRepository.RESTORE_RETRY_TIMEOUT
		var attempt = 0
		while (setCurrentSession(session) == SessionSwitchResult.SERVER_UNAVAILABLE && Instant.now() < deadline) {
			attempt++
			Timber.i("Server unavailable while restoring session, retrying (attempt $attempt)")
			delay(attempt.coerceAtMost(RESTORE_RETRY_MAX_DELAY_SECONDS).seconds)
		}
	}

	override suspend fun switchCurrentSession(serverId: UUID, userId: UUID): SessionSwitchResult {
		// No change in user - don't switch
		if (currentSession.value?.userId == userId) {
			Timber.d("Current session user is the same as the requested user")
			return SessionSwitchResult.SWITCHED
		}

		_state.value = SessionRepositoryState.SWITCHING_SESSION
		Timber.i("Switching current session to user $userId")

		val session = createUserSession(serverId, userId)
		if (session == null) {
			Timber.w("Could not switch to non-existing session for user $userId")
			_state.value = SessionRepositoryState.READY
			return SessionSwitchResult.FAILED
		}

		val result = setCurrentSession(session)
		_state.value = SessionRepositoryState.READY
		return result
	}

	override fun destroyCurrentSession() {
		Timber.i("Destroying current session")

		clearCurrentSession()
		_state.value = SessionRepositoryState.READY
	}

	override fun onBackgrounded() {
		// Keep the earliest, the screensaver running before the app is left counts too
		if (backgroundedAt == null) backgroundedAt = Instant.now()
	}

	override fun lockIfNeeded(): Boolean {
		val since = backgroundedAt ?: return false
		backgroundedAt = null

		val session = currentSession.value ?: return false
		if (Duration.between(since, Instant.now()) < SessionRepository.PIN_LOCK_TIMEOUT) return false
		if (authenticationStore.getUser(session.serverId, session.userId)?.serverPin != true) return false

		Timber.i("Locking PIN protected profile of user ${session.userId} after the TV was left idle")
		destroyCurrentSession()
		return true
	}

	/**
	 * Same as [destroyCurrentSession] without changing [state], so a session restore in progress is not reported as
	 * finished.
	 */
	private fun clearCurrentSession() {
		userRepository.setCurrentUser(null)
		serverRepository.setCurrentServer(null)
		_currentSession.value = null
	}

	private suspend fun setCurrentSession(session: Session?): SessionSwitchResult {
		var server: Server? = null

		if (session != null) {
			// No change in session - don't switch
			if (currentSession.value?.userId == session.userId) return SessionSwitchResult.SWITCHED

			// Update last active user
			authenticationPreferences[AuthenticationPreferences.lastServerId] = session.serverId.toString()
			authenticationPreferences[AuthenticationPreferences.lastUserId] = session.userId.toString()

			// Check if server version is supported
			server = serverRepository.getServer(session.serverId, true)
			if (server == null || !server.versionSupported) return SessionSwitchResult.FAILED
		}

		// Update session after binding the apiclient settings
		val deviceInfo = session?.let { defaultDeviceInfo.forUser(it.userId) } ?: defaultDeviceInfo
		Timber.i("Updating current session. userId=${session?.userId} server=${server?.serverVersion}")

		val applied = userApiClient.applySession(session, deviceInfo)
		if (applied && session != null) {
			try {
				val user = withContext(Dispatchers.IO) {
					userApiClient.userApi.getCurrentUser().content
				}
				userRepository.setCurrentUser(user)
				serverRepository.setCurrentServer(server)
			} catch (err: InvalidStatusException) {
				Timber.e(err, "Unable to authenticate: bad response when getting user info")
				clearCurrentSession()

				// Only these mean the access token was rejected, other errors happen while the server is starting
				return when (err.status) {
					HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> SessionSwitchResult.FAILED
					else -> SessionSwitchResult.SERVER_UNAVAILABLE
				}
			} catch (err: ApiClientException) {
				Timber.e(err, "Unable to authenticate: server unreachable when getting user info")
				clearCurrentSession()
				return SessionSwitchResult.SERVER_UNAVAILABLE
			}

			// Update crash reporting URL
			val crashReportUrl = userApiClient.clientLogApi.logFileUrl()
			telemetryPreferences[TelemetryPreferences.crashReportUrl] = crashReportUrl
			telemetryPreferences[TelemetryPreferences.crashReportToken] = session.accessToken
		} else {
			userRepository.setCurrentUser(null)
			serverRepository.setCurrentServer(null)
		}
		preferencesRepository.onSessionChanged()
		_currentSession.value = session
		// Time spent in the background before this session started doesn't count towards locking it
		backgroundedAt = null

		return SessionSwitchResult.SWITCHED
	}

	private fun createLastUserSession(): Session? {
		val lastUserId = authenticationPreferences[AuthenticationPreferences.lastUserId].toUUIDOrNull()
		val lastServerId = authenticationPreferences[AuthenticationPreferences.lastServerId].toUUIDOrNull()

		return if (lastUserId != null && lastServerId != null) createUserSession(lastServerId, lastUserId)
		else null
	}

	private fun createUserSession(serverId: UUID, userId: UUID): Session? {
		val account = authenticationStore.getUser(serverId, userId)
		if (account?.accessToken == null) return null

		return Session(
			userId = userId,
			serverId = serverId,
			accessToken = account.accessToken
		)
	}

	private fun ApiClient.applySession(session: Session?, newDeviceInfo: DeviceInfo = defaultDeviceInfo): Boolean {
		if (session == null) {
			update(
				baseUrl = null,
				accessToken = null,
				deviceInfo = newDeviceInfo,
			)
		} else {
			val server = authenticationStore.getServer(session.serverId)
				?: return false

			update(
				baseUrl = server.address,
				accessToken = session.accessToken,
				deviceInfo = newDeviceInfo,
			)
		}

		return true
	}
}
