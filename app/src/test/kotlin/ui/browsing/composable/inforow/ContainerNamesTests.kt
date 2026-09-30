package org.jellyfin.androidtv.ui.browsing.composable.inforow

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ContainerNamesTests : FunSpec({
	test("demuxer lists get the name people know") {
		containerDisplayName("mov,mp4,m4a,3gp,3g2,mj2") shouldBe "MP4"
		containerDisplayName("matroska,webm") shouldBe "MKV"
		containerDisplayName("mpegts") shouldBe "TS"
	}

	test("single containers") {
		containerDisplayName("mkv") shouldBe "MKV"
		containerDisplayName("mp4") shouldBe "MP4"
		containerDisplayName("mov") shouldBe "MOV"
		containerDisplayName("ts") shouldBe "TS"
		containerDisplayName("m2ts") shouldBe "M2TS"
		containerDisplayName("webm") shouldBe "WEBM"
		containerDisplayName("avi") shouldBe "AVI"
		containerDisplayName("flv") shouldBe "FLV"
	}

	test("nothing to show") {
		containerDisplayName(null) shouldBe null
		containerDisplayName("") shouldBe null
		containerDisplayName(" , ") shouldBe null
	}
})
