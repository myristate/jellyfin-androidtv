package org.jellyfin.androidtv.ui.itemhandling

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.util.KeyProcessor

class FinlyMenuIdsTests : FunSpec({
	val appIds = listOf(
		KeyProcessor.MENU_MARK_FAVORITE,
		KeyProcessor.MENU_UNMARK_FAVORITE,
		KeyProcessor.MENU_MARK_PLAYED,
		KeyProcessor.MENU_UNMARK_PLAYED,
		KeyProcessor.MENU_PLAY,
		KeyProcessor.MENU_PLAY_SHUFFLE,
		KeyProcessor.MENU_PLAY_FIRST_UNWATCHED,
		KeyProcessor.MENU_ADD_QUEUE,
		KeyProcessor.MENU_ADVANCE_QUEUE,
		KeyProcessor.MENU_REMOVE_FROM_QUEUE,
		KeyProcessor.MENU_GOTO_NOW_PLAYING,
		KeyProcessor.MENU_INSTANT_MIX,
		KeyProcessor.MENU_CLEAR_QUEUE,
		KeyProcessor.MENU_TOGGLE_SHUFFLE,
	)

	test("Finly's item menu ids are all different") {
		FinlyMenuIds.all.toSet().size shouldBe FinlyMenuIds.all.size
	}

	test("Finly's item menu ids don't clash with the app's own") {
		FinlyMenuIds.all.filter { it in appIds } shouldBe emptyList()
	}

	test("the menu helpers use the shared ids") {
		RemoveFromProfile.MENU_ME shouldBe FinlyMenuIds.REMOVE_FROM_ME
		KidsViewMenus.MENU_PROFILES shouldBe FinlyMenuIds.CHILDREN_PROFILES
		ReportProblem.MENU_REPORT shouldBe FinlyMenuIds.REPORT_PROBLEM
	}
})
