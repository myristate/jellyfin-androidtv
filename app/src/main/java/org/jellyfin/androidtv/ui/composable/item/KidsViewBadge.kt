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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.data.repository.KidsViewBadge
import org.jellyfin.androidtv.data.repository.KidsViewRepository
import org.jellyfin.androidtv.data.repository.KidsViewTarget
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.compose.koinInject
import java.util.UUID

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
 * see, faintly when only some of the level's profiles can. Only administrators have the kids view.
 */
@Composable
fun KidsViewBadge(item: BaseItemDto, modifier: Modifier = Modifier) {
	if (item.type !in badgeTypes) return

	val userRepository = koinInject<UserRepository>()
	val user by userRepository.currentUser.collectAsState()
	if (user?.policy?.isAdministrator != true) return

	val kidsView = koinInject<KidsViewRepository>()
	val target by kidsView.target.collectAsState()
	// Nothing below runs, nor watches who can see what, while the kids view is off
	val current = target ?: return

	KidsViewBadgeFor(item.id, current, kidsView, modifier)
}

@Composable
private fun KidsViewBadgeFor(
	itemId: UUID,
	target: KidsViewTarget,
	kidsView: KidsViewRepository,
	modifier: Modifier,
) {
	// Only this item's entry, so answers arriving for other cards don't update this one
	val accessFlow = remember(itemId) { kidsView.accessFlowOf(itemId) }
	val visibleTo by accessFlow.collectAsState(initial = kidsView.accessOf(itemId))

	val badge = kidsView.badge(target, visibleTo)
	LaunchedEffect(itemId, target, badge == null) {
		if (badge == null) kidsView.request(itemId)
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
