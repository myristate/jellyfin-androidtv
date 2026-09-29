package org.jellyfin.androidtv.auth

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * A PIN that protects a user profile on this device. The PIN never leaves the device and is only stored as a
 * salted PBKDF2 hash, formatted as "iterations:salt:hash" with the salt and hash hex encoded.
 */
object ProfilePin {
	const val LENGTH = 4

	// PBKDF2WithHmacSHA256 needs API 26, the app supports API 23
	private const val ALGORITHM = "PBKDF2WithHmacSHA1"
	private const val ITERATIONS = 10_000
	private const val SALT_BYTES = 16
	private const val HASH_BITS = 256
	private const val HASH_PARTS = 3
	private const val HEX_RADIX = 16

	fun isValid(pin: String) = pin.length == LENGTH && pin.all { it in '0'..'9' }

	fun hash(pin: String): String {
		require(isValid(pin)) { "A PIN must be $LENGTH digits" }

		val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
		return "$ITERATIONS:${salt.toHex()}:${derive(pin, salt, ITERATIONS).toHex()}"
	}

	fun verify(pin: String, storedHash: String): Boolean {
		val parts = storedHash.split(':')
		if (parts.size != HASH_PARTS) return false

		val iterations = parts[0].toIntOrNull() ?: return false
		val salt = parts[1].fromHex() ?: return false
		val expected = parts[2].fromHex() ?: return false

		return MessageDigest.isEqual(derive(pin, salt, iterations), expected)
	}

	private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
		val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, HASH_BITS)
		return try {
			SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
		} finally {
			spec.clearPassword()
		}
	}

	private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

	private fun String.fromHex(): ByteArray? {
		if (length % 2 != 0) return null
		return ByteArray(length / 2) { i ->
			substring(i * 2, i * 2 + 2).toIntOrNull(HEX_RADIX)?.toByte() ?: return null
		}
	}
}
