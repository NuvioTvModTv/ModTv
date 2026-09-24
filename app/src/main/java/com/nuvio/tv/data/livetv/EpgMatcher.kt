// Adapted from MarechalSp/NuvioTvMod 82cc79de, GPL-3.0.
package com.nuvio.tv.data.livetv
import com.nuvio.tv.domain.model.livetv.*
class EpgMatcher(channels: List<EpgChannel>) {
    private val cachedChannels=channels.associateBy { it.id }
    private val cachedProgramsByChannelId=channels.associate { it.id to it.hasPrograms }
    private val keyToChannelIds=linkedMapOf<String,MutableList<String>>()
    private val PARENS_REGEX=Regex("\\([^)]*\\)")
    private val matchResults=HashMap<String,String?>()
    fun match(channel: LiveChannel): String? {
        if(matchResults.containsKey(channel.key)) return matchResults[channel.key]
        val clean=channel.name.substringBefore('|').trim().removeSuffix(" - "+channel.addonName).trim()
        val result=computeMatchedXmlTvId(channel.copy(name=clean))
        matchResults[channel.key]=result
        return result
    }
    private val ALIASES = mapOf(
        "h2" to "history2",
        "premiere1" to "premiereclubes",
        "premiere" to "premiereclubes",
        "discoveryhh" to "discoveryhomehealth",
        "universaltv" to "universal",
        "sonychannel" to "sony",
        "cnbbrasil" to "cnbc",
        "historychannel" to "history",
        "uniao" to "recordtv",
        "uniaofortaleza" to "recordtv",
        "espn" to "espnbrasil",
        "espn1" to "espnbrasil",
        "combate" to "canalcombate",
        "telecinepremium" to "telecinepremium",
        "telecinepipoca" to "telecinepipoca",
        "telecineaction" to "telecineaction",
        "benficatv" to "slbtv",
        "canal11" to "canal11pt",
        "usanetwork" to "usa",
        "paramountnetwork" to "paramount",
        "comedycentral" to "comedy",
        "animalplanet" to "animal",
        "nationalgeographic" to "natgeo",
        "disneychannel" to "disney",
        "cartoonnetwork" to "cartoon",
    )

    private val QUALITY_TOKENS = setOf("hd", "fhd", "uhd", "4k", "sd", "hdtv", "fullhd")
    private val FILLER_TOKENS = setOf("canal", "channel", "tv", "rede", "and", "e", "pt", "br", "aovivo", "live")

    init { channels.forEach { ch ->
        val keys=mutableSetOf<String>();addKeyVariants(extractChannelKeyFromId(ch.id),keys)
        ch.displayNames.forEach { addKeyVariants(it,keys) }
        keys.forEach { keyToChannelIds.getOrPut(it) { mutableListOf() }.add(ch.id) }
    } }

    private fun addKeyVariants(raw: String, targetSet: MutableSet<String>) {
        val (k1, _) = normalizeChave(raw, curta = false)
        val (k2, _) = normalizeChave(raw, curta = true)
        if (k1.isNotBlank()) targetSet.add(k1)
        if (k2.isNotBlank()) targetSet.add(k2)
    }

    /**
     * Extrai a chave de canal a partir do formato do ID do epgshare01/XMLTV.
     * Exemplo: "Sao.Paulo/SP..Cartoonito.br" -> "Cartoonito"
     * Exemplo: "MG..TV.Aparecida.(aberta).br" -> "TV Aparecida"
     */
    private fun extractChannelKeyFromId(id: String): String {
        var s = id.trim()
        if (s.endsWith(".br", ignoreCase = true)) {
            s = s.substring(0, s.length - 3)
        }
        val doubleDot = s.indexOf("..")
        if (doubleDot != -1) {
            s = s.substring(doubleDot + 2)
        } else {
            val lastSlash = s.lastIndexOf('/')
            if (lastSlash != -1) {
                s = s.substring(lastSlash + 1)
            }
        }
        // Remove parênteses como "(aberta)" ou "(espelho)"
        s = s.replace(PARENS_REGEX, " ")
        // Substitui pontos e underscores por espaços
        s = s.replace('.', ' ').replace('_', ' ').trim()
        return s
    }

    /**
     * Normalização de alto desempenho idêntica ao normChave do nuvio-native-legacy (epg.c):
     * - Letras com acentos convertidas para base (á->a, ç->c) em passo único direto
     * - Minúsculas
     * - Descarte de sufixos de qualidade (hd, 4k, fhd, etc.)
     * - Se curta = true, descarte de marcadores (canal, tv, rede, etc.)
     * - Retorna a chave unificada e o primeiro token válido sem criar instâncias Regex
     */
    fun normalizeChave(input: String, curta: Boolean): Pair<String, String> {
        val tokens = mutableListOf<String>()
        val currentToken = StringBuilder()

        for (i in 0 until input.length) {
            val rawChar = input[i]
            val lower = rawChar.lowercaseChar()
            val ch = when (lower) {
                'á', 'à', 'â', 'ã', 'ä' -> 'a'
                'é', 'è', 'ê', 'ë' -> 'e'
                'í', 'ì', 'î', 'ï' -> 'i'
                'ó', 'ò', 'ô', 'õ', 'ö' -> 'o'
                'ú', 'ù', 'û', 'ü' -> 'u'
                'ç' -> 'c'
                else -> lower
            }

            if (ch in 'a'..'z' || ch in '0'..'9') {
                currentToken.append(ch)
            } else {
                if (currentToken.isNotEmpty()) {
                    tokens.add(currentToken.toString())
                    currentToken.clear()
                }
            }
        }
        if (currentToken.isNotEmpty()) {
            tokens.add(currentToken.toString())
        }

        val acceptedTokens = mutableListOf<String>()
        for (tok in tokens) {
            if (tok in QUALITY_TOKENS) continue
            if (curta && tok in FILLER_TOKENS) continue
            acceptedTokens.add(tok)
        }

        val fullKey = acceptedTokens.joinToString("")
        val firstToken = acceptedTokens.firstOrNull().orEmpty()
        return fullKey to firstToken
    }

    fun normalizeName(input: String): String = normalizeChave(input, curta = false).first

    fun simplifyChannelName(input: String): String = normalizeChave(input, curta = true).first

    private fun computeMatchedXmlTvId(channel: LiveChannel): String? {
        // 1. Match direto por ID
        if (cachedChannels.containsKey(channel.id)) {
            return disambiguateWithPrograms(listOf(channel.id))
        }

        val (k1, _) = normalizeChave(channel.name, curta = false)
        val (k2, prim) = normalizeChave(channel.name, curta = true)
        if (k1.isBlank()) return null

        // 2. Chave normalizada exata (k1 ou k2)
        keyToChannelIds[k1]?.let { candidates ->
            return disambiguateWithPrograms(candidates)
        }
        if (k2.isNotBlank()) {
            keyToChannelIds[k2]?.let { candidates ->
                return disambiguateWithPrograms(candidates)
            }
        }

        // 3. Tabela de apelidos curada
        val aliasKey = ALIASES[k1] ?: ALIASES[k2]
        if (aliasKey != null) {
            keyToChannelIds[aliasKey]?.let { candidates ->
                return disambiguateWithPrograms(candidates)
            }
        }

        // 4. A chave da grade é prefixo da chave do canal (ex: "recordtv" prefixo de "recordtvpaulista")
        var bestPrefixMatch: String? = null
        var bestPrefixLen = 0
        for ((epgKey, channelIds) in keyToChannelIds) {
            val len = epgKey.length
            if (len in 4 until k1.length && k1.startsWith(epgKey) && len > bestPrefixLen) {
                bestPrefixMatch = disambiguateWithPrograms(channelIds)
                bestPrefixLen = len
            }
        }
        if (bestPrefixMatch != null) return bestPrefixMatch

        // 5. O canal é prefixo de UMA ÚNICA chave da grade (ambígua não casa)
        if (k1.length >= 4) {
            val prefixCandidates = mutableListOf<String>()
            for ((epgKey, channelIds) in keyToChannelIds) {
                if (epgKey.length > k1.length && epgKey.startsWith(k1)) {
                    prefixCandidates.addAll(channelIds)
                }
            }
            if (prefixCandidates.distinct().size == 1) {
                return prefixCandidates.first()
            }
        }

        // 6. Substring: chave comprida da grade (>= 6 chars) aparece inteira no nome do canal
        var bestSubMatch: String? = null
        var bestSubLen = 0
        var isSubAmbig = false
        for ((epgKey, channelIds) in keyToChannelIds) {
            val len = epgKey.length
            if (len >= 6 && k1.length > len && (k1.contains(epgKey) || (k2.isNotBlank() && k2.contains(epgKey)))) {
                if (len > bestSubLen) {
                    bestSubLen = len
                    bestSubMatch = disambiguateWithPrograms(channelIds)
                    isSubAmbig = false
                } else if (len == bestSubLen && bestSubMatch != disambiguateWithPrograms(channelIds)) {
                    isSubAmbig = true
                }
            }
        }
        if (bestSubMatch != null && !isSubAmbig) return bestSubMatch

        // 7. Regra da Afiliada Regional (primeiro token aceito com >= 3 chars é a rede: ex: "SBT RJ" -> "sbt")
        if (prim.length >= 3) {
            keyToChannelIds[prim]?.let { candidates ->
                return disambiguateWithPrograms(candidates)
            }
        }

        return null
    }

    /**
     * Desempate: Se múltiplos canais tiverem a mesma chave, prefere o canal que
     * realmente possui programas no XMLTV (regra 'comGrade' de epg.c).
     */
    private fun disambiguateWithPrograms(candidates: List<String>): String? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()

        for (cid in candidates) {
            val progs = cachedProgramsByChannelId[cid]
            if (progs == true) {
                return cid
            }
        }
        return candidates.first()
    }

}
