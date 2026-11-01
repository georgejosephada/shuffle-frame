package com.gjnet.shuffleframe

import android.text.Html
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Reads link-shared Google Photos albums the way the photos.google.com web page does.
 * Google's official APIs no longer let third-party apps read existing albums (since 2025-03-31),
 * so this uses the page's internal "snAcKc" request. All parsing of Google's format lives here.
 */
object GooglePhotos {
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    private const val RPC = "snAcKc"
    /** Key of the per-item extras entry that only videos carry; value is [durationMs, ...]. */
    private const val VIDEO_INFO = "76647426"
    private const val MAX_PAGES = 500

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    class Resolved(val albumKey: String, val authKey: String, val title: String)
    class Listing(val title: String?, val coverUrl: String?, val items: List<Item>)

    private val LINK = Regex("""https?://(?:photos\.app\.goo\.gl|goo\.gl/photos|photos\.google\.com)/[^\s"'<>]+""")
    private val REQUEST = Regex(
        """snAcKc[^}]*?request:\s*\[\s*"([A-Za-z0-9_-]+)"\s*,\s*null\s*,\s*null\s*,\s*"([A-Za-z0-9_-]+)""""
    )
    private val TITLE = Regex("<title>([^<]*)</title>")

    /** Finds a Google Photos link inside shared text such as "Check out my album https://…". */
    fun findLink(text: String?): String? =
        text?.let { LINK.find(it)?.value?.trimEnd('.', ',', ')', ']') }

    fun resolve(link: String): Resolved {
        val req = Request.Builder().url(link)
            .header("User-Agent", UA)
            .header("Accept-Language", "en")
            .build()
        http.newCall(req).execute().use { resp ->
            val url = resp.request.url
            if ("album" in url.pathSegments && "share" !in url.pathSegments) {
                throw IOException("That is a private album link. In Google Photos open the album, tap Share and create a link, then share that link.")
            }
            if (resp.code == 404) throw IOException("Album not found. Check that link sharing is turned on for it.")
            if (!resp.isSuccessful) throw IOException("Google Photos returned HTTP ${resp.code}")
            val html = resp.body?.string().orEmpty()

            var albumKey: String? = null
            var authKey: String? = null
            REQUEST.find(html)?.let {
                albumKey = it.groupValues[1]
                authKey = it.groupValues[2]
            }
            if (albumKey == null) {
                val i = url.pathSegments.indexOf("share")
                if (i >= 0 && i + 1 < url.pathSegments.size) albumKey = url.pathSegments[i + 1]
                authKey = url.queryParameter("key")
            }
            if (albumKey.isNullOrEmpty() || authKey.isNullOrEmpty()) {
                throw IOException("That link doesn't look like a shared Google Photos album.")
            }
            val title = TITLE.find(html)?.groupValues?.get(1)
                ?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString() }
                ?.removeSuffix(" - Google Photos")?.trim()
            return Resolved(albumKey!!, authKey!!, title?.ifBlank { null } ?: "Untitled album")
        }
    }

    /** Lists every item in the album. Throws rather than returning a partial listing. */
    fun listAlbum(albumKey: String, authKey: String): Listing {
        val all = LinkedHashMap<String, Item>()
        val tokens = HashSet<String>()
        var token: String? = null
        var title: String? = null
        var cover: String? = null
        for (page in 0 until MAX_PAGES) {
            val inner = rpc(albumKey, authKey, token)
            if (page == 0) {
                val meta = inner.optJSONArray(3)
                title = meta?.optString(1)?.takeIf { it.isNotBlank() && it != "null" }
                cover = meta?.optJSONArray(4)?.optString(0)?.takeIf { it.startsWith("http") }
            }
            var added = 0
            inner.optJSONArray(1)?.let { arr ->
                for (i in 0 until arr.length()) {
                    val item = parseItem(arr.optJSONArray(i)) ?: continue
                    if (all.put(item.key, item) == null) added++
                }
            }
            val next = (if (inner.isNull(2)) null else inner.optString(2).ifEmpty { null })
                ?: return Listing(title, cover, all.values.toList())
            if (added == 0 || !tokens.add(next)) throw IOException("Google Photos paging stopped early; keeping the local copy.")
            token = next
        }
        throw IOException("Album is too large to list; keeping the local copy.")
    }

    private fun rpc(albumKey: String, authKey: String, token: String?): JSONArray {
        val args = JSONArray().put(albumKey).put(token ?: JSONObject.NULL).put(JSONObject.NULL).put(authKey)
        val freq = JSONArray().put(JSONArray().put(JSONArray().put(RPC).put(args.toString()).put(JSONObject.NULL).put("generic")))
        val path = URLEncoder.encode("/share/$albumKey", "UTF-8")
        val req = Request.Builder()
            .url("https://photos.google.com/_/PhotosUi/data/batchexecute?rpcids=$RPC&source-path=$path&rt=c")
            .header("User-Agent", UA)
            .header("Origin", "https://photos.google.com")
            .header("Referer", "https://photos.google.com/share/$albumKey?key=$authKey")
            .post(FormBody.Builder().add("f.req", freq.toString()).build())
            .build()
        val text = http.newCall(req).execute().use { resp ->
            if (resp.code == 429) throw IOException("Google is rate-limiting requests; will retry later.")
            if (!resp.isSuccessful) throw IOException("Google Photos returned HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        // The body is ")]}'" followed by length-prefixed chunks, each a JSON array on one line.
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (!t.startsWith("[")) continue
            val outer = try { JSONArray(t) } catch (e: Exception) { continue }
            for (i in 0 until outer.length()) {
                val row = outer.optJSONArray(i) ?: continue
                if (row.optString(0) == "wrb.fr" && row.optString(1) == RPC) {
                    if (row.isNull(2)) throw IOException("Google Photos refused the request. Is the album still shared?")
                    return JSONArray(row.getString(2))
                }
            }
        }
        throw IOException("Unexpected response from Google Photos.")
    }

    // Item layout: [key, [baseUrl, width, height, ...], takenAtMs, ?, tzOffsetMs, ..., {extras}]
    private fun parseItem(a: JSONArray?): Item? {
        if (a == null) return null
        val key = a.optString(0)
        if (key.isEmpty() || key == "null") return null
        val media = a.optJSONArray(1) ?: return null
        val url = media.optString(0)
        if (!url.startsWith("http")) return null
        var extras: JSONObject? = null
        for (i in a.length() - 1 downTo 0) {
            val o = a.opt(i)
            if (o is JSONObject) { extras = o; break }
        }
        val video = extras?.optJSONArray(VIDEO_INFO)
        return Item(
            key = key,
            type = if (video != null) MediaType.VIDEO else MediaType.PHOTO,
            baseUrl = url,
            width = media.optInt(1),
            height = media.optInt(2),
            takenAt = a.optLong(2),
            tzOffsetMs = a.optLong(4),
            durationMs = video?.optLong(0) ?: 0L,
        )
    }

    /** Downloads to a temporary file and renames it, so a partial file is never played. */
    fun download(url: String, dest: File) {
        val part = File(dest.path + ".part")
        try {
            http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                if (r.header("Content-Type").orEmpty().startsWith("text/")) {
                    throw IOException("Google is still processing this item")
                }
                val body = r.body ?: throw IOException("Empty response")
                part.outputStream().use { out -> body.byteStream().copyTo(out, 256 * 1024) }
            }
            if (part.length() == 0L) throw IOException("Empty download")
            if (!part.renameTo(dest)) throw IOException("Could not save ${dest.name}")
        } finally {
            part.delete()
        }
    }
}
