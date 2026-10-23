package dev.local.artistgallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import dev.local.artistgallery.model.CoverRef
import dev.local.artistgallery.data.preview.PreviewDecoder
import dev.local.artistgallery.model.MediaType
import dev.local.artistgallery.model.SampleCoverKey
import androidx.compose.ui.platform.testTag

@Composable
internal fun Modifier.pressMotion(source: MutableInteractionSource, enabled: Boolean): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (enabled && pressed) .96f else 1f, tween(160), label = "buttonPress")
    return drawWithContent { scale(scale) { this@drawWithContent.drawContent() } }
}

@Composable
fun DIconButton(icon: DIcon, label: String, enabled: Boolean = true, tint: Color = DColors.Ink, onClick: () -> Unit) {
    val active = enabled && LocalDDialogActive.current
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.size(48.dp).pressMotion(interaction, active).clip(RoundedCornerShape(20.dp)).clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = active, role = Role.Button, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        DIconImage(icon, tint.copy(alpha = if (enabled) 1f else .4f))
    }
}

@Composable
fun DButton(label: String, modifier: Modifier = Modifier, primary: Boolean = false, soft: Boolean = false, enabled: Boolean = true, icon: DIcon? = null, textAction: Boolean = false, onClick: () -> Unit) {
    val active = enabled && LocalDDialogActive.current
    val interaction = remember { MutableInteractionSource() }
    val color = if (primary) Color.White else if (soft || textAction) DColors.Action else DColors.Ink
    Row(modifier.heightIn(min = 48.dp).pressMotion(interaction, active).clip(RoundedCornerShape(20.dp)).background(if (primary) DColors.Action else if (soft) DColors.Soft else Color.Transparent)
        .clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = active, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { DIconImage(icon, color.copy(alpha = if (enabled) 1f else .4f), 20.dp); Spacer(Modifier.width(6.dp)) }
        Text(label, color = color.copy(alpha = if (enabled) 1f else .4f), fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun DTitle(title: String, detail: Boolean = false, modifier: Modifier = Modifier, onDoubleTap: (() -> Unit)? = null) {
    val currentOnDoubleTap = rememberUpdatedState(onDoubleTap)
    Text(title, style = if (detail) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge,
        modifier = modifier.then(if (onDoubleTap != null) Modifier.pointerInput(Unit) {
            detectTapGestures(onDoubleTap = { currentOnDoubleTap.value?.invoke() })
        } else Modifier).semantics { heading() })
}

@Composable
fun DDetailHeader(title: String, menu: () -> Unit, back: () -> Unit, onDoubleTap: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().layout { measurable, constraints ->
        val extra = 20.dp.roundToPx()
        val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
        layout(placeable.width - extra, placeable.height) { placeable.placeRelative(-10.dp.roundToPx(), 0) }
    }.padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        DIconButton(DIcon.Back, "返回", onClick = back)
        if (trailing != null) trailing() else DIconButton(DIcon.More, "更多操作", onClick = menu)
    }
    DTitle(title, detail = true, modifier = if (onDoubleTap != null) Modifier.fillMaxWidth() else Modifier, onDoubleTap = onDoubleTap)
}

@Composable
fun MediaSelectionDeleteButton(count: Int, isSaving: Boolean, onDelete: () -> Unit) {
    val enabled = count > 0 && !isSaving
    Box(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(20.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onDelete).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text("删除", color = DColors.Error.copy(alpha = if (enabled) 1f else .4f), fontSize = 15.sp)
    }
}

@Composable
fun DGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(DColors.Panel), content = content)
}

@Composable
internal fun DGroupRow(first: Boolean, last: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val top = if (first) 26.dp else 0.dp
    val bottom = if (last) 26.dp else 0.dp
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)).background(DColors.Panel), content = content)
}

@Composable
fun DDivider(inset: Int = 18) { Box(Modifier.fillMaxWidth().padding(horizontal = inset.dp).height(1.dp).background(DColors.Line)) }

@Composable
fun DCover(cover: CoverRef, fallback: DIcon, fixtureCover: (@Composable (SampleCoverKey) -> Unit)?, previewDecoder: PreviewDecoder? = null) {
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFFEFF1F5)), contentAlignment = Alignment.Center) {
        if (cover is CoverRef.Sample && fixtureCover != null) fixtureCover(cover.key)
        else if (cover is CoverRef.Asset) MediaThumbnail(cover.storageKey, MediaType.IMAGE, previewDecoder, Modifier.fillMaxSize())
        else DIconImage(fallback, Color(0xFF969EAD))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DRow(title: String, subtitle: String? = null, icon: DIcon = DIcon.Album, compact: Boolean = false, cover: CoverRef? = null,
         fixtureCover: (@Composable (SampleCoverKey) -> Unit)? = null, previewDecoder: PreviewDecoder? = null,
         onLongClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null, iconBadge: Boolean = false, onClick: () -> Unit) {
    val active = LocalDDialogActive.current
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().heightIn(min = if (compact) 66.dp else 76.dp)
        .then(if (onLongClick == null) Modifier.clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = active, role = Role.Button, onClick = onClick) else Modifier.combinedClickable(interactionSource = interaction, indication = LocalIndication.current, enabled = active, role = Role.Button, onLongClick = onLongClick, onClick = onClick))
        .padding(horizontal = if (compact) 4.dp else 18.dp, vertical = if (compact) 15.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (cover == null) { if (iconBadge) DIconBadge(icon, Modifier.pressMotion(interaction, active)) else DIconImage(icon) } else DCover(cover, icon, fixtureCover, previewDecoder)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, lineHeight = 25.5.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, color = DColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
        if (trailing != null) trailing() else DIconImage(DIcon.Next, Color(0xFF9A9DA5), 18.dp)
    }
}

@Composable
fun DEmpty(title: String, subtitle: String, button: String? = null, icon: DIcon = DIcon.Album, onClick: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(DColors.Panel).padding(horizontal = 22.dp, vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        DIconImage(icon, Color(0xFFA4ADBD), 34.dp)
        Spacer(Modifier.height(12.dp))
        Text(title, fontSize = 17.sp, textAlign = TextAlign.Center)
        Text(subtitle, fontSize = 14.sp, lineHeight = 21.sp, color = DColors.Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 14.dp))
        if (button != null) DButton(button, soft = true, onClick = onClick)
    }
}

@Composable
fun dLoadState(state: LoadState, retry: () -> Unit): Boolean {
    when (state) {
        LoadState.Failed -> DEmpty("暂时无法读取图库", "请重试，已有内容不会被清空。", "重试", onClick = retry)
        LoadState.Loading -> Text("正在读取图库…", color = DColors.Muted, modifier = Modifier.padding(22.dp))
        LoadState.Ready -> return false
    }
    return true
}

@Composable
fun DBottomNavigation(manage: Boolean, onEvent: (GalleryEvent) -> Unit) {
    val fraction by animateFloatAsState(if (manage) 1f else 0f, tween(280), label = "navigationPill")
    BoxWithConstraints(Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(36.dp)).background(Color.White).border(1.dp, DColors.Line, RoundedCornerShape(36.dp)).padding(7.dp)) {
        Box(Modifier.offset { IntOffset((maxWidth / 2 * fraction).roundToPx(), 0) }.width(maxWidth / 2).fillMaxHeight()
            .background(DColors.Soft, RoundedCornerShape(28.dp)).testTag("bottomNavigationPill").semantics { motionFraction = fraction })
        Row(Modifier.fillMaxSize()) {
        listOf(false, true).forEach { isManage ->
            val selected = isManage == manage
            val interaction = remember { MutableInteractionSource() }
            Row(Modifier.weight(1f).fillMaxHeight().pressMotion(interaction, true).clip(RoundedCornerShape(28.dp))
                .semantics { this.selected = selected }.clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Tab) { onEvent(if (isManage) GalleryEvent.OpenManage else GalleryEvent.OpenHome) },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                DIconImage(if (isManage) DIcon.Manage else DIcon.Artist, if (selected) DColors.Action else DColors.Muted)
                Spacer(Modifier.width(9.dp))
                Text(if (isManage) "管理" else "画师", color = if (selected) DColors.Action else DColors.Muted, fontSize = 14.sp, lineHeight = 21.sp)
            }
        }
        }
    }
}

@Composable
internal fun DIconBadge(icon: DIcon, modifier: Modifier = Modifier) {
    Box(modifier.size(36.dp).background(DColors.Soft, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        DIconImage(icon, DColors.Action, 21.dp)
    }
}
