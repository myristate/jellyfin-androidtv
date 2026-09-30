package org.jellyfin.androidtv.ui.itemhandling

import android.app.Activity
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.data.repository.AccessProfile
import org.jellyfin.androidtv.data.repository.KidsViewRepository
import org.jellyfin.androidtv.data.repository.KidsViewTarget
import org.jellyfin.androidtv.data.repository.ProfileLevel
import org.jellyfin.androidtv.data.repository.ProfileLevelRepository
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.java.KoinJavaComponent
import timber.log.Timber

/**
 * Menus for the kids view (Finly): choosing which profile or level it shows, and adding an item to or removing it from
 * the restricted profiles and levels.
 */
object KidsViewMenus {
	private val kidsView by lazy { KoinJavaComponent.get<KidsViewRepository>(KidsViewRepository::class.java) }
	private val levels by lazy { KoinJavaComponent.get<ProfileLevelRepository>(ProfileLevelRepository::class.java) }
	private val userRepository by lazy { KoinJavaComponent.get<UserRepository>(UserRepository::class.java) }

	/** The item menu entry that opens [showProfilesMenu]. */
	const val MENU_PROFILES = FinlyMenuIds.CHILDREN_PROFILES

	// Ids in the target menu: Off, then the levels, then the profiles
	private const val TARGET_OFF = 0
	private const val TARGET_LEVEL_FIRST = 100
	private const val TARGET_PROFILE_FIRST = 1000

	@JvmStatic
	fun isAdministrator() = userRepository.currentUser.value?.policy?.isAdministrator == true

	/** For administrators, add the entry for the children's profiles to an item menu, returning the next order. */
	@JvmStatic
	fun addTo(menu: Menu, order: Int, item: BaseItemDto, activity: FragmentActivity): Int {
		if (!isAdministrator() || !RemoveFromProfile.canRemove(item)) return order
		menu.add(0, MENU_PROFILES, order, activity.getString(R.string.kids_view_profiles))
		return order + 1
	}

	/**
	 * Choose what the kids view shows, the current choice is ticked. [onChanged] runs after a choice. The server is asked
	 * for the profiles first, in [owner]'s scope: pass a fragment's view lifecycle owner so leaving the screen meanwhile
	 * cancels the menu.
	 */
	@JvmStatic
	fun showTargetMenu(activity: FragmentActivity, owner: LifecycleOwner, anchor: View, onChanged: Runnable) {
		owner.lifecycleScope.launch {
			levels.refresh()
			val profiles = kidsView.loadProfiles()
			val restricted = kidsView.restrictedLevels()
			val current = kidsView.target.value
			if (!canShowMenu(activity, owner, anchor)) return@launch

			val menu = PopupMenu(activity, anchor, Gravity.END)
			menu.menu.add(0, TARGET_OFF, 0, activity.getString(R.string.kids_view_off))
			restricted.forEachIndexed { index, level ->
				menu.menu.add(0, TARGET_LEVEL_FIRST + index, index + 1, activity.getString(R.string.kids_view_level, level.name))
			}
			profiles.forEachIndexed { index, profile ->
				menu.menu.add(0, TARGET_PROFILE_FIRST + index, 100 + index, profile.name)
			}
			// One choice at a time, with the current one ticked; Off when the kids view is off
			menu.menu.setGroupCheckable(0, true, true)
			menu.menu.findItem(targetMenuId(current, restricted, profiles))?.isChecked = true

			menu.setOnMenuItemClickListener { choice ->
				kidsView.setTarget(
					when (val id = choice.itemId) {
						TARGET_OFF -> null
						in TARGET_LEVEL_FIRST until TARGET_PROFILE_FIRST -> restricted[id - TARGET_LEVEL_FIRST].let {
							KidsViewTarget(levelId = it.id, userId = null, name = activity.getString(R.string.kids_view_level, it.name))
						}

						else -> profiles[id - TARGET_PROFILE_FIRST].let { KidsViewTarget(levelId = null, userId = it.id, name = it.name) }
					}
				)
				onChanged.run()
				true
			}
			menu.show()
		}
	}

	private fun targetMenuId(target: KidsViewTarget?, restricted: List<ProfileLevel>, profiles: List<AccessProfile>): Int {
		if (target == null) return TARGET_OFF
		val key = { id: String? -> id?.replace("-", "")?.lowercase() }
		return when {
			target.userId != null -> profiles.indexOfFirst { key(it.id) == key(target.userId) }.let { if (it < 0) -1 else TARGET_PROFILE_FIRST + it }
			else -> restricted.indexOfFirst { key(it.id) == key(target.levelId) }.let { if (it < 0) -1 else TARGET_LEVEL_FIRST + it }
		}
	}

	/**
	 * Add an item to, or remove it from, each restricted level and profile: Add where some of them can't see it yet,
	 * Remove where some can. The server is asked first, in [owner]'s scope: pass a fragment's view lifecycle owner where
	 * there is one so leaving the screen meanwhile cancels the menu.
	 */
	@JvmStatic
	@JvmOverloads
	fun showProfilesMenu(activity: FragmentActivity, anchor: View?, item: BaseItemDto, owner: LifecycleOwner = activity) {
		owner.lifecycleScope.launch {
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

			val menuAnchor = anchor?.takeIf { it.isAttachedToWindow } ?: activity.currentFocus ?: activity.window.decorView
			if (!canShowMenu(activity, owner, menuAnchor)) return@launch

			val menu = PopupMenu(activity, menuAnchor, Gravity.END)
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

	/**
	 * Whether a menu can still be shown after waiting for the server: the screen may have been left meanwhile, and a
	 * popup on a detached view or a finishing activity crashes.
	 */
	private fun canShowMenu(activity: Activity, owner: LifecycleOwner, anchor: View): Boolean {
		val showing = anchor.isAttachedToWindow && anchor.windowToken != null &&
			!activity.isFinishing && !activity.isDestroyed &&
			owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
		if (!showing) Timber.i("Not showing the kids view menu, the screen was left")
		return showing
	}
}
