package com.droplay.tv.data

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.text.SimpleDateFormat
import java.util.Locale
import javax.xml.parsers.SAXParserFactory

object EpgParser {
    fun parse(url: String, now: Long = System.currentTimeMillis()): Map<String, EpgProgram> {
        val programs = mutableMapOf<String, EpgProgram>()
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        Network.open(url).use { input ->
            factory.newSAXParser().parse(input, object : DefaultHandler() {
                var channel = ""; var start = 0L; var stop = 0L
                var tag = ""; var title = ""; var desc = ""; val text = StringBuilder()
                override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                    tag = qName; text.clear()
                    if (qName == "programme") {
                        channel = attributes.getValue("channel").orEmpty()
                        start = xmlTime(attributes.getValue("start"))
                        stop = xmlTime(attributes.getValue("stop"))
                        title = ""; desc = ""
                    }
                }
                override fun characters(ch: CharArray, start: Int, length: Int) { text.append(ch, start, length) }
                override fun endElement(uri: String?, localName: String?, qName: String) {
                    when (qName) { "title" -> title = text.toString().trim(); "desc" -> desc = text.toString().trim() }
                    if (qName == "programme" && now in start until stop && channel !in programs) {
                        programs[channel] = EpgProgram(channel, title.ifBlank { "No ar" }, start, stop, desc)
                    }
                    tag = ""
                }
            })
        }
        return programs
    }

    private fun xmlTime(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0
        val normalized = raw.trim().replace(Regex("\\s+"), " ")
        val patterns = listOf("yyyyMMddHHmmss Z", "yyyyMMddHHmm Z", "yyyyMMddHHmmss")
        return patterns.firstNotNullOfOrNull { pattern ->
            runCatching { SimpleDateFormat(pattern, Locale.US).parse(normalized)?.time }.getOrNull()
        } ?: 0
    }
}
