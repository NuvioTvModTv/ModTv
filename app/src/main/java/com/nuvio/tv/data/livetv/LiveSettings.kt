package com.nuvio.tv.data.livetv
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.domain.model.livetv.LivePreferences
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class LiveSettings @Inject constructor(private val factory: ProfileDataStoreFactory,private val profiles: ProfileManager) {
    private val key=stringPreferencesKey("preferences_v1")
    private val json=Json { ignoreUnknownKeys=true }
    val preferences=profiles.activeProfileId.flatMapLatest { id -> factory.get(id,"live_tv").data.map { id to decode(it[key]) } }
    private fun decode(s: String?)=s?.let { runCatching { json.decodeFromString<LivePreferences>(it) }.getOrNull() } ?: LivePreferences()
    suspend fun edit(transform: (LivePreferences)->LivePreferences) {
        factory.get(profiles.activeProfileId.value,"live_tv").edit { p -> p[key]=json.encodeToString(LivePreferences.serializer(),transform(decode(p[key]))) }
    }
}
