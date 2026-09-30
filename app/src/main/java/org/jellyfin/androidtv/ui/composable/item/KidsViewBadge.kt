package org.jellyfin.androidtv.ui.composable.item

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.KidsViewBadge
import org.jellyfin.androidtv.data.repository.KidsViewRepository
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.compose.koinInject

private val badgeTypes = setOf(
	BaseItemKind.MOVIE,
	BaseItemKind.SERIES,
	BaseItemKind.SEASON,
	BaseItemKind.EPISODE,
	BaseItemKind.VIDEO,
	BaseItemKind.MUSIC_VIDEO,
	BaseItemKind.BOX_SET,
)

private val badgeColor = Color(0xFF00A4DC)

/**
 * The kids view badge (Finly): while a parent has the kids view on, marks the items the chosen profile or level can
 * see, faintly when only some of the level's profiles can.
 */
@Composable
fun KidsViewBadge(item: BaseItemDto, modifier: Modifier = Modifier) {
	if (item.type !in badgeTypes) return

	val kidsView = koinInject<KidsViewRepository>()
	val target by kidsView.target.collectAsState()
	val access by kidsView.access.collectAsState()
	val current = target ?: return

	val badge = kidsView.badge(item.id, current, access)
	LaunchedEffect(item.id, current, badge == null) {
		if (badge == null) kidsView.request(item.id)
	}
	if (badge == null || badge == KidsViewBadge.NONE) return

	Box(
		contentAlignment = Alignment.Center,
		modifier = modifier
			.padding(4.dp)
			.size(22.dp)
			.background(if (badge == KidsViewBadge.ALL) badgeColor else badgeColor.copy(alpha = 0.45f), CircleShape)
	) {
		Image(
			painter = painterResource(R.drawable.ic_child_care),
			contentDescription = null,
			modifier = Modifier.size(16.dp),
		)
	}
}
