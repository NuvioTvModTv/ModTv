// Catalog grouping adapted from MarechalSp/NuvioTvMod (GPL-3.0).
package com.nuvio.tv.data.livetv
import com.nuvio.tv.domain.model.livetv.LiveChannel
data class TvCatalogSection(val id: String,val title: String,val iconEmoji: String?,val channels: List<LiveChannel>)
object LiveSections {
    fun buildCatalogSections(
        channels: List<LiveChannel>,
        favoriteKeys: Set<String>,
    ): List<TvCatalogSection> {
        if (channels.isEmpty()) return emptyList()

        val sections = mutableListOf<TvCatalogSection>()

        // 1. Favoritos
        val favorites = channels.filter { favoriteKeys.contains(it.key) }
        if (favorites.isNotEmpty()) {
            sections.add(
                TvCatalogSection(
                    id = "favorites",
                    title = "Meus Favoritos",
                    iconEmoji = "⭐",
                    channels = favorites,
                )
            )
        }

        // 2. Esportes & Futebol
        val sports = channels.filter { ch ->
            matchesTheme(ch, setOf("esporte", "esportes", "sport", "sports", "futebol", "premiere", "espn", "sportv", "dazn", "combate", "band sports", "caze"))
        }
        if (sports.isNotEmpty()) {
            sections.add(
                TvCatalogSection(
                    id = "sports",
                    title = "Esportes & Futebol",
                    iconEmoji = "⚽",
                    channels = sports,
                )
            )
        }

        // 3. Notícias & Jornalismo
        val news = channels.filter { ch ->
            matchesTheme(ch, setOf("noticia", "noticias", "news", "jornal", "globonews", "cnn", "bandnews", "record news", "jovem pan"))
        }
        if (news.isNotEmpty()) {
            sections.add(
                TvCatalogSection(
                    id = "news",
                    title = "Notícias & Jornalismo",
                    iconEmoji = "📰",
                    channels = news,
                )
            )
        }

        // 4. Filmes & Séries
        val movies = channels.filter { ch ->
            matchesTheme(ch, setOf("filme", "filmes", "serie", "series", "cinema", "movie", "movies", "telecine", "hbo", "cinemax", "megapix", "warner", "sony", "universal", "paramount", "axn", "tnt", "space", "star"))
        }
        if (movies.isNotEmpty()) {
            sections.add(
                TvCatalogSection(
                    id = "movies",
                    title = "Filmes & Séries",
                    iconEmoji = "🍿",
                    channels = movies,
                )
            )
        }

        // 5. Infantil & Família
        val kids = channels.filter { ch ->
            matchesTheme(ch, setOf("infantil", "desenho", "desenhos", "kids", "animacao", "cartoon", "nick", "disney", "discovery kids", "gloob", "toonavi"))
        }
        if (kids.isNotEmpty()) {
            sections.add(
                TvCatalogSection(
                    id = "kids",
                    title = "Infantil & Desenhos",
                    iconEmoji = "🧸",
                    channels = kids,
                )
            )
        }

        // 6. TV Aberta & Variedades
        val openTv = channels.filter { ch ->
            matchesTheme(ch, setOf("aberta", "globo", "sbt", "record", "band", "rede tv", "cultura", "tv brasil", "gazeta"))
        }
        if (openTv.isNotEmpty()) {
            sections.add(
                TvCatalogSection(
                    id = "opentv",
                    title = "TV Aberta & Variedades",
                    iconEmoji = "📺",
                    channels = openTv,
                )
            )
        }

        // 7. Catálogos específicos dos Addons
        val channelsByCatalog = channels.groupBy { it.catalogName }
        for ((catalogName, catalogChannels) in channelsByCatalog) {
            if (catalogChannels.isNotEmpty() && catalogName.isNotBlank()) {
                val secId = "catalog_${catalogName.lowercase().replace(" ", "_")}"
                if (sections.none { it.id == secId || it.title.equals(catalogName, ignoreCase = true) }) {
                    sections.add(
                        TvCatalogSection(
                            id = secId,
                            title = catalogName,
                            iconEmoji = "📡",
                            channels = catalogChannels,
                        )
                    )
                }
            }
        }

        // 8. Garante que qualquer canal que não tenha entrado em seções anteriores seja exibido
        val coveredKeys = sections.flatMap { it.channels }.map { it.key }.toSet()
        val remainingChannels = channels.filter { !coveredKeys.contains(it.key) }
        if (remainingChannels.isNotEmpty()) {
            sections.add(
                TvCatalogSection(
                    id = "more_channels",
                    title = "Outros Canais",
                    iconEmoji = "📺",
                    channels = remainingChannels,
                )
            )
        }

        return sections
    }

    private fun matchesTheme(channel: LiveChannel, keywords: Set<String>): Boolean {
        val nameLower = channel.name.lowercase()
        val catLower = channel.catalogName.lowercase()
        val genres = channel.genres.map { it.lowercase() }
        for (kw in keywords) {
            if (nameLower.contains(kw) || catLower.contains(kw) || genres.any { it.contains(kw) }) {
                return true
            }
        }
        return false
    }
}
