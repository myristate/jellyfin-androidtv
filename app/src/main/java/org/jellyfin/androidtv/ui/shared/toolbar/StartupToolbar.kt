package org.jellyfin.androidtv.ui.shared.toolbar

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.button.IconButton
import org.jellyfin.androidtv.ui.navigation.ProvideRouter
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.composable.SettingsDialog
import org.jellyfin.androidtv.ui.settings.composable.SettingsRouterContent
import org.jellyfin.androidtv.ui.settings.routes

/**
 * @param serverName shows the add account and change server buttons when not null. They are kept small and out of
 * the way in the toolbar so young kids picking their profile don't press them by accident.
 */
@Composable
fun StartupToolbar(
	openHelp: () -> Unit,
	serverName: String? = null,
	addAccount: () -> Unit = {},
	changeServer: () -> Unit = {},
) {
	var settingsVisible by remember { mutableStateOf(false) }

	Toolbar(
		end = {
			ToolbarButtons {
				if (serverName != null) {
					IconButton(onClick = addAccount) {
						Icon(
							imageVector = ImageVector.vectorResource(R.drawable.ic_user_add),
							contentDescription = stringResource(R.string.add_user),
						)
					}

					IconButton(onClick = changeServer) {
						Icon(
							imageVector = ImageVector.vectorResource(R.drawable.ic_server),
							contentDescription = stringResource(R.string.change_server, serverName),
						)
					}
				}

				IconButton(onClick = openHelp) {
					Icon(
						imageVector = ImageVector.vectorResource(R.drawable.ic_help),
						contentDescription = stringResource(R.string.help),
					)
				}

				IconButton(onClick = { settingsVisible = true }) {
					Icon(
						imageVector = ImageVector.vectorResource(R.drawable.ic_settings),
						contentDescription = stringResource(R.string.lbl_settings),
					)
				}

				Spacer(Modifier.width(8.dp))

				ToolbarClock()
			}
		}
	)


	ProvideRouter(routes, Routes.AUTHENTICATION_FROM_LOGIN) {
		SettingsDialog(
			visible = settingsVisible,
			onDismissRequest = { settingsVisible = false }
		) {
			SettingsRouterContent()
		}
	}
}
