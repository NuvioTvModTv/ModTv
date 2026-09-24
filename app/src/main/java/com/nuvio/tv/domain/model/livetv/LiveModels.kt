package com.nuvio.tv.domain.model.livetv

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.security.MessageDigest

fun liveHash(s: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return buildString(bytes.size * 2) {
        bytes.forEach { byte -> val value = byte.toInt() and 255; append(hex[value ushr 4]); append(hex[value and 15]) }
    }
}
@Serializable
data class LiveChannel(val id: String, val type: String, val name: String,
    val logo: String? = null, val genres: List<String> = emptyList(), val description: String? = null,
    val addonName: String, val addonUrl: String, val addonLogo: String? = null, val catalogName: String) {
    @Transient val key: String = liveHash("$addonUrl\u0000$type\u0000$id")
}
@Serializable
data class EpgSource(val id: String, val name: String, val url: String, val enabled: Boolean = true)
@Serializable
data class LivePreferences(val selectedAddons: Set<String>? = null, val favorites: Set<String> = emptySet(),
    val sources: List<EpgSource> = defaultEpgSources(), val view: String = "LIST", val showEpg: Boolean = true,
    val fullscreen: Boolean = false, val cards: Boolean = true,
    val sourceSelectionCompleted: Boolean = false)
fun defaultEpgSources() = listOf(
    Triple("BR1","Brasil Principal",true), Triple("BR2","Brasil Regionais",true),
    Triple("PT1","Portugal",true), Triple("US1","USA Principal",true), Triple("US2","USA Entretenimento",true),
    Triple("US_SPORTS1","Esportes",true), Triple("US_LOCALS1","USA Locais",false), Triple("AR1","América Latina",false)
).map { (id,name,on) -> EpgSource(id,name,"https://epgshare01.online/epgshare01/epg_ripper_$id.xml.gz",on) }
data class EpgProgram(val channel: String, val title: String, val description: String?, val start: Long, val end: Long, val category: String? = null) {
    val key get() = "$channel:$start:$end:$title"
    fun isLive(now: Long) = now >= start && now < end
    fun progress(now: Long) = if(end <= start) 0f else ((now-start).toDouble()/(end-start)).toFloat().coerceIn(0f,1f)
}
data class EpgChannel(val id: String, val displayNames: List<String>, val hasPrograms: Boolean)
data class EpgMatch(val source: String, val channel: String)
data class ChannelGuide(val programs: List<EpgProgram> = emptyList()) {
    fun current(now: Long) = programs.firstOrNull { it.isLive(now) }
    fun next(now: Long) = programs.firstOrNull { it.start >= (current(now)?.end ?: now) }
}
