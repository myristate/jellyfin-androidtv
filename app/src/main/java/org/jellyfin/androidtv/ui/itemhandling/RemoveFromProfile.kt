package org.jellyfin.androidtv.ui.itemhandling

import android.view.Menu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.data.repository.ProfileLevel
import org.jellyfin.androidtv.data.repository.ProfileLevelRepository
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.java.KoinJavaComponent

/**
 * The "Remove from <name>'s profile" and "Remove from all <level> profiles" choices for films and shows, in item menus
 * and on detail pages.
 */
object RemoveFromProfile {
	/** Menu item ids, level choices follow [MENU_LEVEL_FIRST] in the order of [ProfileLevelRepository.offeredLevels]. */
	const val MENU_ME = 900
	const val MENU_LEVEL_FIRST = 901

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

	/** Load the levels in the background, so the next menu offers them. */
	@JvmStatic
	fun refreshLevels(activity: FragmentActivity) {
		activity.lifecycleScope.launch { repository.refresh() }
	}

	/** The choices, as labels with the action to take. */
	@JvmStatic
	fun choices(activity: FragmentActivity): List<Pair<String, Int>> {
		val name = userRepository.currentUser.value?.name.orEmpty()
		return buildList {
			add(activity.getString(R.string.remove_from_profile, name) to MENU_ME)
			// Parents get the children's profiles menu instead, which knows who can see the item
			if (KidsViewMenus.isAdministrator()) return@buildList
			repository.offeredLevels().forEachIndexed { index, level ->
				add(activity.getString(R.string.remove_from_level, level.name) to MENU_LEVEL_FIRST + index)
			}
		}
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
		val level: ProfileLevel? = when {
			menuId == MENU_ME -> null
			menuId >= MENU_LEVEL_FIRST -> repository.offeredLevels().getOrNull(menuId - MENU_LEVEL_FIRST) ?: return false
			else -> return false
		}

		val message = if (level == null) activity.getString(R.string.remove_confirm_profile, item.name)
		else activity.getString(R.string.remove_confirm_level, item.name, level.name)

		AlertDialog.Builder(activity)
			.setTitle(R.string.remove_confirm_title)
			.setMessage(message)
			.setPositiveButton(R.string.remove_button) { _, _ -> remove(activity, item, level, onRemoved) }
			.setNegativeButton(R.string.btn_cancel, null)
			.show()
		return true
	}

	private fun remove(activity: FragmentActivity, item: BaseItemDto, level: ProfileLevel?, onRemoved: Runnable?) {
		activity.lifecycleScope.launch {
			val removed = if (level == null) repository.hideFromMe(item.id) else repository.hideFromLevel(item.id, level.id)
			if (!removed) {
				Toast.makeText(activity, activity.getString(R.string.remove_failed, item.name), Toast.LENGTH_LONG).show()
				return@launch
			}

			Toast.makeText(activity, activity.getString(R.string.remove_done, item.name), Toast.LENGTH_LONG).show()
			// Removing from a level the profile isn't on leaves it here
			val goneHere = level == null || level.id.replace("-", "").equals(repository.ownLevelId, ignoreCase = true)
			if (goneHere) onRemoved?.run()
		}
	}
}
