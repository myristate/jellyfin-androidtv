package org.jellyfin.androidtv.auth.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jellyfin.androidtv.auth.store.AuthenticationStore
import org.jellyfin.sdk.api.client.RawResponse
import timber.log.Timber
import java.util.UUID

/**
 * The number of digits in a profile PIN (Finly), the same as the server's.
 */
const val PIN_LENGTH = 4

/**
 * Whether [value] has the form of a PIN, [PIN_LENGTH] digits.
 */
fun isPinFormat(value: String) = value.length == PIN_LENGTH && value.all { it in '0'..'9' }

/**
 * Read Finly's HasPin flag from a user object returned by the server, such as /Users/Me. Null when the response has no
 * such flag or can't be read, for example with a server that isn't Finly's.
 */
internal fun parseHasPin(body: String): Boolean? = parseUserFlag(body, "HasPin")

/**
 * Read Finly's PinProtected flag from a user object: whether the user has a PIN at all, also when it can't be entered
 * from here (away from home, or PIN sign-in turned off after too many wrong PINs). Null when the server doesn't say.
 */
internal fun parsePinProtected(body: String): Boolean? = parseUserFlag(body, "PinProtected")

private fun parseUserFlag(body: String, name: String): Boolean? = try {
	Json.parseToJsonElement(body).jsonObject[name]?.jsonPrimitive?.booleanOrNull
} catch (err: IllegalArgumentException) {
	// Covers kotlinx.serialization's SerializationException and a body that isn't a JSON object
	Timber.w(err, "Unable to read %s from the server's user", name)
	null
}

/**
 * Whether the user is protected by a PIN: PinProtected, or HasPin when the server doesn't send that. A HasPin of false
 * alone says nothing, it is also false when the PIN only can't be used from here. Null when unknown.
 */
internal fun RawResponse.pinProtected(): Boolean? {
	val text = body.decodeToString()
	return parsePinProtected(text) ?: parseHasPin(text)?.takeIf { it }
}

/**
 * Remember whether the server asks a stored user for a PIN (Finly).
 */
internal fun AuthenticationStore.rememberServerPin(serverId: UUID, userId: UUID, hasPin: Boolean) {
	val stored = getUser(serverId, userId) ?: return
	if (stored.serverPin != hasPin) putUser(serverId, userId, stored.copy(serverPin = hasPin))
}
