package com.artbrain.dbap

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.artbrain.dbap.data.LibraryBook
import com.artbrain.dbap.data.LibraryRepository
import com.artbrain.dbap.data.Yes24
import com.artbrain.dbap.data.Yes24Exception
import com.artbrain.dbap.data.Yes24Result
import com.artbrain.dbap.data.Yes24Search
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SearchStatus { Loading, Ready, Empty, Error, Registering }

/** 한 번의 검색과 그 결과 화면 상태 */
class SearchSession(val query: String) {
    internal val search = Yes24Search(query)
    val results = mutableStateListOf<Yes24Result>()
    val items by derivedStateOf {
        results.map {
            ShelfItem(
                key = it.candidate.goodsNo,
                title = it.candidate.title,
                front = it.frontBitmap,
                spine = null,
                heightMm = 1f
            )
        }
    }
    val shelfState = ShelfState(0f)
    var status by mutableStateOf(SearchStatus.Loading)
        internal set
    /** 잠깐 띄웠다 지우는 안내 문구 */
    var notice by mutableStateOf<String?>(null)
        internal set
    internal var job: Job? = null
    internal var loadingMore = false
}

/**
 * 책장 + 책 추가 흐름.
 *  + 버튼 → [enterInput] → 제목 입력 → [submit] → 표지 목록 → 롱탭 [register] → 맨 끝에 추가
 */
class BookshelfController(
    private val repo: LibraryRepository,
    private val scope: CoroutineScope
) {
    var shelfItems by mutableStateOf<List<ShelfItem>>(emptyList())
        private set
    val shelfState = ShelfState()

    var inputMode by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
    var search by mutableStateOf<SearchSession?>(null)
        private set

    private val itemCache = HashMap<Long, ShelfItem>()
    private var loaded = false

    suspend fun load() {
        if (loaded) return
        val books = repo.load()
        applyLibrary(books)
        shelfState.position = ((books.size - 1) / 2).toFloat().coerceAtLeast(0f)
        loaded = true
    }

    fun enterInput() {
        inputMode = true
    }

    fun exitInput() {
        search?.job?.cancel()
        search = null
        query = ""
        inputMode = false
    }

    fun submit() {
        val q = query.trim()
        if (q.isEmpty()) return
        search?.job?.cancel()
        val session = SearchSession(q)
        search = session
        session.job = scope.launch { loadMore(session) }
    }

    fun onNearEnd() {
        val s = search ?: return
        if (s.loadingMore || s.search.exhausted || s.status != SearchStatus.Ready) return
        s.loadingMore = true
        s.job = scope.launch {
            try {
                loadMore(s)
            } finally {
                s.loadingMore = false
            }
        }
    }

    /** 롱탭한 표지를 등록한다. 받아들였으면 true */
    fun register(index: Int): Boolean {
        val s = search ?: return false
        if (s.status != SearchStatus.Ready) return false
        val result = s.results.getOrNull(index) ?: return false
        val goodsNo = result.candidate.goodsNo

        val existing = shelfItems.indexOfFirst { it.key == goodsNo }
        if (existing >= 0) {
            exitInput()
            shelfState.scrollTo(existing)
            return true
        }

        s.job?.cancel()
        s.status = SearchStatus.Registering
        scope.launch {
            try {
                val (spine, heightMm) = Yes24.fetchForRegister(result)
                val c = result.candidate
                val books = repo.add(
                    LibraryBook(
                        goodsNo = goodsNo,
                        title = c.title,
                        author = c.author,
                        publisher = c.publisher,
                        yearMonth = c.yearMonth,
                        heightMm = heightMm
                    ),
                    front = result.frontBytes,
                    spine = spine
                )
                applyLibrary(books)
                exitInput()
                shelfState.scrollTo(books.lastIndex)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                s.status = SearchStatus.Ready
                showNotice(s, messageOf(e))
            }
        }
        return true
    }

    private suspend fun loadMore(s: SearchSession) {
        try {
            val found = s.search.loadMore()
            s.results += found
            s.status = if (s.results.isEmpty()) SearchStatus.Empty else SearchStatus.Ready
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (s.results.isEmpty()) {
                s.notice = messageOf(e)
                s.status = SearchStatus.Error
            } else {
                s.status = SearchStatus.Ready
                showNotice(s, messageOf(e))
            }
        }
    }

    private fun showNotice(s: SearchSession, text: String) {
        s.notice = text
        scope.launch {
            delay(2500)
            if (s.notice == text) s.notice = null
        }
    }

    private fun messageOf(e: Exception): String {
        Log.w(TAG, "search/register failed", e)
        return (e as? Yes24Exception)?.message ?: "네트워크 연결을 확인해 주세요"
    }

    private suspend fun applyLibrary(books: List<LibraryBook>) {
        shelfItems = withContext(Dispatchers.IO) {
            books.mapNotNull { b ->
                itemCache[b.goodsNo] ?: run {
                    val front = BitmapFactory.decodeFile(repo.frontFile(b.goodsNo).path)
                    val spine = BitmapFactory.decodeFile(repo.spineFile(b.goodsNo).path)
                    if (front == null || spine == null) null
                    else ShelfItem(b.goodsNo, b.title, front, spine, b.heightMm)
                        .also { itemCache[b.goodsNo] = it }
                }
            }
        }
    }

    private companion object {
        const val TAG = "Bookshelf"
    }
}
