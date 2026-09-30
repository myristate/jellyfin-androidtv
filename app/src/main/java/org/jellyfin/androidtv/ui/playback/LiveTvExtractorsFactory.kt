package org.jellyfin.androidtv.ui.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.TsPayloadReader
import timber.log.Timber

/**
 * Extractors factory that makes Live TV transport streams start reliably.
 *
 * ExoPlayer only starts once every elementary stream listed in the programme map has shown its format. Broadcast
 * channels list extra audio streams (audio description, a second language) that often carry no data, for example
 * overnight, and playback then never starts. For Live TV the transport stream extractor only creates the main audio
 * stream (the first one that isn't audio description), plus video and subtitles.
 *
 * Other media use the [delegate] unchanged. All settings are forwarded to it.
 */
@OptIn(UnstableApi::class)
class LiveTvExtractorsFactory(
	private val delegate: DefaultExtractorsFactory,
	private val timestampSearchBytes: Int,
	private val isLiveTv: () -> Boolean,
) : ExtractorsFactory {
	private var subtitleParserFactory: SubtitleParser.Factory? = null
	private var textTrackTranscodingEnabled = true

	override fun createExtractors(): Array<Extractor> = replaceTsExtractor(delegate.createExtractors())

	override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
		replaceTsExtractor(delegate.createExtractors(uri, responseHeaders))

	override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
		this.subtitleParserFactory = subtitleParserFactory
		delegate.setSubtitleParserFactory(subtitleParserFactory)
		return this
	}

	@Deprecated("Deprecated in media3")
	override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory {
		this.textTrackTranscodingEnabled = textTrackTranscodingEnabled
		@Suppress("DEPRECATION")
		delegate.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
		return this
	}

	private fun replaceTsExtractor(extractors: Array<Extractor>): Array<Extractor> {
		if (!isLiveTv()) return extractors

		return Array(extractors.size) { index ->
			if (extractors[index] is TsExtractor) createLiveTsExtractor() else extractors[index]
		}
	}

	private fun createLiveTsExtractor(): TsExtractor {
		val flags = if (textTrackTranscodingEnabled) 0 else TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA
		val payloadReaders = MainAudioOnlyPayloadReaderFactory(
			DefaultTsPayloadReaderFactory(0, listOf(Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA608).build()))
		)
		return TsExtractor(
			TsExtractor.MODE_SINGLE_PMT,
			flags,
			subtitleParserFactory ?: SubtitleParser.Factory.UNSUPPORTED,
			TimestampAdjuster(0),
			payloadReaders,
			timestampSearchBytes,
		)
	}

	/** Creates readers for every stream except audio streams after the main one. Used for a single extractor. */
	private class MainAudioOnlyPayloadReaderFactory(
		private val delegate: TsPayloadReader.Factory,
	) : TsPayloadReader.Factory {
		private var mainAudioCreated = false

		override fun createInitialPayloadReaders() = delegate.createInitialPayloadReaders()

		override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? {
			if (isAudio(streamType, esInfo)) {
				val description = esInfo.audioType == TsPayloadReader.EsInfo.AUDIO_TYPE_VISUAL_IMPAIRED_COMMENTARY
				if (description || mainAudioCreated) {
					Timber.d("Skipping extra Live TV audio stream (type %d, language %s, audio type %d)", streamType, esInfo.language, esInfo.audioType)
					return null
				}
				mainAudioCreated = true
			}

			return delegate.createPayloadReader(streamType, esInfo)
		}

		private fun isAudio(streamType: Int, esInfo: TsPayloadReader.EsInfo): Boolean = when (streamType) {
			TS_STREAM_TYPE_MPA, TS_STREAM_TYPE_MPA_LSF, TS_STREAM_TYPE_AAC_ADTS, TS_STREAM_TYPE_AAC_LATM,
			TS_STREAM_TYPE_AC3, TS_STREAM_TYPE_E_AC3, TS_STREAM_TYPE_DTS, TS_STREAM_TYPE_DTS_HD, TS_STREAM_TYPE_AC4 -> true
			// Private data: DVB signals AC-3, E-AC-3, DTS and AC-4 audio with a descriptor
			TS_STREAM_TYPE_PRIVATE -> hasAudioDescriptor(esInfo.descriptorBytes)
			else -> false
		}

		private fun hasAudioDescriptor(descriptors: ByteArray): Boolean {
			var position = 0
			while (position + 1 < descriptors.size) {
				val tag = descriptors[position].toInt() and 0xFF
				val length = descriptors[position + 1].toInt() and 0xFF
				if (tag in AUDIO_DESCRIPTOR_TAGS) return true
				// Extension descriptor 0x7F carries AC-4 (extension tag 0x15) among others
				if (tag == 0x7F && position + 2 < descriptors.size && (descriptors[position + 2].toInt() and 0xFF) == 0x15) return true
				position += 2 + length
			}
			return false
		}

		companion object {
			private const val TS_STREAM_TYPE_MPA = 0x03
			private const val TS_STREAM_TYPE_MPA_LSF = 0x04
			private const val TS_STREAM_TYPE_PRIVATE = 0x06
			private const val TS_STREAM_TYPE_AAC_ADTS = 0x0F
			private const val TS_STREAM_TYPE_AAC_LATM = 0x11
			private const val TS_STREAM_TYPE_AC3 = 0x81
			private const val TS_STREAM_TYPE_DTS = 0x82
			private const val TS_STREAM_TYPE_DTS_HD = 0x88
			private const val TS_STREAM_TYPE_E_AC3 = 0x87
			private const val TS_STREAM_TYPE_AC4 = 0xAC

			// AC-3, E-AC-3 and DTS descriptors
			private val AUDIO_DESCRIPTOR_TAGS = setOf(0x6A, 0x7A, 0x7B)
		}
	}
}
