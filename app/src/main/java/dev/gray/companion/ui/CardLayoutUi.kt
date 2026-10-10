package dev.gray.companion.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.gray.companion.core.*

private fun CardRegion.label() = when (this) { CardRegion.CHAT -> "正文下方"; CardRegion.SIDEBAR -> "侧栏"; CardRegion.INPUT -> "输入框上方"; CardRegion.MENU -> "顶部菜单" }

/** Only accepted, self-contained snapshots render fields. Live definitions never reinterpret values. */
@Composable
internal fun CardRegionDisplay(library: Library, session: Session, region: CardRegion,
                               body: ChatMessage? = null, allowLegacyFloor: Boolean = true,
                               modifier: Modifier = Modifier) {
    val resolved = remember(library, session, body) { CardDisplays.resolve(library, session, body).associateBy { it.card.nodeId } }
    val placed = CardDisplays.effectivePlacements(library, session, body).filter { placement ->
        val item = resolved[placement.nodeId]
        !placement.hidden && placement.region == region && item != null &&
            (region != CardRegion.CHAT || body != null &&
                CardDisplays.belongsBelowBody(item, body, allowLegacyFloor)) &&
            region in CardDisplays.allowedRegions(item.card.definition.presentation) &&
            region in CardDisplays.allowedRegions(item.snapshot?.presentation ?: item.card.definition.presentation)
    }.sortedBy { it.order }
    if (placed.isEmpty()) return
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val limit = when (region) { CardRegion.INPUT -> minOf(144.dp, screenHeight * 0.2f); CardRegion.CHAT -> minOf(320.dp, screenHeight * 0.45f); CardRegion.SIDEBAR, CardRegion.MENU -> screenHeight * 0.45f }
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        val availableWidth = maxWidth
        val rows = buildList<List<CardPlacement>> {
            var index = 0
            while (index < placed.size) {
                val first = placed[index]
                val firstCard = resolved.getValue(first.nodeId)
                val second = placed.getOrNull(index + 1)
                val secondCard = second?.let { resolved.getValue(it.nodeId) }
                val canPair = region != CardRegion.INPUT && secondCard != null &&
                    (firstCard.snapshot?.autoAdapt ?: firstCard.card.definition.autoAdapt) && (secondCard.snapshot?.autoAdapt ?: secondCard.card.definition.autoAdapt) &&
                    (firstCard.snapshot?.presentation ?: firstCard.card.definition.presentation) == CardPresentation.CARD &&
                    (secondCard.snapshot?.presentation ?: secondCard.card.definition.presentation) == CardPresentation.CARD &&
                    availableWidth >= ((firstCard.snapshot?.minimumWidthDp ?: firstCard.card.definition.minimumWidthDp) + (secondCard.snapshot?.minimumWidthDp ?: secondCard.card.definition.minimumWidthDp)).dp + 8.dp
                if (canPair && second != null) { add(listOf(first, second)); index += 2 } else { add(listOf(first)); index++ }
            }
        }
        val scroll = rememberScrollState()
        val contentModifier = if (region == CardRegion.CHAT) Modifier.fillMaxWidth()
            else Modifier.fillMaxWidth().heightIn(max = limit).verticalScroll(scroll)
        Column(contentModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                row.forEach { placement -> key(placement.nodeId) { Box(Modifier.weight(1f)) { CardSnapshotPanel(resolved.getValue(placement.nodeId), placement.collapsed, region == CardRegion.INPUT) } } }
            } }
        }
    }
}

@Composable
private fun CardSnapshotPanel(resolved: ResolvedCard, collapsed: Boolean, forceCompact: Boolean) {
    val snapshot = resolved.snapshot
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                collapsed -> Text(snapshot?.title ?: resolved.card.title, style = MaterialTheme.typography.titleSmall)
                resolved.error != null -> Text(resolved.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                snapshot == null -> Text("等待首次生成", style = MaterialTheme.typography.bodySmall)
                else -> InertHtmlView(CardMarkup.render(snapshot, compact = forceCompact), MaterialTheme.colorScheme.onSurface,
                    MaterialTheme.colorScheme.surfaceContainerLow, if (forceCompact) 13 else 14, snapshot.nodeId)
            }
        }
    }
}

/** Menu content uses the same accepted snapshots and placement visibility as other regions. */
internal fun menuCards(library: Library, session: Session): List<ResolvedCard> {
    val resolved = CardDisplays.resolve(library, session).associateBy { it.card.nodeId }
    return CardDisplays.effectivePlacements(library, session)
        .filter { it.region == CardRegion.MENU && !it.hidden }.sortedBy { it.order }
        .mapNotNull { resolved[it.nodeId] }
        .filter { CardRegion.MENU in CardDisplays.allowedRegions(it.card.definition.presentation) &&
            CardRegion.MENU in CardDisplays.allowedRegions(it.snapshot?.presentation ?: it.card.definition.presentation) }
}

@Composable
internal fun CardMenuItems(library: Library, session: Session, onOpen: (String) -> Unit, onClose: () -> Unit) {
    val cards = remember(library, session) { menuCards(library, session) }
    cards.forEach { resolved -> key(resolved.card.nodeId) {
        DropdownMenuItem(
            text = { Text(resolved.snapshot?.title ?: resolved.card.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            onClick = { onClose(); onOpen(resolved.card.nodeId) },
        )
    } }
}

@Composable
internal fun CardMenuDialog(library: Library, session: Session, nodeId: String, onDismiss: () -> Unit) {
    val card = remember(library, session, nodeId) { menuCards(library, session).firstOrNull { it.card.nodeId == nodeId } }
    if (card == null) {
        LaunchedEffect(nodeId) { onDismiss() }
        return
    }
    val configuration = LocalConfiguration.current
    val maxHeight = (configuration.screenHeightDp.dp - 48.dp).coerceAtLeast(120.dp)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 600.dp).fillMaxWidth().heightIn(max = maxHeight),
                shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        CardSnapshotPanel(card, collapsed = false, forceCompact = false)
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
                }
            }
        }
    }
}

/** Fixed region targets, plus button alternatives. No absolute coordinates are persisted. */
@Composable
internal fun CardLayoutPanel(library: Library, session: Session, canEdit: Boolean, onChange: (List<CardPlacement>) -> Unit, onDismiss: () -> Unit) {
    val effective = remember(library, session) { CardDisplays.effectivePlacements(library, session) }
    val configured = remember(library, session) { CardDisplays.configured(library, session) }
    val snapshots = remember(library, session) { CardDisplays.resolve(library, session).associateBy { it.card.nodeId } }
    var editMode by remember(session.id) { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragPosition by remember { mutableStateOf(Offset.Zero) }
    val targets = remember { mutableStateMapOf<CardRegion, Rect>() }
    val currentOnChange by rememberUpdatedState(onChange)
    val currentPlacements by rememberUpdatedState(session.cardPlacements)
    val currentCanEdit by rememberUpdatedState(canEdit)
    val currentConfigured by rememberUpdatedState(configured)
    fun place(nodeId: String, region: CardRegion) {
        if (!currentCanEdit) return
        val definition = currentConfigured.firstOrNull { it.nodeId == nodeId }?.definition ?: return
        if (!definition.userAdjustable || region !in CardDisplays.allowedRegions(definition.presentation)) return
        val placements = currentPlacements
        val previous = placements.firstOrNull { it.nodeId == nodeId }
        if (previous?.region == region && !previous.hidden) return
        currentOnChange(placements.filterNot { it.nodeId == nodeId } + CardPlacement(nodeId, region, (placements.filter { it.region == region }.maxOfOrNull { it.order } ?: -1) + 1, previous?.collapsed ?: false))
    }
    fun reorder(placement: CardPlacement, direction: Int) {
        if (!canEdit) return
        val ordered = effective.filter { it.region == placement.region && configured.firstOrNull { card -> card.nodeId == it.nodeId }?.definition?.userAdjustable == true }.sortedBy { it.order }.toMutableList()
        val index = ordered.indexOfFirst { it.nodeId == placement.nodeId }
        val destination = index + direction
        if (index < 0 || destination !in ordered.indices) return
        val moved = ordered.removeAt(index)
        ordered.add(destination, moved)
        val updated = ordered.mapIndexed { order, item -> item.copy(order = order) }.associateBy { it.nodeId }
        onChange(session.cardPlacements.filterNot { it.nodeId in updated } + updated.values)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Surface(Modifier.width(minOf(maxWidth * 0.94f, 480.dp)).fillMaxHeight(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
                Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("聊天卡片", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text("关闭") }
                    }
                    Row {
                        FilterChip(selected = !editMode, onClick = { editMode = false }, label = { Text("侧栏") })
                        Spacer(Modifier.width(8.dp))
                        FilterChip(selected = editMode, onClick = { editMode = true }, enabled = canEdit, label = { Text("卡片布局") })
                    }
                    if (!canEdit) Text("生成或恢复期间暂不可更改布局。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (editMode) {
                        Text(if (dragging == null) "长按卡片拖到区域，或用区域按钮放置。各区域会自动适配屏幕。" else "松手放到高亮区域", style = MaterialTheme.typography.bodySmall)
                        // Targets stay visible while the tray scrolls.
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CardRegion.entries.forEach { region ->
                                val allowed = dragging?.let { nodeId -> configured.firstOrNull { it.nodeId == nodeId }?.definition?.presentation?.let { region in CardDisplays.allowedRegions(it) } } ?: true
                                val hovered = allowed && dragging != null && targets[region]?.contains(dragPosition) == true
                                Surface(Modifier.weight(1f).heightIn(min = 60.dp).onGloballyPositioned { targets[region] = it.boundsInRoot() }, shape = MaterialTheme.shapes.small, color = if (hovered) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                                    Box(Modifier.padding(8.dp), contentAlignment = Alignment.Center) { Text(if (allowed) region.label() else "此品类不支持", style = MaterialTheme.typography.labelMedium, color = if (allowed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                        }
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("未放置托盘", style = MaterialTheme.typography.titleSmall)
                            val unplaced = configured.filter { card -> effective.none { it.nodeId == card.nodeId && !it.hidden } }
                            if (unplaced.isEmpty()) Text("暂无未放置卡片", style = MaterialTheme.typography.bodySmall)
                            val groups: List<Pair<CardRegion?, List<ConfiguredCard>>> = listOf(null to unplaced) + CardRegion.entries.map { region -> region to configured.filter { card -> effective.any { it.nodeId == card.nodeId && it.region == region && !it.hidden } }.sortedBy { card -> effective.first { it.nodeId == card.nodeId }.order } }
                            groups.forEach { (region, cards) ->
                                if (region != null) Text(region.label(), style = MaterialTheme.typography.titleSmall)
                                cards.forEach { card -> key(card.nodeId) {
                                    val placement = effective.firstOrNull { it.nodeId == card.nodeId }
                                    val adjustable = canEdit && card.definition.userAdjustable
                                    var bounds by remember { mutableStateOf(Rect.Zero) }
                                    val dragModifier = Modifier.onGloballyPositioned { bounds = it.boundsInRoot() }.pointerInput(card.nodeId, adjustable) {
                                        if (adjustable) detectDragGesturesAfterLongPress(
                                            onDragStart = { start -> dragging = card.nodeId; dragPosition = bounds.topLeft + start },
                                            onDrag = { change, amount -> change.consume(); dragPosition += amount },
                                            onDragCancel = { dragging = null },
                                            onDragEnd = {
                                                targets.entries.firstOrNull { it.value.contains(dragPosition) }?.key?.let { place(card.nodeId, it) }
                                                dragging = null
                                            },
                                        )
                                    }
                                    Surface(dragModifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = if (dragging == card.nodeId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(card.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            if (!card.enabled) Text("卡片或所属调用已停用；布局会保留", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            val allowed = CardDisplays.allowedRegions(card.definition.presentation)
                                            val oldSnapshot = snapshots[card.nodeId]?.snapshot
                                            if (placement != null && (placement.region !in allowed || oldSnapshot != null && placement.region !in CardDisplays.allowedRegions(oldSnapshot.presentation))) Text("品类已变化，需要重新放置或生成相应品类的内容。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                            if (adjustable) ChoiceLine<CardRegion?>("显示区域", placement?.takeUnless { it.hidden }?.region, listOf(null to "隐藏") + allowed.map { it to it.label() }) { selected ->
                                                if (selected == null) onChange(session.cardPlacements.filterNot { it.nodeId == card.nodeId } + (placement ?: CardPlacement(card.nodeId, card.definition.defaultRegion ?: allowed.first())).copy(hidden = true)) else place(card.nodeId, selected)
                                            } else Text("${placement?.region?.label() ?: "未放置"} · 作者固定布局", style = MaterialTheme.typography.bodySmall)
                                            if (placement != null) {
                                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                    TextButton(onClick = { reorder(placement, -1) }, enabled = adjustable) { Text("上移") }
                                                    TextButton(onClick = { reorder(placement, 1) }, enabled = adjustable) { Text("下移") }
                                                    Spacer(Modifier.weight(1f))
                                                    TextButton(onClick = { onChange(session.cardPlacements.filterNot { it.nodeId == card.nodeId } + placement.copy(collapsed = !placement.collapsed)) }, enabled = adjustable) { Text(if (placement.collapsed) "展开" else "折叠") }
                                                }
                                            }
                                        }
                                    }
                                } }
                            }
                            val orphanCount = session.cardPlacements.count { placement -> configured.none { it.nodeId == placement.nodeId } }
                            if (orphanCount > 0) Text("另保留 $orphanCount 个暂未加载的卡片布局；重新加载后恢复。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CardRegionDisplay(library, session, CardRegion.SIDEBAR)
                    }
                }
            }
        }
    }
}
