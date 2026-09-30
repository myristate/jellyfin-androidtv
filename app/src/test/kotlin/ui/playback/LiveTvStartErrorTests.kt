package org.jellyfin.androidtv.ui.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.data.compat.PlaybackException
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.jellyfin.sdk.model.api.PlaybackErrorCode
import java.net.SocketTimeoutException

class LiveTvStartErrorTests : FunSpec({
	// PlaybackManager hands errors to the controller wrapped like this
	fun wrapped(error: Throwable) = Exception(error)

	test("off air is not retried") {
		LiveTvStartError.classify(wrapped(InvalidStatusException(503))) shouldBe LiveTvStartError.OFF_AIR
		LiveTvStartError.OFF_AIR.retry shouldBe false
	}

	test("busy tuners, server and tuner failures and timeouts are retried") {
		LiveTvStartError.classify(wrapped(InvalidStatusException(409))) shouldBe LiveTvStartError.TUNERS_BUSY
		LiveTvStartError.classify(wrapped(InvalidStatusException(500))) shouldBe LiveTvStartError.SERVER
		LiveTvStartError.classify(wrapped(InvalidStatusException(502))) shouldBe LiveTvStartError.SERVER
		LiveTvStartError.classify(wrapped(InvalidStatusException(504))) shouldBe LiveTvStartError.SERVER
		LiveTvStartError.classify(wrapped(TimeoutException("slow", null))) shouldBe LiveTvStartError.TIMEOUT
		LiveTvStartError.classify(wrapped(ApiClientException("io", SocketTimeoutException()))) shouldBe LiveTvStartError.TIMEOUT

		listOf(LiveTvStartError.TUNERS_BUSY, LiveTvStartError.SERVER, LiveTvStartError.TIMEOUT).forEach {
			it.retry shouldBe true
		}
	}

	test("a parental block or an unplayable stream is not retried") {
		val notAllowed = PlaybackException().apply { errorCode = PlaybackErrorCode.NOT_ALLOWED }
		val incompatible = PlaybackException().apply { errorCode = PlaybackErrorCode.NO_COMPATIBLE_STREAM }

		LiveTvStartError.classify(wrapped(notAllowed)) shouldBe LiveTvStartError.OTHER
		LiveTvStartError.classify(wrapped(incompatible)) shouldBe LiveTvStartError.OTHER
		LiveTvStartError.OTHER.retry shouldBe false
	}

	test("refused requests and unknown errors are not retried") {
		LiveTvStartError.classify(wrapped(InvalidStatusException(401))) shouldBe LiveTvStartError.OTHER
		LiveTvStartError.classify(wrapped(InvalidStatusException(403))) shouldBe LiveTvStartError.OTHER
		LiveTvStartError.classify(wrapped(InvalidStatusException(404))) shouldBe LiveTvStartError.OTHER
		LiveTvStartError.classify(IllegalStateException()) shouldBe LiveTvStartError.OTHER
		LiveTvStartError.classify(null) shouldBe LiveTvStartError.OTHER
	}
})
