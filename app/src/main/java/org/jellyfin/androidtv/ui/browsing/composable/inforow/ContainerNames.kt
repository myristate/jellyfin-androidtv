package org.jellyfin.androidtv.ui.browsing.composable.inforow

/**
 * The name to show for a media container (Finly). The server reports the demuxer's list of formats, such as
 * "mov,mp4,m4a,3gp,3g2,mj2" for any MP4 file or "matroska,webm" for MKV and WebM, so the first entry isn't the name
 * people know.
 */
fun containerDisplayName(container: String?): String? {
	val formats = container
		?.split(',')
		?.map { it.trim().lowercase() }
		?.filter { it.isNotEmpty() }
		?.takeIf { it.isNotEmpty() }
		?: return null

	return when {
		"matroska" in formats || "mkv" in formats -> "MKV"
		"webm" in formats -> "WEBM"
		"mp4" in formats || "mov" in formats || "m4v" in formats -> if (formats == listOf("mov")) "MOV" else "MP4"
		"mpegts" in formats || "ts" in formats -> "TS"
		"m2ts" in formats -> "M2TS"
		"avi" in formats -> "AVI"
		else -> formats.first().uppercase()
	}
}
