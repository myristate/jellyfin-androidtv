package org.jellyfin.androidtv.ui.itemhandling

import android.view.Gravity
import android.view.Menu
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jellyfin.androidtv.R
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.LocationType
import org.koin.java.KoinJavaComponent
import timber.log.Timber

@Serializable
private data class ReportBody(@SerialName("Problem") val problem: String)

/**
 * Report a problem with a film or episode (Finly), such as a corrupted picture or the wrong language, for an
 * administrator to look at on the dashboard.
 */
object ReportProblem {
	/** The item menu entry that opens the list of problems. */
	const val MENU_REPORT = 960

	private val reportableTypes = setOf(
		BaseItemKind.MOVIE,
		BaseItemKind.EPISODE,
		BaseItemKind.VIDEO,
		BaseItemKind.MUSIC_VIDEO,
	)

	/** The problems as the server names them, with what to show. */
	private val problems = listOf(
		"Video" to R.string.report_problem_video,
		"Audio" to R.string.report_problem_audio,
		"WrongLanguage" to R.string.report_problem_language,
		"Subtitles" to R.string.report_problem_subtitles,
		"Other" to R.string.report_problem_other,
	)

	private val api by lazy { KoinJavaComponent.get<ApiClient>(ApiClient::class.java) }

	@JvmStatic
	fun canReport(item: BaseItemDto) = item.type in reportableTypes && item.locationType != LocationType.VIRTUAL

	/** Add the entry to an item menu, returning the next order. */
	@JvmStatic
	fun addTo(menu: Menu, order: Int, item: BaseItemDto, activity: FragmentActivity): Int {
		if (!canReport(item)) return order
		menu.add(0, MENU_REPORT, order, activity.getString(R.string.report_problem))
		return order + 1
	}

	/** Ask what is wrong and send the report. */
	@JvmStatic
	fun show(activity: FragmentActivity, anchor: View?, item: BaseItemDto) {
		val menu = PopupMenu(activity, anchor ?: activity.currentFocus ?: activity.window.decorView, Gravity.END)
		problems.forEachIndexed { index, (_, label) -> menu.menu.add(0, index, index, label) }
		menu.setOnMenuItemClickListener { choice ->
			val problem = problems[choice.itemId].first
			activity.lifecycleScope.launch {
				val sent = send(item, problem)
				Toast.makeText(
					activity,
					if (sent) R.string.report_problem_sent else R.string.report_problem_failed,
					Toast.LENGTH_LONG
				).show()
			}
			true
		}
		menu.show()
	}

	private suspend fun send(item: BaseItemDto, problem: String) = withContext(Dispatchers.IO) {
		try {
			api.request(HttpMethod.POST, "/Items/${item.id}/Reports", requestBody = ReportBody(problem))
			true
		} catch (err: ApiClientException) {
			Timber.e(err, "Unable to report a problem with ${item.id}")
			false
		}
	}
}
