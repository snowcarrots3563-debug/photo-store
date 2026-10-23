package dev.local.artistgallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp

@Composable
fun ManageScreen(state: GalleryUiState, padding: Dp, onEvent: (GalleryEvent) -> Unit, archiveHistory: (() -> Unit)? = null,
                 onExport: (() -> Unit)? = null, onBackup: (() -> Unit)? = null, onRestore: (() -> Unit)? = null) {
    fun unavailable(feature: String, phase: Int) { onEvent(GalleryEvent.OpenDialog(GalleryDialog.Unavailable(feature, phase))) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = padding, end = padding, top = 28.dp, bottom = 126.dp)) {
        DTitle("管理")
        if (!state.browseOptions.storageNoticeDismissed) {
            Column(Modifier.padding(top = 28.dp).fillMaxWidth().background(DColors.Soft, RoundedCornerShape(26.dp)).padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("保存在这台设备上", fontSize = 17.sp, modifier = Modifier.weight(1f))
                    DIconButton(DIcon.Close, "关闭保存提示") { onEvent(GalleryEvent.DismissStorageNotice) }
                }
                Text("画师和作品集已在本机保存。卸载应用或清除数据会删除图库副本与整理信息，系统相册源文件不受影响。", color = Color(0xFF5E6B82), fontSize = 13.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        ManageLabel("图库")
        DGroup {
            DRow("未归组媒体", "已保存但尚未加入作品集的图片、GIF 和视频", DIcon.Image, iconBadge = true) { onEvent(GalleryEvent.OpenUnassigned) }
            DDivider()
            DRow("导入", "相册、文件、压缩包、文件夹", DIcon.Import, iconBadge = true) { onEvent(GalleryEvent.OpenDialog(GalleryDialog.AddMenu)) }
            if (archiveHistory != null) {
                DDivider()
                DRow("压缩包／文件夹导入记录", "查看结果、继续中断或失败的任务", DIcon.History, iconBadge = true, onClick = archiveHistory)
            }
            DDivider()
            DRow("按范围导出", "图片、作品集、画师或全部图库", DIcon.Export, iconBadge = true) { if (onExport != null) onExport() else unavailable("按范围导出", 5) }
            DDivider()
            DRow("完整备份", "原图、作品集与整理关系", DIcon.Backup, iconBadge = true) { if (onBackup != null) onBackup() else unavailable("完整备份", 5) }
            DDivider()
            DRow("从备份恢复", "校验完整备份，并合并到当前图库", DIcon.Restore, iconBadge = true) { if (onRestore != null) onRestore() else unavailable("从备份恢复", 5) }
        }
        ManageLabel("浏览")
        DGroup {
            BrowseSwitchRow("卡牌效果", "拖动时呈现镭射与立体感", DIcon.Card, state.browseOptions.cardEffects) { onEvent(GalleryEvent.SetCardEffects(it)) }
            DDivider()
            BrowseSwitchRow("播放 GIF 动画", "关闭后显示静态首帧，原 GIF 保留", DIcon.Gif, state.browseOptions.playGifs) { onEvent(GalleryEvent.SetPlayGifs(it)) }
            DDivider()
            BrowseSwitchRow("开启滑动删除", "关闭后仍可长按图片进入选择模式", DIcon.Swipe, state.browseOptions.swipeDelete) { onEvent(GalleryEvent.SetSwipeDelete(it)) }
            DDivider()
            BrowseSwitchRow("打开视频时自动播放", "关闭后先暂停，可手动播放", DIcon.Video, state.browseOptions.autoPlayVideos) { onEvent(GalleryEvent.SetAutoPlayVideos(it)) }
        }
        state.recentImportBatches.firstOrNull { it.status != "COMPLETED" }?.let { batch ->
            ManageLabel("导入记录")
            DGroup { DRow("导入有未完成项", "查看结果并重试可恢复的失败项", DIcon.History, iconBadge = true) { onEvent(GalleryEvent.OpenImportResult(batch.id)) } }
        }
        ManageLabel("应用")
        DGroup {
            DRow("应用说明", "查看当前功能与后续计划", DIcon.Info, iconBadge = true) { onEvent(GalleryEvent.OpenDialog(GalleryDialog.About)) }
            DDivider()
            DRow("查看更新日志", "查看所有版本的完整更新内容", DIcon.History, iconBadge = true) { onEvent(GalleryEvent.OpenDialog(GalleryDialog.UpdateHistory)) }
        }
    }
}

@Composable
private fun ManageLabel(label: String) { Text(label, color = DColors.Muted, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 10.dp)) }

@Composable
private fun BrowseSwitchRow(title: String, subtitle: String, icon: DIcon, checked: Boolean, onToggle: (Boolean) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val fraction by animateFloatAsState(if (checked) 1f else 0f, tween(160), label = "switchThumb")
    val track by animateColorAsState(if (checked) DColors.Action else Color(0xFFBEC3CD), tween(160), label = "switchTrack")
    Row(Modifier.fillMaxWidth().toggleable(interactionSource = interaction, indication = LocalIndication.current, value = checked, role = Role.Switch, onValueChange = onToggle).padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        DIconBadge(icon, Modifier.pressMotion(interaction, true))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp)
            Text(subtitle, fontSize = 13.sp, lineHeight = 20.sp, color = DColors.Muted, modifier = Modifier.padding(top = 4.dp))
        }
        Box(Modifier.size(width = 52.dp, height = 48.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(52.dp, 31.dp).background(track, RoundedCornerShape(20.dp)))
            Box(Modifier.align(Alignment.CenterStart).offset { IntOffset((4.dp + 21.dp * fraction).roundToPx(), 0) }.size(23.dp).background(Color.White, RoundedCornerShape(20.dp)))
        }
    }
}
