package org.jellyfin.androidtv.ui.itemhandling

import android.view.Gravity
import android.view.Menu
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.data.repository.KidsViewRepository
import org.jellyfin.androidtv.data.repository.KidsViewTarget
import org.jellyfin.androidtv.data.repository.ProfileLevelRepository
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.java.KoinJavaComponent

/**
 * Menus for the kids view (Finly): choosing which profile or level it shows, and adding an item to or removing it from
 * the restricted profiles and levels.
 */
object KidsViewMenus {
	private val kidsView by lazy { KoinJavaComponent.get<KidsViewRepository>(KidsViewRepository::class.java) }
	private val levels by lazy { KoinJavaComponent.get<ProfileLevelRepository>(ProfileLevelRepository::class.java) }
	private val userRepository by lazy { KoinJavaComponent.get<UserRepository>(UserRepository::class.java) }

	/** The item menu entry that opens [showProfilesMenu]. */
	const val MENU_PROFILES = 950

	@JvmStatic
	fun isAdministrator() = userRepository.currentUser.value?.policy?.isAdministrator == true

	/** For administrators, add the entry for the children's profiles to an item menu, returning the next order. */
	@JvmStatic
	fun addTo(menu: Menu, order: Int, item: BaseItemDto, activity: FragmentActivity): Int {
		if (!isAdministrator() || !RemoveFromProfile.canRemove(item)) return order
		menu.add(0, MENU_PROFILES, order, activity.getString(R.string.kids_view_profiles))
		return order + 1
	}

	/** Choose what the kids view shows, [onChanged] runs after a choice. */
	@JvmStatic
	fun showTargetMenu(activity: FragmentActivity, anchor: View, onChanged: Runnable) {
		activity.lifecycleScope.launch {
			levels.refresh()
			val profiles = kidsView.loadProfiles()
			val restricted = kidsView.restrictedLevels()
			val current = kidsView.target.value

			val menu = PopupMenu(activity, anchor, Gravity.END)
			menu.menu.add(0, 0, 0, activity.getString(R.string.kids_view_off))
			restricted.forEachIndexed { index, level ->
				menu.menu.add(0, 100 + index, index + 1, activity.getString(R.string.kids_view_level, level.name))
			}
			profiles.forEachIndexed { index, profile ->
				menu.menu.add(0, 1000 + index, 100 + index, profile.name)
			}
			current?.let { menu.menu.setGroupCheckable(0, true, true) }

			menu.setOnMenuItemClickListener { choice ->
				kidsView.setTarget(
					when (val id = choice.itemId) {
						0 -> null
						in 100 until 1000 -> restricted[id - 100].let {
							KidsViewTarget(levelId = it.id, userId = null, name = activity.getString(R.string.kids_view_level, it.name))
						}
						else -> profiles[id - 1000].let { KidsViewTarget(levelId = null, userId = it.id, name = it.name) }
					}
				)
				onChanged.run()
				true
			}
			menu.show()
		}
	}

	/**
	 * Add an item to, or remove it from, each restricted level and profile: Add where some of them can't see it yet,
	 * Remove where some can.
	 */
	@JvmStatic
	fun showProfilesMenu(activity: FragmentActivity, anchor: View?, item: BaseItemDto) {
		activity.lifecycleScope.launch {
			levels.refresh()
			val visibleTo = kidsView.accessFor(item.id)
			val profiles = kidsView.profiles
			val key = { id: String? -> id?.replace("-", "")?.lowercase() }

			data class Choice(val label: String, val userId: String?, val levelId: String?, val add: Boolean)

			val choices = buildList {
				for (level in kidsView.restrictedLevels()) {
					val members = profiles.filter { key(it.levelId) == key(level.id) }
					if (members.isEmpty()) continue
					if (!members.all { key(it.id) in visibleTo }) add(Choice(activity.getString(R.string.add_to_level, level.name), null, level.id, true))
					if (members.any { key(it.id) in visibleTo }) add(Choice(activity.getString(R.string.remove_from_level, level.name), null, level.id, false))
				}
				for (profile in profiles) {
					val sees = key(profile.id) in visibleTo
					add(
						if (sees) Choice(activity.getString(R.string.remove_from_profile, profile.name), profile.id, null, false)
						else Choice(activity.getString(R.string.add_to_profile, profile.name), profile.id, null, true)
					)
				}
			}
			if (choices.isEmpty()) return@launch

			val menu = PopupMenu(activity, anchor ?: activity.currentFocus ?: activity.window.decorView, Gravity.END)
			choices.forEachIndexed { index, choice -> menu.menu.add(0, index, index, choice.label) }
			menu.setOnMenuItemClickListener { menuItem ->
				val choice = choices[menuItem.itemId]
				activity.lifecycleScope.launch {
					val done = if (choice.add) kidsView.allow(item.id, choice.userId, choice.levelId)
					else kidsView.hide(item.id, choice.userId, choice.levelId)
					val message = when {
						!done -> activity.getString(R.string.remove_failed, item.name)
						choice.add -> activity.getString(R.string.add_done, item.name)
						else -> activity.getString(R.string.remove_done, item.name)
					}
					Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
				}
				true
			}
			menu.show()
		}
	}
}
