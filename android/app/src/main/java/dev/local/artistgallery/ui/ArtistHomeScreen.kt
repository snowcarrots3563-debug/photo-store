package dev.local.artistgallery.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.local.artistgallery.data.preview.PreviewDecoder
import dev.local.artistgallery.model.SampleCoverKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtistHomeScreen(state: GalleryUiState, padding: Dp, scrollToArtist: String?, scrolled: () -> Unit, fixtureCover: (@Composable (SampleCoverKey) -> Unit)?, previewDecoder: PreviewDecoder?, onEvent: (GalleryEvent) -> Unit) {
    val scrollState = rememberScrollState()
    val scrollScope = rememberCoroutineScope()
    var scrollTopJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(state.overlay) { if (state.overlay != GalleryOverlay.None) scrollTopJob?.cancel() }
    val scrollToTop: () -> Unit = {
        scrollTopJob?.cancel()
        if (state.overlay == GalleryOverlay.None && scrollState.value > 0) {
            scrollTopJob = scrollScope.launch { scrollState.animateScrollTo(0) }
        }
    }
    val selecting = state.entitySelectionKind == EntitySelectionKind.Artists
    val artists = state.artists.filter { it.name.contains(state.searchQuery.trim()) }
    val reorder = rememberReorderRows(artists.map { it.id }, selecting && !state.isSaving, { scrollState.scrollBy(it) })
    val byId = artists.associateBy { it.id }
    val commitOrder = { ids: List<String> -> onEvent(GalleryEvent.ReorderArtists(ids)) }
    Column(Modifier.fillMaxSize().padding(start = padding, end = padding, top = 28.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            DTitle(if (selecting) "已选择 ${state.selectedEntityIds.size} 项" else "画师", modifier = Modifier.weight(1f), onDoubleTap = scrollToTop)
            if (selecting) Row {
                DIconButton(DIcon.Close, "退出选择", !state.isSaving) { onEvent(GalleryEvent.ExitEntitySelection) }
                DIconButton(DIcon.Trash, "删除所选画师", state.selectedEntityIds.isNotEmpty() && !state.isSaving, tint = DColors.Error) { onEvent(GalleryEvent.OpenDeleteSelectedEntities) }
            } else DIconButton(DIcon.Plus, "添加") { onEvent(GalleryEvent.OpenDialog(GalleryDialog.AddMenu)) }
        }
        Column(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { reorder.viewport = it.boundsInRoot() }.verticalScroll(scrollState).testTag("artistHomeList").padding(bottom = 126.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).background(DColors.Panel, RoundedCornerShape(20.dp)).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                DIconImage(DIcon.Search, DColors.Muted)
                Spacer(Modifier.width(10.dp))
                BasicTextField(state.searchQuery, { onEvent(GalleryEvent.SearchChanged(it)) }, Modifier.weight(1f).heightIn(min = 54.dp).testTag("artistSearch").semantics { contentDescription = "搜索画师" }, singleLine = true,
                    textStyle = TextStyle(fontSize = 16.sp, color = DColors.Ink), cursorBrush = SolidColor(DColors.Action),
                    decorationBox = { field -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) { if (state.searchQuery.isEmpty()) Text("搜索画师", color = DColors.Muted, fontSize = 16.sp); field() } })
                DIconButton(DIcon.Close, "清除搜索") { onEvent(GalleryEvent.ClearSearch) }
            }
            Text("我的画师", color = DColors.Muted, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(start = 16.dp, top = 28.dp, bottom = 10.dp))
            if (!dLoadState(state.loadState) { onEvent(GalleryEvent.RetryLoad) }) {
                if (state.artists.isEmpty()) DEmpty("还没有画师", "先添加一位画师，再创建作品集。", "添加画师", DIcon.Artist) { onEvent(GalleryEvent.OpenDialog(GalleryDialog.AddArtist())) }
                else if (artists.isEmpty()) DEmpty("没有找到画师", "试试更短的名字，或清除搜索。", "清除搜索") { onEvent(GalleryEvent.ClearSearch) }
                else DGroup {
                    reorder.order.mapNotNull { byId[it] }.forEachIndexed { index, artist ->
                        key(artist.id) {
                            val requester = remember { BringIntoViewRequester() }
                            LaunchedEffect(scrollToArtist, artist.id) { if (scrollToArtist == artist.id) { requester.bringIntoView(); scrolled() } }
                            val isSelected = artist.id in state.selectedEntityIds
                            ReorderRow(reorder, artist.id, Modifier.bringIntoViewRequester(requester).testTag("artistRow:${artist.id}").semantics {
                                if (selecting) {
                                    selected = isSelected
                                    toggleableState = if (isSelected) ToggleableState.On else ToggleableState.Off
                                }
                            }) {
                                Column {
                                    if (index > 0) DDivider()
                                    DRow(artist.name, "${artist.collectionCount} 个作品集 · ${artist.imageCount} 张图片", icon = DIcon.Artist, cover = artist.cover, fixtureCover = fixtureCover, previewDecoder = previewDecoder,
                                        onLongClick = if (selecting || state.isSaving) null else ({ onEvent(GalleryEvent.StartEntitySelection(EntitySelectionKind.Artists, artist.id)) }),
                                        trailing = if (selecting) ({
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Checkbox(artist.id in state.selectedEntityIds, onCheckedChange = null)
                                                ReorderHandle(reorder, artist.id, artist.name, !state.isSaving, commitOrder)
                                            }
                                        }) else null) {
                                        if (!state.isSaving) onEvent(if (selecting) GalleryEvent.ToggleEntitySelection(artist.id) else GalleryEvent.OpenArtist(artist.id))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
