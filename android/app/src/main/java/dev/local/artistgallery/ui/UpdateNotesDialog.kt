package dev.local.artistgallery.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.local.artistgallery.model.VersionUpdate

@Composable
fun UpdateNotesDialog(updates: List<VersionUpdate>, showAll: Boolean = false, visible: Boolean = true, onDismiss: () -> Unit) {
    DAnimatedDialog((updates to showAll).takeIf { visible }, onDismiss, contentKey = showAll) { model, panel, fraction ->
        UpdateNotesContent(model.first, model.second, onDismiss, panel, fraction)
    }
}

@Composable
internal fun UpdateNotesContent(updates: List<VersionUpdate>, showAll: Boolean, onDismiss: () -> Unit, modifier: Modifier, fraction: Float) {
    val title = if (showAll) "应用更新日志" else "更新内容"
    run {
        val dialogInsets = galleryDialogWindowInsets(includeIme = false)
        DPopupCard(modifier, fraction, dialogInsets, onDismiss, Modifier.testTag("updateNotesBackdrop")) { card ->
            Column(card.semantics { paneTitle = title }) {
                DPopupHeader(title, onClose = onDismiss, closeLabel = "关闭$title")
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("updateNotesChanges")) {
                    updates.maxByOrNull { it.versionCode }?.let { current ->
                        Text(buildAnnotatedString {
                            append("当前版本：")
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(current.versionName) }
                            append("（版本代码 ${current.versionCode}） · ${current.date}")
                        }, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(bottom = 18.dp))
                    }
                    (if (showAll) updates else updates.take(2)).forEachIndexed { index, update ->
                        if (index > 0) Spacer(Modifier.height(20.dp))
                        Text("${update.versionName} · ${update.date}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp).semantics { heading() })
                        update.changes.forEach { change ->
                            Text("• $change", color = DColors.Muted, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(bottom = 4.dp))
                        }
                    }
                }
                DButton(if (showAll) "关闭" else "知道了", Modifier.fillMaxWidth().padding(top = 18.dp), primary = true, onClick = onDismiss)
            }
        }
    }
}
