package com.lagradost.cloudstream3.animeproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.util.*
import kotlin.collections.ArrayList

class TioAnimeProvider:MainAPI() {
    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA") || t.contains("Especial")) TvType.OVA
            else if (t.contains("Película")) TvType.AnimeMovie
            else TvType.Anime
        }
    }
    override val mainUrl = "https://tioanime.com"
    override val name = "TioAnime"
    override val lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.Anime,
    )

    override suspend fun getMainPage(): HomePageResponse {
        val urls = listOf(
            Pair("$mainUrl/directorio?year=1950%2C2022&status=2&sort=recent", "Animes"),
            Pair("$mainUrl/directorio?year=1950%2C2022&status=1&sort=recent", "En Emisión"),
            Pair("$mainUrl/directorio?type[]=1&year=1950%2C2022&status=2&sort=recent", "Películas"),
        )
        val items = ArrayList<HomePageList>()
        items.add(
            HomePageList(
                "Últimos episodios",
                app.get(mainUrl).document.select("ul.episodes li article").map {
                    val title = it.selectFirst("h3.title").text().replace(Regex("((\\d+)\$)"),"")
                    val poster = it.selectFirst("figure img").attr("src")
                    val epRegex = Regex("(-(\\d+)\$)")
                    val url = it.selectFirst("a").attr("href").replace(epRegex,"")
                        .replace("ver/","anime/")
                    val epNum = epRegex.findAll(it.selectFirst("a").attr("href")).map {
                        it.value
                    }.first().toIntOrNull()
                    AnimeSearchResponse(
                        title,
                        fixUrl(url),
                        this.name,
                        TvType.Anime,
                        fixUrl(poster),
                        null,
                        if (title.contains("Latino") || title.contains("Castellano")) EnumSet.of(
                            DubStatus.Dubbed
                        ) else EnumSet.of(DubStatus.Subbed),
                        subEpisodes = epNum,
                        dubEpisodes = epNum,
                    )
                })
        )
        for ((url, name) in urls) {
            try {
                val doc = app.get(url).document
                val home = doc.select("ul.animes li article").map {
                    val title = it.selectFirst("h3.title").text()
                    val poster = it.selectFirst("figure img").attr("src")
                    AnimeSearchResponse(
                        title,
                        fixUrl(it.selectFirst("a").attr("href")),
                        this.name,
                        TvType.Anime,
                        fixUrl(poster),
                        null,
                        if (title.contains("Latino") || title.contains("Castellano")) EnumSet.of(DubStatus.Dubbed) else EnumSet.of(DubStatus.Subbed),
                    )
                }

                items.add(HomePageList(name, home))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }

    data class SearchObject (
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("last_id") val lastId: String?,
        @JsonProperty("slug") val slug: String
    )

    override suspend fun search(query: String): ArrayList<SearchResponse> {
        val response = app.post("https://tioanime.com/api/search",
            data = mapOf(Pair("value",query))
        ).text
        val json = parseJson<List<SearchObject>>(response)
        val search = ArrayList<AnimeSearchResponse>()
        json.map { searchr ->
            val title = searchr.title
            val href = "$mainUrl/anime/${searchr.slug}"
            val image = "$mainUrl/uploads/portadas/${searchr.id}.jpg"
            search.add(
                AnimeSearchResponse(
                    title,
                    href,
                    this.name,
                    TvType.Anime,
                    fixUrl(image),
                    null,
                    if (title.contains("Latino") || title.contains("Castellano")) EnumSet.of(DubStatus.Dubbed) else EnumSet.of(DubStatus.Subbed),
                )
            )
        }
        return ArrayList(search)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val episodes = ArrayList<AnimeEpisode>()
        val title = doc.selectFirst("h1.Title").text()
        val poster = doc.selectFirst("div.thumb img").attr("src")
        val description = doc.selectFirst("p.sinopsis").text()
        val type = doc.selectFirst("span.anime-type-peli").text()
        val status = when (doc.selectFirst("div.thumb a.btn.status i")?.text()) {
            "En emision" -> ShowStatus.Ongoing
            "Finalizado" -> ShowStatus.Completed
            else -> null
        }
        val genre = doc.select("p.genres a")
            .map { it?.text()?.trim().toString() }
        val year = doc.selectFirst("span.year").text().toIntOrNull()

        doc.select("script").map { script ->
            if (script.data().contains("var episodes = [")) {
                val data = script.data().substringAfter("var episodes = [").substringBefore("];")
                data.split("],").forEach {
                     it.split(",").forEach { epNum ->
                        val link = url.replace("/anime/","/ver/")+"-$epNum"
                        episodes.add( AnimeEpisode(
                            link,
                            "Capítulo $epNum",
                            posterUrl = null,
                            episode = epNum.toIntOrNull()
                        )
                        )
                    }
                }
            }
        }
        return newAnimeLoadResponse(title, url, getType(type)) {
            posterUrl = fixUrl(poster)
            addEpisodes(DubStatus.Subbed, episodes.reversed())
            showStatus = status
            plot = description
            tags = genre
            this.year = year
        }
    }
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        app.get(data).document.select("script").apmap { script ->
            if (script.data().contains("var videos =") || script.data().contains("var anime_id =") || script.data().contains("server")) {
                val videos = script.data().replace("\\/", "/")
                fetchUrls(videos).map {
                    it.replace("https://embedsb.com/e/","https://watchsb.com/e/")
                        .replace("https://ok.ru","http://ok.ru")
                }.toList().apmap {
                    loadExtractor(it, data, callback)
                }
            }
        }
        return true
    }
}
