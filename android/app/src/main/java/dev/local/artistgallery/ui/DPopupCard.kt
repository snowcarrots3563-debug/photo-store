package dev.local.artistgallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared appearance only. Each caller still owns its content, scrolling and real actions. */
@Composable
internal fun DPopupCard(
    modifier: Modifier,
    scrimFraction: Float,
    insets: WindowInsets,
    onDismiss: () -> Unit,
    backdropModifier: Modifier = Modifier,
    content: @Composable BoxWithConstraintsScope.(Modifier) -> Unit,
) {
    val active = LocalDDialogActive.current
    Box(backdropModifier.fillMaxSize().background(DColors.Ink.copy(alpha = .2f * scrimFraction))
        .clickable(enabled = active, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(insets).padding(15.dp), contentAlignment = Alignment.BottomCenter) {
            content(Modifier.widthIn(max = 398.dp).fillMaxWidth().heightIn(max = maxHeight * .94f)
                .then(modifier).testTag("dPopupCard").clip(RoundedCornerShape(29.dp)).background(DColors.Panel)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 18.dp))
        }
    }
}

@Composable
internal fun DPopupHeader(title: String, onClose: () -> Unit, closeLabel: String = "关闭", enabled: Boolean = true, onBack: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (onBack != null) DIconButton(DIcon.Back, "上一步", enabled, onClick = onBack)
        Text(title, Modifier.weight(1f).semantics { heading() }, fontSize = 22.sp, lineHeight = 28.6.sp, fontWeight = FontWeight.SemiBold)
        DIconButton(DIcon.Close, closeLabel, enabled, onClick = onClose)
    }
}

@Composable
internal fun DPopupOption(label: String, icon: DIcon, subtitle: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val active = enabled && LocalDDialogActive.current
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).pressMotion(interaction, active).clip(RoundedCornerShape(17.dp))
        .clickable(enabled = active, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
        .padding(horizontal = 11.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        DIconImage(icon, Color(0xFF6B7D98).copy(alpha = if (enabled) 1f else .4f), 23.dp, strokeWidth = 1.75f)
        Column(Modifier.weight(1f)) {
            Text(label, color = DColors.Ink.copy(alpha = if (enabled) 1f else .4f), fontSize = 15.sp, lineHeight = 24.sp)
            if (subtitle != null) Text(subtitle, color = DColors.Muted, fontSize = 11.sp, lineHeight = 18.7.sp, modifier = Modifier.padding(top = 3.dp))
        }
        DIconImage(DIcon.Next, Color(0xFFA1A7B1), 18.dp, strokeWidth = 1.75f)
    }
}
