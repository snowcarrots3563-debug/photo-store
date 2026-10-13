package dev.local.artistgallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.local.artistgallery.model.VersionUpdate

@Composable
fun UpdateNotesDialog(updates: List<VersionUpdate>, showAll: Boolean = false, onDismiss: () -> Unit) {
    val title = if (showAll) "更新日志" else "更新内容"
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(decorFitsSystemWindows = false)) {
        val dialogInsets = galleryDialogWindowInsets(includeIme = false)
        BoxWithConstraints(
            Modifier.fillMaxSize().background(DColors.Ink.copy(alpha = .2f))
                .windowInsetsPadding(dialogInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .testTag("updateNotesBackdrop"),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val inset = if (maxWidth <= 370.dp) 20.dp else 22.dp
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).widthIn(max = 398.dp).fillMaxWidth()
                    .heightIn(max = maxHeight - 16.dp).clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                    .background(DColors.Panel).windowInsetsPadding(dialogInsets.only(WindowInsetsSides.Bottom))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                    .padding(inset).semantics { paneTitle = title },
            ) {
                Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).padding(start = 6.dp).semantics { heading() })
                    DIconButton(DIcon.Close, "关闭$title", onClick = onDismiss)
                }
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("updateNotesChanges")) {
                    (if (showAll) updates else updates.take(2)).forEachIndexed { index, update ->
                        if (index > 0) Spacer(Modifier.height(20.dp))
                        Text("${update.versionName} · ${update.date}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp).semantics { heading() })
                        listOf("修复", "优化", "新增").forEach { category ->
                            val changes = update.changes.filter { change ->
                                val changeCategory = listOf("修复", "优化", "新增").firstOrNull { change.startsWith("【$it】") } ?: "新增"
                                changeCategory == category
                            }
                            if (changes.isNotEmpty()) {
                                Text("【$category】", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp).semantics { heading() })
                                changes.forEach { change ->
                                    Text("• ${change.removePrefix("【$category】")}", color = DColors.Muted, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(bottom = 4.dp))
                                }
                            }
                        }
                    }
                }
                DButton(if (showAll) "关闭" else "知道了", Modifier.fillMaxWidth().padding(top = 18.dp), primary = true, onClick = onDismiss)
            }
        }
    }
}
