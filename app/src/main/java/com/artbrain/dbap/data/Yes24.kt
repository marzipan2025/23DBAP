package com.artbrain.dbap.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 검색 결과 중 제목이 정확히 일치하는 도서 */
data class Yes24Candidate(
    val goodsNo: Long,
    val title: String,
    val author: String,
    val publisher: String,
    /** "yyyyMM", 모르면 "000000" */
    val yearMonth: String
)

/** 표지·책등이 모두 확인된 후보 + 이미 받아둔 표지 원본 */
class Yes24Result(
    val candidate: Yes24Candidate,
    val frontBytes: ByteArray,
    val frontBitmap: Bitmap
)

class Yes24Exception(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * YES24 비공식 경로로 책을 찾는다. (2026-09 검증)
 *
 * - 검색: /Product/Search?domain=BOOK&order=RECENT — 최신순, 페이지당 40건
 * - 걸러내기: [도서] 이고 제목(공백 무시)이 입력과 완전히 같은 것만.
 *   저자 역할에 그림·편·편저·원저가 있으면 각색판으로 보고 뺀다.
 * - "책이 있다": 표지(XL)·책등(SIDE) 모두 200 + ETag 있음 + ETag에 Noimg 없음.
 *   플레이스홀더는 ETag가 없고, "이미지 준비중"은 ETag에 Noimg가 들어 있다.
 * - 등록 직전에 책등 원본의 가로/세로 비율로 한 번 더 확인한다.
 */
object Yes24 {
    private const val USER_AGENT = "Mozilla/5.0"
    private const val PAGE_SIZE = 40
    /** 동시에 보내는 이미지 요청 수 */
    internal const val PARALLEL = 8
    private const val SPINE_MAX_ASPECT = 0.35f
    private const val FALLBACK_HEIGHT_MM = 210f

    private val EXCLUDED_ROLES = setOf("그림", "편", "편저", "원저")

    private val itemStart = Regex("""<li[^>]*data-goods-no="(\d+)"""")
    private val resRe = Regex("""class="gd_res">([^<]*)""")
    private val nameRe = Regex("""class="gd_name"[^>]*>([^<]*)""")
    private val authRe = Regex("""info_auth"[^>]*>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
    private val pubRe = Regex("""info_pub"[^>]*>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
    private val dateRe = Regex("""info_date">(\d{4})년 (\d{2})월""")
    private val tagRe = Regex("<[^>]*>")
    private val spaceRe = Regex("""\s+""")
    private val sizeRe = Regex("""(\d+)\s*[*xX×]\s*(\d+)\s*[*xX×]\s*(\d+)\s*mm""")

    /** 한 페이지 검색. 페이지에 원래 몇 건이 있었는지(끝 판정용)와 일치 후보를 돌려준다. */
    suspend fun searchPage(query: String, page: Int): Pair<List<Long>, List<Yes24Candidate>> =
        withContext(Dispatchers.IO) {
            val q = URLEncoder.encode(query, "UTF-8")
            val html = getText(
                "https://www.yes24.com/Product/Search?domain=BOOK&query=$q" +
                    "&order=RECENT&page=$page&size=$PAGE_SIZE"
            )
            val starts = itemStart.findAll(html).toList()
            val all = starts.map { it.groupValues[1].toLong() }
            val wanted = normalize(query)
            val matches = starts.mapIndexedNotNull { i, m ->
                val end = if (i + 1 < starts.size) starts[i + 1].range.first else html.length
                val chunk = html.substring(m.range.first, end)
                val res = resRe.find(chunk)?.groupValues?.get(1)?.trim()
                val name = nameRe.find(chunk)?.groupValues?.get(1)?.let(::plain) ?: return@mapIndexedNotNull null
                if (res != "[도서]" || normalize(name) != wanted) return@mapIndexedNotNull null
                val author = authRe.find(chunk)?.groupValues?.get(1)?.let(::plain).orEmpty()
                if (isAdaptation(author)) return@mapIndexedNotNull null
                val date = dateRe.find(chunk)
                Yes24Candidate(
                    goodsNo = m.groupValues[1].toLong(),
                    title = name,
                    author = author,
                    publisher = pubRe.find(chunk)?.groupValues?.get(1)?.let(::plain).orEmpty(),
                    yearMonth = date?.let { it.groupValues[1] + it.groupValues[2] } ?: "000000"
                )
            }
            all to matches
        }

    /** 표지·책등 확인 후 표지를 내려받는다. 둘 중 하나라도 없으면 null */
    suspend fun verify(candidate: Yes24Candidate, gate: Semaphore): Yes24Result? =
        withContext(Dispatchers.IO) {
            val base = "https://image.yes24.com/goods/${candidate.goodsNo}"
            val front = async { gate.withPermit { hasRealImage("$base/XL") } }
            val spine = async { gate.withPermit { hasRealImage("$base/SIDE") } }
            if (!front.await() || !spine.await()) return@withContext null
            val bytes = gate.withPermit { getBytes("$base/XL") }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return@withContext null
            Yes24Result(candidate, bytes, bitmap)
        }

    /** 등록에 필요한 책등 원본과 판형 높이를 받는다 */
    suspend fun fetchForRegister(result: Yes24Result): Pair<ByteArray, Float> = coroutineScope {
        val no = result.candidate.goodsNo
        val spine = async(Dispatchers.IO) { getBytes("https://image.yes24.com/goods/$no/SIDE/XL") }
        val height = async(Dispatchers.IO) {
            runCatching { heightMm(no) }.getOrNull() ?: FALLBACK_HEIGHT_MM
        }
        val spineBytes = spine.await()
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(spineBytes, 0, spineBytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0 ||
            opts.outWidth.toFloat() / opts.outHeight > SPINE_MAX_ASPECT
        ) {
            throw Yes24Exception("책등 이미지가 올바르지 않아요")
        }
        spineBytes to height.await()
    }

    private fun heightMm(goodsNo: Long): Float? {
        val html = getText("https://www.yes24.com/Product/Goods/$goodsNo")
        return sizeRe.find(html)?.groupValues?.get(2)?.toFloatOrNull()?.takeIf { it in 80f..500f }
    }

    private fun isAdaptation(author: String): Boolean =
        author.split('/').any { part ->
            part.trim().substringAfterLast(' ') in EXCLUDED_ROLES
        }

    private fun normalize(s: String) = spaceRe.replace(s, "")

    private fun plain(s: String): String {
        val noTags = tagRe.replace(s, "")
        return spaceRe.replace(Html.fromHtml(noTags, Html.FROM_HTML_MODE_LEGACY).toString(), " ").trim()
    }

    private suspend fun hasRealImage(url: String): Boolean = withContext(Dispatchers.IO) {
        val conn = open(url).apply { requestMethod = "HEAD" }
        try {
            val etag = conn.getHeaderField("ETag")
            conn.responseCode == 200 && etag != null && !etag.contains("Noimg", ignoreCase = true)
        } finally {
            conn.disconnect()
        }
    }

    private fun getText(url: String): String = String(getBytes(url), Charsets.UTF_8)

    private fun getBytes(url: String): ByteArray {
        val conn = open(url)
        try {
            if (conn.responseCode != 200) throw Yes24Exception("응답 ${conn.responseCode}: $url")
            return conn.inputStream.use { it.readBytes() }
        } catch (e: IOException) {
            throw Yes24Exception("네트워크 연결을 확인해 주세요", e)
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
}

/**
 * 한 번의 검색. 최신순으로 페이지를 넘기며 조건에 맞는 책을 모은다.
 * 결과가 하나도 안 나온 페이지만 이어지면 [maxEmptyPages] 만큼만 더 넘긴다.
 */
class Yes24Search(val query: String) {
    private var nextPage = 1
    private val seen = mutableSetOf<Long>()
    private val gate = Semaphore(Yes24.PARALLEL)

    var exhausted = false
        private set

    suspend fun loadMore(maxEmptyPages: Int = 4): List<Yes24Result> {
        var emptyPages = 0
        while (!exhausted) {
            val (all, matches) = Yes24.searchPage(query, nextPage)
            nextPage++
            val fresh = all.filter { seen.add(it) }
            if (fresh.isEmpty()) {
                exhausted = true
                break
            }
            val found = coroutineScope {
                matches.filter { it.goodsNo in fresh }
                    .map { async { Yes24.verify(it, gate) } }
                    .awaitAll()
                    .filterNotNull()
            }
            if (found.isNotEmpty()) {
                return found.sortedWith(
                    compareByDescending<Yes24Result> { it.candidate.yearMonth }
                        .thenByDescending { it.candidate.goodsNo }
                )
            }
            if (++emptyPages >= maxEmptyPages) break
        }
        return emptyList()
    }
}
