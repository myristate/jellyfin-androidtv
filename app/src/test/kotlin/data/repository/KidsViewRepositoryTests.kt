package org.jellyfin.androidtv.data.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import org.jellyfin.androidtv.auth.repository.Session
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.RawResponse
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

class KidsViewRepositoryTests : FunSpec({
	val child = "c0000000000000000000000000000001"
	fun key(id: UUID) = id.toString().replace("-", "")

	fun accessResponse(ids: List<String>) = RawResponse(
		body = """{"Profiles":[{"Id":"$child","Name":"Kid"}],"Items":{${ids.joinToString(",") { "\"$it\":[\"$child\"]" }}}}"""
			.toByteArray(),
		status = 200,
		headers = emptyMap(),
	)

	fun sessionRepository(session: MutableStateFlow<Session?>) = mockk<SessionRepository>(relaxed = true) {
		every { currentSession } returns session
	}

	suspend fun waitUntil(condition: () -> Boolean) = withTimeout(5_000) {
		while (!condition()) delay(10)
	}

	test("items asked for while a batch is being fetched are fetched next") {
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
		val batches = CopyOnWriteArrayList<List<String>>()
		val firstStarted = CompletableDeferred<Unit>()
		val releaseFirst = CompletableDeferred<Unit>()
		val api = mockk<ApiClient> {
			coEvery { request(any(), any(), any(), any(), any()) } coAnswers {
				val ids = (arg<Map<String, Any?>>(3)["ids"] as String).split(",")
				batches += ids
				if (batches.size == 1) {
					firstStarted.complete(Unit)
					releaseFirst.await()
				}
				accessResponse(ids)
			}
		}
		val repository = KidsViewRepository(
			api = api,
			profileLevelRepository = mockk(relaxed = true),
			sessionRepository = sessionRepository(MutableStateFlow(null)),
			scope = scope,
			batchDelay = 10.milliseconds,
		)

		val first = UUID.randomUUID()
		val second = UUID.randomUUID()
		val third = UUID.randomUUID()

		repository.request(first)
		withTimeout(5_000) { firstStarted.await() }
		// Asked for while the first batch is still with the server
		repository.request(second)
		repository.request(third)
		releaseFirst.complete(Unit)

		waitUntil { repository.accessOf(third) != null && repository.accessOf(second) != null }
		batches.size shouldBe 2
		batches[1] shouldContainExactlyInAnyOrder listOf(key(second), key(third))
		repository.accessOf(first) shouldBe setOf(child)

		// A later item still starts a new batch
		val fourth = UUID.randomUUID()
		repository.request(fourth)
		waitUntil { repository.accessOf(fourth) != null }
		batches.size shouldBe 3

		scope.cancel()
	}

	test("an unexpected body doesn't stop later batches") {
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
		var calls = 0
		val api = mockk<ApiClient> {
			coEvery { request(any(), any(), any(), any(), any()) } coAnswers {
				calls++
				val ids = (arg<Map<String, Any?>>(3)["ids"] as String).split(",")
				if (calls == 1) RawResponse("Error processing request.".toByteArray(), 200, emptyMap())
				else accessResponse(ids)
			}
		}
		val repository = KidsViewRepository(
			api = api,
			profileLevelRepository = mockk(relaxed = true),
			sessionRepository = sessionRepository(MutableStateFlow(null)),
			scope = scope,
			batchDelay = 10.milliseconds,
		)

		val first = UUID.randomUUID()
		repository.request(first)
		waitUntil { calls == 1 }
		delay(50)
		repository.accessOf(first) shouldBe null

		val second = UUID.randomUUID()
		repository.request(second)
		waitUntil { repository.accessOf(second) != null }

		scope.cancel()
	}

	test("everything is forgotten when the profile changes") {
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
		val api = mockk<ApiClient> {
			coEvery { request(any(), any(), any(), any(), any()) } coAnswers {
				accessResponse((arg<Map<String, Any?>>(3)["ids"] as String).split(","))
			}
		}
		val session = MutableStateFlow<Session?>(Session(UUID.randomUUID(), UUID.randomUUID(), "token"))
		val repository = KidsViewRepository(
			api = api,
			profileLevelRepository = mockk(relaxed = true),
			sessionRepository = sessionRepository(session),
			scope = scope,
			batchDelay = 10.milliseconds,
		)

		val item = UUID.randomUUID()
		repository.setTarget(KidsViewTarget(levelId = null, userId = child, name = "Kid"))
		repository.request(item)
		waitUntil { repository.accessOf(item) != null }
		repository.profiles.size shouldBe 1

		session.value = null
		waitUntil { repository.target.value == null }
		repository.accessOf(item) shouldBe null
		repository.profiles shouldBe emptyList()

		scope.cancel()
	}

	test("badge() tells whether all, some or none of a level's profiles can see an item") {
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
		val repository = KidsViewRepository(
			api = mockk(),
			profileLevelRepository = mockk(relaxed = true),
			sessionRepository = sessionRepository(MutableStateFlow(null)),
			scope = scope,
		)
		val target = KidsViewTarget(levelId = null, userId = child, name = "Kid")

		repository.badge(target, null) shouldBe null
		repository.badge(target, emptySet()) shouldBe KidsViewBadge.NONE
		repository.badge(target, setOf(child)) shouldBe KidsViewBadge.ALL

		scope.cancel()
	}
})
