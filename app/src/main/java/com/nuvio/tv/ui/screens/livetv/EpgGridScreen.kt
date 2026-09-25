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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.livetv.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged

private const val EpgHalfHour = 1_800_000L
private const val EpgWindow = 7_200_000L
private val EpgBlue = Color(0xFF43B0FF)

/** A single time origin and scale shared by every row and the ruler. */
private data class EpgAxis(val start: Long, val width: Float) {
    fun offset(time: Long) = ((time-start).toDouble()/EpgWindow*width).toFloat().dp
    fun width(start: Long, end: Long) = ((end-start).toDouble()/EpgWindow*width).toFloat().coerceAtLeast(0f).dp
}

private fun nearestProgram(programs: List<EpgProgram>, time: Long): EpgProgram? =
    programs.firstOrNull { it.start<=time && it.end>time }
        ?: programs.minByOrNull { if(time<it.start) it.start-time else time-it.end }

/** One focus owner implements remote navigation over virtualized, non-focusable cells. */
@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
internal fun EpgGridScreen(
    channels: List<LiveChannel>, guides: Map<String,ChannelGuide>, now: Long, selectedKey: String?,
    onWindow: (Long?,List<String>)->Unit, onClose: ()->Unit, onPlay: (LiveChannel)->Unit
) {
    val origin=rememberSaveable { now/EpgHalfHour*EpgHalfHour }
    var start by rememberSaveable { mutableLongStateOf(origin) }
    var cursor by rememberSaveable { mutableLongStateOf(now) }
    var row by rememberSaveable { mutableIntStateOf(channels.indexOfFirst { it.key==selectedKey }.coerceAtLeast(0)) }
    val list=rememberLazyListState(initialFirstVisibleItemIndex=row)
    val focus=remember { FocusRequester() };val back=remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val channel=channels.getOrNull(row)
    val program=nearestProgram(guides[channel?.key]?.programs.orEmpty(),cursor)
    val format=remember { SimpleDateFormat("HH:mm",Locale.getDefault()) }
    val dateFormat=remember { SimpleDateFormat("EEE dd/MM",Locale.getDefault()) }
    val latestWindow by rememberUpdatedState(onWindow)
    DisposableEffect(Unit) { onDispose { latestWindow(null,emptyList()) } }
    LaunchedEffect(start,list) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }
            .distinctUntilChanged().collect { latestWindow(start,it) }
    }
    LaunchedEffect(channels.size) { row=row.coerceIn(0,(channels.size-1).coerceAtLeast(0)) }
    LaunchedEffect(row) {
        if(channels.isNotEmpty() && list.layoutInfo.visibleItemsInfo.none { it.index==row }) list.scrollToItem(row)
    }
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        LaunchedEffect(Unit) { withFrameNanos { };focus.requestFocus() }
        Column(Modifier.fillMaxSize().background(Color(0xFF0B0C0E)).padding(24.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(stringResource(R.string.live_epg_grid),Modifier.weight(1f),color=Color.White,fontSize=24.sp)
                Text(dateFormat.format(Date(cursor)),Modifier.padding(end=20.dp),color=EpgBlue,fontSize=16.sp)
                Button(onClick=onClose,modifier=Modifier.focusRequester(back).onPreviewKeyEvent {
                    if(it.nativeKeyEvent.keyCode==KeyEvent.KEYCODE_DPAD_DOWN) {
                        if(it.nativeKeyEvent.action==KeyEvent.ACTION_DOWN) focus.requestFocus()
                        true
                    } else false
                }) { Text(stringResource(R.string.epg_back_channels)) }
            }
            Row(Modifier.fillMaxWidth().height(106.dp).padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                AsyncImage(model=channel?.logo,contentDescription=null,contentScale=ContentScale.Fit,
                    modifier=Modifier.size(58.dp).background(Color(0xFF20242A),RoundedCornerShape(8.dp)).padding(6.dp))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Text(channel?.name.orEmpty(),color=Color.White,fontSize=17.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(program?.let { "${format.format(Date(it.start))} – ${format.format(Date(it.end))}  ${it.title}" }
                        ?:stringResource(R.string.live_no_epg),color=EpgBlue,fontSize=16.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(program?.description.orEmpty(),color=Color(0xFFB0B8C3),fontSize=13.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth().focusRequester(focus).onFocusChanged { focused=it.isFocused }
                .onPreviewKeyEvent { event ->
                    val key=event.nativeKeyEvent.keyCode
                    if(key !in listOf(KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,
                            KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER)) false
                    else {
                        if(event.nativeKeyEvent.action==KeyEvent.ACTION_DOWN && event.nativeKeyEvent.repeatCount==0) {
                            when(key) {
                                KeyEvent.KEYCODE_DPAD_UP -> if(row>0) row-- else back.requestFocus()
                                KeyEvent.KEYCODE_DPAD_DOWN -> if(row<channels.lastIndex) row++
                                KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    val programs=guides[channel?.key]?.programs.orEmpty()
                                    val next=if(key==KeyEvent.KEYCODE_DPAD_RIGHT) {
                                        val following=programs.firstOrNull { it.start >= (program?.end ?: cursor+1) }
                                        following?.start ?: program?.end?.takeIf { it>cursor } ?: (cursor+EpgHalfHour)
                                    } else {
                                        val previous=programs.lastOrNull { it.end <= (program?.start ?: cursor) }
                                        previous?.let { it.end-1 } ?: program?.let { it.start-1 }?.takeIf { it<cursor } ?: (cursor-EpgHalfHour)
                                    }
                                    cursor=next.coerceIn(origin-172800000L,origin+345600000L)
                                    if(cursor<start) start=cursor/EpgHalfHour*EpgHalfHour
                                    else if(cursor>=start+EpgWindow) start=cursor/EpgHalfHour*EpgHalfHour-EpgWindow+EpgHalfHour
                                }
                                else -> channel?.let(onPlay)
                            }
                        }
                        true
                    }
                }.focusable()) {
                Row(Modifier.fillMaxWidth().height(34.dp)) {
                    Text(stringResource(R.string.live_channels_title),Modifier.width(190.dp).padding(6.dp),color=Color.White,fontSize=13.sp,maxLines=1)
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp))) {
                        val axis=EpgAxis(start,maxWidth.value)
                        repeat(4) { tick -> Text(format.format(Date(start+tick*EpgHalfHour)),
                            Modifier.offset(x=axis.offset(start+tick*EpgHalfHour)).padding(start=5.dp),color=Color(0xFFB0B8C3),fontSize=14.sp) }
                    }
                }
                LazyColumn(Modifier.fillMaxSize(),state=list,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(channels,key={_,c -> c.key}) { index,c ->
                        val programs=guides[c.key]?.programs.orEmpty()
                        val visible=remember(programs,start) { programs.filter { it.end>start && it.start<start+EpgWindow && it.end>it.start } }
                        Row(Modifier.fillMaxWidth().height(68.dp)) {
                            Row(Modifier.width(190.dp).fillMaxHeight().background(if(index==row && focused) Color(0xFF102F49) else Color(0xFF15171B))
                                .padding(8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                AsyncImage(model=c.logo,contentDescription=null,contentScale=ContentScale.Fit,modifier=Modifier.size(38.dp))
                                Text(c.name,color=Color.White,fontSize=14.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                            }
                            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(Color(0xFF15171B))) {
                                val axis=EpgAxis(start,maxWidth.value)
                                repeat(4) { tick -> Box(Modifier.offset(x=axis.offset(start+tick*EpgHalfHour)).width(1.dp).fillMaxHeight().background(Color(0xFF30343B))) }
                                if(visible.isEmpty()) Text(stringResource(R.string.live_no_epg),Modifier.padding(12.dp),color=Color(0xFFB0B8C3),fontSize=13.sp)
                                visible.forEach { p -> key(p.key) {
                                    val left=maxOf(p.start,start);val right=minOf(p.end,start+EpgWindow)
                                    val selected=focused && index==row && p.key==program?.key
                                    Column(Modifier.offset(x=axis.offset(left)).width(axis.width(left,right)).fillMaxHeight().padding(end=2.dp)
                                        .background(if(selected) Color(0xFF102F49) else Color(0xFF24272C),RoundedCornerShape(6.dp))
                                        .border(if(selected) 2.dp else 1.dp,if(selected) EpgBlue else Color(0xFF30343B),RoundedCornerShape(6.dp)).padding(7.dp)) {
                                        Text("${format.format(Date(p.start))} – ${format.format(Date(p.end))}",color=if(p.isLive(now)) EpgBlue else Color(0xFFB0B8C3),fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                                        Text(p.title,color=Color.White,fontSize=14.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                                    }
                                } }
                                if(visible.isEmpty() && focused && index==row) Box(Modifier.fillMaxSize().border(2.dp,EpgBlue,RoundedCornerShape(6.dp)))
                                if(now in start until start+EpgWindow) Box(Modifier.offset(x=axis.offset(now)).width(2.dp).fillMaxHeight().background(Color(0xFFEA443A)))
                            }
                        }
                    }
                }
            }
        }
    }
}
