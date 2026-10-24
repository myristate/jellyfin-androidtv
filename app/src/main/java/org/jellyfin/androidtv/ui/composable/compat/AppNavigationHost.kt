package org.jellyfin.androidtv.ui.composable.compat

import android.content.Context
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withStarted
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.ui.browsing.DestinationFragmentView
import org.jellyfin.androidtv.ui.navigation.NavigationAction
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.koin.compose.koinInject
import timber.log.Timber

@Composable
fun AppNavigationHost(
	modifier: Modifier = Modifier,
	navigationRepository: NavigationRepository = koinInject(),
) {
	val factory = remember { AppNavigationHostViewFactory() }

	val canGoBack by remember {
		navigationRepository.currentAction.map { navigationRepository.canGoBack }.distinctUntilChanged()
	}.collectAsState(navigationRepository.canGoBack)

	BackHandler(canGoBack) { navigationRepository.goBack() }

	AndroidView(
		factory = factory,
		modifier = modifier,
	)

	val lifecycle = LocalLifecycleOwner.current.lifecycle
	LaunchedEffect(Unit) {
		// (Finly) Only change screens while the activity is on screen: a screen swapped in while MainActivity was hidden,
		// such as the home screen opened again from the profiles screen, could be left without a layout pass and stay
		// black. Actions are queued so none is lost while waiting.
		val pending = Channel<NavigationAction>(Channel.UNLIMITED)
		launch { navigationRepository.currentAction.collect(pending::send) }
		for (action in pending) {
			if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) Timber.i("Waiting for the app to show before navigating")
			lifecycle.withStarted {
				when (action) {
					is NavigationAction.NavigateFragment -> factory.view.navigate(action)
					NavigationAction.GoBack -> factory.view.goBack()
					NavigationAction.Nothing -> Unit
				}
			}
		}
	}
}

private class AppNavigationHostViewFactory : (Context) -> View {
	private var _view: DestinationFragmentView? = null

	val view get() = requireNotNull(_view)

	override operator fun invoke(
		context: Context
	): View = DestinationFragmentView(context).also { view ->
		_view = view
	}
}
