package com.nuvio.tv.data.livetv
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.nuvio.tv.domain.model.livetv.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class EpgDatabase @Inject constructor(@ApplicationContext context: Context): SQLiteOpenHelper(context,"live_epg.db",null,1) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sources(source TEXT PRIMARY KEY,updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE channels(source TEXT,channel TEXT,name TEXT,PRIMARY KEY(source,channel,name))")
        db.execSQL("CREATE TABLE programs(source TEXT,channel TEXT,start INTEGER,end INTEGER,title TEXT,description TEXT,category TEXT,PRIMARY KEY(source,channel,start,end,title))")
        db.execSQL("CREATE INDEX epg_window ON programs(source,channel,start,end)")
    }
    override fun onUpgrade(db: SQLiteDatabase,old: Int,new: Int)=Unit
    fun updated(source: String)=readableDatabase.rawQuery("SELECT updated FROM sources WHERE source=?",arrayOf(source)).use { if(it.moveToFirst()) it.getLong(0) else 0L }
    fun touch(source: String,now: Long) { writableDatabase.execSQL("UPDATE sources SET updated=? WHERE source=?",arrayOf<Any>(now,source)) }
    fun replace(source: String,input: InputStream,now: Long,selected: List<LiveChannel>,checkCancellation: ()->Unit) {
        val db=writableDatabase;db.beginTransactionNonExclusive()
        try {
            db.delete("channels","source=?",arrayOf(source));db.delete("programs","source=?",arrayOf(source))
            db.compileStatement("INSERT OR IGNORE INTO channels VALUES(?,?,?)").use { ch ->
                db.compileStatement("INSERT OR IGNORE INTO programs VALUES(?,?,?,?,?,?,?)").use { p ->
                    val declarations=ArrayList<EpgChannel>();var ids: Set<String>?=null;var count=0
                    XmlTvReader.parse(input,now,{ id,names ->
                        if(++count>30000) throw java.io.IOException("Channel limit")
                        declarations.add(EpgChannel(id,names,true))
                        names.ifEmpty { listOf(id) }.forEach { name -> ch.bindString(1,source);ch.bindString(2,id);ch.bindString(3,name);ch.executeInsert() }
                    }, { program ->
                        if(ids==null) { val matcher=EpgMatcher(declarations);ids=selected.mapNotNull { matcher.match(it) }.toSet()+selected.map { it.id };declarations.clear() }
                        if(program.channel in ids.orEmpty()) {
                            p.bindString(1,source);p.bindString(2,program.channel);p.bindLong(3,program.start);p.bindLong(4,program.end)
                            p.bindString(5,program.title);p.bindString(6,program.description.orEmpty());p.bindString(7,program.category.orEmpty());p.executeInsert()
                        }
                    },checkCancellation)
                }
            }
            db.execSQL("INSERT OR REPLACE INTO sources VALUES(?,?)",arrayOf<Any>(source,now));db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun index(source: String): List<EpgChannel> {
        val names=linkedMapOf<String,MutableList<String>>();val programs=hashSetOf<String>()
        readableDatabase.rawQuery("SELECT channel,name FROM channels WHERE source=? ORDER BY rowid",arrayOf(source)).use { c -> while(c.moveToNext()) names.getOrPut(c.getString(0)) { mutableListOf() }.add(c.getString(1)) }
        readableDatabase.rawQuery("SELECT DISTINCT channel FROM programs WHERE source=?",arrayOf(source)).use { c -> while(c.moveToNext()) programs.add(c.getString(0)) }
        programs.forEach { names.putIfAbsent(it,mutableListOf(it)) }
        return names.map { (id,n) -> EpgChannel(id,n,id in programs) }
    }
    fun window(match: EpgMatch,from: Long,to: Long,limit: Int): ChannelGuide {
        val list=ArrayList<EpgProgram>()
        readableDatabase.rawQuery("SELECT channel,title,description,start,end,category FROM programs WHERE source=? AND channel=? AND start<? AND end>? ORDER BY start LIMIT ?",arrayOf(match.source,match.channel,to.toString(),from.toString(),limit.toString())).use { c ->
            while(c.moveToNext()) list.add(EpgProgram(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),c.getLong(4),c.getString(5)))
        };return ChannelGuide(list)
    }
    fun prune(now: Long) {
        writableDatabase.delete("programs","end<?",arrayOf((now-48*3600000L).toString()))
        val obsolete=mutableListOf<String>()
        readableDatabase.rawQuery("SELECT source FROM sources WHERE updated<?",arrayOf((now-7*86400000L).toString())).use { c -> while(c.moveToNext()) obsolete.add(c.getString(0)) }
        obsolete.forEach { id -> listOf("programs","channels","sources").forEach { writableDatabase.delete(it,"source=?",arrayOf(id)) } }
    }
}
