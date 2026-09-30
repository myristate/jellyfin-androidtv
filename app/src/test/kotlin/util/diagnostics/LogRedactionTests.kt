package org.jellyfin.androidtv.util.diagnostics

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LogRedactionTests : FunSpec({
	test("the access token is taken out of stream URLs") {
		LogRedaction.redact("http://192.0.2.1:8096/Videos/abc/stream?static=true&api_key=0123456789abcdef&Tag=x") shouldBe
			"http://192.0.2.1:8096/Videos/abc/stream?static=true&api_key=***&Tag=x"
		LogRedaction.redact("http://host/Videos/abc/master.m3u8?ApiKey=secret") shouldBe
			"http://host/Videos/abc/master.m3u8?ApiKey=***"
		LogRedaction.redact("http://host/a?access_token=secret#frag") shouldBe "http://host/a?access_token=***#frag"
	}

	test("every token in a line is taken out") {
		LogRedaction.redact("from http://h/a?api_key=one to http://h/b?x=1&api_key=two") shouldBe
			"from http://h/a?api_key=*** to http://h/b?x=1&api_key=***"
	}

	test("lines without a token are left alone") {
		LogRedaction.redact("Video path set to: http://host/Videos/abc/stream?static=true") shouldBe
			"Video path set to: http://host/Videos/abc/stream?static=true"
		LogRedaction.redact("monkey=banana") shouldBe "monkey=banana"
	}
})
