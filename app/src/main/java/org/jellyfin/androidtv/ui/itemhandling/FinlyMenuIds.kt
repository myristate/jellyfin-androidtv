package org.jellyfin.androidtv.ui.itemhandling

/**
 * Ids of Finly's entries in the item menus (Finly), kept together so they can't clash with each other or with the
 * app's own entries (KeyProcessor's MENU_ ids, which are small numbers).
 */
object FinlyMenuIds {
	/** Remove from <name>'s profile, see [RemoveFromProfile]. */
	const val REMOVE_FROM_ME = 900

	/** Children's profiles…, see [KidsViewMenus]. */
	const val CHILDREN_PROFILES = 950

	/** Report a problem, see [ReportProblem]. */
	const val REPORT_PROBLEM = 960

	val all = listOf(REMOVE_FROM_ME, CHILDREN_PROFILES, REPORT_PROBLEM)
}
