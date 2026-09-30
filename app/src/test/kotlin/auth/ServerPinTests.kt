package org.jellyfin.androidtv.auth.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ServerPinTests : FunSpec({
	test("parseHasPin() reads the server's flag") {
		parseHasPin("""{"Id":"a","HasPin":true}""") shouldBe true
		parseHasPin("""{"Id":"a","HasPin":false}""") shouldBe false
	}

	test("parseHasPin() is null when the server doesn't say") {
		parseHasPin("""{"Id":"a"}""") shouldBe null
		parseHasPin("""{"HasPin":"maybe"}""") shouldBe null
	}

	test("parseHasPin() is null for bodies that aren't a user") {
		parseHasPin("") shouldBe null
		parseHasPin("Error processing request.") shouldBe null
		parseHasPin("[]") shouldBe null
	}

	test("isPinFormat() only accepts four digits") {
		isPinFormat("1234") shouldBe true
		isPinFormat("0000") shouldBe true
		isPinFormat("123") shouldBe false
		isPinFormat("12345") shouldBe false
		isPinFormat("12a4") shouldBe false
		isPinFormat("") shouldBe false
	}
})
