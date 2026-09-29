package org.jellyfin.androidtv.ui.startup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.androidtv.auth.model.AuthenticationSortBy
import org.jellyfin.androidtv.auth.model.AutomaticAuthenticateMethod
import org.jellyfin.androidtv.auth.model.LoginState
import org.jellyfin.androidtv.auth.model.PinAuthenticateMethod
import org.jellyfin.androidtv.auth.model.PrivateUser
import org.jellyfin.androidtv.auth.model.Server
import org.jellyfin.androidtv.auth.model.User
import org.jellyfin.androidtv.auth.repository.AuthenticationRepository
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.auth.repository.ServerUserRepository
import org.jellyfin.androidtv.auth.store.AuthenticationPreferences
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class StartupViewModel(
	private val serverRepository: ServerRepository,
	private val serverUserRepository: ServerUserRepository,
	private val authenticationRepository: AuthenticationRepository,
	private val authenticationPreferences: AuthenticationPreferences,
) : ViewModel() {
	val storedServers = serverRepository.storedServers
	val discoveredServers = serverRepository.discoveredServers

	private val _users = MutableStateFlow<List<User>>(emptyList())
	val users = _users.asStateFlow()

	private val _userPickerServer = MutableStateFlow<Server?>(null)

	/**
	 * The server whose user picker is showing, the toolbar shows the add account and change server buttons for it.
	 */
	val userPickerServer = _userPickerServer.asStateFlow()

	fun setUserPickerServer(server: Server?) {
		_userPickerServer.value = server
	}

	private val userComparator = compareByDescending<User> { user ->
		if (
			authenticationPreferences[AuthenticationPreferences.sortBy] == AuthenticationSortBy.LAST_USE &&
			user is PrivateUser
		) user.lastUsed
		else null
	}.thenBy { user -> user.name }

	private val discoveryMutex = Mutex()
	private var loadUsersJob: Job? = null
	private val pinFailures = mutableMapOf<UUID, Int>()
	private val pinLockedUntil = mutableMapOf<UUID, Instant>()

	private companion object {
		const val PUBLIC_USERS_RETRY_ATTEMPTS = 6
		const val PUBLIC_USERS_RETRY_MAX_DELAY_SECONDS = 4
		const val PIN_ATTEMPTS_BEFORE_LOCKOUT = 5
		val PIN_LOCKOUT: Duration = Duration.ofSeconds(30)
	}

	fun getServer(id: UUID) = serverRepository.storedServers.value
		.find { it.id == id }

	fun loadUsers(server: Server) {
		loadUsersJob?.cancel()
		loadUsersJob = viewModelScope.launch {
			val storedUsers = serverUserRepository.getStoredServerUsers(server)
			_users.value = storedUsers.sortedWith(userComparator)

			// Keep trying while the server is unreachable, the network may still be connecting after the device woke up
			var publicUsers = serverUserRepository.getPublicServerUsers(server)
			var attempt = 0
			while (publicUsers == null && attempt < PUBLIC_USERS_RETRY_ATTEMPTS) {
				attempt++
				delay(attempt.coerceAtMost(PUBLIC_USERS_RETRY_MAX_DELAY_SECONDS).seconds)
				publicUsers = serverUserRepository.getPublicServerUsers(server)
			}

			val storedUserIds = storedUsers.map { it.id }
			_users.value = (storedUsers + publicUsers.orEmpty().filterNot { it.id in storedUserIds }).sortedWith(userComparator)
		}
	}

	fun authenticatePin(server: Server, user: User, pin: String): Flow<LoginState> =
		authenticationRepository.authenticate(server, PinAuthenticateMethod(user, pin))

	/**
	 * Seconds until the PIN of [userId] may be tried again, 0 when it can be tried now.
	 */
	fun pinLockoutSeconds(userId: UUID): Long {
		val lockedUntil = pinLockedUntil[userId] ?: return 0
		return Duration.between(Instant.now(), lockedUntil).seconds.coerceAtLeast(0)
	}

	/**
	 * Count a wrong PIN, locking out further attempts for a while after too many.
	 * @return the lockout in seconds, 0 when the PIN can be tried again straight away.
	 */
	fun onPinFailed(userId: UUID): Long {
		val failures = (pinFailures[userId] ?: 0) + 1
		if (failures < PIN_ATTEMPTS_BEFORE_LOCKOUT) {
			pinFailures[userId] = failures
			return 0
		}

		pinFailures.remove(userId)
		pinLockedUntil[userId] = Instant.now() + PIN_LOCKOUT
		return PIN_LOCKOUT.seconds
	}

	fun onPinSucceeded(userId: UUID) {
		pinFailures.remove(userId)
		pinLockedUntil.remove(userId)
	}

	fun addServer(address: String) = serverRepository.addServer(address)

	fun deleteServer(serverId: UUID) {
		viewModelScope.launch { serverRepository.deleteServer(serverId) }
	}

	fun authenticate(server: Server, user: User): Flow<LoginState> =
		authenticationRepository.authenticate(server, AutomaticAuthenticateMethod(user))

	fun getUserImage(server: Server, user: User): String? =
		authenticationRepository.getUserImageUrl(server, user)

	fun loadDiscoveryServers() {
		// Only run one discovery process at a time
		if (discoveryMutex.isLocked) return

		viewModelScope.launch(Dispatchers.IO) {
			discoveryMutex.withLock {
				serverRepository.loadDiscoveryServers()
			}
		}
	}

	fun reloadStoredServers() {
		viewModelScope.launch { serverRepository.loadStoredServers() }
	}

	suspend fun getLastServer(): Server? {
		serverRepository.loadStoredServers()
		return serverRepository.storedServers.value.maxByOrNull { it.dateLastAccessed }
	}

	suspend fun updateServer(server: Server): Boolean = serverRepository.updateServer(server)
}

