package org.jellyfin.androidtv.util.diagnostics

/**
 * Keeps secrets out of log lines (Finly). Stream URLs carry the access token as a query parameter, and log lines end up
 * in logcat and in the diagnostics reports uploaded to the server.
 */
object LogRedaction {
	private val secretQueryParameter = Regex("""(?i)([?&](?:api_key|apikey|access_token|accesstoken)=)[^&#\s"']*""")

	/** [text] with the values of api_key style query parameters replaced. */
	@JvmStatic
	fun redact(text: String): String =
		if (!text.contains("key=", ignoreCase = true) && !text.contains("token=", ignoreCase = true)) text
		else text.replace(secretQueryParameter, "\$1***")
}
