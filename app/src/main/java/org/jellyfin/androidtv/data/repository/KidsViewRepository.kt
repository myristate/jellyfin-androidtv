package org.jellyfin.androidtv.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.exception.ApiClientException
import timber.log.Timber
import java.util.UUID

/**
 * A restricted profile, as the server lists it with who can see what.
 */
@Serializable
data class AccessProfile(
	@SerialName("Id") val id: String,
	@SerialName("Name") val name: String,
	@SerialName("LevelId") val levelId: String? = null,
)

@Serializable
private data class ProfileAccessResponse(
	@SerialName("Profiles") val profiles: List<AccessProfile> = emptyList(),
	@SerialName("Items") val items: Map<String, List<String>> = emptyMap(),
)

/**
 * What the kids view shows: one profile, or every profile on a level.
 */
data class KidsViewTarget(
	val levelId: String?,
	val userId: String?,
	val name: String,
)

enum class KidsViewBadge { NONE, SOME, ALL }

/**
 * The kids view (Finly): lets a parent browsing a library see which films and shows a child's profile, or every
 * profile on a level such as Child, can see. Which restricted profiles can see an item is asked of the server in
 * batches as cards appear, and adding or removing items goes through here so the badges follow.
 */
class KidsViewRepository(
	private val api: ApiClient,
	private val profileLevelRepository: ProfileLevelRepository,
) {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val json = Json { ignoreUnknownKeys = true }

	private val _target = MutableStateFlow<KidsViewTarget?>(null)
	val target: StateFlow<KidsViewTarget?> = _target.asStateFlow()

	private val _access = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
	val access: StateFlow<Map<String, Set<String>>> = _access.asStateFlow()

	@Volatile
	var profiles: List<AccessProfile> = emptyList()
		private set

	private val pending = mutableSetOf<String>()
	private var fetchJob: Job? = null

	fun setTarget(target: KidsViewTarget?) {
		_target.value = target
	}

	/** Load the restricted profiles, for choosing what the kids view shows. */
	suspend fun loadProfiles(): List<AccessProfile> {
		fetch(emptyList())
		return profiles
	}

	/** The badge for an item, or null when it isn't known yet; see [request]. */
	fun badge(itemId: UUID, target: KidsViewTarget, access: Map<String, Set<String>>): KidsViewBadge? {
		val visibleTo = access[itemId.key()] ?: return null
		val covered = covered(target)
		if (covered.isEmpty()) return KidsViewBadge.NONE
		val count = covered.count { it in visibleTo }
		return when (count) {
			0 -> KidsViewBadge.NONE
			covered.size -> KidsViewBadge.ALL
			else -> KidsViewBadge.SOME
		}
	}

	/** Which restricted profiles can see an item, asking the server. */
	suspend fun accessFor(itemId: UUID): Set<String> {
		fetch(listOf(itemId.key()))
		return _access.value[itemId.key()].orEmpty()
	}

	fun invalidate(itemId: UUID) {
		_access.update { it - itemId.key() }
	}

	/** Add an item to a profile, or to every profile on a level. */
	suspend fun allow(itemId: UUID, userId: String?, levelId: String?): Boolean = post(
		if (levelId != null) "/Items/$itemId/AllowForLevel/$levelId" else "/Users/$userId/AllowedItems/$itemId", itemId
	)

	/** Remove an item from a profile, or from every profile on a level. */
	suspend fun hide(itemId: UUID, userId: String?, levelId: String?): Boolean = post(
		if (levelId != null) "/Items/$itemId/HideFromLevel/$levelId" else "/Users/$userId/HiddenItems/$itemId", itemId
	)

	private suspend fun post(path: String, itemId: UUID) = withContext(Dispatchers.IO) {
		try {
			api.request(HttpMethod.POST, path)
			invalidate(itemId)
			true
		} catch (err: ApiClientException) {
			Timber.e(err, "Unable to change profiles: $path")
			false
		}
	}

	private fun covered(target: KidsViewTarget): List<String> = when {
		target.userId != null -> listOf(target.userId.key())
		else -> profiles.filter { it.levelId?.key() == target.levelId?.key() }.map { it.id.key() }
	}

	/** Ask the server which restricted profiles can see an item, batched with other cards. */
	fun request(itemId: UUID) {
		synchronized(pending) {
			if (!pending.add(itemId.key())) return
			if (fetchJob?.isActive == true) return
			fetchJob = scope.launch {
				delay(200)
				val ids = synchronized(pending) { pending.toList().also { pending.clear() } }
				ids.chunked(100).forEach { fetch(it) }
			}
		}
	}

	private suspend fun fetch(ids: List<String>) = withContext(Dispatchers.IO) {
		try {
			val body = api.request(HttpMethod.GET, "/Items/ProfileAccess", queryParameters = mapOf("ids" to ids.joinToString(","))).body
			val response = json.decodeFromString<ProfileAccessResponse>(body.decodeToString())
			profiles = response.profiles
			val found = response.items.map { (id, users) -> id.key() to users.map { it.key() }.toSet() }.toMap()
			// Items the server didn't list are visible to nobody restricted
			_access.update { current -> current + ids.associateWith { emptySet<String>() } + found }
		} catch (err: ApiClientException) {
			Timber.w(err, "Unable to load which profiles can see items")
		}
	}

	/** The restricted levels, for choosing what the kids view shows. */
	fun restrictedLevels() = profileLevelRepository.levels.filter { it.isRestricted }

	private fun String.key() = replace("-", "").lowercase()
	private fun UUID.key() = toString().key()
}
