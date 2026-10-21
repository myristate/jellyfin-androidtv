package org.jellyfin.androidtv.ui.startup.fragment

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.add
import androidx.fragment.app.commit
import androidx.fragment.app.replace
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.model.ApiClientErrorLoginState
import org.jellyfin.androidtv.auth.model.AuthenticatedState
import org.jellyfin.androidtv.auth.model.AuthenticatingState
import org.jellyfin.androidtv.auth.model.InvalidPinState
import org.jellyfin.androidtv.auth.model.RefusedLoginState
import org.jellyfin.androidtv.auth.model.PrivateUser
import org.jellyfin.androidtv.auth.model.RequirePinState
import org.jellyfin.androidtv.auth.model.RequireSignInState
import org.jellyfin.androidtv.auth.model.Server
import org.jellyfin.androidtv.auth.model.ServerUnavailableState
import org.jellyfin.androidtv.auth.model.ServerVersionNotSupported
import org.jellyfin.androidtv.auth.model.User
import org.jellyfin.androidtv.auth.repository.AuthenticationRepository
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.auth.repository.ServerUserRepository
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.databinding.FragmentServerBinding
import org.jellyfin.androidtv.ui.card.UserCardView
import org.jellyfin.androidtv.ui.startup.StartupViewModel
import org.jellyfin.androidtv.util.ListAdapter
import org.jellyfin.androidtv.util.apiclient.refusalMessage
import org.jellyfin.androidtv.util.MarkdownRenderer
import org.jellyfin.androidtv.util.createBundle
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class ServerFragment : Fragment() {
	companion object {
		const val ARG_SERVER_ID = "server_id"
	}

	private val startupViewModel: StartupViewModel by activityViewModel()
	private val markdownRenderer: MarkdownRenderer by inject()
	private val authenticationRepository: AuthenticationRepository by inject()
	private val serverUserRepository: ServerUserRepository by inject()
	private val backgroundService: BackgroundService by inject()
	private var _binding: FragmentServerBinding? = null
	private val binding get() = _binding!!

	private val serverIdArgument get() = arguments?.getString(ARG_SERVER_ID)?.ifBlank { null }?.toUUIDOrNull()

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
		val server = serverIdArgument?.let(startupViewModel::getServer)

		if (server == null) {
			navigateFragment<SelectServerFragment>(keepToolbar = true, keepHistory = false)
			return null
		}

		_binding = FragmentServerBinding.inflate(inflater, container, false)

		val userAdapter = UserAdapter(requireContext(), server, startupViewModel, authenticationRepository, serverUserRepository)
		userAdapter.onItemPressed = { user ->
			startupViewModel.authenticate(server, user).onEach { state ->
				when (state) {
					// Ignored states
					AuthenticatingState -> Unit
					AuthenticatedState -> Unit
					InvalidPinState -> Unit
					// Actions
					RequireSignInState -> navigateFragment<UserLoginFragment>(createBundle {
						putString(UserLoginFragment.ARG_SERVER_ID, server.id.toString())
						putString(UserLoginFragment.ARG_USERNAME, user.name)
					})

					RequirePinState -> navigateFragment<UserPinFragment>(createBundle {
						putString(UserPinFragment.ARG_SERVER_ID, server.id.toString())
						putString(UserPinFragment.ARG_USER_ID, user.id.toString())
					})
					// Errors
					is RefusedLoginState -> Toast.makeText(context, requireContext().refusalMessage(state.refusal, state.status), Toast.LENGTH_LONG).show()
					ServerUnavailableState,
					is ApiClientErrorLoginState -> Toast.makeText(context, R.string.refusal_unreachable, Toast.LENGTH_LONG).show()

					is ServerVersionNotSupported -> Toast.makeText(
						context,
						getString(
							R.string.server_issue_outdated_version,
							state.server.version,
							ServerRepository.recommendedServerVersion.toString()
						),
						Toast.LENGTH_LONG
					).show()
				}
			}.launchIn(lifecycleScope)
		}
		binding.users.adapter = userAdapter
		// (Finly) Keep the profiles centred on screen, the grid itself lines them up from the left
		binding.users.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> centerUsers() }

		startupViewModel.users
			.flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
			.onEach { users ->
				userAdapter.items = users

				binding.users.isFocusable = users.any()
				binding.noUsersWarning.isVisible = users.isEmpty()
				binding.root.requestFocus()
			}.launchIn(viewLifecycleOwner.lifecycleScope)

		startupViewModel.loadUsers(server)

		onServerChange(server)

		lifecycleScope.launch {
			lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
				val updated = startupViewModel.updateServer(server)
				if (updated) startupViewModel.getServer(server.id)?.let(::onServerChange)
			}
		}

		return binding.root
	}

	override fun onDestroyView() {
		super.onDestroyView()

		_binding = null
	}

	/**
	 * Pad the profile row so its cards sit in the middle. When there are more profiles than fit on screen the row
	 * keeps no padding and scrolls as usual.
	 */
	private fun centerUsers() {
		val grid = _binding?.users ?: return
		val itemCount = grid.adapter?.itemCount ?: 0
		val cards = (0 until grid.childCount).map { grid.getChildAt(it) }

		val padding = if (itemCount == 0 || cards.size < itemCount) 0 else {
			val contentWidth = cards.maxOf { it.right } - cards.minOf { it.left }
			((grid.width - contentWidth) / 2).coerceAtLeast(0)
		}

		// Changing the padding lays the row out again, only do it when it changes
		if (padding != grid.paddingStart) grid.post { grid.setPaddingRelative(padding, grid.paddingTop, padding, grid.paddingBottom) }
	}

	private fun onServerChange(server: Server) {
		binding.loginDisclaimer.text = server.loginDisclaimer?.let { markdownRenderer.toMarkdownSpanned(it) }

		// The add account and change server buttons are in the toolbar, away from the user profiles
		if (isResumed) startupViewModel.setUserPickerServer(server)

		if (!server.versionSupported) {
			binding.notification.isVisible = true
			binding.notification.text = getString(
				R.string.server_unsupported_notification,
				server.version,
				ServerRepository.recommendedServerVersion.toString(),
			)
		} else if (!server.setupCompleted) {
			binding.notification.isVisible = true
			binding.notification.text = getString(R.string.server_setup_incomplete)
		} else {
			binding.notification.isGone = true
		}
	}

	private inline fun <reified F : Fragment> navigateFragment(
		args: Bundle = createBundle(),
		keepToolbar: Boolean = false,
		keepHistory: Boolean = true,
	) {
		requireActivity()
			.supportFragmentManager
			.commit {
				if (keepToolbar) {
					replace<StartupToolbarFragment>(R.id.content_view)
					add<F>(R.id.content_view, null, args)
				} else {
					replace<F>(R.id.content_view, null, args)
				}

				if (keepHistory) addToBackStack(null)
			}
	}

	override fun onResume() {
		super.onResume()

		startupViewModel.reloadStoredServers()
		backgroundService.clearBackgrounds()

		val server = serverIdArgument?.let(startupViewModel::getServer)
		if (server != null) {
			startupViewModel.loadUsers(server)
			startupViewModel.setUserPickerServer(server)
		} else navigateFragment<SelectServerFragment>(keepToolbar = true)
	}

	override fun onPause() {
		super.onPause()

		startupViewModel.setUserPickerServer(null)
	}

	private class UserAdapter(
		private val context: Context,
		private val server: Server,
		private val startupViewModel: StartupViewModel,
		private val authenticationRepository: AuthenticationRepository,
		private val serverUserRepository: ServerUserRepository,
	) : ListAdapter<User, UserAdapter.ViewHolder>() {
		var onItemPressed: (User) -> Unit = {}

		override fun areItemsTheSame(old: User, new: User): Boolean = old.id == new.id

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
			val cardView = UserCardView(context)

			return ViewHolder(cardView)
		}

		override fun onBindViewHolder(holder: ViewHolder, user: User) {
			holder.cardView.name = user.name
			holder.cardView.image = startupViewModel.getUserImage(server, user)

			// Signing out of or removing a PIN protected profile is only possible from its own settings
			if (user is PrivateUser && user.hasPin) holder.cardView.setPopupMenu { }
			else holder.cardView.setPopupMenu {
				// Logout button
				if (user is PrivateUser && user.accessToken != null) {
					item(context.getString(R.string.lbl_sign_out)) {
						authenticationRepository.logout(user)
					}
				}

				// Remove button
				if (user is PrivateUser) {
					item(context.getString(R.string.lbl_remove)) {
						serverUserRepository.deleteStoredUser(user)
						startupViewModel.loadUsers(server)
					}
				}
			}

			holder.cardView.setOnClickListener {
				onItemPressed(user)
			}
		}

		private class ViewHolder(
			val cardView: UserCardView,
		) : RecyclerView.ViewHolder(cardView)
	}
}

