package org.jellyfin.androidtv.ui.startup.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.add
import androidx.fragment.app.commit
import androidx.fragment.app.replace
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.model.Server
import org.jellyfin.androidtv.ui.shared.toolbar.StartupToolbar
import org.jellyfin.androidtv.ui.startup.StartupViewModel
import org.jellyfin.androidtv.util.createBundle
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class StartupToolbarFragment : Fragment() {
	private val startupViewModel: StartupViewModel by activityViewModel()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			val server by startupViewModel.userPickerServer.collectAsState()

			StartupToolbar(
				openHelp = {
					parentFragmentManager.commit {
						addToBackStack(null)
						replace<ConnectHelpAlertFragment>(R.id.content_view)
					}
				},
				serverName = server?.name,
				addAccount = { server?.let(::openAddAccount) },
				changeServer = ::openServerSelection,
			)
		}
	}

	private fun openAddAccount(server: Server) = parentFragmentManager.commit {
		replace<UserLoginFragment>(R.id.content_view, null, createBundle {
			putString(UserLoginFragment.ARG_SERVER_ID, server.id.toString())
			putString(UserLoginFragment.ARG_USERNAME, null)
		})
		addToBackStack(null)
	}

	private fun openServerSelection() = parentFragmentManager.commit {
		replace<StartupToolbarFragment>(R.id.content_view)
		add<SelectServerFragment>(R.id.content_view)
		addToBackStack(null)
	}
}
