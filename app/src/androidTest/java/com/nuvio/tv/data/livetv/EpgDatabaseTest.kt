package com.nuvio.tv.data.livetv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.domain.model.livetv.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the Android SAX implementation and real SQLite rollback, not a mock. */
@RunWith(AndroidJUnit4::class)
class EpgDatabaseTest {
    @Test fun androidParserAndAtomicRefresh() {
        val context=InstrumentationRegistry.getInstrumentation().context
        context.deleteDatabase("live_epg.db")
        val now=XmlTvReader.parseDate("20260921120000 +0000")!!
        val channel=LiveChannel("one","tv","One",addonName="test",addonUrl="https://example.org",catalogName="TV")
        val xml="""<tv><channel id="one"><display-name>One</display-name></channel>
        <programme channel="one" start="20260921110000 +0000" stop="20260921130000 +0000"><title>News &amp; weather</title></programme>
        <programme channel="unused" start="20260921110000 +0000" stop="20260921130000 +0000"><title>Unused</title></programme></tv>"""
        EpgDatabase(context).use { db ->
            db.replace("source",xml.byteInputStream(),now,listOf(channel)) {}
            assertEquals(now,db.updated("source"))
            val guide=db.window(EpgMatch("source","one"),now,now+1000,10)
            assertEquals("News & weather",guide.current(now)?.title)
            assertTrue(db.window(EpgMatch("source","unused"),now,now+1000,10).programs.isEmpty())
            var failed=false
            try { db.replace("source","<tv><programme>".byteInputStream(),now+1000,listOf(channel)) {} } catch(e: Exception) { failed=true }
            assertTrue(failed)
            assertEquals(now,db.updated("source"))
            assertEquals(guide,db.window(EpgMatch("source","one"),now,now+1000,10))
        }
        context.deleteDatabase("live_epg.db")
    }
}
