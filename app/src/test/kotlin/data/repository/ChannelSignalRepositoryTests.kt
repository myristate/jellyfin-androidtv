package org.jellyfin.androidtv.data.repository

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.mockk
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import java.util.UUID

class ChannelSignalRepositoryTests : FunSpec({
	val channel = UUID.fromString("0A1B2C3D-0000-4000-8000-00000000ABCD")

	fun response(body: String) = RawResponse(body.toByteArray(), 200, emptyMap())

	test("parseAll reads every channel and normalises the ids") {
		val signals = ChannelSignalRepository.parseAll(
			"""
			{
				"WeakQualityThreshold": 80,
				"Channels": {
					"0A1B2C3D00004000800000000000ABCD": { "Strength": 84, "Quality": 100, "SymbolQuality": null, "MeasuredAt": "2026-10-01T12:00:00Z", "IsLive": true, "IsWeak": false },
					"11111111-1111-1111-1111-111111111111": { "Strength": 61.6, "Quality": 42, "SymbolQuality": 90, "MeasuredAt": "2026-10-01T11:00:00Z", "IsLive": false, "IsWeak": true, "Extra": 1 }
				}
			}
			""".trimIndent()
		)

		signals.weakQualityThreshold shouldBe 80.0
		signals.channels.keys shouldBe setOf("0a1b2c3d00004000800000000000abcd", "11111111111111111111111111111111")

		val first = signals.forChannel(channel)
		first shouldNotBe null
		first!!.strengthPercent shouldBe 84
		first.qualityPercent shouldBe 100
		first.symbolQuality shouldBe null
		first.isLive shouldBe true
		first.isWeak shouldBe false

		val weak = signals.forChannel(UUID.fromString("11111111-1111-1111-1111-111111111111"))!!
		weak.isWeak shouldBe true
		weak.strengthPercent shouldBe 62
		weak.qualityPercent shouldBe 42
		weak.symbolQuality shouldBe 90.0
	}

	test("a channel without a reading is unknown") {
		val signals = ChannelSignalRepository.parseAll("""{ "WeakQualityThreshold": 80, "Channels": {} }""")
		signals.forChannel(channel) shouldBe null
		ChannelSignalRepository.parseAll("{}").channels shouldBe emptyMap()
	}

	test("parseOne reads a single reading") {
		val signal = ChannelSignalRepository.parseOne(
			"""{ "Strength": 84, "Quality": 100, "SymbolQuality": 100, "MeasuredAt": "2026-10-01T12:00:00Z", "IsLive": true, "IsWeak": false }"""
		)
		signal.strengthPercent shouldBe 84
		signal.qualityPercent shouldBe 100
		signal.isLive shouldBe true
	}

	test("an unexpected body throws an IllegalArgumentException") {
		shouldThrow<IllegalArgumentException> { ChannelSignalRepository.parseAll("Error processing request.") }
		shouldThrow<IllegalArgumentException> { ChannelSignalRepository.parseOne("[]") }
	}

	test("getAll gives the readings, or null when the server fails") {
		val api = mockk<ApiClient>()
		val repository = ChannelSignalRepository(api)

		coEvery { api.request(any(), "/LiveTv/ChannelSignal", any(), any(), any()) } returns
			response("""{ "Channels": { "0a1b2c3d00004000800000000000abcd": { "Strength": 50, "Quality": 60, "IsWeak": true } } }""")
		repository.getAll()!!.forChannel(channel)!!.isWeak shouldBe true

		coEvery { api.request(any(), "/LiveTv/ChannelSignal", any(), any(), any()) } throws InvalidStatusException(404)
		repository.getAll() shouldBe null

		coEvery { api.request(any(), "/LiveTv/ChannelSignal", any(), any(), any()) } returns response("<html></html>")
		repository.getAll() shouldBe null
	}

	test("get asks for one channel and gives null when it is unknown") {
		val api = mockk<ApiClient>()
		val repository = ChannelSignalRepository(api)
		val path = "/LiveTv/ChannelSignal/0a1b2c3d00004000800000000000abcd"

		coEvery { api.request(any(), path, any(), any(), any()) } returns
			response("""{ "Strength": 84, "Quality": 100, "IsLive": true, "IsWeak": false }""")
		repository.get(channel)!!.qualityPercent shouldBe 100

		coEvery { api.request(any(), path, any(), any(), any()) } throws InvalidStatusException(404)
		repository.get(channel) shouldBe null

		coEvery { api.request(any(), path, any(), any(), any()) } returns response("not json")
		repository.get(channel) shouldBe null
	}
})
