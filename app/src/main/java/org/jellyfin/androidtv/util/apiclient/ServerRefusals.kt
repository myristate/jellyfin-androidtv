package org.jellyfin.androidtv.util.apiclient

import android.content.Context
import okhttp3.Interceptor
import okhttp3.Response
import org.jellyfin.androidtv.R
import timber.log.Timber
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap

/**
 * Why the Finly server refused a request (finly/SIGN-IN.md in the server repository): the X-Finly-Reason and Retry-After
 * headers, and the message it sent for the person. The SDK only passes on the status code, so [ServerResponseInterceptor]
 * keeps these for the code that catches the error.
 */
data class ServerRefusal(
	val path: String,
	val status: Int,
	val reason: String?,
	val retryAfterSeconds: Long?,
	val message: String?,
	val at: Long,
) {
	companion object {
		const val WRONG_PIN = "WrongPin"
		const val WRONG_PASSWORD = "WrongPassword"
		const val TOO_MANY_ATTEMPTS = "TooManyAttempts"
		const val PIN_LOCKED_OUT = "PinLockedOut"
		const val PIN_DISABLED = "PinDisabled"
		const val PASSWORD_REQUIRED = "PasswordRequired"
		const val NOT_ALLOWED = "NotAllowed"
	}
}

object ServerRefusals {
	// A refusal is read straight after the request failed, older ones belong to something else
	private const val MAX_AGE_MS = 30_000L

	private val latest = ConcurrentHashMap<String, ServerRefusal>()

	internal fun record(refusal: ServerRefusal) {
		latest[refusal.path] = refusal
	}

	/**
	 * The latest refusal of a request to a path ending in [pathSuffix], such as "/Users/Pin", taken so it isn't read
	 * again for a later error.
	 */
	fun take(pathSuffix: String): ServerRefusal? {
		val now = System.currentTimeMillis()
		val match = latest.values
			.filter { it.path.endsWith(pathSuffix, ignoreCase = true) && now - it.at < MAX_AGE_MS }
			.maxByOrNull { it.at }
			?: return null
		latest.remove(match.path, match)
		return match
	}
}

/**
 * Logs every request that fails, is refused or is slow, for diagnostics reports, and keeps the Finly server's refusal
 * reasons for [ServerRefusals]. Only the path is logged: the query can carry the access token.
 */
class ServerResponseInterceptor : Interceptor {
	private companion object {
		const val SLOW_MS = 5_000L
		const val MESSAGE_BYTES = 1_024L
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val path = request.url.encodedPath
		val startedAt = System.currentTimeMillis()

		val response = try {
			chain.proceed(request)
		} catch (err: IOException) {
			Timber.tag("HTTP").w("%s %s failed after %d ms: %s", request.method, path, System.currentTimeMillis() - startedAt, err.toString())
			throw err
		}

		val took = System.currentTimeMillis() - startedAt
		if (response.code >= 400) {
			val reason = response.header("X-Finly-Reason")
			val retryAfter = response.header("Retry-After")?.toLongOrNull()
			// Only refusals with a reason carry a message meant for the person
			val message = if (reason != null) runCatching { response.peekBody(MESSAGE_BYTES).string() }.getOrNull()?.takeIf { it.isNotBlank() } else null
			Timber.tag("HTTP").w("%s %s answered %d in %d ms%s%s", request.method, path, response.code, took,
				reason?.let { ", reason $it" }.orEmpty(), retryAfter?.let { ", retry after $it s" }.orEmpty())
			ServerRefusals.record(ServerRefusal(path, response.code, reason, retryAfter, message, System.currentTimeMillis()))
		} else if (took > SLOW_MS) {
			Timber.tag("HTTP").i("%s %s took %d ms", request.method, path, took)
		}

		return response
	}
}

/**
 * The message to show for a refused or failed request, the same wording as the web client (finly/SIGN-IN.md).
 * [status] is the HTTP status when there was one, null when the server couldn't be reached.
 */
fun Context.refusalMessage(refusal: ServerRefusal?, status: Int?): String {
	val seconds = (refusal?.retryAfterSeconds ?: 0L).coerceAtLeast(1L).toInt()
	return when (refusal?.reason) {
		ServerRefusal.WRONG_PIN -> getString(R.string.refusal_wrong_pin)
		ServerRefusal.WRONG_PASSWORD -> getString(R.string.refusal_wrong_password)
		ServerRefusal.TOO_MANY_ATTEMPTS -> getString(R.string.refusal_too_many_attempts, seconds)
		ServerRefusal.PIN_LOCKED_OUT -> getString(R.string.refusal_pin_locked_out, seconds)
		ServerRefusal.PIN_DISABLED -> getString(R.string.refusal_pin_disabled)
		ServerRefusal.PASSWORD_REQUIRED -> getString(R.string.refusal_password_required)
		ServerRefusal.NOT_ALLOWED -> getString(R.string.refusal_not_allowed)
		else -> when (status ?: refusal?.status) {
			HttpURLConnection.HTTP_UNAUTHORIZED -> getString(R.string.refusal_wrong_password)
			HttpURLConnection.HTTP_FORBIDDEN -> getString(R.string.refusal_not_allowed)
			else -> getString(R.string.refusal_unreachable)
		}
	}
}
