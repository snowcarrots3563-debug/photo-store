package dev.local.artistgallery.ui.archive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.local.artistgallery.data.archive.*
import dev.local.artistgallery.model.ArtistSummary
import dev.local.artistgallery.ui.*

@Composable
fun ArchiveImportDialog(
    state: ArchiveImportState,
    artists: List<ArtistSummary>,
    coordinator: ArchiveImportCoordinator,
    pick: (Boolean) -> Unit,
    addArtist: () -> Unit,
    viewGallery: (String) -> Unit,
    confirm: () -> Unit = coordinator::confirm,
    retry: () -> Unit = coordinator::retry,
    resume: (String) -> Unit = coordinator::resume,
    backToMenu: () -> Unit,
) {
    if (!state.visible) return
    val isFolder = state.format == "FOLDER"
    val title = when (state.step) {
        ArchiveStep.Source, ArchiveStep.Target -> if (isFolder) "从文件夹导入" else "从压缩包导入"
        ArchiveStep.Preparing -> if (isFolder) "正在准备文件夹" else "正在准备压缩包"
        ArchiveStep.Password -> "输入压缩包密码"
        ArchiveStep.Preview -> "确认导入"
        ArchiveStep.Importing -> if (isFolder) "正在导入文件夹" else "正在解压导入"
        ArchiveStep.Result -> "导入结果"
        ArchiveStep.Error -> if (isFolder) "文件夹无法导入" else if (state.reason == ArchiveError.MISSING_VOLUME) "补齐缺少的分卷" else "压缩包无法导入"
        ArchiveStep.History -> "压缩包／文件夹导入记录"
    }
    var password by remember(state.step, state.taskId, state.message) { mutableStateOf("") }
    DisposableEffect(state.step) { onDispose { password = "" } }
    Dialog(onDismissRequest = coordinator::hide, properties = DialogProperties(decorFitsSystemWindows = false)) {
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(state.step) { if (state.step != ArchiveStep.Password) keyboard?.hide() }
        val dialogInsets = galleryDialogWindowInsets(includeIme = state.step == ArchiveStep.Password)
        BoxWithConstraints(Modifier.fillMaxSize().background(DColors.Ink.copy(alpha = .2f)).windowInsetsPadding(dialogInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { coordinator.hide() }, contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).widthIn(max = 398.dp).fillMaxWidth().wrapContentHeight().heightIn(max = maxHeight - 16.dp)
                .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)).background(DColors.Panel)
                .windowInsetsPadding(dialogInsets.only(WindowInsetsSides.Bottom))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .padding(if (maxWidth <= 370.dp) 20.dp else 22.dp).semantics { paneTitle = title }) {
                Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.step !in setOf(ArchiveStep.Preparing, ArchiveStep.Importing, ArchiveStep.Result, ArchiveStep.History)) {
                        DIconButton(DIcon.Back, "上一步") {
                            if (state.step == ArchiveStep.Source) { coordinator.done(); backToMenu() } else coordinator.back()
                        }
                    }
                    Text(title, Modifier.weight(1f).padding(start = 6.dp).semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                    DIconButton(DIcon.Close, "关闭窗口") { coordinator.hide() }
                }
                val artist = artists.firstOrNull { it.id == state.artistId }
                if (artist != null && state.step != ArchiveStep.Target) Hint(listOfNotNull(artist.name, if (isFolder) "文件夹" else state.format.takeIf { it.isNotBlank() }, state.files.firstOrNull()).joinToString(" · "))
                state.message?.let { Text(it, fontSize = 13.sp, lineHeight = 22.sp, color = DColors.Error, modifier = Modifier.padding(bottom = 10.dp).semantics { liveRegion = LiveRegionMode.Polite }) }
                when (state.step) {
                    ArchiveStep.Source, ArchiveStep.Target -> {
                        Hint(if (isFolder) "选择文件夹，递归读取子目录。源文件夹会保留，图库保存独立副本。" else "选择压缩包；分卷包请同时选择全部分卷。原压缩包会保留，图库保存独立副本。")
                        DButton(artist?.name ?: "选择画师", Modifier.fillMaxWidth(), soft = true) { coordinator.target() }
                    Hint("按目录新建作品集；同名另建带序号作品集。PDF 每页转为 PNG 并保留整页排版，TXT 列入跳过清单，视频原件保留。")
                        if (artists.isEmpty()) Hint("还没有画师，请先添加画师。")
                        DButton("添加画师", Modifier.fillMaxWidth().padding(top = 10.dp), soft = true, onClick = addArtist)
                        Buttons("取消", coordinator::done, if (coordinator.hasSelection) "继续预览" else if (isFolder) "选择文件夹" else "选择文件", { if (coordinator.hasSelection) coordinator.previewSelection() else pick(false) }, artist != null)
                    }
                    ArchiveStep.Preparing, ArchiveStep.Importing -> {
                        Hint(state.progress)
                        Hint("关闭窗口或离开页面不会取消任务。应用进程中断后，可在管理页的压缩包／文件夹导入记录继续。")
                        Buttons("取消任务", coordinator::cancel, "后台继续", coordinator::hide)
                    }
                    ArchiveStep.Password -> {
                        Hint("密码仅用于当前任务，不保存。加密目录解锁后才展示预览。")
                        Text("密码", fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                        BasicTextField(password, { password = it }, singleLine = true,
                            textStyle = TextStyle(fontSize = 15.sp, color = DColors.Ink), cursorBrush = SolidColor(DColors.Action),
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth().background(DColors.Background, RoundedCornerShape(20.dp)).padding(16.dp).semantics { contentDescription = "压缩包密码" })
                        Buttons("取消任务", coordinator::cancel, "解锁预览", { val chars = password.toCharArray(); password = ""; coordinator.unlock(chars) }, password.isNotEmpty())
                    }
                    ArchiveStep.Preview -> {
                        Hint("${state.groups.count { it.canImport }} 个有效目录。全部媒体失败的目录不建作品集；仅有视频的目录仍建组。")
                        var expanded by remember { mutableStateOf<String?>(null) }
                        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 360.dp).clip(RoundedCornerShape(22.dp)).background(DColors.Background)) {
                            state.groups.forEach { group ->
                                item("group:${group.relativeDirectory}") {
                                    val images = group.items.count { it.mediaType == dev.local.artistgallery.model.MediaType.IMAGE }
                                    val gifs = group.items.count { it.mediaType == dev.local.artistgallery.model.MediaType.GIF }
                                    val videos = group.items.count { it.mediaType == dev.local.artistgallery.model.MediaType.VIDEO }
                                    val skipped = group.items.count { it.skipReason != null }
                                    DRow(group.name, "$images 图片 · $gifs GIF · $videos 视频 · $skipped 跳过${if (!group.canImport) " · 不建组" else ""}", compact = true) { expanded = if (expanded == group.relativeDirectory) null else group.relativeDirectory }
                                    DDivider()
                                }
                                if (expanded == group.relativeDirectory) items(group.items, key = { "entry:${it.entry.index}" }) { item ->
                                    Text("${item.entry.path}\n${item.skipReason ?: "可导入 · ${item.entry.byteSize / 1024} KB"}", fontSize = 13.sp, lineHeight = 21.sp, color = DColors.Muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                                }
                            }
                        }
                        Hint("点击目录查看文件与跳过原因。确认后只重试尚未成功的条目。")
                        Buttons("取消任务", coordinator::cancel, "开始导入", confirm, state.groups.any { it.canImport })
                    }
                    ArchiveStep.Result -> {
                        val added = state.items.count { it.status == "COMPLETE" }
                        val reused = state.items.count { it.status == "REUSED" }
                        val skipped = state.items.count { it.status == "SKIPPED" }
                        val cancelled = state.items.count { it.status == "CANCELLED" }
                        val failed = state.items.count { it.status !in setOf("COMPLETE", "REUSED", "SKIPPED", "CANCELLED") }
                        Hint("新增 $added 项，复用 $reused 项，跳过 $skipped 项，失败／未完成 $failed 项，取消 $cancelled 项。\n目标作品集 ${state.collectionNames.size} 个。")
                        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 360.dp)) {
                            items(state.items, key = { it.id }) { item ->
                                val label = when (item.status) { "COMPLETE" -> "新增资产"; "REUSED" -> "复用已有原图"; "SKIPPED" -> "跳过"; "CANCELLED" -> "已取消"; else -> "失败／待恢复" }
                                Text("${item.sourceRelativePath ?: item.sourceName}\n$label${item.errorMessage?.let { " · $it" }.orEmpty()}", fontSize = 13.sp, lineHeight = 22.sp, color = if (item.status == "FAILED") DColors.Error else DColors.Ink, modifier = Modifier.padding(vertical = 10.dp))
                                DDivider()
                            }
                        }
                        if (failed > 0) DButton("重试失败／未完成项", Modifier.fillMaxWidth().padding(top = 12.dp), soft = true, onClick = retry)
                        Buttons("完成", coordinator::done, "查看图库", { val id = state.artistId; coordinator.done(); if (id != null) viewGallery(id) })
                    }
                    ArchiveStep.Error -> {
                        Hint(if (isFolder) "源文件夹会保留。可重试读取，或重新选择文件夹；已经成功的条目不会重复提交。" else "已选择的文件只用于这一次导入。缺卷补齐后仍是同一任务，已经成功的条目不会重复提交。")
                        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 200.dp)) { items(state.files) { Text(it, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp)) } }
                        if (!isFolder) {
                            if (state.files.isNotEmpty()) DButton("补选分卷", Modifier.fillMaxWidth().padding(top = 8.dp), soft = true) { pick(true) }
                            DButton("重新输入密码", Modifier.fillMaxWidth().padding(top = 8.dp), soft = true) { coordinator.requestPassword() }
                        }
                        if (state.taskId != null) DButton("重试读取", Modifier.fillMaxWidth().padding(top = 8.dp), soft = true, onClick = retry)
                        Buttons("取消任务", coordinator::cancel, if (isFolder) "重新选择文件夹" else "重新选择", { pick(false) })
                    }
                    ArchiveStep.History -> {
                        Hint("文件夹任务使用已准备的副本；压缩包任务保留所选分卷，可继续处理，加密压缩包重启后需重新输入密码。")
                        if (state.history.isEmpty()) Hint("没有压缩包／文件夹导入记录。")
                        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 360.dp)) {
                            items(state.history, key = { it.id }) { task -> DRow(task.mainName, "${if (task.format == "FOLDER") "文件夹" else task.format} · 点击查看或继续", compact = true) { resume(task.id) } }
                        }
                        DButton("关闭", Modifier.fillMaxWidth().padding(top = 18.dp), primary = true) { coordinator.hide() }
                    }
                }
            }
        }
    }
    if (state.step == ArchiveStep.Target) {
        ImportTargetOptionDialog("选择画师", artists.map { it.id to it.name }, state.artistId,
            onDismissRequest = coordinator::back) { id ->
            coordinator.selectArtist(id)
            coordinator.back()
        }
    }
}

@Composable
private fun Hint(text: String) { Text(text, color = DColors.Muted, fontSize = 13.sp, lineHeight = 22.sp, modifier = Modifier.padding(vertical = 10.dp)) }

@Composable
private fun Buttons(left: String, onLeft: () -> Unit, right: String, onRight: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DButton(left, Modifier.weight(1f), onClick = onLeft)
        DButton(right, Modifier.weight(1f), primary = true, enabled = enabled, onClick = onRight)
    }
}
