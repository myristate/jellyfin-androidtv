package org.jellyfin.androidtv.ui.playback

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class LiveStreamOpeningTests : FunSpec({
	test("a stream opened after the caller gave up is released") {
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		val openStarted = CompletableDeferred<Unit>()
		val finishOpen = CompletableDeferred<Unit>()
		val released = CompletableDeferred<String>()

		coroutineScope {
			val caller = launch {
				openDetached(scope = scope, release = { stream: String -> released.complete(stream) }) {
					openStarted.complete(Unit)
					finishOpen.await()
					"stream-1"
				}
				error("The caller should have been cancelled")
			}

			withTimeout(5_000) { openStarted.await() }
			// The highlight moved on while the server was still tuning
			caller.cancelAndJoin()
			finishOpen.complete(Unit)
		}

		withTimeout(5_000) { released.await() } shouldBe "stream-1"
		scope.cancel()
	}

	test("a stream the caller received is not released") {
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		var released = false

		val stream = openDetached(scope = scope, release = { _: String -> released = true }) { "stream-2" }

		stream shouldBe "stream-2"
		delay(50)
		released shouldBe false
		scope.cancel()
	}

	test("errors from opening reach the caller") {
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

		shouldThrow<IllegalStateException> {
			coroutineScope {
				async {
					openDetached<String>(scope = scope, release = {}) { throw IllegalStateException("busy") }
				}.await()
			}
		}
		scope.cancel()
	}
})
