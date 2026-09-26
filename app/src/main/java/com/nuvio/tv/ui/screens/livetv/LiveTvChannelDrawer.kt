package com.nuvio.tv.ui.screens.livetv

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.livetv.ChannelGuide
import com.nuvio.tv.domain.model.livetv.LiveChannel

/** UI only: uses the current filtered list and already available guide data. */
@Composable
internal fun LiveTvChannelDrawer(
    channels: List<LiveChannel>, playingKey: String?, guides: Map<String,ChannelGuide>, now: Long,
    modifier: Modifier=Modifier, onClose: ()->Unit, onPlay: (LiveChannel)->Unit
) {
    var index by remember { mutableIntStateOf(channels.indexOfFirst { it.key==playingKey }.coerceAtLeast(0)) }
    LaunchedEffect(channels.size) { index=index.coerceIn(0,(channels.size-1).coerceAtLeast(0)) }
    val list=rememberLazyListState(initialFirstVisibleItemIndex=(index-2).coerceAtLeast(0))
    val focus=remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { };focus.requestFocus() }
    LaunchedEffect(index,channels.size) {
        if(channels.isNotEmpty() && list.layoutInfo.visibleItemsInfo.none { it.index==index }) list.scrollToItem(index)
    }
    Column(modifier.width(340.dp).fillMaxHeight().background(Color(0xF5101216)).padding(16.dp)
        .focusRequester(focus).onPreviewKeyEvent { event ->
            val key=event.nativeKeyEvent.keyCode
            when(key) {
                KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if(event.nativeKeyEvent.action==KeyEvent.ACTION_DOWN && channels.isNotEmpty()) {
                        val next=(index+if(key==KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1).coerceIn(0,channels.lastIndex)
                        index=next
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    // Complete the key sequence here so closing the drawer cannot also pause playback.
                    if(event.nativeKeyEvent.action==KeyEvent.ACTION_UP && !event.nativeKeyEvent.isCanceled)
                        channels.getOrNull(index)?.let(onPlay)
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_BACK -> {
                    if(event.nativeKeyEvent.action==KeyEvent.ACTION_UP) onClose()
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> true
                else -> false
            }
        }.focusable(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.live_channels_title),color=Color.White,fontSize=20.sp)
        if(channels.isEmpty()) Text(stringResource(R.string.live_empty),color=Color(0xFFB0B8C3))
        LazyColumn(Modifier.weight(1f),state=list,verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(2.dp)) {
            itemsIndexed(channels,key={_,c -> c.key}) { i,c ->
                val selected=i==index
                Row(Modifier.fillMaxWidth().height(76.dp)
                    .background(if(selected) Color(0xFF102F49) else Color(0xFF1C2026),RoundedCornerShape(10.dp))
                    .border(if(selected) 2.dp else 1.dp,if(selected) Color(0xFF43B0FF) else Color(0xFF30343B),RoundedCornerShape(10.dp))
                    .padding(10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    AsyncImage(model=c.logo,contentDescription=null,contentScale=ContentScale.Fit,modifier=Modifier.size(42.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name,color=Color.White,fontSize=15.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                        guides[c.key]?.current(now)?.let { Text(it.title,color=Color(0xFFB0B8C3),fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis) }
                    }
                    if(c.key==playingKey) Text("▶",color=Color(0xFF43B0FF),fontSize=13.sp)
                }
            }
        }
    }
}
