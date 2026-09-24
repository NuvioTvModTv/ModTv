package com.nuvio.tv.data.livetv
import com.nuvio.tv.domain.model.livetv.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
class LiveTvTest {
    private val now=XmlTvReader.parseDate("20260921120000 -0300")!!
    private fun channel(name: String,id: String="addon-id")=LiveChannel(id,"tv",name,addonName="Test",addonUrl="https://example.org",catalogName="TV")
    @Test fun timezoneAndBoundaries() {
        assertEquals(now,XmlTvReader.parseDate("20260921150000 +0000"))
        assertNull(XmlTvReader.parseDate("20260230120000 +0000"));assertNull(XmlTvReader.parseDate("garbage"))
        val p=EpgProgram("a","t",null,now,now+100)
        assertEquals(0f,p.progress(now));assertEquals(1f,p.progress(now+100));assertFalse(p.isLive(now+100))
    }
    @Test fun parseUnicodeEntitiesAndSkipInvalidRows() {
        val programs=mutableListOf<EpgProgram>();val channels=mutableMapOf<String,List<String>>()
        val xml="""<tv><channel id="Globo.br"><display-name>Globo São Paulo</display-name></channel>
        <programme channel="Globo.br" start="20260921110000 -0300" stop="20260921130000 -0300"><title>Notícias &amp; ação</title><desc><![CDATA[A < B]]></desc></programme>
        <programme channel="Globo.br" start="bad" stop="bad"><title>Broken</title></programme>
        <programme channel="Globo.br" start="20260920110000 -0300" stop="20260919110000 -0300"><title>Reversed</title></programme>
        <programme channel="Globo.br" start="20260901110000 -0300" stop="20260901130000 -0300"><title>Expired</title></programme></tv>"""
        XmlTvReader.parse(xml.byteInputStream(),now,{id,n->channels[id]=n},{programs.add(it)})
        assertEquals(1,programs.size);assertEquals("Notícias & ação",programs[0].title);assertEquals("A < B",programs[0].description)
        assertTrue(programs[0].isLive(now));assertEquals(listOf("Globo São Paulo"),channels["Globo.br"])
    }
    @Test fun malformedXmlFails() {
        assertThrows(Exception::class.java) { XmlTvReader.parse("<tv><programme>".byteInputStream(),now,{_,_->},{}) }
        assertThrows(Exception::class.java) { XmlTvReader.parse("<html/>".byteInputStream(),now,{_,_->},{}) }
    }
    @Test fun dtdAndEntitiesAreRejected() {
        val xml="""<!DOCTYPE tv [<!ENTITY x SYSTEM "file:///etc/passwd">]><tv><channel id="a"><display-name>&x;</display-name></channel></tv>"""
        assertThrows(Exception::class.java) { XmlTvReader.parse(xml.byteInputStream(),now,{_,_->},{}) }
    }
    @Test fun inputLimitWorksForBulkReads() {
        assertThrows(java.io.IOException::class.java) { LimitedInputStream(ByteArrayInputStream(ByteArray(100)),20).readBytes() }
    }
    @Test fun cancellationIsNotSwallowed() {
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { XmlTvReader.parse("<tv/>".byteInputStream(),now,{_,_->},{}) { throw kotlinx.coroutines.CancellationException() } }
    }
    @Test fun manyProgramsAreDeliveredIncrementally() {
        val row="<programme channel=\"a\" start=\"20260921110000 -0300\" stop=\"20260921130000 -0300\"><title>T</title></programme>"
        var count=0;XmlTvReader.parse(("<tv>"+row.repeat(20000)+"</tv>").byteInputStream(),now,{_,_->},{count++});assertEquals(20000,count)
    }
    private val matcher=EpgMatcher(listOf(EpgChannel("Globo.br",listOf("Globo São Paulo"),true),EpgChannel("History2.br",listOf("History 2"),true),EpgChannel("Record.br",listOf("RecordTV"),true)))
    @Test fun exactIdWins() { assertEquals("Globo.br",matcher.match(channel("Other","Globo.br"))) }
    @Test fun qualityAndAccentNormalization() { assertEquals("Globo.br",matcher.match(channel("Globo Sao Paulo FHD"))) }
    @Test fun aliasesAndRegionalFallback() { assertEquals("History2.br",matcher.match(channel("H2")));assertEquals("Record.br",matcher.match(channel("RecordTV Paulista HD"))) }
    @Test fun missingGuideStaysMissing() { assertNull(matcher.match(channel("Not a television station"))) }
    @Test fun keySeparatesAddons() { assertNotEquals(channel("A").key,channel("A").copy(addonUrl="https://other.org").key) }
    @Test fun urlPreservesPrivateQueryAndEncodesIds() {
        assertEquals("https://example.org/config/stream/tv/abc%2Fdef.json?token=abc",LiveRepository.resourceUrl("https://example.org/config/manifest.json?token=abc","stream","tv","abc/def"))
    }
}
