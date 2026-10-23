package dev.local.artistgallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import dev.local.artistgallery.data.preview.PreviewDecoder
import dev.local.artistgallery.data.exporting.ExportScope
import dev.local.artistgallery.model.MediaSort
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

@Composable
fun GalleryDialogs(state: GalleryUiState, toast: String?, onEvent: (GalleryEvent) -> Unit, onArchive: (() -> Unit)? = null, previewDecoder: PreviewDecoder? = null,
                   onFolder: (() -> Unit)? = null,
                   onExport: ((ExportScope) -> Unit)? = null) {
    if (state.isDeleting) {
        DeletionProgressDialog()
        return
    }
    val dialog = state.dialog
    val allowed = when (dialog) {
        GalleryDialog.None -> false
        GalleryDialog.ArtistImageSort -> state.route is GalleryRoute.Artist && state.artistTab == ArtistTab.Images && !state.mediaSelectionMode && state.entitySelectionKind == null && !state.isSaving && state.overlay == GalleryOverlay.None
        GalleryDialog.CollectionMediaSort -> (state.route as? GalleryRoute.Collection)?.id?.let { state.collection?.id == it } == true && state.loadState == LoadState.Ready && state.mediaLoadState == LoadState.Ready && !state.mediaSelectionMode && state.entitySelectionKind == null && !state.isSaving && state.overlay == GalleryOverlay.None
        else -> true
    }
    var centered by remember { mutableStateOf(false) }
    if (allowed) centered = dialog == GalleryDialog.ArtistImageSort || dialog == GalleryDialog.CollectionMediaSort
    DAnimatedDialog(state.takeIf { allowed }, { if (!state.isSaving) onEvent(GalleryEvent.DismissDialog) },
        DialogProperties(decorFitsSystemWindows = false, dismissOnBackPress = !state.isSaving, dismissOnClickOutside = !state.isSaving), centered) { presentation, panel, fraction ->
        GalleryDialogContent(presentation, toast, { if (allowed) onEvent(it) }, onArchive, previewDecoder, onFolder, onExport, panel, fraction)
    }
}

@Composable
private fun GalleryDialogContent(state: GalleryUiState, toast: String?, onEvent: (GalleryEvent) -> Unit, onArchive: (() -> Unit)?, previewDecoder: PreviewDecoder?,
                                 onFolder: (() -> Unit)?, onExport: ((ExportScope) -> Unit)?, modifier: Modifier, fraction: Float) {
    val dialog = state.dialog
    if (dialog == GalleryDialog.ArtistImageSort) {
        val artistId = (state.route as? GalleryRoute.Artist)?.id ?: return
        ImportTargetOptionContent(if ((state.artistDetail?.artist?.videoCount ?: 0) > 0) "媒体排序" else "图片排序", MediaSort.entries.map { it.name to it.label },
            (state.browseOptions.artistImageSorts[artistId] ?: MediaSort.Default).name,
            { onEvent(GalleryEvent.DismissDialog) }, modifier, fraction) { id ->
            onEvent(GalleryEvent.SetArtistImageSort(MediaSort.valueOf(id)))
        }
        return
    }
    if (dialog == GalleryDialog.CollectionMediaSort) {
        val collectionId = (state.route as? GalleryRoute.Collection)?.id ?: return
        ImportTargetOptionContent("图集排序", MediaSort.entries.map { it.name to if (it == MediaSort.Default) "默认顺序（文件名称升序）" else it.label },
            (state.browseOptions.collectionMediaSorts[collectionId] ?: MediaSort.Default).name,
            { onEvent(GalleryEvent.DismissDialog) }, modifier, fraction) { id ->
            onEvent(GalleryEvent.SetCollectionMediaSort(MediaSort.valueOf(id)))
        }
        return
    }
    if (dialog == GalleryDialog.UpdateHistory) {
        val context = LocalContext.current
        val updates = remember(context) { dev.local.artistgallery.data.LocalUpdateHistory(context).all() }
        UpdateNotesContent(updates, true, { onEvent(GalleryEvent.DismissDialog) }, modifier, fraction)
        return
    }
    fun open(d: GalleryDialog) { onEvent(GalleryEvent.OpenDialog(d)) }
    fun unavailable(feature: String, phase: Int) { open(GalleryDialog.Unavailable(feature, phase)) }
    val title = when (dialog) {
        GalleryDialog.AddMenu -> if (state.route == GalleryRoute.Home) "添加" else "导入"
        is GalleryDialog.AddArtist -> "添加画师"
        GalleryDialog.ArtistMenu -> "画师操作"
        GalleryDialog.CollectionMenu -> "作品集操作"
        is GalleryDialog.CreateCollection -> "新建作品集"
        is GalleryDialog.RenameCollection -> "作品集改名"
        is GalleryDialog.RenameArtist -> "修改画师名字"
        is GalleryDialog.DeleteArtist -> "删除整个画师"
        is GalleryDialog.DeleteArtists -> "批量删除画师"
        is GalleryDialog.DeleteCollections -> "批量删除作品集"
        GalleryDialog.ImageDisplayMenu -> "图片显示方式"
        GalleryDialog.ArtistImageSort -> "图片排序"
        GalleryDialog.CollectionMediaSort -> "图集排序"
        is GalleryDialog.Unavailable -> "功能尚未开放"
        GalleryDialog.About -> "应用说明"
        GalleryDialog.UpdateHistory -> "更新日志"
        is GalleryDialog.ImportTarget -> if (state.pendingAddAssetIds.isNotEmpty()) "加入作品集" else "选择导入位置"
        GalleryDialog.ImportPreview -> "确认导入"
        GalleryDialog.ImportProgress -> "正在导入"
        is GalleryDialog.ImportResult -> "导入结果"
        is GalleryDialog.RemoveLastReference -> "移除最后一个引用"
        is GalleryDialog.DeleteMedia -> "删除媒体"
        is GalleryDialog.DeleteSelectedMedia -> "批量删除媒体"
        is GalleryDialog.ArtistCover -> "更换画师封面"
        GalleryDialog.None -> ""
    }
    val isImportRunning = dialog == GalleryDialog.ImportProgress
    run {
        val hasInput = dialog is GalleryDialog.AddArtist || dialog is GalleryDialog.CreateCollection || dialog is GalleryDialog.RenameCollection || dialog is GalleryDialog.RenameArtist
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(hasInput) { if (!hasInput) keyboard?.hide() }
        val dialogInsets = galleryDialogWindowInsets(includeIme = hasInput)
        BoxWithConstraints(Modifier.fillMaxSize().background(DColors.Ink.copy(alpha = .2f * fraction)).testTag("galleryDialogBackdrop").windowInsetsPadding(dialogInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).clickable(enabled = LocalDDialogActive.current, interactionSource = remember { MutableInteractionSource() }, indication = null) { if (!isImportRunning && !state.isSaving) onEvent(GalleryEvent.DismissDialog) }, contentAlignment = Alignment.BottomCenter) {
            val inset = if (maxWidth <= 370.dp) 20.dp else 22.dp
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).widthIn(max = 398.dp).fillMaxWidth().heightIn(max = maxHeight - 16.dp)
                .then(modifier).clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)).background(DColors.Panel)
                .windowInsetsPadding(dialogInsets.only(WindowInsetsSides.Bottom))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .then(if (dialog is GalleryDialog.ArtistCover) Modifier else Modifier.verticalScroll(rememberScrollState())).padding(inset).semantics { paneTitle = title }) {
                Row(Modifier.fillMaxWidth().layout { measurable, constraints ->
                    val margin = 6.dp.roundToPx()
                    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + margin * 2, maxWidth = constraints.maxWidth + margin * 2))
                    layout(placeable.width - margin * 2, placeable.height - margin) { placeable.placeRelative(-margin, -margin) }
                }.padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (dialog is GalleryDialog.AddArtist) DIconButton(DIcon.Back, "上一步", !state.isSaving) {
                        if (dialog.fromArchiveImport) onEvent(GalleryEvent.DismissDialog) else open(GalleryDialog.AddMenu)
                    }
                    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).padding(start = 6.dp).semantics { heading() })
                    DIconButton(DIcon.Close, if (isImportRunning) "关闭导入进度" else "取消", !state.isSaving) { onEvent(GalleryEvent.DismissDialog) }
                }
                when (dialog) {
                    GalleryDialog.AddMenu -> {
                        DialogHint("选择来源，或添加画师。")
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(DColors.Background).padding(horizontal = 12.dp)) {
                            DRow("从相册导入", "选择手机中的图片、GIF 或视频", DIcon.Image, compact = true) { onEvent(GalleryEvent.OpenImportTarget()) }
                            DDivider(0)
                            DRow("从文件导入", "在系统文件管理中选择图片、GIF 或视频", DIcon.Import, compact = true) { onEvent(GalleryEvent.OpenImportTarget(fromFiles = true)) }
                            DDivider(0)
                            DRow("从压缩包导入", "ZIP、RAR、7z，支持密码与分卷", DIcon.Archive, compact = true) { if (onArchive != null) onArchive() else unavailable("从压缩包导入", 4) }
                            DDivider(0)
                            DRow("从文件夹导入", "递归子目录，按目录新建作品集", DIcon.Import, compact = true) { if (onFolder != null) onFolder() else unavailable("从文件夹导入", 4) }
                            if (state.route == GalleryRoute.Home) {
                                DDivider(0)
                                DRow("添加画师", icon = DIcon.Plus, compact = true) { open(GalleryDialog.AddArtist()) }
                            }
                        }
                    }
                    GalleryDialog.ArtistMenu -> {
                        val id = (state.route as? GalleryRoute.Artist)?.id
                        DRow("修改画师名字", compact = true) { state.artistDetail?.artist?.let { open(GalleryDialog.RenameArtist(it.id, it.name)) } }
                        DDivider(4)
                        DRow("新建作品集", icon = DIcon.Plus, compact = true) { if (id != null) open(GalleryDialog.CreateCollection(id)) }
                        DDivider(4)
                        DRow("更换封面", icon = DIcon.Image, compact = true) { if (id != null) onEvent(GalleryEvent.OpenArtistCover(id)) }
                        DDivider(4)
                        DRow("从相册导入", icon = DIcon.Image, compact = true) { onEvent(GalleryEvent.OpenImportTarget(artistId = id)) }
                        DDivider(4)
                        DRow("从文件导入", icon = DIcon.Import, compact = true) { onEvent(GalleryEvent.OpenImportTarget(artistId = id, fromFiles = true)) }
                        DDivider(4)
                        DRow("导出这位画师", icon = DIcon.Export, compact = true) {
                            if (onExport != null && id != null) { onEvent(GalleryEvent.DismissDialog); onExport(ExportScope.Artists(listOf(id))) }
                            else unavailable("导出这位画师", 5)
                        }
                        DDivider(4)
                        DRow("删除整个画师", icon = DIcon.Close, compact = true) { state.artistDetail?.artist?.let { open(GalleryDialog.DeleteArtist(it.id, it.name)) } }
                    }
                    GalleryDialog.CollectionMenu -> {
                        DRow("作品集改名", compact = true) { state.collection?.let { open(GalleryDialog.RenameCollection(it.id, it.name)) } }
                        DDivider(4)
                        DRow("导入", icon = DIcon.Import, compact = true) { state.collection?.let { onEvent(GalleryEvent.OpenImportTarget(it.artistId, it.id)) } }
                        DDivider(4)
                        DRow("导出这个作品集", icon = DIcon.Export, compact = true) {
                            val id = state.collection?.id
                            if (onExport != null && id != null) { onEvent(GalleryEvent.DismissDialog); onExport(ExportScope.Collections(listOf(id))) }
                            else unavailable("导出这个作品集", 5)
                        }
                    }
                    is GalleryDialog.AddArtist, is GalleryDialog.CreateCollection, is GalleryDialog.RenameCollection, is GalleryDialog.RenameArtist -> NameForm(dialog, state.isSaving, state.fieldError, onEvent)
                    GalleryDialog.ImageDisplayMenu -> {
                        dev.local.artistgallery.data.ArtistImageLayout.entries.forEachIndexed { index, layout ->
                            if (index > 0) DDivider(4)
                            DRow(layout.label, icon = if (state.browseOptions.imageLayout == layout) DIcon.Check else DIcon.Image, compact = true) { onEvent(GalleryEvent.SetImageLayout(layout)) }
                        }
                    }
                    is GalleryDialog.DeleteArtist -> {
                        DialogHint("确认删除画师“${dialog.name}”？")
                        DialogHint("将永久删除这位画师、全部作品集，以及其中的图片、GIF 和视频图库副本。共享副本也会彻底删除，并从其他画师和作品集中移除。因此变空的作品集也会自动删除。此操作无法撤销。")
                        DialogHint("相册、文件管理和压缩包里的源文件不受影响。")
                        state.fieldError?.let { Text(it, color = DColors.Error, fontSize = 13.sp) }
                        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            DButton("取消", Modifier.weight(1f), enabled = !state.isSaving) { onEvent(GalleryEvent.DismissDialog) }
                            DButton(if (state.isSaving) "正在删除" else "确认删除", Modifier.weight(1f), primary = true, enabled = !state.isSaving) { onEvent(GalleryEvent.ConfirmDeleteArtist) }
                        }
                    }
                    is GalleryDialog.DeleteArtists, is GalleryDialog.DeleteCollections -> {
                        val deletingArtists = dialog is GalleryDialog.DeleteArtists
                        val count = when (dialog) {
                            is GalleryDialog.DeleteArtists -> dialog.ids.size
                            is GalleryDialog.DeleteCollections -> dialog.ids.size
                            else -> 0
                        }
                        DialogHint(if (deletingArtists) "确认删除选中的 $count 位画师？" else "确认删除选中的 $count 个作品集？")
                        DialogHint(if (deletingArtists) "将永久删除这些画师、全部作品集，以及其中的图片、GIF 和视频图库副本。" else "将永久删除这些作品集，以及其中的图片、GIF 和视频图库副本。")
                        DialogHint("共享副本也会彻底删除，并从其他画师和作品集中移除。因此变空的作品集也会自动删除。此操作无法撤销。相册、文件管理和压缩包里的源文件不受影响。")
                        state.fieldError?.let { Text(it, color = DColors.Error, fontSize = 13.sp) }
                        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            DButton("取消", Modifier.weight(1f), enabled = !state.isSaving) { onEvent(GalleryEvent.DismissDialog) }
                            DButton(if (state.isSaving) "正在删除" else "确认删除", Modifier.weight(1f), primary = true, enabled = !state.isSaving && count > 0) { onEvent(GalleryEvent.ConfirmDeleteSelectedEntities) }
                        }
                    }
                    is GalleryDialog.Unavailable -> {
                        DialogHint("此功能将在阶段 ${dialog.phase} 开放，本次没有读取或写入文件。")
                        DButton("知道了", Modifier.fillMaxWidth().padding(top = 22.dp), primary = true) { onEvent(GalleryEvent.DismissDialog) }
                    }
                    GalleryDialog.About -> {
                        DialogHint("私人画师图库，收藏内容保存在本机，不上传。")
                        Column(Modifier.fillMaxWidth().background(DColors.Background, RoundedCornerShape(22.dp)).padding(14.dp)) {
                            Text("当前可用", fontSize = 14.sp, lineHeight = 21.sp)
                            DialogHint("画师与作品集管理、相册／文件导入、原图／GIF／漫画／卡牌浏览、ZIP／RAR／7z 密码与分卷导入，以及按范围导出、完整备份和合并恢复。")
                            DDivider(0)
                            Text("后续计划", fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 14.dp))
                            DialogHint("阶段 6：分享当前图片。")
                        }
                    }
                    is GalleryDialog.ImportTarget -> ImportTargetContent(state, dialog, onEvent)
                    GalleryDialog.ImportPreview -> ImportPreviewContent(state, onEvent)
                    GalleryDialog.ImportProgress -> ImportProgressContent(state)
                    is GalleryDialog.ImportResult -> ImportResultContent(state, dialog, onEvent)
                    is GalleryDialog.RemoveLastReference -> {
                        state.fieldError?.let { Text(it, color = DColors.Error, fontSize = 13.sp) }
                        RemoveLastReferenceContent(onEvent)
                    }
                    is GalleryDialog.DeleteMedia -> {
                        DialogHint(dialog.name)
                        DialogHint("删除这个图库副本，并移除所有画师及作品集中的引用。因此变空的作品集也会自动删除。相册、文件管理和压缩包里的源文件不受影响。")
                        state.fieldError?.let { Text(it, color = DColors.Error, fontSize = 13.sp) }
                        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            DButton("取消", Modifier.weight(1f), enabled = !state.isSaving) { onEvent(GalleryEvent.DismissDialog) }
                            DButton(if (state.isSaving) "正在删除" else "删除", Modifier.weight(1f), primary = true, enabled = !state.isSaving) { onEvent(GalleryEvent.ConfirmDeleteMedia) }
                        }
                    }
                    is GalleryDialog.DeleteSelectedMedia -> {
                        DialogHint("已选择 ${dialog.assetIds.size} 项")
                        DialogHint("删除这些图库副本，并移除所有画师及作品集中的对应引用。因此变空的作品集也会自动删除。相册、文件管理和压缩包里的源文件不受影响。")
                        state.fieldError?.let { Text(it, color = DColors.Error, fontSize = 13.sp) }
                        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            DButton("取消", Modifier.weight(1f), enabled = !state.isSaving) { onEvent(GalleryEvent.DismissDialog) }
                            DButton(if (state.isSaving) "正在删除" else "确认删除", Modifier.weight(1f), primary = true, enabled = !state.isSaving) { onEvent(GalleryEvent.ConfirmDeleteSelectedMedia) }
                        }
                    }
                    is GalleryDialog.ArtistCover -> {
                        DialogHint("从这位画师已导入的图片中选择封面。")
                        when (state.coverLoadState) {
                            LoadState.Loading -> DialogHint("正在读取图片…")
                            LoadState.Failed -> DButton("读取失败，点击重试", Modifier.fillMaxWidth(), soft = true) { onEvent(GalleryEvent.OpenArtistCover(dialog.artistId)) }
                            LoadState.Ready -> if (state.coverItems.isEmpty()) DialogHint("这位画师还没有图片，请先导入。")
                                else LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(state.coverItems, key = { it.id }) { media ->
                                        MediaListItem(media, previewDecoder, selected = media.id == dialog.selectedAssetId,
                                            onSelect = { onEvent(GalleryEvent.SelectArtistCover(media.id)) })
                                    }
                                }
                        }
                        state.fieldError?.let { Text(it, color = DColors.Error, fontSize = 13.sp) }
                        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            DButton("取消", Modifier.weight(1f), enabled = !state.isSaving) { onEvent(GalleryEvent.DismissDialog) }
                            DButton(if (state.isSaving) "正在保存" else "设为封面", Modifier.weight(1f), primary = true,
                                enabled = !state.isSaving && state.coverItems.any { it.id == dialog.selectedAssetId }) { onEvent(GalleryEvent.ConfirmArtistCover) }
                        }
                    }
                    GalleryDialog.None, GalleryDialog.UpdateHistory, GalleryDialog.ArtistImageSort, GalleryDialog.CollectionMediaSort -> Unit
                }
            }
            toast?.let { message ->
                Box(Modifier.align(Alignment.BottomCenter).widthIn(max = 430.dp).fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 112.dp), contentAlignment = Alignment.Center) {
                    Text(message, color = Color.White, fontSize = 13.sp, lineHeight = 20.sp,
                        modifier = Modifier.background(Color(0xFF252A33), RoundedCornerShape(18.dp)).padding(horizontal = 18.dp, vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        }
    }
}

@Composable
private fun DialogHint(text: String) { Text(text, fontSize = 13.sp, lineHeight = 22.sp, color = DColors.Muted, modifier = Modifier.padding(vertical = 12.dp)) }

@Composable
private fun ImportTargetContent(state: GalleryUiState, target: GalleryDialog.ImportTarget, onEvent: (GalleryEvent) -> Unit) {
    val addingExisting = state.pendingAddAssetIds.isNotEmpty()
    val defaultName = state.importTargetCollections.firstOrNull { it.isDefault }?.name ?: "默认图集"
    val defaultReady = !addingExisting && target.artistId != null && (target.allowDefault || state.importTargetCollections.any { it.isDefault })
    DialogHint(if (addingExisting) "选择要加入这些媒体的作品集。" else if (defaultReady) "未选择其他作品集时，将导入到$defaultName。" else "选择目标作品集，也可以先导入到未归组媒体。")
    val selectedArtist = state.artists.firstOrNull { it.id == target.artistId }
    var artistMenu by remember { mutableStateOf(false) }
    DButton(selectedArtist?.name ?: "选择画师", soft = true, modifier = Modifier.fillMaxWidth()) { artistMenu = true }
    ImportTargetOptionDialog("选择画师", state.artists.map { it.id to it.name }, target.artistId,
            onDismissRequest = { artistMenu = false }, visible = artistMenu) { id ->
            artistMenu = false
            onEvent(GalleryEvent.ImportArtistSelected(id))
    }
    val selectedCollection = state.importTargetCollections.firstOrNull { it.id == target.collectionId }
    if (target.artistId != null) {
        Spacer(Modifier.height(10.dp))
        var collectionMenu by remember { mutableStateOf(false) }
        val options = if (defaultReady) state.importTargetCollections.filterNot { it.isDefault } else state.importTargetCollections
        DButton(selectedCollection?.name ?: if (defaultReady && !target.toUnassigned) defaultName else "选择作品集", soft = true, modifier = Modifier.fillMaxWidth(), enabled = options.isNotEmpty()) { collectionMenu = true }
        ImportTargetOptionDialog("选择作品集", options.map { it.id to it.name }, target.collectionId,
                onDismissRequest = { collectionMenu = false }, visible = collectionMenu) { id ->
                collectionMenu = false
                onEvent(GalleryEvent.ImportCollectionSelected(id))
        }
        if (defaultReady && (target.collectionId != null || target.toUnassigned)) {
            DButton("使用$defaultName", modifier = Modifier.fillMaxWidth().padding(top = 8.dp), soft = true) { onEvent(GalleryEvent.ImportCollectionSelected(null)) }
        }
        if (state.importTargetCollections.isEmpty() && !defaultReady) DialogHint("该画师还没有作品集，请先创建作品集，或导入到未归组媒体。")
    }
    if (!addingExisting) {
        DButton("导入到未归组媒体", modifier = Modifier.fillMaxWidth().padding(top = 8.dp), soft = target.toUnassigned) {
            if (!target.toUnassigned) onEvent(GalleryEvent.ToggleUnassignedImportTarget)
        }
        if (target.toUnassigned) DialogHint("文件会保存在图库中，稍后可以加入作品集。")
    }
    if (state.fieldError != null) Text(state.fieldError, color = DColors.Error, fontSize = 13.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 8.dp))
    Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DButton("取消", Modifier.weight(1f)) { onEvent(GalleryEvent.DismissDialog) }
        DButton(if (addingExisting) "加入所选" else if (target.fromFiles) "选择文件" else "选择媒体", Modifier.weight(1f), primary = true, enabled = if (addingExisting) target.collectionId != null else defaultReady || target.toUnassigned || target.collectionId != null) { onEvent(GalleryEvent.ConfirmImportTarget) }
    }
}

@Composable
internal fun ImportTargetOptionDialog(
    title: String,
    options: List<Pair<String, String>>,
    selectedId: String?,
    onDismissRequest: () -> Unit,
    visible: Boolean = true,
    onSelect: (String) -> Unit,
) {
    DAnimatedDialog(Triple(title, options, selectedId).takeIf { visible }, onDismissRequest, centered = true) { model, panel, fraction ->
        ImportTargetOptionContent(model.first, model.second, model.third, onDismissRequest, panel, fraction, onSelect)
    }
}

@Composable
private fun ImportTargetOptionContent(title: String, options: List<Pair<String, String>>, selectedId: String?, onDismissRequest: () -> Unit,
                                      modifier: Modifier, fraction: Float, onSelect: (String) -> Unit) {
        val active = LocalDDialogActive.current
        val dialogInsets = galleryDialogWindowInsets()
        Box(Modifier.fillMaxSize().background(DColors.Ink.copy(alpha = .2f * fraction)).windowInsetsPadding(dialogInsets)
            .clickable(enabled = active, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismissRequest), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(260.dp).heightIn(max = 360.dp).then(modifier).clip(RoundedCornerShape(28.dp))
                    .background(DColors.Panel).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                    .verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
            ) {
                Text(title, fontSize = 13.sp, lineHeight = 20.sp, color = DColors.Muted,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 6.dp))
                options.forEach { (id, label) ->
                    val selected = id == selectedId
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp)
                            .semantics { this.selected = selected }
                            .clickable(enabled = active, role = Role.RadioButton) { onSelect(id) }
                            .padding(horizontal = 22.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, Modifier.weight(1f), fontSize = 16.sp, lineHeight = 24.sp,
                            color = if (selected) DColors.Action else DColors.Ink)
                        if (selected) DIconImage(DIcon.Check, DColors.Action, 18.dp)
                    }
                }
            }
        }
}

@Composable
private fun ImportPreviewContent(state: GalleryUiState, onEvent: (GalleryEvent) -> Unit) {
    DialogHint("已选择 ${state.importCandidates.size} 个文件。图库只保存一份原文件，完全相同的内容会复用。")
    DGroup {
        state.importCandidates.forEachIndexed { index, item ->
            if (index > 0) DDivider()
            DRow(item.name, item.error ?: item.skipReason ?: when (item.type) {
                dev.local.artistgallery.model.MediaType.GIF -> "GIF 动图"
                dev.local.artistgallery.model.MediaType.VIDEO -> "视频原文件"
                dev.local.artistgallery.model.MediaType.IMAGE -> "图片"
                null -> "无法读取所选媒体"
            }, DIcon.Image, compact = true) { }
        }
    }
    val defaultArtistId = state.pendingImportDefaultArtistId
    val defaultName = state.importTargetCollections.firstOrNull { it.artistId == defaultArtistId && it.isDefault }?.name
        ?: state.artistDetail?.takeIf { it.artist.id == defaultArtistId }?.collections?.firstOrNull { it.isDefault }?.name ?: "默认图集"
    DialogHint(if (defaultArtistId != null) "导入后会加入$defaultName。" else if (state.pendingImportTargetCollectionId == null) "导入后会出现在未归组媒体中。" else "导入后会加入所选作品集。")
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DButton("取消", Modifier.weight(1f)) { onEvent(GalleryEvent.DismissDialog) }
        DButton("开始导入", Modifier.weight(1f), primary = true) { onEvent(GalleryEvent.ConfirmImport) }
    }
}

@Composable
private fun ImportProgressContent(state: GalleryUiState) {
    val progress = state.importProgress
    DialogHint("关闭此窗口或离开页面不会取消导入。已完成项会保留；中断项可能需要继续、重试或重新选择来源。")
    DGroup(Modifier.padding(top = 8.dp)) {
        Text(progress?.sourceName ?: "正在准备媒体…", fontSize = 15.sp, modifier = Modifier.padding(16.dp))
        Text(if (progress == null) "正在读取文件信息…" else "第 ${progress.currentItem} / ${progress.itemCount} 项 · ${formatBytes(progress.copiedBytes)}",
            fontSize = 13.sp, lineHeight = 20.sp, color = DColors.Muted, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
    }
}

@Composable
private fun ImportResultContent(state: GalleryUiState, dialog: GalleryDialog.ImportResult, onEvent: (GalleryEvent) -> Unit) {
    val result = state.importResult?.takeIf { it.id == dialog.batchId }
    val items = result?.items.orEmpty()
    val added = items.count { it.status == dev.local.artistgallery.model.ImportItemStatus.COMPLETE }
    val reused = items.count { it.status == dev.local.artistgallery.model.ImportItemStatus.REUSED }
    val skipped = items.count { it.status == dev.local.artistgallery.model.ImportItemStatus.SKIPPED }
    val failed = items.filter { it.status in setOf(dev.local.artistgallery.model.ImportItemStatus.FAILED, dev.local.artistgallery.model.ImportItemStatus.STAGED, dev.local.artistgallery.model.ImportItemStatus.FINALIZED) }
    DialogHint("新增 $added 项，复用 $reused 项，跳过 $skipped 项，失败或待恢复 ${failed.size} 项。")
    if (failed.isNotEmpty()) DGroup(Modifier.padding(top = 8.dp)) {
        failed.forEachIndexed { index, item ->
            if (index > 0) DDivider()
            DRow(item.sourceName, item.error ?: "导入失败。", DIcon.Image, compact = true) { }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (failed.any { it.canRetry }) DButton("重试失败项", Modifier.weight(1f), soft = true) { onEvent(GalleryEvent.RetryImport(dialog.batchId)) }
        DButton("完成", Modifier.weight(1f), primary = true) { onEvent(GalleryEvent.DismissDialog) }
    }
}

@Composable
private fun RemoveLastReferenceContent(onEvent: (GalleryEvent) -> Unit) {
    DialogHint("这是最后一个作品集引用。取消时不做任何更改；保留会移到未归组媒体；删除时因此变空的作品集也会自动删除，只删除图库副本，不会改动系统相册原文件。")
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DButton("取消", Modifier.weight(1f)) { onEvent(GalleryEvent.DismissDialog) }
        DButton("保留", Modifier.weight(1f), soft = true) { onEvent(GalleryEvent.KeepLastReference) }
        DButton("删除", Modifier.weight(1f), primary = true) { onEvent(GalleryEvent.DeleteLastReference) }
    }
}

private fun formatBytes(value: Long): String = if (value < 1024 * 1024) "${value / 1024} KB" else "${value / (1024 * 1024)} MB"

@Composable
private fun NameForm(dialog: GalleryDialog, saving: Boolean, error: String?, onEvent: (GalleryEvent) -> Unit) {
    val artist = dialog is GalleryDialog.AddArtist || dialog is GalleryDialog.RenameArtist
    val draft = when (dialog) {
        is GalleryDialog.AddArtist -> dialog.draft
        is GalleryDialog.CreateCollection -> dialog.draft
        is GalleryDialog.RenameCollection -> dialog.draft
        is GalleryDialog.RenameArtist -> dialog.draft
        else -> ""
    }
    if (!artist) DialogHint("留空将生成新的临时名称，之后可随时改名。")
    val label = if (artist) "画师名称" else "作品集名称"
    Text(label, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val active = LocalDDialogActive.current
    LaunchedEffect(dialog::class, active) { if (artist && active) focusRequester.requestFocus() }
    BasicTextField(draft, { onEvent(GalleryEvent.DraftChanged(it)) },
        Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("nameInput").focusRequester(focusRequester).onFocusChanged { focused = it.isFocused }
            .background(DColors.Background, RoundedCornerShape(20.dp)).border(if (focused) 3.dp else 1.dp, if (focused) DColors.Action else DColors.Line, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp).semantics { contentDescription = label; if (error != null) this.error(error) },
        enabled = !saving && active, singleLine = true, textStyle = TextStyle(fontSize = 17.sp, color = DColors.Ink), cursorBrush = SolidColor(DColors.Action),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { onEvent(GalleryEvent.Submit) }),
        decorationBox = { field -> Box(contentAlignment = Alignment.CenterStart) { if (draft.isEmpty()) Text(if (artist) "请输入画师名称" else "暂时还没想好", fontSize = 17.sp, color = DColors.Muted); field() } })
    if (error != null) Text(error, color = DColors.Error, fontSize = 13.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
    Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DButton("取消", Modifier.weight(1f), enabled = !saving) { onEvent(GalleryEvent.DismissDialog) }
        DButton(if (saving) "保存中…" else if (dialog is GalleryDialog.AddArtist) "添加" else if (dialog is GalleryDialog.RenameCollection || dialog is GalleryDialog.RenameArtist) "保存名称" else "创建作品集", Modifier.weight(1f), primary = true, enabled = !saving) { onEvent(GalleryEvent.Submit) }
    }
}
