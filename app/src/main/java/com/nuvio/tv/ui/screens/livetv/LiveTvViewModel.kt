package com.nuvio.tv.ui.screens.livetv
import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import com.nuvio.tv.data.livetv.*
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.livetv.*
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class LiveTvViewModel @Inject constructor(@param:ApplicationContext private val context: Context,
    private val repo: LiveRepository,private val settings: LiveSettings,addons: AddonRepository,
    private val saved: SavedStateHandle,private val trailerPool: com.nuvio.tv.core.player.TrailerPlayerPool): ViewModel() {
    data class State(val channels: List<LiveChannel> = emptyList(),val visible: List<LiveChannel> = emptyList(),
        val addons: List<Addon> = emptyList(),val selectedAddons: Set<String> = emptySet(),val prefs: LivePreferences=LivePreferences(),
        val categories: List<String> = emptyList(),val category: String="",val search: String="",val selectedKey: String?=null,
        val playing: LiveChannel?=null,val streams: List<Stream> = emptyList(),val streamIndex: Int=0,
        val loading: Boolean=false,val epgLoading: Boolean=false,val buffering: Boolean=false,
        val catalogError: Boolean=false,val epgError: Boolean=false,val playbackError: Boolean=false,
        val guides: Map<String,ChannelGuide> = emptyMap(),val schedule: ChannelGuide=ChannelGuide(),
        val now: Long=System.currentTimeMillis(),val offset: Int=0,
        val profileId: Int = -1,val needsSourceSelection: Boolean = true)
    private val _state=MutableStateFlow(State(category=saved["category"]?:"",search=saved["search"]?:"",selectedKey=saved["selected"],offset=saved["offset"]?:0))
    val state=_state.asStateFlow()
    private val entries=MutableStateFlow(0)
    private var lastEntry=-1
    fun entered() { entries.value++ }
    private var profile=-1
    private var channelsJob: Job?=null;private var epgJob: Job?=null;private var streamJob: Job?=null
    private var guideJob: Job?=null;private var ticker: Job?=null;private var retryJob: Job?=null
    private var matches=emptyMap<String,EpgMatch>();private var visibleKeys=emptyList<String>()
    private var foreground=true
    private var scheduleChannel: LiveChannel?=null;private var retries=0;private var resume=false
    private var sourceJob: Job?=null
    private var sourceFactory: PlayerMediaSourceFactory?=null
    private var _player: ExoPlayer?=null
    private val _playerState=MutableStateFlow<ExoPlayer?>(null)
    val playerState=_playerState.asStateFlow()
    init {
        viewModelScope.launch {
            combine(addons.getReadyInstalledAddons(),settings.preferences,entries) { a,p,e -> Triple(a.filter { it.enabled },p,e) }.collect { (a,pair,entry) ->
                val reentered=lastEntry!=entry;lastEntry=entry
                val (pid,p)=pair
                val eligible=a.filter(repo::isTvAddon)
                val stored=p.selectedAddons.orEmpty()
                val valid=stored.size==1 && eligible.any { it.baseUrl==stored.single() }
                val selected=if(valid) stored else emptySet()
                val needsSelection=!valid
                val old=_state.value
                val changed=profile!=pid || old.addons!=eligible || old.selectedAddons!=selected || old.needsSourceSelection!=needsSelection
                val epgChanged=old.prefs.sources!=p.sources
                if(profile!=pid) {
                    channelsJob?.cancel();epgJob?.cancel();guideJob?.cancel()
                    releasePlayer();matches=emptyMap();if(profile>=0) _state.value=State();profile=pid
                }
                if(old.selectedAddons!=selected || needsSelection) {
                    channelsJob?.cancel();epgJob?.cancel();guideJob?.cancel()
                    resetAddonPlayback();matches=emptyMap();visibleKeys=emptyList();scheduleChannel=null
                    saved["category"]="";saved["selected"]=null
                    _state.update { it.copy(channels=emptyList(),visible=emptyList(),categories=emptyList(),category="",selectedKey=null,
                        guides=emptyMap(),schedule=ChannelGuide(),loading=false,epgLoading=false) }
                }
                _state.update { it.copy(addons=eligible,prefs=p,selectedAddons=selected,profileId=pid,needsSourceSelection=needsSelection) };filter()
                if(!needsSelection) { if(changed || reentered) refresh() else if(epgChanged) sync() else if(old.prefs.view!=p.view) loadGuides() }
            }
        }
    }
    fun refresh(force: Boolean=false) {
        if(profile<0 || _state.value.needsSourceSelection) return
        channelsJob?.cancel();epgJob?.cancel()
        channelsJob=viewModelScope.launch {
            _state.update { it.copy(loading=true,catalogError=false,epgLoading=false) }
            try {
                val s=_state.value;val result=repo.channels(profile,s.addons.filter { it.baseUrl in s.selectedAddons },force)
                ensureActive()
                _state.update { it.copy(channels=result.channels,loading=false,catalogError=result.failed>0,
                    categories=result.channels.flatMap { c -> c.genres+c.catalogName }.filter { it.isNotBlank() }.distinct().sorted()) }
                filter();sync()
            } catch(e: CancellationException) { throw e } catch(e: Exception) { _state.update { it.copy(loading=false,catalogError=true) } }
        }
    }
    fun sync(force: Boolean=false) {
        if(_state.value.needsSourceSelection || _state.value.channels.isEmpty()) return
        epgJob?.cancel();epgJob=viewModelScope.launch {
            _state.update { it.copy(epgLoading=true,epgError=false) }
            try {
                val s=_state.value;val result=repo.sync(s.prefs.sources,s.channels,force) { partial ->
                    withContext(Dispatchers.Main.immediate) { matches=partial.matches;loadGuides() }
                };matches=result.matches
                _state.update { it.copy(epgLoading=false,epgError=result.failed>0) };loadGuides()
            } catch(e: CancellationException) { throw e } catch(e: Exception) { _state.update { it.copy(epgLoading=false,epgError=true) } }
        }
    }
    private fun filter() { _state.update { s -> s.copy(visible=s.channels.filter { c ->
        (s.category.isBlank() || s.category=="*" && c.key in s.prefs.favorites || s.category==c.catalogName || s.category in c.genres) &&
            (s.search.isBlank() || (listOf(c.name,c.addonName,c.catalogName)+c.genres).any { it.contains(s.search.trim(),true) })
    }) } }
    fun search(s: String) { saved["search"]=s;_state.update { it.copy(search=s) };filter() }
    fun category(s: String) { saved["category"]=s;_state.update { it.copy(category=s) };filter() }
    fun select(c: LiveChannel) { saved["selected"]=c.key;_state.update { it.copy(selectedKey=c.key) } }
    fun offset(n: Int) { val v=n.coerceIn(-2880,5640);saved["offset"]=v;_state.update { it.copy(offset=v) };loadGuides() }
    fun visible(keys: List<String>) { if(keys!=visibleKeys) { visibleKeys=keys.take(30);loadGuides() } }
    fun schedule(c: LiveChannel?) { scheduleChannel=c;loadGuides() }
    private fun loadGuides() {
        guideJob?.cancel();guideJob=viewModelScope.launch {
            val s=_state.value;val start=s.now/1800000*1800000+s.offset*60000L
            val guides=linkedMapOf<String,ChannelGuide>()
            for(key in (visibleKeys+listOfNotNull(s.playing?.key,s.selectedKey)).distinct()) {
                val current=repo.guide(matches[key],s.now,s.now+96*3600000L,2)
                val grid=if(s.prefs.view=="GRID") repo.guide(matches[key],start,start+7200000) else ChannelGuide()
                guides[key]=ChannelGuide((current.programs+grid.programs).distinctBy { it.key }.sortedBy { it.start })
            }
            val schedule=scheduleChannel?.let { repo.guide(matches[it.key],s.now,s.now+96*3600000L,2000) } ?: ChannelGuide()
            _state.update { it.copy(guides=guides,schedule=schedule) }
        }
    }
    fun active(on: Boolean) {
        ticker?.cancel()
        if(on) { if(!foreground && resume) _player?.play();ticker=viewModelScope.launch { while(isActive) { _state.update { it.copy(now=System.currentTimeMillis()) };loadGuides();delay(30000) } } }
        else { if(foreground) resume=_player?.playWhenReady==true;_player?.pause() }
        foreground=on
    }
    fun edit(f: (LivePreferences)->LivePreferences) { viewModelScope.launch { settings.edit(f) } }
    fun favorite(c: LiveChannel)=edit { it.copy(favorites=if(c.key in it.favorites) it.favorites-c.key else it.favorites+c.key) }
    fun chooseAddon(url: String) {
        if(_state.value.addons.none { it.baseUrl==url }) return
        if(_state.value.selectedAddons!=setOf(url)) {
            channelsJob?.cancel();epgJob?.cancel();guideJob?.cancel();resetAddonPlayback()
            saved["selected"]=null
            _state.update { it.copy(channels=emptyList(),visible=emptyList(),categories=emptyList(),guides=emptyMap(),selectedKey=null,loading=true) }
        }
        edit { it.copy(selectedAddons=setOf(url),sourceSelectionCompleted=true) }
    }
    // Source changes must not release the player still attached to the persistent PlayerView.
    // Keep its video surface/renderer ownership; discard only the outgoing media and work.
    private fun resetAddonPlayback() {
        sourceJob?.cancel();sourceJob=null
        streamJob?.cancel();streamJob=null
        retryJob?.cancel();retryJob=null
        resume=false;retries=0
        _player?.let { player ->
            player.playWhenReady=false
            player.stop()
            player.clearMediaItems()
        }
        _state.update { it.copy(playing=null,streams=emptyList(),streamIndex=0,
            buffering=false,playbackError=false) }
    }
    fun play(c: LiveChannel,force: Boolean=false) {
        select(c);streamJob?.cancel();sourceJob?.cancel();retryJob?.cancel();_player?.stop();retries=0
        _state.update { it.copy(playing=c,streams=emptyList(),buffering=true,playbackError=false) }
        streamJob=viewModelScope.launch {
            try { val streams=repo.streams(c,force);ensureActive();_state.update { it.copy(streams=streams,streamIndex=0,buffering=false,playbackError=streams.isEmpty()) };if(streams.isNotEmpty()) source(0);loadGuides() }
            catch(e: CancellationException) { throw e } catch(e: Exception) { Log.w("LiveTV", "stage=addon_resolution exception=${e.javaClass.simpleName}");_state.update { it.copy(buffering=false,playbackError=true) } }
        }
    }
    fun adjacent(delta: Int) {
        val s=_state.value;if(s.visible.isEmpty()) return
        val i=s.visible.indexOfFirst { it.key==s.playing?.key }
        val target=if(i<0) { if(delta>0) 0 else s.visible.lastIndex } else Math.floorMod(i+delta,s.visible.size)
        val channel=s.visible[target]
        if(channel.key!=s.playing?.key) play(channel)
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun source(index: Int) {
        val stream=_state.value.streams.getOrNull(index) ?: return
        val url=LiveRepository.playableUrl(stream) ?: return
        sourceJob?.cancel()
        _state.update { it.copy(streamIndex=index,playbackError=false,buffering=true) }
        sourceJob=viewModelScope.launch {
        try {
            val (clean,headers)=PlayerMediaSourceFactory.extractUserInfoAuth(url,stream.behaviorHints?.proxyHeaders?.request.orEmpty())
            val mime=PlayerMediaSourceFactory.probeMimeType(clean,headers,stream.behaviorHints?.filename,stream.behaviorHints?.proxyHeaders?.response)
                ?: PlayerMediaSourceFactory.probeNetworkMimeType(clean,headers)
            ensureActive()
            trailerPool.yield()
            if(_player==null) {
                sourceFactory=PlayerMediaSourceFactory(context).apply { vodCacheEnabled=false;useParallelConnections=false;nuvioPerformanceModeEnabled=false }
                _player=ExoPlayer.Builder(context).setRenderersFactory(DefaultRenderersFactory(context).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON))
                    .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(3000,12000,750,1500).setTargetBufferBytes(12*1024*1024).setPrioritizeTimeOverSizeThresholds(false).build())
                    .build().apply {
                        setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT,true);setHandleAudioBecomingNoisy(true)
                        addListener(object: Player.Listener {
                            override fun onPlaybackStateChanged(state: Int) {
                                _state.update { it.copy(buffering=state==Player.STATE_BUFFERING,
                                    playbackError=if(state==Player.STATE_READY) false else it.playbackError) }
                                if(state==Player.STATE_READY) { retryJob?.cancel();retryJob=null }
                            }
                            override fun onIsPlayingChanged(isPlaying: Boolean) {
                                if(isPlaying) _state.update { it.copy(playbackError=false,buffering=false) }
                            }
                            override fun onPlayerError(error: PlaybackException) {
                                if(_player?.playerError!==error) return
                                val http=generateSequence(error as Throwable) { it.cause }.filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                                Log.w("LiveTV", "stage=media3 code=${error.errorCodeName} http=${http?.responseCode} cause=${error.cause?.javaClass?.simpleName}")
                                retryJob?.cancel()
                                if(retries<2) {
                                    retries++
                                    val channelKey=_state.value.playing?.key
                                    _state.update { it.copy(playbackError=false,buffering=true) }
                                    retryJob=viewModelScope.launch {
                                        delay(retries*2000L)
                                        if(_state.value.playing?.key==channelKey && _player?.playerError===error) {
                                            if(error.errorCode==PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                                                _player?.seekToDefaultPosition();_player?.prepare()
                                            } else source(_state.value.streamIndex)
                                        }
                                    }
                                } else _state.update { it.copy(playbackError=true,buffering=false) }
                            }
                        })
                    }
            }
            _playerState.value=_player
            val media=sourceFactory!!.createMediaSource(context,clean,headers,filename=stream.behaviorHints?.filename,responseHeaders=stream.behaviorHints?.proxyHeaders?.response.orEmpty(),mimeTypeOverride=mime)
            _player!!.setMediaSource(media);_player!!.prepare();_player!!.playWhenReady=foreground;resume=true
            _state.update { it.copy(streamIndex=index,playbackError=false,buffering=true) }
        } catch(e: CancellationException) { throw e } catch(e: Exception) {
            Log.w("LiveTV", "stage=player_prepare exception=${e.javaClass.simpleName}")
            _state.update { it.copy(playbackError=true,buffering=false) }
        }
        }
    }
    fun pauseToggle() { _player?.let { if(it.playWhenReady) it.pause() else it.play() } }
    fun seek(delta: Long) { _player?.let { if(it.isCurrentMediaItemSeekable) it.seekTo((it.currentPosition+delta).coerceAtLeast(0)) } }
    fun retry() { _state.value.playing?.let { play(it,true) } }
    fun releasePlayer() { sourceJob?.cancel();streamJob?.cancel();retryJob?.cancel();_playerState.value=null;_player?.release();_player=null;sourceFactory=null;resume=false;_state.update { it.copy(playing=null,streams=emptyList(),buffering=false) } }
    override fun onCleared() { releasePlayer();super.onCleared() }
}
