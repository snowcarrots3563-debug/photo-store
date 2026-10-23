package dev.local.artistgallery.ui.exporting

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.local.artistgallery.data.exporting.LocalTransferCoordinator
import dev.local.artistgallery.data.exporting.LocalTransferState
import dev.local.artistgallery.data.exporting.TransferKind
import dev.local.artistgallery.data.exporting.TransferStep
import dev.local.artistgallery.ui.*
import java.util.Locale

@Composable
fun LocalTransferDialog(state: LocalTransferState, coordinator: LocalTransferCoordinator, save: () -> Unit, pickRestore: () -> Unit) {
    DAnimatedDialog(state.takeIf { state.visible }, coordinator::hide) { presentation, panel, fraction ->
        val insets = galleryDialogWindowInsets()
        BoxWithConstraints(Modifier.fillMaxSize().background(DColors.Ink.copy(alpha = .2f * fraction))
            .windowInsetsPadding(insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .clickable(enabled = LocalDDialogActive.current, interactionSource = remember { MutableInteractionSource() }, indication = null) { coordinator.hide() }, contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).widthIn(max = 398.dp).fillMaxWidth().heightIn(max = maxHeight - 16.dp)
                .then(panel).clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)).background(DColors.Panel)
                .windowInsetsPadding(insets.only(WindowInsetsSides.Bottom))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .padding(if (maxWidth <= 370.dp) 20.dp else 22.dp)) {
                LocalTransferContent(presentation, coordinator::choose, coordinator::toggle, coordinator::previewSelection,
                    save, pickRestore, coordinator::confirmRestore, coordinator::cancel, coordinator::hide, coordinator::done, coordinator::chooseScope)
            }
        }
    }
}

@Composable
internal fun ColumnScope.LocalTransferContent(
    state: LocalTransferState,
    choose: (TransferKind) -> Unit,
    toggle: (String) -> Unit,
    preview: () -> Unit,
    save: () -> Unit,
    pickRestore: () -> Unit,
    confirmRestore: () -> Unit,
    cancel: () -> Unit,
    hide: () -> Unit,
    done: () -> Unit,
    backToScope: () -> Unit,
) {
    val title = when (state.step) {
        TransferStep.Scope -> "选择导出范围"
        TransferStep.Selection -> when (state.kind) {
            TransferKind.Images -> "选择导出图片"
            TransferKind.Collections -> "选择导出作品集"
            TransferKind.Artists -> "选择导出画师"
            else -> "选择导出内容"
        }
        TransferStep.Preparing -> if (state.kind == TransferKind.Restore) "正在校验备份" else "正在准备导出"
        TransferStep.Preview -> if (state.kind == TransferKind.Backup) "完整备份预览" else "导出预览"
        TransferStep.Destination -> if (state.kind == TransferKind.Restore) "选择备份文件" else "选择保存位置"
        TransferStep.Working -> when (state.kind) {
            TransferKind.Backup -> "正在备份"
            TransferKind.Restore -> "正在合并恢复"
            else -> "正在导出"
        }
        TransferStep.RestorePick -> "从备份恢复"
        TransferStep.RestorePreview -> "确认合并恢复"
        TransferStep.Result -> when (state.kind) {
            TransferKind.Backup -> "备份结果"
            TransferKind.Restore -> "恢复结果"
            else -> "导出结果"
        }
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp).semantics { paneTitle = title }, verticalAlignment = Alignment.CenterVertically) {
        if (state.step == TransferStep.Selection) DIconButton(DIcon.Back, "上一步", onClick = backToScope)
        Text(title, Modifier.weight(1f).padding(start = 6.dp).semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
        DIconButton(DIcon.Close, "关闭窗口", onClick = hide)
    }
    if (state.step != TransferStep.Result) state.message?.let {
        Text(it, color = DColors.Error, fontSize = 13.sp, lineHeight = 22.sp, modifier = Modifier.padding(bottom = 10.dp).semantics { liveRegion = LiveRegionMode.Polite })
    }
    when (state.step) {
        TransferStep.Scope -> {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(DColors.Background)) {
                DRow("图片", "从图库选择图片、GIF 和视频", DIcon.Image) { choose(TransferKind.Images) }
                DDivider()
                DRow("作品集", "保留作品集内的原文件与目录", DIcon.Album) { choose(TransferKind.Collections) }
                DDivider()
                DRow("画师", "导出所选画师的全部作品集", DIcon.Artist) { choose(TransferKind.Artists) }
                DDivider()
                DRow("全部图库", "按画师和作品集组织目录", DIcon.Export) { choose(TransferKind.Library) }
            }
        }
        TransferStep.Selection -> {
            TransferHint("已选择 ${state.selected.size} 项。导出为 ZIP，保留原图、GIF 和视频。")
            if (state.choices.isEmpty()) TransferHint("暂无可导出的内容。")
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 360.dp).clip(RoundedCornerShape(22.dp)).background(DColors.Background)) {
                items(state.choices, key = { it.id }) { choice ->
                    val selected = choice.id in state.selected
                    Row(Modifier.fillMaxWidth().testTag("transferChoice:${choice.id}").toggleable(selected, enabled = LocalDDialogActive.current, role = Role.Checkbox) { toggle(choice.id) }
                        .background(if (selected) DColors.Soft else DColors.Background).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(22.dp).border(1.dp, if (selected) DColors.Action else DColors.Muted, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                            if (selected) DIconImage(DIcon.Check, DColors.Action, 18.dp)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(choice.title, fontSize = 15.sp, lineHeight = 22.sp)
                            if (choice.subtitle.isNotBlank()) Text(choice.subtitle, color = DColors.Muted, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    DDivider()
                }
            }
            TransferButtons("取消", done, "继续预览", preview, state.selected.isNotEmpty())
        }
        TransferStep.Preview -> {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                TransferHint(state.label)
                TransferCounts(state)
                TransferHint(if (state.kind == TransferKind.Backup)
                    "每份原文件只保存一次，包含画师、作品集、共享引用、封面、顺序和浏览设置。未归组媒体也会备份。"
                    else "同一原文件在不同作品集目录中会分别导出。GIF 和视频保留原文件，文件名重复时自动添加序号。")
                TransferHint(if (state.kind == TransferKind.Backup)
                    "完整备份可用于恢复整理关系，请将 ZIP 保存到安全的位置。"
                    else "普通导出 ZIP 用于查看和保存媒体。恢复整理关系请使用完整备份。")
                if (state.choices.isNotEmpty()) LazyColumn(Modifier.heightIn(max = 200.dp).fillMaxWidth()) {
                    items(state.choices, key = { it.id }) { entry ->
                        Text(entry.title, fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.padding(vertical = 10.dp))
                        DDivider(0)
                    }
                }
            }
            TransferButtons("取消", done, "选择保存位置", save, state.kind == TransferKind.Backup || state.fileCount > 0)
        }
        TransferStep.RestorePick -> {
            TransferHint("选择本应用生成的完整备份 ZIP。普通导出 ZIP 请使用压缩包导入。")
            TransferNote("现有图库保留。备份中的画师与作品集另建，同名自动添加序号；共享原文件仍只保存一份。")
            TransferButtons("取消", done, "选择备份文件", pickRestore)
        }
        TransferStep.RestorePreview -> {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                TransferHint(if (state.label.isBlank()) "已完成完整性校验" else "${state.label} · 已完成完整性校验")
                TransferCounts(state)
                TransferHint("恢复封面、组内顺序和每位画师的展示方式。现有画师顺序保留，恢复的画师追加在后。")
                TransferNote("合并不会覆盖现有图库，同名画师与作品集自动添加序号。保留这台设备当前的全局浏览设置。")
                TransferHint("确认前不会写入图库。请确保设备有足够空间容纳约 ${transferBytes(state.totalBytes)} 的原文件。")
            }
            TransferButtons("取消", done, "确认合并恢复", confirmRestore)
        }
        TransferStep.Preparing, TransferStep.Working -> {
            TransferHint(state.progress.ifBlank { "正在处理，请稍候…" })
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp), color = DColors.Action, trackColor = DColors.Soft)
            TransferHint(if (state.canCancel) "关闭窗口或离开页面不会取消任务。可从管理页重新查看进度。" else "正在提交恢复，请稍候。关闭窗口后任务会继续，可从管理页重新查看进度。")
            TransferButtons("取消任务", cancel, "后台继续", hide, leftEnabled = state.canCancel)
        }
        TransferStep.Destination -> {
            TransferHint(if (state.kind == TransferKind.Restore) "请在系统文件管理中选择备份文件。" else "请在系统文件管理中选择保存位置和文件名。")
            TransferHint("完成选择后继续；取消系统窗口可返回。")
            DButton("关闭窗口", Modifier.fillMaxWidth().padding(top = 18.dp), soft = true, onClick = hide)
        }
        TransferStep.Result -> {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                TransferNote(if (state.warning != null) "数据已恢复，收尾未完成。" else if (state.success) when (state.kind) {
                    TransferKind.Backup -> "完整备份已保存。"
                    TransferKind.Restore -> "合并恢复完成，原有图库已保留。"
                    else -> "导出文件已保存。"
                } else "本次${if (state.kind == TransferKind.Restore) "恢复" else if (state.kind == TransferKind.Backup) "备份" else "导出"}未完成。")
                if (state.success) TransferCounts(state)
                state.warning?.let { Text(it, color = DColors.Error, fontSize = 13.sp, lineHeight = 22.sp, modifier = Modifier.padding(vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }) }
                state.message?.let { Text(it, color = if (state.success) DColors.Muted else DColors.Error, fontSize = 13.sp, lineHeight = 22.sp, modifier = Modifier.padding(vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }) }
            }
            DButton("完成", Modifier.fillMaxWidth().padding(top = 18.dp), primary = true, onClick = done)
        }
    }
}

@Composable
private fun TransferCounts(state: LocalTransferState) {
    Column(Modifier.fillMaxWidth().background(DColors.Background, RoundedCornerShape(22.dp)).padding(16.dp)) {
        Text(if (state.kind == TransferKind.Backup || state.kind == TransferKind.Restore) "原文件 ${state.assetCount} 份 · ${transferBytes(state.totalBytes)}"
            else "唯一原文件 ${state.assetCount} 份", fontSize = 15.sp, lineHeight = 23.sp)
        if (state.kind == TransferKind.Backup || state.kind == TransferKind.Restore) {
            Text("${state.artistCount} 位画师 · ${state.collectionCount} 个作品集 · ${state.referenceCount} 个引用", color = DColors.Muted, fontSize = 13.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 8.dp))
        } else {
            Text("按目录输出 ${state.fileCount} 项 · ${transferBytes(state.totalBytes)}${if (state.fileCount > state.assetCount) "\n含 ${state.fileCount - state.assetCount} 项重复引用" else ""}", color = DColors.Muted, fontSize = 13.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun TransferHint(text: String) { Text(text, color = DColors.Muted, fontSize = 13.sp, lineHeight = 22.sp, modifier = Modifier.padding(vertical = 10.dp)) }

@Composable
private fun TransferNote(text: String) { Text(text, color = DColors.Action, fontSize = 13.sp, lineHeight = 22.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).background(DColors.Soft, RoundedCornerShape(22.dp)).padding(16.dp)) }

@Composable
private fun TransferButtons(left: String, onLeft: () -> Unit, right: String, onRight: () -> Unit, enabled: Boolean = true, leftEnabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DButton(left, Modifier.weight(1f), enabled = leftEnabled, onClick = onLeft)
        DButton(right, Modifier.weight(1f), primary = true, enabled = enabled, onClick = onRight)
    }
}

private fun transferBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
}
