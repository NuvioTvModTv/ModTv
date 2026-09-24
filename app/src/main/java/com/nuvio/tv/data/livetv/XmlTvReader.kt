package com.nuvio.tv.data.livetv

import com.nuvio.tv.domain.model.livetv.EpgProgram
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2
import java.io.InputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.StringReader
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import javax.xml.parsers.SAXParserFactory

/** One bounded XMLTV record at a time. Call on Dispatchers.IO, never on Main. */
object XmlTvReader {
    private val stamp=DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT)
    fun parseDate(raw: String?): Long? = runCatching {
        val parts=raw?.trim()?.split(Regex("\\s+")) ?: return null
        if(parts[0].length !in listOf(8,10,12,14)) return null
        LocalDateTime.parse(parts[0].padEnd(14,'0'),stamp)
            .toInstant(parts.getOrNull(1)?.let(ZoneOffset::of) ?: ZoneOffset.UTC).toEpochMilli()
    }.getOrNull()
    fun parse(input: InputStream, now: Long, channelSink: (String,List<String>)->Unit,
        programSink: (EpgProgram)->Unit, checkCancellation: ()->Unit = {}) {
        val factory=SAXParserFactory.newInstance()
        factory.isNamespaceAware=false; factory.isValidating=false
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false)
        val reader=factory.newSAXParser().xmlReader
        reader.entityResolver=org.xml.sax.EntityResolver { _,_ -> InputSource(StringReader("")) }
        val handler=object: DefaultHandler2() {
            var depth=0; var root=false; var channel: String?=null; var names=mutableListOf<String>()
            var programChannel: String?=null; var start: Long?=null; var end: Long?=null
            var title=""; var description: String?=null; var category: String?=null; var capture: String?=null
            val text=StringBuilder(); var count=0
            override fun startDTD(name: String?,publicId: String?,systemId: String?) { throw SAXException("DTD is not supported") }
            override fun startElement(uri: String?,localName: String?,q: String,a: Attributes) {
                checkCancellation(); depth++
                if(depth>32) throw SAXException("XML nesting limit")
                if(depth==1) { if(q!="tv") throw SAXException("Not XMLTV"); root=true }
                when(q) {
                    "channel" -> { channel=a.getValue("id")?.take(512); names=mutableListOf() }
                    "programme" -> { programChannel=a.getValue("channel")?.take(512); start=parseDate(a.getValue("start")); end=parseDate(a.getValue("stop")); title="";description=null;category=null }
                    "display-name","title","desc","category" -> { capture=q;text.setLength(0) }
                }
            }
            override fun characters(chars: CharArray,offset: Int,length: Int) {
                if(capture!=null && text.length<8192) text.append(chars,offset,minOf(length,8192-text.length))
            }
            override fun endElement(uri: String?,localName: String?,q: String) {
                if(capture==q) {
                    val value=text.toString().trim()
                    when(q) {
                        "display-name" -> if(channel!=null && names.size<4 && value.isNotEmpty()) names.add(value.take(256))
                        "title" -> if(title.isEmpty()) title=value.take(512)
                        "desc" -> if(description==null) description=value.take(4096)
                        "category" -> if(category==null) category=value.take(128)
                    };capture=null
                }
                if(q=="channel") { channel?.takeIf { it.isNotBlank() }?.let { channelSink(it,names) };channel=null }
                if(q=="programme") {
                    val s=start;val e=end;val id=programChannel
                    if(!id.isNullOrBlank() && s!=null && e!=null && e>s && e>now-48*3600000L && s<now+96*3600000L && title.isNotBlank()) {
                        if(++count>1_000_000) throw SAXException("Program limit")
                        programSink(EpgProgram(id,title,description,s,e,category))
                    };programChannel=null
                };depth--
            }
            override fun endDocument() { if(!root) throw SAXException("Empty XMLTV") }
            override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
        }
        reader.setProperty("http://xml.org/sax/properties/lexical-handler",handler)
        reader.contentHandler=handler;reader.errorHandler=handler;reader.parse(InputSource(input))
    }
}
internal class LimitedInputStream(input: InputStream,private val limit: Long): FilterInputStream(input) {
    private var count=0L
    private fun count(n: Int): Int { if(n>0) { count+=n;if(count>limit) throw IOException("Input size limit") };return n }
    override fun read(): Int { val b=`in`.read();if(b>=0) count(1);return b }
    override fun read(b: ByteArray,off: Int,len: Int)=count(`in`.read(b,off,len))
}
