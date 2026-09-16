package com.artbrain.dbap.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 내 책장에 꽂힌 책 한 권 */
data class LibraryBook(
    val goodsNo: Long,
    val title: String,
    val author: String,
    val publisher: String,
    /** 출간 연월 "yyyyMM" */
    val yearMonth: String,
    /** 실제 판형 높이(mm) — 책끼리의 높이 비율에 쓴다 */
    val heightMm: Float,
    /** 책 메모 — UI는 아직 없음 */
    val memo: String = "",
    val addedAt: Long = 0L
)

/**
 * 책 목록과 표지·책등 원본 이미지를 앱 내부 저장소에 보관한다.
 *
 * 구조 — 백업은 이 폴더를 통째로 묶으면 된다:
 *   files/library/books.json
 *   files/library/images/{goodsNo}_front.jpg   (YES24 XL 원본 그대로)
 *   files/library/images/{goodsNo}_spine.jpg   (YES24 SIDE/XL 원본 그대로)
 *
 * books.json 이 없으면 assets/seed 의 기본 책들로 채운다.
 */
class LibraryRepository(context: Context) {
    private val assets = context.assets
    val rootDir = File(context.filesDir, "library")
    private val imagesDir = File(rootDir, "images")
    private val indexFile = File(rootDir, "books.json")
    private val mutex = Mutex()

    fun frontFile(goodsNo: Long) = File(imagesDir, "${goodsNo}_front.jpg")
    fun spineFile(goodsNo: Long) = File(imagesDir, "${goodsNo}_spine.jpg")

    suspend fun load(): List<LibraryBook> = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!indexFile.exists()) seed()
            readIndex()
        }
    }

    /** 맨 끝에 책을 추가하고 새 목록을 돌려준다. 이미 있으면 그대로 돌려준다. */
    suspend fun add(book: LibraryBook, front: ByteArray, spine: ByteArray): List<LibraryBook> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val books = readIndex()
                if (books.any { it.goodsNo == book.goodsNo }) return@withContext books
                imagesDir.mkdirs()
                writeAtomically(frontFile(book.goodsNo), front)
                writeAtomically(spineFile(book.goodsNo), spine)
                val next = books + book.copy(addedAt = System.currentTimeMillis())
                writeIndex(next)
                next
            }
        }

    private fun seed() {
        imagesDir.mkdirs()
        val seed = JSONObject(assets.open("seed/seed.json").bufferedReader().use { it.readText() })
        val arr = seed.getJSONArray("books")
        val books = (0 until arr.length()).map { i -> fromJson(arr.getJSONObject(i)) }
        for (b in books) {
            assets.open("seed/${b.goodsNo}_front.jpg").use { input ->
                writeAtomically(frontFile(b.goodsNo), input.readBytes())
            }
            assets.open("seed/${b.goodsNo}_spine.jpg").use { input ->
                writeAtomically(spineFile(b.goodsNo), input.readBytes())
            }
        }
        writeIndex(books)
    }

    private fun readIndex(): List<LibraryBook> {
        val arr = JSONObject(indexFile.readText()).getJSONArray("books")
        return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }

    private fun writeIndex(books: List<LibraryBook>) {
        rootDir.mkdirs()
        val json = JSONObject()
            .put("version", INDEX_VERSION)
            .put("books", JSONArray(books.map { toJson(it) }))
        writeAtomically(indexFile, json.toString(2).toByteArray())
    }

    /** 쓰는 도중 앱이 죽어도 기존 파일이 깨지지 않도록 임시 파일에 쓴 뒤 바꿔치기 */
    private fun writeAtomically(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun toJson(b: LibraryBook) = JSONObject()
        .put("goodsNo", b.goodsNo)
        .put("title", b.title)
        .put("author", b.author)
        .put("publisher", b.publisher)
        .put("yearMonth", b.yearMonth)
        .put("heightMm", b.heightMm.toDouble())
        .put("memo", b.memo)
        .put("addedAt", b.addedAt)

    private fun fromJson(o: JSONObject) = LibraryBook(
        goodsNo = o.getLong("goodsNo"),
        title = o.getString("title"),
        author = o.optString("author"),
        publisher = o.optString("publisher"),
        yearMonth = o.optString("yearMonth"),
        heightMm = o.getDouble("heightMm").toFloat(),
        memo = o.optString("memo"),
        addedAt = o.optLong("addedAt")
    )

    private companion object {
        const val INDEX_VERSION = 1
    }
}
