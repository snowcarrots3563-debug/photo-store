package dev.local.artistgallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Composable
fun ManageScreen(state: GalleryUiState, padding: Dp, onEvent: (GalleryEvent) -> Unit, archiveHistory: (() -> Unit)? = null,
                 onExport: (() -> Unit)? = null, onBackup: (() -> Unit)? = null, onRestore: (() -> Unit)? = null) {
    fun unavailable(feature: String, phase: Int) { onEvent(GalleryEvent.OpenDialog(GalleryDialog.Unavailable(feature, phase))) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = padding, end = padding, top = 28.dp, bottom = 126.dp)) {
        DTitle("管理")
        AnimatedVisibility(!state.browseOptions.storageNoticeDismissed,
            enter = fadeIn(tween(280)) + expandVertically(tween(280), expandFrom = Alignment.Top),
            exit = fadeOut(tween(280)) + shrinkVertically(tween(280), shrinkTowards = Alignment.Top)) {
            Column(Modifier.testTag("manageNotice").padding(top = 28.dp).fillMaxWidth().background(DColors.Soft, RoundedCornerShape(26.dp)).padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("保存在这台设备上", fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 25.6.sp, modifier = Modifier.weight(1f))
                    DIconButton(DIcon.Close, "关闭保存提示", enabled = !state.browseOptions.storageNoticeDismissed) { onEvent(GalleryEvent.DismissStorageNotice) }
                }
                Text("画师和作品集已在本机保存。卸载应用或清除数据会删除图库副本与整理信息，系统相册源文件不受影响。", color = Color(0xFF64728B), fontSize = 12.sp, lineHeight = 22.8.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        ManageLabel("图库")
        DGroup {
            ManageRow("unassigned", "未归组媒体", "已保存但尚未加入作品集的图片、GIF 和视频", DIcon.SettingsImage) { onEvent(GalleryEvent.OpenUnassigned) }
            ManageRow("import", "导入", "相册、文件、压缩包、文件夹", DIcon.SettingsDownload, divider = true) { onEvent(GalleryEvent.OpenDialog(GalleryDialog.AddMenu)) }
            if (archiveHistory != null) {
                ManageRow("history", "压缩包／文件夹导入记录", "查看结果、继续中断或失败的任务", DIcon.SettingsArchive, divider = true, onClick = archiveHistory)
            }
            ManageRow("export", "按范围导出", "图片、作品集、画师或全部图库", DIcon.SettingsUpload, divider = true) { if (onExport != null) onExport() else unavailable("按范围导出", 5) }
            ManageRow("backup", "完整备份", "原图、作品集与整理关系", DIcon.SettingsBackup, divider = true) { if (onBackup != null) onBackup() else unavailable("完整备份", 5) }
            ManageRow("restore", "从备份恢复", "校验完整备份，并合并到当前图库", DIcon.SettingsFolder, divider = true) { if (onRestore != null) onRestore() else unavailable("从备份恢复", 5) }
        }
        ManageLabel("浏览")
        DGroup {
            ManageRow("card", "卡牌效果", "拖动时呈现镭射与立体感", DIcon.SettingsSpark, checked = state.browseOptions.cardEffects) { onEvent(GalleryEvent.SetCardEffects(!state.browseOptions.cardEffects)) }
            ManageRow("gif", "播放 GIF 动画", "关闭后显示静态首帧，原 GIF 保留", DIcon.SettingsPlay, divider = true, checked = state.browseOptions.playGifs) { onEvent(GalleryEvent.SetPlayGifs(!state.browseOptions.playGifs)) }
            ManageRow("swipe", "开启滑动删除", "关闭后仍可长按图片进入选择模式", DIcon.SettingsTrash, divider = true, checked = state.browseOptions.swipeDelete) { onEvent(GalleryEvent.SetSwipeDelete(!state.browseOptions.swipeDelete)) }
            ManageRow("auto", "打开视频时自动播放", "关闭后先暂停，可手动播放", DIcon.SettingsPlay, divider = true, checked = state.browseOptions.autoPlayVideos) { onEvent(GalleryEvent.SetAutoPlayVideos(!state.browseOptions.autoPlayVideos)) }
        }
        state.recentImportBatches.firstOrNull { it.status != "COMPLETED" }?.let { batch ->
            ManageLabel("导入记录")
            DGroup { ManageRow("incomplete", "导入有未完成项", "查看结果并重试可恢复的失败项", DIcon.SettingsArchive) { onEvent(GalleryEvent.OpenImportResult(batch.id)) } }
        }
        ManageLabel("应用")
        DGroup {
            ManageRow("about", "应用说明", "查看当前功能与后续计划", DIcon.SettingsInfo) { onEvent(GalleryEvent.OpenDialog(GalleryDialog.About)) }
            ManageRow("changelog", "查看更新日志", "查看所有版本的完整更新内容", DIcon.SettingsFile, divider = true) { onEvent(GalleryEvent.OpenDialog(GalleryDialog.UpdateHistory)) }
        }
    }
}

@Composable
private fun ManageLabel(label: String) { Text(label, color = DColors.Muted, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 10.dp)) }

@Composable
private fun ManageRow(key: String, title: String, subtitle: String, icon: DIcon, divider: Boolean = false, checked: Boolean? = null, onClick: () -> Unit) {
    val active = LocalDDialogActive.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale = animateFloatAsState(if (active && pressed) .97f else 1f, tween(160), label = "managePress")
    val iconScale = remember { Animatable(1f) }
    val iconRotation = remember { Animatable(0f) }
    var previousChecked by remember { mutableStateOf(checked) }
    LaunchedEffect(checked) {
        if (checked == previousChecked) return@LaunchedEffect
        previousChecked = checked
        coroutineScope {
            launch {
                iconScale.animateTo(1f, keyframes {
                    durationMillis = 350
                    iconScale.value at 0
                    .86f at 40
                    1.05f at 175
                    1f at 350
                })
            }
            launch {
                iconRotation.animateTo(0f, keyframes {
                    durationMillis = 350
                    iconRotation.value at 0
                    -7f at 40
                    2f at 175
                    0f at 350
                })
            }
        }
    }
    val action = if (checked == null) Modifier.clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = active, role = Role.Button, onClick = onClick)
        else Modifier.toggleable(interactionSource = interaction, indication = LocalIndication.current, enabled = active, value = checked, role = Role.Switch, onValueChange = { onClick() })
    Row(Modifier.fillMaxWidth().heightIn(min = 87.dp).testTag("manageRow:$key").semantics { motionFraction = pressScale.value }
        .drawWithContent {
            scale(pressScale.value) {
                this@drawWithContent.drawContent()
                if (divider) drawLine(DColors.Line, Offset(0f, .5.dp.toPx()), Offset(size.width, .5.dp.toPx()), 1.dp.toPx())
            }
        }.then(action).padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        Box(Modifier.size(36.dp).testTag("manageIcon:$key").semantics { motionFraction = iconScale.value }
            .graphicsLayer { scaleX = iconScale.value; scaleY = iconScale.value; rotationZ = iconRotation.value }
            .background(Color(0xFFF1F5FC), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            DIconImage(icon, DColors.Action, 21.dp, strokeWidth = 1.75f)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 25.6.sp)
            Text(subtitle, fontSize = 11.sp, lineHeight = 17.6.sp, color = DColors.Muted, modifier = Modifier.padding(top = 4.dp))
        }
        if (checked == null) DIconImage(DIcon.Next, Color(0xFFA1A7B1), 18.dp, strokeWidth = 1.75f)
        else {
            val fraction = animateFloatAsState(if (checked) 1f else 0f, tween(440, easing = CubicBezierEasing(.2f, 1.12f, .36f, 1f)), label = "manageThumb")
            val track = animateColorAsState(if (checked) Color(0xFF4B7FF3) else Color(0xFFBFC5D0), tween(160), label = "manageTrack")
            Box(Modifier.size(44.dp, 27.dp).testTag("manageTrack:$key").semantics { motionFraction = fraction.value }.drawWithContent {
                drawRoundRect(track.value, cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()))
                drawContent()
            }) {
                Box(Modifier.size(21.dp).testTag("manageThumb:$key").graphicsLayer {
                    translationX = (3.dp + 17.dp * fraction.value).toPx()
                    translationY = 3.dp.toPx()
                }.background(Color.White, RoundedCornerShape(50)))
            }
        }
    }
}
