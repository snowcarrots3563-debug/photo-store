package dev.local.artistgallery.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.local.artistgallery.R

@Composable
internal fun DeletionProgressDialog() {
    Dialog(onDismissRequest = {}, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
        dismissOnBackPress = false, dismissOnClickOutside = false,
    )) {
        Box(Modifier.fillMaxSize().background(DColors.Ink.copy(alpha = .2f)).testTag("deletion-window"), contentAlignment = Alignment.Center) {
            Row(Modifier.padding(horizontal = 24.dp).widthIn(max = 320.dp).fillMaxWidth()
                .clip(RoundedCornerShape(26.dp)).background(DColors.Panel).testTag("deletion-progress")
                .padding(horizontal = 26.dp, vertical = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(28.dp), color = DColors.Action, strokeWidth = 3.dp)
                Spacer(Modifier.width(20.dp))
                Text("正在删除…", color = DColors.Ink, fontSize = 17.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
}

@Composable
internal fun BoxScope.DeletionCompleteNotice(count: Int) {
    Row(Modifier.align(Alignment.BottomCenter).padding(start = 20.dp, end = 20.dp, bottom = 112.dp)
        .clip(RoundedCornerShape(22.dp)).background(Color(0xFFF0EFF0).copy(alpha = .96f))
        .testTag("deletion-complete").padding(horizontal = 20.dp, vertical = 16.dp)
        .semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.mipmap.ic_launcher), contentDescription = null, modifier = Modifier.size(26.dp).testTag("deletion-app-icon"))
        Spacer(Modifier.width(12.dp))
        Text("${count}项已删除", color = DColors.Ink, fontSize = 15.sp, lineHeight = 22.sp)
    }
}
