package org.jellyfin.androidtv

import android.content.Context
import androidx.startup.Initializer
import org.jellyfin.androidtv.util.diagnostics.DiagnosticsLog
import timber.log.Timber

class LogInitializer : Initializer<Unit> {
	override fun create(context: Context) {
		// Enable improved logging for leaking resources
		// https://wh0.github.io/2020/08/12/closeguard.html
		if (BuildConfig.DEBUG) {
			try {
				Class.forName("dalvik.system.CloseGuard")
					.getMethod("setEnabled", Boolean::class.javaPrimitiveType)
					.invoke(null, true)
			} catch (e: ReflectiveOperationException) {
				@Suppress("TooGenericExceptionThrown")
				throw RuntimeException(e)
			}
		}

		// Initialize the logging library
		Timber.plant(Timber.DebugTree())
		// Recent log lines kept in memory for diagnostics reports sent to the server
		Timber.plant(DiagnosticsLog.tree)
		Timber.i("Debug tree planted")
	}

	override fun dependencies() = emptyList<Class<out Initializer<*>>>()
}
