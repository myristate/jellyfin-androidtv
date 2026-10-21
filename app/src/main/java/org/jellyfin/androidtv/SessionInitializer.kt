package org.jellyfin.androidtv

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.startup.AppInitializer
import androidx.startup.Initializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.di.KoinInitializer
import org.jellyfin.androidtv.ui.livetv.clearTvManagerOnProfileChange
import org.jellyfin.androidtv.ui.playback.MediaManager

@Suppress("unused")
class SessionInitializer : Initializer<Unit> {
	override fun create(context: Context) {
		val koin = AppInitializer.getInstance(context)
			.initializeComponent(KoinInitializer::class.java)
			.koin

		// Restore system session
		ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
			koin.get<SessionRepository>().restoreSession(destroyOnly = false)
		}

		// (Finly) The Live TV channel list belongs to the profile that loaded it
		ProcessLifecycleOwner.get().lifecycleScope.clearTvManagerOnProfileChange(koin.get())

		// (Finly) The whole app going to the background counts towards locking a PIN protected profile. Moving between
		// the app's own screens, such as to the external player screen and back, doesn't.
		ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
			override fun onStop(owner: LifecycleOwner) {
				// Music playing on means someone is listening, not that the TV was left
				if (!koin.get<MediaManager>().isPlayingAudio) koin.get<SessionRepository>().onBackgrounded()
			}
		})
	}

	override fun dependencies() = listOf(KoinInitializer::class.java)
}
