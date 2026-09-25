package com.nuvio.tv.ui.screens.livetv

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.ui.PlayerView
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.nuvio.tv.LocalLiveTvFullscreen
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.livetv.*
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LiveBackground=Color(0xFF0B0C0E)
private val LiveSurface=Color(0xFF15171B)
private val LiveControl=Color(0xFF24272C)
private val LiveBlue=Color(0xFF168CF0)
private val LiveFocus=Color(0xFF43B0FF)
private val LiveFocusedCard=Color(0xFF102F49)
private val LiveSecondary=Color(0xFFB0B8C3)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveTvScreen(onManageAddons: ()->Unit,vm: LiveTvViewModel=hiltViewModel()) {
    val s by vm.state.collectAsState()
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current
    val contentFocus=LocalContentFocusRequester.current
    val toolbar=remember { FocusRequester() };val fullFocus=remember { FocusRequester() }
    val panelFocus=remember { FocusRequester() }
    val browserState=rememberSaveableStateHolder()
    val list=rememberLazyListState();val requesters=remember { mutableMapOf<String,FocusRequester>() }
    var full by rememberSaveable { mutableStateOf(false) };var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var scheduleChannel by remember { mutableStateOf<LiveChannel?>(null) };var detail by remember { mutableStateOf<EpgProgram?>(null) }
    var gridOpen by rememberSaveable { mutableStateOf(false) }
    var restore by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val latestState=rememberUpdatedState(s)
    // Move the same AndroidView/PlayerView between layouts; never create a second surface.
    val video=remember(vm) { movableContentOf<Modifier> { modifier -> VideoPane(vm,latestState.value,modifier) } }
    LaunchedEffect(vm) { vm.entered() }
    var automaticSelector by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf(false) }
    LaunchedEffect(full,s.playing?.key) {
        banner=full
        if(full) { delay(3000);banner=false }
    }
    LaunchedEffect(s.profileId,s.needsSourceSelection) {
        if(s.profileId>=0 && s.needsSourceSelection) {
            automaticSelector=true;dialog="addons"
        } else if(automaticSelector) {
            if(dialog=="addons") dialog=null
            automaticSelector=false
        }
    }
    val fullscreenChanged=rememberUpdatedState(LocalLiveTvFullscreen.current)
    LaunchedEffect(full) { fullscreenChanged.value(full) }
    DisposableEffect(Unit) { onDispose { fullscreenChanged.value(false) } }
    val activity=context as? Activity
    fun close() { automaticSelector=false;dialog=null;detail=null;scheduleChannel=null;vm.schedule(null);restore++ }
    fun guide(c: LiveChannel?) { scheduleChannel=c;vm.schedule(c);dialog="schedule" }
    DisposableEffect(lifecycle) {
        vm.active(true)
        val observer=LifecycleEventObserver { _,event -> when(event) {
            Lifecycle.Event.ON_RESUME -> { vm.active(true) }
            Lifecycle.Event.ON_PAUSE -> if(activity?.isInPictureInPictureMode!=true) vm.active(false)
            Lifecycle.Event.ON_STOP -> vm.active(false)
            else -> Unit
        } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer);vm.active(false);vm.releasePlayer() }
    }
    LaunchedEffect(list,s.visible) { snapshotFlow { list.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }.collect(vm::visible) }
    LaunchedEffect(full,restore) {
        withFrameNanos { }
        if(full) runCatching { fullFocus.requestFocus() }
        else {
            val index=s.visible.indexOfFirst { it.key==s.selectedKey }
            if(index>=0 && list.layoutInfo.visibleItemsInfo.none { it.key==s.selectedKey }) list.scrollToItem(index)
            withFrameNanos { }
            val ok=s.selectedKey?.let { requesters[it]?.let { r -> runCatching { r.requestFocus() }.getOrDefault(false) } } ?: false
            if(!ok) runCatching { toolbar.requestFocus() }
        }
    }
    // A source switch from fullscreen returns to the new source's channel list.
    LaunchedEffect(s.selectedAddons) {
        if(full && s.playing==null) { full=false;restore++ }
    }
    BackHandler(full || dialog!=null) { if(dialog!=null) close() else { full=false;restore++ } }
    Box(Modifier.fillMaxSize().background(LiveBackground).onPreviewKeyEvent { event ->
        if(dialog!=null) false
        else if(full && event.nativeKeyEvent.keyCode in listOf(android.view.KeyEvent.KEYCODE_DPAD_CENTER,android.view.KeyEvent.KEYCODE_ENTER,android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            if(event.type==KeyEventType.KeyUp) vm.pauseToggle()
            true
        } else if(full && event.nativeKeyEvent.keyCode in listOf(android.view.KeyEvent.KEYCODE_DPAD_LEFT,android.view.KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if(event.type==KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount==0)
                vm.adjacent(if(event.nativeKeyEvent.keyCode==android.view.KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
            true
        } else if(event.type!=KeyEventType.KeyDown) false else when(event.nativeKeyEvent.keyCode) {
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { vm.pauseToggle();true }
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT,android.view.KeyEvent.KEYCODE_CHANNEL_UP -> { vm.adjacent(1);true }
            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,android.view.KeyEvent.KEYCODE_CHANNEL_DOWN -> { vm.adjacent(-1);true }
            android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { vm.seek(10000);true }
            android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> { vm.seek(-10000);true }
            android.view.KeyEvent.KEYCODE_MENU -> { dialog="addons";true }
            else -> false
        }
    }) {
        if(full) {
            video(Modifier.fillMaxSize().focusRequester(fullFocus).focusable())
            AnimatedVisibility(visible=banner,enter=fadeIn(),exit=fadeOut(),modifier=Modifier.align(Alignment.BottomCenter)) {
                Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha=.62f)).padding(24.dp),
                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    s.playing?.let { channel ->
                        ChannelLogo(channel,48)
                        Column {
                            Text(channel.name,color=Color.White,style=MaterialTheme.typography.titleLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
                            s.guides[channel.key]?.current(s.now)?.let { Text(it.title,color=Color.White,maxLines=1,overflow=TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
        } else browserState.SaveableStateProvider("browser") { Column(Modifier.fillMaxSize().padding(horizontal=22.dp,vertical=20.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom=16.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(stringResource(R.string.live_channels_title),Modifier.weight(1f),color=Color.White,
                    fontSize=23.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
                LiveBadge()
                Text(stringResource(R.string.live_channel_count,s.visible.size),color=LiveSecondary,style=MaterialTheme.typography.bodySmall)
                LiveAction(stringResource(R.string.live_search_channels),Icons.Default.Search,
                    Modifier.width(164.dp).focusRequester(contentFocus).focusRequester(toolbar),pill=true) { query=s.search;dialog="search" }
                LiveAction(stringResource(R.string.live_epg_grid),Icons.Default.GridView) { gridOpen=true }
                LiveAction(stringResource(R.string.live_addons),Icons.Default.Extension) { dialog="addons" }
            }
            LazyRow(contentPadding=PaddingValues(horizontal=4.dp,vertical=5.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                item(key="all") { LiveCategory(stringResource(R.string.live_all),s.category.isEmpty()) { vm.category("") } }
                item(key="favorites") { LiveCategory(stringResource(R.string.live_favorites),s.category=="*") { vm.category("*") } }
                items(s.categories,key={"category:$it"}) { c -> LiveCategory(c,s.category==c) { vm.category(c) } }
            }
            if(s.loading) Text(stringResource(R.string.live_loading))
            if(s.catalogError) Text(stringResource(R.string.live_catalog_error),maxLines=2)
            if(s.epgLoading) Text(stringResource(R.string.live_epg_loading))
            Row(Modifier.weight(1f).padding(top=14.dp),horizontalArrangement=Arrangement.spacedBy(18.dp)) {
                LazyColumn(Modifier.weight(.52f),state=list,contentPadding=PaddingValues(4.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    if(s.visible.isEmpty() && !s.loading) item { Text(stringResource(R.string.live_empty)) }
                    items(s.visible,key={it.key}) { channel ->
                        val requester=remember(channel.key) { FocusRequester() }
                        DisposableEffect(channel.key) { requesters[channel.key]=requester;onDispose { requesters.remove(channel.key) } }
                        val epg=s.guides[channel.key]?:ChannelGuide()
                        val current=epg.current(s.now)
                        val openChannel: ()->Unit = { if(s.playing?.key==channel.key && !s.playbackError) full=true else vm.play(channel,force=s.playbackError) }
                        Card(
                            onClick=openChannel,
                            modifier=Modifier.fillMaxWidth().then(channelRemoteInput(openChannel) { vm.favorite(channel) }).focusRequester(requester)
                                .focusProperties { if(s.playing!=null) right=panelFocus }
                                .onFocusChanged { if(it.isFocused) vm.select(channel) },
                            colors=CardDefaults.colors(containerColor=LiveSurface,contentColor=Color.White,
                                focusedContainerColor=LiveFocusedCard,focusedContentColor=Color.White),
                            border=CardDefaults.border(
                                border=Border(BorderStroke(1.dp,if(channel.key==s.playing?.key) LiveBlue.copy(alpha=.45f) else Color(0xFF20242A)),shape=RoundedCornerShape(12.dp)),
                                focusedBorder=Border(BorderStroke(2.dp,LiveFocus),shape=RoundedCornerShape(12.dp))),
                            shape=CardDefaults.shape(shape=RoundedCornerShape(12.dp)),
                            scale=CardDefaults.scale(focusedScale=1f)
                        ) {
                            Row(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                                LogoTile(channel,52)
                                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                        Text(channel.name,Modifier.weight(1f),color=Color.White,fontSize=17.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
                                        if(channel.key in s.prefs.favorites) Text("★",color=LiveSecondary,fontSize=13.sp)
                                        LiveBadge()
                                    }
                                    run {
                                        Text(current?.title?:stringResource(R.string.live_no_epg),color=LiveSecondary,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=14.sp)
                                        current?.let { program ->
                                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                                LinearProgressIndicator(progress={program.progress(s.now)},modifier=Modifier.width(100.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),color=LiveBlue,trackColor=Color(0xFF313B48))
                                                Text(stringResource(R.string.live_minutes_remaining,((program.end-s.now+59999)/60000).coerceAtLeast(0)),color=LiveSecondary,fontSize=12.sp)
                                            }
                                        }
                                        epg.next(s.now)?.let { Text(time(it.start)+" · "+it.title,color=LiveSecondary.copy(alpha=.8f),maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=11.sp) }
                                    }
                                }
                            }
                        }

                    }
                }
                Column(
                    Modifier.weight(.48f).background(LiveSurface,RoundedCornerShape(16.dp)).border(1.dp,Color(0xFF272B31),RoundedCornerShape(16.dp)).padding(12.dp),
                    verticalArrangement=Arrangement.spacedBy(6.dp)
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f/9f).clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
                        video(Modifier.fillMaxSize())
                        if(s.playing!=null) Box(Modifier.align(Alignment.TopStart).padding(10.dp)) { LiveBadge() }
                    }
                    val channel=s.playing
                    if(channel==null) Text(stringResource(R.string.live_select),style=MaterialTheme.typography.bodyMedium)
                    else {
                        val current=s.guides[channel.key]?.current(s.now)
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            LogoTile(channel,44)
                            Column(Modifier.weight(1f)) {
                                Text(channel.name,color=Color.White,fontSize=19.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
                                Text(channel.addonName,color=LiveSecondary,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
                            }
                        }
                        LiveAction(stringResource(R.string.live_guide),Icons.Default.DateRange,
                            Modifier.widthIn(min=124.dp).focusRequester(panelFocus).focusProperties {
                                requesters[s.selectedKey]?.let { left=it }
                            }) { guide(channel) }
                        Row(Modifier.fillMaxWidth().background(Color(0xFF1C1F24),RoundedCornerShape(8.dp)).padding(horizontal=10.dp,vertical=8.dp),
                            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            Text(current?.title?:stringResource(R.string.live_no_epg),Modifier.weight(1f),color=Color.White,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=14.sp,lineHeight=18.sp)
                            current?.let { Text(time(it.start)+" – "+time(it.end),color=LiveFocus,fontSize=12.sp,lineHeight=16.sp) }
                        }
                    }
                }
            }
        } }
    }
    if(gridOpen) EpgGridScreen(
        channels=s.visible, guides=s.gridGuides, now=s.now, selectedKey=s.selectedKey,
        onWindow=vm::gridWindow,
        onClose={ gridOpen=false;restore++ },
        onPlay={ channel -> vm.select(channel);if(s.playing?.key!=channel.key || s.playbackError) vm.play(channel,force=s.playbackError);gridOpen=false;restore++ }
    )
    val selectedAddonIndex=s.addons.indexOfFirst { it.baseUrl in s.selectedAddons }.coerceAtLeast(0)
    if(dialog!=null) key(dialog) { LiveDialog(::close,addonMode=dialog=="addons",initialIndex=if(dialog=="addons") selectedAddonIndex else 0) { dialogFocus ->
        when(dialog) {
            "search" -> {
                item { OutlinedTextField(query,{query=it},label={androidx.compose.material3.Text(stringResource(R.string.live_search))},singleLine=true) }
                item { LiveButton(stringResource(R.string.live_apply)) { vm.search(query);close() } }
            }
            "addons" -> {
                if(s.addons.isEmpty()) item { Text(stringResource(R.string.live_no_tv_addons),Modifier.focusRequester(dialogFocus).focusable()) }
                itemsIndexed(s.addons,key={_,a->a.baseUrl}) { index,a ->
                    LiveButton((if(a.baseUrl in s.selectedAddons) "✓ " else "")+a.displayName,
                        Modifier.fillMaxWidth().then(if(index==selectedAddonIndex) Modifier.focusRequester(dialogFocus) else Modifier)) {
                        vm.chooseAddon(a.baseUrl);close()
                    }
                }
            }
            "schedule" -> {
                item {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                        scheduleChannel?.let { ChannelLogo(it,48) }
                        Text(scheduleChannel?.name.orEmpty(),style=MaterialTheme.typography.titleLarge)
                    }
                }
                if(s.schedule.programs.isEmpty()) item { Text(stringResource(R.string.live_no_epg)) }
                val next=s.schedule.programs.firstOrNull { it.start>s.now }?.key
                items(s.schedule.programs,key={it.key}) { p ->
                    val current=p.start<=s.now && p.end>s.now
                    Card(onClick={detail=p;dialog="detail"},modifier=Modifier.fillMaxWidth(),
                        border=CardDefaults.border(focusedBorder=Border(border=NuvioTheme.focusRing.border(3.dp),shape=RoundedCornerShape(12.dp))),
                        scale=CardDefaults.scale(focusedScale=1f)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                                Text(time(p.start,true)+" – "+time(p.end),style=MaterialTheme.typography.bodyMedium)
                                if(current) LiveBadge() else if(p.key==next) Text(stringResource(R.string.live_next_program),style=MaterialTheme.typography.labelMedium)
                            }
                            Text(p.title,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                            if(current) LinearProgressIndicator(progress={p.progress(s.now)},modifier=Modifier.fillMaxWidth().height(3.dp),color=NuvioTheme.colors.FocusRing)
                        }
                    }
                }
            }

            "detail" -> detail?.let { p ->
                item { Text(p.title,style=MaterialTheme.typography.titleLarge);Text(time(p.start,true)+" – "+time(p.end));Text(p.category.orEmpty()) }
                items(p.description.orEmpty().chunked(600)) { text -> Card(onClick={}) { Text(text,Modifier.padding(8.dp)) } }
            }
        }
    } }
}
@Composable
private fun VideoPane(vm: LiveTvViewModel,s: LiveTvViewModel.State,modifier: Modifier) {
    val player by vm.playerState.collectAsState()
    Box(modifier.background(Color.Black)) {
        AndroidView(factory={ ctx -> PlayerView(ctx).apply { useController=false;isFocusable=false;descendantFocusability=android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS } },update={if(it.player!==player) it.player=player;it.keepScreenOn=player?.playWhenReady==true},onRelease={it.player=null;it.keepScreenOn=false},modifier=Modifier.fillMaxSize())
        if(s.buffering) CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp),color=LiveSecondary,strokeWidth=2.dp)
        if(s.playbackError) Column(Modifier.align(Alignment.Center).background(Color.Black).padding(8.dp)) {
            Text(stringResource(R.string.live_play_error),color=Color.White)
        }
    }
}
@Composable
private fun LiveBadge() {
    Text(stringResource(R.string.live_on_air),color=Color.White,
        modifier=Modifier.background(Color(0xFFD92329),RoundedCornerShape(4.dp)).padding(horizontal=6.dp,vertical=2.dp),
        fontSize=10.sp,fontWeight=FontWeight.SemiBold,lineHeight=12.sp)
}
@Composable
private fun LogoTile(channel: LiveChannel,size: Int) {
    Box(Modifier.size(size.dp).background(Color(0xFF202329),RoundedCornerShape(9.dp))
        .border(1.dp,Color(0xFF30353C),RoundedCornerShape(9.dp)),contentAlignment=Alignment.Center) {
        ChannelLogo(channel,size-12)
    }
}
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveAction(text: String,icon: ImageVector,modifier: Modifier=Modifier,pill: Boolean=false,onClick: ()->Unit) {
    val shape=RoundedCornerShape(if(pill) 24.dp else 10.dp)
    Button(onClick=onClick,modifier=modifier.height(40.dp),
        shape=ButtonDefaults.shape(shape),scale=ButtonDefaults.scale(focusedScale=1f),
        colors=ButtonDefaults.colors(containerColor=LiveControl,contentColor=Color.White,
            focusedContainerColor=LiveBlue,focusedContentColor=Color.White),
        border=ButtonDefaults.border(focusedBorder=Border(BorderStroke(2.dp,LiveFocus),shape=shape)),
        contentPadding=PaddingValues(horizontal=12.dp,vertical=8.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            Icon(icon,contentDescription=null,modifier=Modifier.size(18.dp))
            Text(text,fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
}
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveCategory(text: String,selected: Boolean,onClick: ()->Unit) {
    val shape=RoundedCornerShape(24.dp)
    Button(onClick=onClick,modifier=Modifier.height(42.dp).widthIn(min=80.dp,max=220.dp),
        shape=ButtonDefaults.shape(shape),scale=ButtonDefaults.scale(focusedScale=1f),
        colors=ButtonDefaults.colors(containerColor=if(selected) LiveBlue else LiveControl,contentColor=Color.White,
            focusedContainerColor=if(selected) LiveBlue else LiveFocusedCard,focusedContentColor=Color.White),
        border=ButtonDefaults.border(focusedBorder=Border(BorderStroke(2.dp,LiveFocus),shape=shape)),
        contentPadding=PaddingValues(horizontal=18.dp,vertical=9.dp)) {
        Text(text,fontSize=15.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}
@Composable
private fun ChannelLogo(c: LiveChannel,size: Int) {
    var failed by remember(c.logo) { mutableStateOf(false) }
    Box(Modifier.size(size.dp),contentAlignment=Alignment.Center) {
        if(c.logo.isNullOrBlank() || failed) Text(c.name.take(2).uppercase()) else AsyncImage(model=ImageRequest.Builder(LocalContext.current).data(c.logo).size(160,160).build(),contentDescription=null,contentScale=ContentScale.Fit,onError={failed=true},modifier=Modifier.fillMaxSize())
    }
}
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun LiveButton(text: String,modifier: Modifier=Modifier,onClick: ()->Unit) {
    Button(onClick=onClick,modifier=modifier,
        colors=ButtonDefaults.colors(containerColor=LiveControl,contentColor=Color.White,
            focusedContainerColor=LiveBlue,focusedContentColor=Color.White),
        scale=ButtonDefaults.scale(focusedScale=1f),
        border=ButtonDefaults.border(focusedBorder=Border(BorderStroke(2.dp,LiveFocus),shape=RoundedCornerShape(24.dp)))) {
        Text(text,maxLines=2,overflow=TextOverflow.Ellipsis)
    }
}
@Composable
private fun LiveDialog(onClose: ()->Unit,addonMode: Boolean=false,initialIndex: Int=0,content: LazyListScope.(FocusRequester)->Unit) {
    val focus=remember { FocusRequester() }
    val dialogList=rememberLazyListState(initialFirstVisibleItemIndex=if(addonMode) initialIndex else 0)
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        LazyColumn(Modifier.width(if(addonMode) 480.dp else 680.dp).then(if(addonMode) Modifier.heightIn(max=360.dp) else Modifier.height(440.dp)).background(NuvioTheme.colors.Background).padding(24.dp),state=dialogList,verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(!addonMode) item { LiveButton(stringResource(R.string.live_close),Modifier.focusRequester(focus),onClose) }
            content(focus)
        }
        LaunchedEffect(Unit) { withFrameNanos { };runCatching { focus.requestFocus() } }
    }
}
private fun time(epoch: Long,day: Boolean=false)=SimpleDateFormat(if(day) "EEE dd/MM HH:mm" else "HH:mm",Locale.getDefault()).format(Date(epoch))

// Consume the whole OK gesture before Card sees it, including repeated key downs.
@Composable
private fun channelRemoteInput(onClick: ()->Unit,onLongClick: ()->Unit): Modifier {
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    val scope=rememberCoroutineScope()
    var held by remember { mutableStateOf(false) }
    var fired by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Job?>(null) }
    val threshold=android.view.ViewConfiguration.getLongPressTimeout().toLong()
    DisposableEffect(Unit) { onDispose { pending?.cancel() } }
    return Modifier.onFocusChanged { if(!it.isFocused) { pending?.cancel();held=false } }.onPreviewKeyEvent { event ->
        if(event.nativeKeyEvent.keyCode !in listOf(android.view.KeyEvent.KEYCODE_DPAD_CENTER,android.view.KeyEvent.KEYCODE_ENTER,android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)) false
        else {
            if(event.type==KeyEventType.KeyDown && !held && event.nativeKeyEvent.repeatCount==0) {
                held=true;fired=false
                pending=scope.launch { delay(threshold);if(held && !fired) { fired=true;longClick() } }
            } else if(event.type==KeyEventType.KeyUp) {
                pending?.cancel()
                if(held && !fired && !event.nativeKeyEvent.isCanceled) {
                    if(event.nativeKeyEvent.eventTime-event.nativeKeyEvent.downTime>=threshold) longClick() else click()
                }
                held=false
            }
            true
        }
    }
}
