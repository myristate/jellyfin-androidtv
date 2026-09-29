package org.jellyfin.androidtv.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class ProfilePinTests : FunSpec({
	test("ProfilePin.isValid() only accepts four digits") {
		ProfilePin.isValid("1234") shouldBe true
		ProfilePin.isValid("0000") shouldBe true
		ProfilePin.isValid("123") shouldBe false
		ProfilePin.isValid("12345") shouldBe false
		ProfilePin.isValid("12a4") shouldBe false
		ProfilePin.isValid("") shouldBe false
	}

	test("ProfilePin.verify() accepts the right PIN only") {
		val hash = ProfilePin.hash("4821")

		ProfilePin.verify("4821", hash) shouldBe true
		ProfilePin.verify("4820", hash) shouldBe false
		ProfilePin.verify("1284", hash) shouldBe false
	}

	test("ProfilePin.hash() salts every hash and never stores the PIN") {
		val first = ProfilePin.hash("1111")
		val second = ProfilePin.hash("1111")

		first shouldNotBe second
		first.contains("1111") shouldBe false
		ProfilePin.verify("1111", second) shouldBe true
	}

	test("ProfilePin.verify() rejects malformed hashes") {
		ProfilePin.verify("1234", "") shouldBe false
		ProfilePin.verify("1234", "not-a-hash") shouldBe false
		ProfilePin.verify("1234", "10000:zz:zz") shouldBe false
	}
})
