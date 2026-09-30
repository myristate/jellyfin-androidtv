package org.jellyfin.androidtv.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.time.Instant

interface ItemMutationRepository {
	suspend fun setFavorite(item: UUID, favorite: Boolean): UserItemDataDto
	suspend fun setPlayed(item: UUID, played: Boolean): UserItemDataDto

	suspend fun hasTag(item: UUID, tag: String): Boolean

	/**
	 * Add or remove a tag on the server, requires an administrator. Tags added to a series are also added to its
	 * seasons and episodes by the server.
	 */
	suspend fun setTag(item: UUID, tag: String, tagged: Boolean)

	companion object {
		/**
		 * The tag that marks movies and series as suitable for kids. A kid's user on the server is limited to it with
		 * "Allow items with tags" in the user's parental control settings.
		 */
		const val KIDS_TAG = "Kids"
	}
}

class ItemMutationRepositoryImpl(
	private val api: ApiClient,
	private val dataRefreshService: DataRefreshService,
) : ItemMutationRepository {
	override suspend fun setFavorite(item: UUID, favorite: Boolean): UserItemDataDto {
		val response by when {
			favorite -> withContext(Dispatchers.IO) { api.userLibraryApi.markFavoriteItem(itemId = item) }
			else -> withContext(Dispatchers.IO) { api.userLibraryApi.unmarkFavoriteItem(itemId = item) }
		}

		dataRefreshService.lastFavoriteUpdate = Instant.now()
		return response
	}

	override suspend fun setPlayed(item: UUID, played: Boolean): UserItemDataDto {
		val response by when {
			played -> withContext(Dispatchers.IO) { api.playStateApi.markPlayedItem(itemId = item) }
			else -> withContext(Dispatchers.IO) { api.playStateApi.markUnplayedItem(itemId = item) }
		}

		return response
	}

	override suspend fun hasTag(item: UUID, tag: String): Boolean =
		getItemJson(item).tags().any { it.equals(tag, ignoreCase = true) }

	override suspend fun setTag(item: UUID, tag: String, tagged: Boolean) = setTag(api, item, tag, tagged)

	private suspend fun setTag(client: ApiClient, item: UUID, tag: String, tagged: Boolean) {
		val itemJson = getItemJson(client, item)
		val tags = itemJson.tags()
		if (tags.any { it.equals(tag, ignoreCase = true) } == tagged) return

		val newTags = if (tagged) tags + tag else tags.filterNot { it.equals(tag, ignoreCase = true) }

		// The server replaces the whole item with the posted one, so post back everything it returned with only the
		// tags changed. The raw JSON is used because the SDK model would drop properties it doesn't know about.
		val updatedItem = JsonObject(itemJson + (TAGS to JsonArray(newTags.map(::JsonPrimitive))))
		withContext(Dispatchers.IO) {
			client.request(HttpMethod.POST, ITEM_PATH, mapOf("itemId" to item), emptyMap(), updatedItem)
		}
	}

	private suspend fun getItemJson(item: UUID): JsonObject = getItemJson(api, item)

	private suspend fun getItemJson(client: ApiClient, item: UUID): JsonObject = withContext(Dispatchers.IO) {
		val response = client.request(HttpMethod.GET, ITEM_PATH, mapOf("itemId" to item), emptyMap(), null)
		Json.parseToJsonElement(response.body.decodeToString()).jsonObject
	}

	private fun JsonObject.tags(): List<String> = this[TAGS]
		?.jsonArray
		?.mapNotNull { it.jsonPrimitive.contentOrNull }
		.orEmpty()

	private companion object {
		const val ITEM_PATH = "/Items/{itemId}"
		const val TAGS = "Tags"
	}
}
