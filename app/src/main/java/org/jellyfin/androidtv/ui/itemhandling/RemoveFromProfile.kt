package org.jellyfin.androidtv.ui.itemhandling

import android.view.Menu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.data.repository.ProfileLevelRepository
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.java.KoinJavaComponent

/**
 * The "Remove from <name>'s profile" choice for films and shows, in item menus and on detail pages. Only the signed
 * in profile's own list can be changed here: removing from every profile on a level is for parents, who do that from
 * the children's profiles menu ([KidsViewMenus]), and the server refuses it for anyone else.
 */
object RemoveFromProfile {
	/** Menu item id of the choice, see [FinlyMenuIds]. */
	const val MENU_ME = FinlyMenuIds.REMOVE_FROM_ME

	private val removableTypes = setOf(
		BaseItemKind.MOVIE,
		BaseItemKind.SERIES,
		BaseItemKind.SEASON,
		BaseItemKind.EPISODE,
		BaseItemKind.VIDEO,
		BaseItemKind.MUSIC_VIDEO,
		BaseItemKind.BOX_SET,
	)

	private val repository by lazy { KoinJavaComponent.get<ProfileLevelRepository>(ProfileLevelRepository::class.java) }
	private val userRepository by lazy { KoinJavaComponent.get<UserRepository>(UserRepository::class.java) }

	@JvmStatic
	fun canRemove(item: BaseItemDto) = item.type in removableTypes

	/** The choices, as labels with the menu item id to hand to [handle]. */
	@JvmStatic
	fun choices(activity: FragmentActivity): List<Pair<String, Int>> {
		val name = userRepository.currentUser.value?.name.orEmpty()
		return listOf(activity.getString(R.string.remove_from_profile, name) to MENU_ME)
	}

	/** Add the choices to a menu, returning the next order. */
	@JvmStatic
	fun addTo(menu: Menu, order: Int, item: BaseItemDto, activity: FragmentActivity): Int {
		if (!canRemove(item)) return order
		var next = order
		for ((label, id) in choices(activity)) menu.add(0, id, next++, label)
		return next
	}

	/**
	 * Handle a chosen menu item, asking first. [onRemoved] runs when the item is gone from the signed in profile.
	 * @return whether the id was one of these choices.
	 */
	@JvmStatic
	@JvmOverloads
	fun handle(activity: FragmentActivity, menuId: Int, item: BaseItemDto, onRemoved: Runnable? = null): Boolean {
		if (menuId != MENU_ME) return false

		AlertDialog.Builder(activity)
			.setTitle(R.string.remove_confirm_title)
			.setMessage(activity.getString(R.string.remove_confirm_profile, item.name))
			.setPositiveButton(R.string.remove_button) { _, _ -> remove(activity, item, onRemoved) }
			.setNegativeButton(R.string.btn_cancel, null)
			.show()
		return true
	}

	private fun remove(activity: FragmentActivity, item: BaseItemDto, onRemoved: Runnable?) {
		activity.lifecycleScope.launch {
			if (!repository.hideFromMe(item.id)) {
				Toast.makeText(activity, activity.getString(R.string.remove_failed, item.name), Toast.LENGTH_LONG).show()
				return@launch
			}

			Toast.makeText(activity, activity.getString(R.string.remove_done, item.name), Toast.LENGTH_LONG).show()
			onRemoved?.run()
		}
	}
}
