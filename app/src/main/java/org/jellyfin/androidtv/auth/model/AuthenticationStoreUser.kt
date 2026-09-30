package org.jellyfin.androidtv.auth.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Locally stored user information. New properties require default values or deserialization will fail.
 */
@Serializable
data class AuthenticationStoreUser(
	val name: String,
	@SerialName("last_used") val lastUsed: Long = Instant.now().toEpochMilli(),
	@SerialName("image_tag") val imageTag: String? = null,
	@SerialName("access_token") val accessToken: String? = null,
	// Salted hash of the profile PIN set on this device, see [org.jellyfin.androidtv.auth.ProfilePin]
	@SerialName("pin_hash") val pinHash: String? = null,
	// Wrong PIN attempts since the last correct one, and when the PIN may be tried again (epoch milliseconds)
	@SerialName("pin_failures") val pinFailures: Int = 0,
	@SerialName("pin_locked_until") val pinLockedUntil: Long = 0,
)
