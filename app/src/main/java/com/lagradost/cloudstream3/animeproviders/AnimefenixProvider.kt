package com.lagradost.cloudstream3.animeproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.util.*
import kotlin.collections.ArrayList


class AnimefenixProvider:MainAPI() {

    override var mainUrl = "https://animefenix.com"
    override var name = "Animefenix"
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
            Pair("$mainUrl/", "Animes"),
            Pair("$mainUrl/animes?type[]=movie&order=default", "Peliculas", ),
            Pair("$mainUrl/animes?type[]=ova&order=default", "OVA's", ),
        )

        val items = ArrayList<HomePageList>()

        items.add(
            HomePageList(
                "Últimos episodios",
                app.get(mainUrl).document.select(".capitulos-grid div.item").map {
                    val title = it.selectFirst("div.overtitle").text()
                    val poster = it.selectFirst("a img").attr("src")
                    val epRegex = Regex("(-(\\d+)\$|-(\\d+)\\.(\\d+))")
                    val url = it.selectFirst("a").attr("href").replace(epRegex,"")
                        .replace("/ver/","/")
                    val epNum = it.selectFirst(".is-size-7").text().replace("Episodio ","").toIntOrNull()
                    AnimeSearchResponse(
                        title,
                        url,
                        this.name,
                        TvType.Anime,
                        poster,
                        null,
                        if (title.contains("Latino") || title.contains("Castellano")) EnumSet.of(
                            DubStatus.Dubbed
                        ) else EnumSet.of(DubStatus.Subbed),
                        subEpisodes = epNum,
                        dubEpisodes = epNum,
                    )
                })
        )

        urls.apmap { (url, name) ->
            val response = app.get(url)
            val soup = Jsoup.parse(response.text)
            val home = soup.select(".list-series article").map {
                val title = it.selectFirst("h3 a").text()
                val poster = it.selectFirst("figure img").attr("src")
                AnimeSearchResponse(
                    title,
                    it.selectFirst("a").attr("href"),
                    this.name,
                    TvType.Anime,
                    poster,
                    null,
                    if (title.contains("Latino")) EnumSet.of(DubStatus.Dubbed) else EnumSet.of(DubStatus.Subbed),
                )
            }

            items.add(HomePageList(name, home))
        }

        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): ArrayList<SearchResponse> {
        val search =
            Jsoup.parse(app.get("$mainUrl/animes?q=$query", timeout = 120).text).select(".list-series article").map {
                val title = it.selectFirst("h3 a").text()
                val href = it.selectFirst("a").attr("href")
                val image = it.selectFirst("figure img").attr("src")
                AnimeSearchResponse(
                    title,
                    href,
                    this.name,
                    TvType.Anime,
                    fixUrl(image),
                    null,
                    if (title.contains("Latino") || title.contains("Castellano")) EnumSet.of(DubStatus.Dubbed) else EnumSet.of(
                        DubStatus.Subbed),
                )
            }
        return ArrayList(search)
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = Jsoup.parse(app.get(url, timeout = 120).text)
        val poster = doc.selectFirst(".image > img").attr("src")
        val title = doc.selectFirst("h1.title.has-text-orange").text()
        val description = doc.selectFirst("p.has-text-light").text()
        val genres = doc.select(".genres a").map { it.text() }
        val status = when (doc.selectFirst(".is-narrow-desktop a.button")?.text()) {
            "Emisión" -> ShowStatus.Ongoing
            "Finalizado" -> ShowStatus.Completed
            else -> null
        }
        val episodes = doc.select(".anime-page__episode-list li").map {
            val name = it.selectFirst("span").text()
            val link = it.selectFirst("a").attr("href")
            AnimeEpisode(link, name)
        }.reversed()

        val href = doc.selectFirst(".anime-page__episode-list li")
        val hrefmovie = href.selectFirst("a").attr("href")
        val type = if (doc.selectFirst("ul.has-text-light").text()
                .contains("Película") && episodes.size == 1
        ) TvType.AnimeMovie else TvType.Anime

        return when (type) {
            TvType.Anime -> {
                return newAnimeLoadResponse(title, url, type) {
                    japName = null
                    engName = title
                    posterUrl = poster
                    addEpisodes(DubStatus.Subbed, episodes)
                    plot = description
                    tags = genres
                    showStatus = status

                }
            }
            TvType.AnimeMovie -> {
                MovieLoadResponse(
                    title,
                    url,
                    this.name,
                    type,
                    hrefmovie,
                    poster,
                    null,
                    description,
                    null,
                    null,
                    genres,
                )
            }
            else -> null
        }
    }

    private fun cleanStreamID(input: String): String = input.replace(Regex("player=.*&amp;code=|&"),"")

    data class Amazon (
        @JsonProperty("file") var file  : String? = null,
        @JsonProperty("type") var type  : String? = null,
        @JsonProperty("label") var label : String? = null
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        app.get(data).document.select(".player-container script").apmap { script ->
            if (script.data().contains("var tabsArray =")) {
                val html = script.data()
                val sourcesRegex = Regex("player=.*&amp;code(.*)&")
                val test = sourcesRegex.findAll(html).toList()
                test.apmap {
                    val codestream = it.value
                    val fembed = if (codestream.contains("player=2&amp")) {
                        "https://embedsito.com/v/"+cleanStreamID(codestream)
                    } else ""
                    val mp4Upload = if (codestream.contains("player=3&amp")) {
                        "https://www.mp4upload.com/embed-"+cleanStreamID(codestream)+".html"
                    } else ""
                    val yourUpload = if (codestream.contains("player=6&amp")) {
                        "https://www.yourupload.com/embed/"+cleanStreamID(codestream)
                    } else ""
                    val okru = if (codestream.contains("player=12&amp")) {
                        "https://ok.ru/videoembed/"+cleanStreamID(codestream)
                    } else ""
                    val sendvid = if (codestream.contains("player=4&amp")) {
                        "https://sendvid.com/"+cleanStreamID(codestream)
                    } else ""
                    val amazon =  if (codestream.contains("player=9&amp")) {
                        "https://www.animefenix.com/stream/amz.php?v="+cleanStreamID(codestream)
                    } else ""
                    val amazonES =  if (codestream.contains("player=11&amp")) {
                        "https://www.animefenix.com/stream/amz.php?v="+cleanStreamID(codestream)
                    } else ""
                    val fireload = if (codestream.contains("player=22&amp")) {
                        "https://www.animefenix.com/stream/fl.php?v="+cleanStreamID(codestream)
                    } else ""
                    val servers = listOf(
                        fembed,
                        mp4Upload,
                        yourUpload,
                        okru,
                        sendvid)
                    servers.apmap { url ->
                        loadExtractor(url, data, callback)
                    }
                argamap({
                    if (amazon.isNotBlank()) {
                        val doc = app.get(amazon).document
                        doc.select("script").map { script ->
                            if (script.data().contains("sources: [{\"file\"")) {
                                val text = script.data().substringAfter("sources:").substringBefore("]").replace("[","")
                                val json = parseJson<Amazon>(text)
                                if (json.file != null) {
                                    callback(
                                        ExtractorLink(
                                            "Amazon",
                                            "Amazon ${json.label}",
                                            json.file!!,
                                            "",
                                            Qualities.Unknown.value,
                                            isM3u8 = false
                                        )
                                    )
                                }
                            }
                        }
                    }

                    if (amazonES.isNotBlank()) {
                        val doc = app.get("$amazonES&ext=es").document
                        doc.select("script").map { script ->
                            if (script.data().contains("sources: [{\"file\"")) {
                                val text = script.data().substringAfter("sources:").substringBefore("]").replace("[","")
                                val json = parseJson<Amazon>(text)
                                if (json.file != null) {
                                    callback(
                                        ExtractorLink(
                                            "AmazonES",
                                            "AmazonES ${json.label}",
                                            json.file!!,
                                            "",
                                            Qualities.Unknown.value,
                                            isM3u8 = false
                                        )
                                    )
                                }
                            }
                        }
                    }
                    if (fireload.isNotBlank()) {
                        val doc = app.get(fireload).document
                        doc.select("script").map { script ->
                            if (script.data().contains("sources: [{\"file\"")) {
                                val text = script.data().substringAfter("sources:").substringBefore("]").replace("[","")
                                val json = parseJson<Amazon>(text)
                                val testurl = if (json.file?.contains("fireload") == true) {
                                    app.get("https://${json.file}").text
                                } else null
                                if (testurl?.contains("error") == true) {
                                    //
                                } else if (json.file?.contains("fireload") == true) {
                                    callback(
                                        ExtractorLink(
                                            "Fireload",
                                            "Fireload ${json.label}",
                                            "https://"+json.file!!,
                                            "",
                                            Qualities.Unknown.value,
                                            isM3u8 = false
                                        )
                                    )

                                }
                            }
                        }
                    }
                })
                }
            }
        }
        return true
    }
}