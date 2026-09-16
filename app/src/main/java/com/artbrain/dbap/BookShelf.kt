package com.artbrain.dbap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

data class Book(
    val title: String,
    @DrawableRes val front: Int,
    @DrawableRes val spine: Int,
    /** 실제 판형 높이(mm) — 책끼리의 높이 비율에만 쓴다 */
    val heightMm: Float
)

/** Downloads/Spine-samples (YES24 SIDE/XL 이미지). 높이는 YES24 상품 정보의 판형 */
val SAMPLE_BOOKS = listOf(
    Book("말과 사물", R.drawable.book_6430748_front, R.drawable.book_6430748_spine, 224f),
    Book("지각의 현상학", R.drawable.book_167452496_front, R.drawable.book_167452496_spine, 225f),
    Book("구술문화와 문자문화", R.drawable.book_64438007_front, R.drawable.book_64438007_spine, 224f),
    Book("감각의 논리", R.drawable.book_2856495_front, R.drawable.book_2856495_spine, 224f),
    Book("데미안", R.drawable.book_176787_front, R.drawable.book_176787_spine, 225f),
    Book("채식주의자", R.drawable.book_108422348_front, R.drawable.book_108422348_spine, 194f),
    Book("달러구트 꿈 백화점", R.drawable.book_192468942_front, R.drawable.book_192468942_spine, 200f),
    Book("82년생 김지영", R.drawable.book_32972572_front, R.drawable.book_32972572_spine, 188f),
    Book("미드나잇 라이브러리", R.drawable.book_99534783_front, R.drawable.book_99534783_spine, 205f),
    Book("불편한 편의점", R.drawable.book_99308021_front, R.drawable.book_99308021_spine, 200f),
    Book("살인자의 기억법", R.drawable.book_91901136_front, R.drawable.book_91901136_spine, 198f),
    Book("아몬드", R.drawable.book_119700269_front, R.drawable.book_119700269_spine, 225f),
    Book("이방인", R.drawable.book_4827613_front, R.drawable.book_4827613_spine, 225f),
    Book("일곱 해의 마지막", R.drawable.book_90619474_front, R.drawable.book_90619474_spine, 200f),
    Book("작별하지 않는다", R.drawable.book_103495056_front, R.drawable.book_103495056_spine, 201f),
    Book("파친코 2", R.drawable.book_111680193_front, R.drawable.book_111680193_spine, 205f),
    Book("흰", R.drawable.book_143896198_front, R.drawable.book_143896198_spine, 188f),
)

/** 가장 큰 책의 높이 = 노란 셀 전체 높이 × 이 비율 */
private const val BOOK_HEIGHT_RATIO = 0.6f

/** 책들의 세로 위치 — 셀 높이 중심에서 셀 높이 × 이 비율만큼 아래로 */
private const val BAND_OFFSET_RATIO = 0.05f

/** 펼쳐진 표지의 최대 폭 = 셀 폭 × 이 비율 */
private const val MAX_COVER_WIDTH_RATIO = 0.56f

/** 책 사이 기본 간격(책등끼리) / 표지 양옆에 추가로 생기는 여백 */
private const val SPINE_GAP_DP = 2f
private const val COVER_MARGIN_DP = 28f

/** 표지 그림자 — 최대 진하기(0~255), 기본 오프셋에 더해 아래로 내리는 거리 */
private const val SHADOW_MAX_ALPHA = 28
private const val SHADOW_DROP_DP = 10f

/** 그림자 색 #A26016 */
private const val SHADOW_R = 0xA2
private const val SHADOW_G = 0x60
private const val SHADOW_B = 0x16

/** 책 제목 — 책 아랫선에서의 간격, 글자 크기, 색, 글꼴(Paperlogy Light) */
private const val TITLE_GAP_DP = 10f
private const val TITLE_SIZE_DP = 12f
private val TITLE_COLOR = Color(0xFF5D3306)
private val TITLE_FONT = FontFamily(Font(R.font.paperlogy_light, FontWeight.Light))

/** 원근감 — 카메라 거리 = 책 높이 × 이 값. 작을수록 입체감이 과장된다 */
private const val CAMERA_DISTANCE_RATIO = 2.2f

/** 좌우 끝에서 넘어갈 수 있는 여유 (책 단위) */
private const val OVERSCROLL = 0.35f

/** 플링 속도 → 이동량 환산 계수 (초). 클수록 멀리 날아간다 */
private const val FLING_REACH = 0.28f

private class LoadedBook(val front: Bitmap, val spine: Bitmap, val heightMm: Float) {
    /** 높이 1 기준의 표지 폭·책등 폭(=두께). 원본 이미지 비율 그대로 */
    val coverAspect = front.width / front.height.toFloat()
    val spineAspect = spine.width / spine.height.toFloat()
}

/** 한 프레임에서 책 한 권의 상태 */
private class BookFrame(
    var theta: Float = 0f,      // 0 = 표지 정면, π/2 = 책등 정면
    var openness: Float = 0f,   // 0 = 책등, 1 = 표지
    var slot: Float = 0f,       // 화면상 점유 폭(px)
    var centerX: Float = 0f     // 셀 중앙 기준 x(px)
)

/**
 * 가로로 꽂힌 책장.
 *
 * 스크롤 위치 [position]은 "지금 중앙에 있는 책의 인덱스"를 실수로 표현한 값이다.
 * 각 책은 중앙과의 거리에 따라 0°(표지)~90°(책등)로 회전한 직육면체로 그려지고,
 * 점유 폭도 회전각에 맞춰 W·cosθ + D·sinθ (정사영 폭)로 바뀐다.
 * 그래서 책이 돌면서 옆 책들을 자연스럽게 밀어낸다.
 */
@Composable
fun BookShelf(
    books: List<Book>,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    showTitle: Boolean = true
) {
    val density = LocalDensity.current
    val res = LocalContext.current.resources
    // 이미지 디코딩은 메인 스레드 밖에서 — 권수가 늘면 첫 화면이 그만큼 늦어진다
    val loaded by produceState(emptyList<LoadedBook>(), books) {
        value = withContext(Dispatchers.IO) {
            books.map { book ->
                LoadedBook(
                    BitmapFactory.decodeResource(res, book.front),
                    BitmapFactory.decodeResource(res, book.spine),
                    book.heightMm
                )
            }
        }
    }
    val count = loaded.size

    var position by remember { mutableFloatStateOf((books.size - 1) / 2f) }
    val scope = rememberCoroutineScope()
    var settleJob by remember { mutableStateOf<Job?>(null) }

    val spineGap = with(density) { SPINE_GAP_DP.dp.toPx() }
    val coverMargin = with(density) { COVER_MARGIN_DP.dp.toPx() }
    val shadowDrop = with(density) { SHADOW_DROP_DP.dp.toPx() }

    // 책이 정면(정수 위치)을 지나거나 도착하는 순간 햅틱.
    // 스프링이 목표를 살짝 넘었다 돌아오는 경우 두 번 울리지 않도록,
    // 한 번 울린 책은 반 칸 이상 멀어져야 다시 울릴 수 있다.
    val view = LocalView.current
    LaunchedEffect(count) {
        if (count == 0) return@LaunchedEffect
        var prev = position
        var lastTicked = position.roundToInt()
        snapshotFlow { position }.collect { cur ->
            if (lastTicked >= 0 && abs(cur - lastTicked) > 0.5f) lastTicked = -1
            val k = ceil(minOf(prev, cur)).toInt()
            val crossed = cur != prev && k <= floor(maxOf(prev, cur)).toInt()
            if (crossed && k in 0 until count && k != lastTicked) {
                view.performHapticFeedback(
                    if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK
                    else HapticFeedbackConstants.CLOCK_TICK
                )
                lastTicked = k
            }
            prev = cur
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val cellW = constraints.maxWidth.toFloat()
        val cellH = constraints.maxHeight.toFloat()
        val topInsetPx = with(density) { topInset.toPx() }
        val maxMm = loaded.maxOfOrNull { it.heightMm } ?: 1f
        // 높이 1(가장 큰 책 기준)일 때의 최대 표지 폭
        val maxCoverWidth = loaded.maxOfOrNull { it.coverAspect * it.heightMm / maxMm } ?: 1f
        // 가장 큰 책의 높이(px). 폭과 상태바 아래 공간도 넘지 않게 제한한다.
        val bookH = minOf(
            cellH * BOOK_HEIGHT_RATIO,
            cellW * MAX_COVER_WIDTH_RATIO / maxCoverWidth,
            (cellH - topInsetPx) * 0.92f
        )
        // 가장 큰 책이 차지하는 띠를 셀 중앙에 두되, 상태바와 겹치면 아래로 내린다.
        // 모든 책은 이 띠의 아랫선(baseline)에 맞춰 선다.
        // 기준 위치는 셀 높이 중심에서 셀 높이의 5% 만큼 아래
        val centered = ((cellH - bookH) / 2f + cellH * BAND_OFFSET_RATIO)
            .coerceAtMost(cellH - bookH)
        val bandTop = if (centered >= topInsetPx) centered
        else topInsetPx + (cellH - topInsetPx - bookH) / 2f
        val baseline = bandTop + bookH
        fun heightOf(i: Int) = bookH * loaded[i].heightMm / maxMm

        val frames = remember(count) { List(count) { BookFrame() } }
        fun layout(pos: Float) = layoutShelf(loaded, frames, pos, bookH / maxMm, spineGap, coverMargin)

        // 현재 위치에서 "책 한 칸"이 몇 px인지 — 손가락 이동량을 인덱스로 환산할 때 쓴다
        fun pitchAt(pos: Float): Float {
            if (count < 2) return 1f
            layout(pos)
            val k = floor(pos).toInt().coerceIn(0, count - 2)
            return (frames[k + 1].centerX - frames[k].centerX).coerceAtLeast(1f)
        }

        fun settleTo(target: Float, initialVelocity: Float = 0f) {
            settleJob?.cancel()
            settleJob = scope.launch {
                animate(
                    initialValue = position,
                    targetValue = target,
                    initialVelocity = initialVelocity,
                    animationSpec = spring(
                        dampingRatio = 0.86f,
                        stiffness = Spring.StiffnessLow
                    )
                ) { value, _ -> position = value }
            }
        }

        Canvas(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(count, bookH) {
                    if (count == 0) return@pointerInput
                    val tracker = VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = {
                            settleJob?.cancel()
                            tracker.resetTracking()
                        },
                        onDragEnd = {
                            // px/s → 책/s
                            val v = -tracker.calculateVelocity().x / pitchAt(position)
                            val target = (position + v * FLING_REACH)
                                .roundToInt()
                                .coerceIn(0, count - 1)
                                .toFloat()
                            settleTo(target, v)
                        },
                        onDragCancel = {
                            settleTo(position.roundToInt().coerceIn(0, count - 1).toFloat())
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        val next = position - dragAmount / pitchAt(position)
                        // 양 끝을 넘어가면 저항을 줘서 절반만 따라온다
                        val damped = if (next < 0f || next > count - 1f) {
                            position - dragAmount / pitchAt(position) * 0.5f
                        } else next
                        position = damped.coerceIn(-OVERSCROLL, count - 1 + OVERSCROLL)
                    }
                }
                .pointerInput(count, bookH) {
                    if (count == 0) return@pointerInput
                    // 책을 탭하면 그 책이 중앙으로 온다
                    detectTapGestures { offset ->
                        layout(position)
                        val x = offset.x - size.width / 2f
                        if (offset.y > baseline || offset.y < baseline - bookH) return@detectTapGestures
                        val hit = frames.indices.indexOfFirst {
                            abs(x - frames[it].centerX) <= frames[it].slot / 2f &&
                                offset.y >= baseline - heightOf(it)
                        }
                        if (hit >= 0) settleTo(hit.toFloat())
                    }
                }
        ) {
            if (count == 0) return@Canvas
            layout(position)
            val cx = size.width / 2f
            val camY = baseline - bookH / 2f
            val painter = BookPainter(
                cameraDistance = bookH * CAMERA_DISTANCE_RATIO,
                shadowDropPx = shadowDrop
            )

            // 중앙에서 먼 책부터 그려서, 회전 중인 책이 이웃 위로 올라오게 한다
            val order = frames.indices
                .filter { abs(frames[it].centerX) - frames[it].slot < size.width / 2f + bookH }
                .sortedByDescending { abs(it - position) }

            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                for (i in order) {
                    val f = frames[i]
                    painter.draw(
                        canvas = native,
                        book = loaded[i],
                        centerX = cx + f.centerX,
                        centerY = baseline - heightOf(i) / 2f,
                        // 카메라를 책마다 그 책 정면에 둔다 — 회전이 좌우 대칭으로 보이고,
                        // 옆으로 돈 표지가 원근 때문에 비껴 보이는 일이 없다
                        camX = cx + f.centerX,
                        camY = camY,
                        height = heightOf(i),
                        theta = f.theta,
                        openness = f.openness
                    )
                }
            }
        }

        // 정면을 보는 책의 제목. 책이 돌아가기 시작하면 흐려지고, 반 칸에서 다음 책 제목으로 바뀐다.
        val titleAlpha by animateFloatAsState(if (showTitle) 1f else 0f, label = "titleAlpha")
        val centerIndex by remember(count) {
            derivedStateOf { position.roundToInt().coerceIn(0, (count - 1).coerceAtLeast(0)) }
        }
        if (count > 0 && titleAlpha > 0f) {
            val titleSize = with(density) { TITLE_SIZE_DP.dp.toSp() }
            val titleTop = baseline + with(density) { TITLE_GAP_DP.dp.toPx() }
            Text(
                text = books[centerIndex].title,
                color = TITLE_COLOR,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontSize = titleSize,
                    lineHeight = titleSize,
                    fontFamily = TITLE_FONT,
                    fontWeight = FontWeight.Light,
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, titleTop.roundToInt()) }
                    .graphicsLayer {
                        val d = abs(position - position.roundToInt())
                        alpha = titleAlpha * (1f - 2f * d).coerceIn(0f, 1f)
                    }
                    .padding(horizontal = 24.dp)
            )
        }
    }
}

/** 위치 [pos]에 대한 각 책의 회전각·폭·중심을 [frames]에 채운다 */
private fun layoutShelf(
    books: List<LoadedBook>,
    frames: List<BookFrame>,
    pos: Float,
    pxPerMm: Float,
    spineGap: Float,
    coverMargin: Float
) {
    val n = books.size
    if (n == 0) return

    var x = 0f
    for (i in 0 until n) {
        val f = frames[i]
        val t = (1f - abs(i - pos)).coerceIn(0f, 1f)
        f.openness = t * t * (3f - 2f * t)  // smoothstep
        f.theta = (1f - f.openness) * (Math.PI.toFloat() / 2f)
        val h = books[i].heightMm * pxPerMm
        val w = books[i].coverAspect * h
        val d = books[i].spineAspect * h
        f.slot = w * cos(f.theta) + d * sin(f.theta)

        if (i > 0) {
            val prev = frames[i - 1]
            x += prev.slot / 2f + spineGap + (prev.openness + f.openness) * coverMargin + f.slot / 2f
        }
        f.centerX = x
    }

    // pos 가 가리키는 지점(두 책 중심 사이 보간)을 셀 중앙으로 옮긴다.
    // 끝을 넘어선 오버스크롤 구간은 가장자리 두 책의 간격으로 외삽한다.
    val ref = if (n == 1) {
        frames[0].centerX
    } else {
        val k = floor(pos).toInt().coerceIn(0, n - 2)
        val frac = pos - k
        frames[k].centerX + (frames[k + 1].centerX - frames[k].centerX) * frac
    }
    for (f in frames) f.centerX -= ref
}

/**
 * 책 한 권을 직육면체로 보고 앞표지·책등 두 면을 원근 투영해서 그린다.
 *
 * 좌표계: 화면 평면이 z=0, 카메라는 z=+C (화면 앞쪽).
 * 책의 로컬 좌표에서 앞표지는 z=+D/2, 책등은 x=-W/2 에 있다.
 * y축 기준으로 θ 만큼 돌리면 책등의 바깥 법선(-x)이 θ=90°에서 카메라를 향한다.
 * 정면으로 보이는 면이 항상 z=0 평면에 오도록 책 중심을 뒤로 밀어두므로,
 * 멈춰 있을 때는 이미지가 원근 왜곡 없이 슬롯 크기 그대로 보인다.
 */
private class BookPainter(
    private val cameraDistance: Float,
    /** 그림자를 추가로 아래로 내리는 거리(px) */
    private val shadowDropPx: Float
) {
    private val matrix = Matrix()
    private val src = FloatArray(8)
    private val dst = FloatArray(8)
    private val path = Path()
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK }

    fun draw(
        canvas: android.graphics.Canvas,
        book: LoadedBook,
        centerX: Float,
        centerY: Float,
        camX: Float,
        camY: Float,
        height: Float,
        theta: Float,
        openness: Float
    ) {
        val w = book.coverAspect * height
        val d = book.spineAspect * height
        val c = cos(theta)
        val s = sin(theta)
        val zCenter = -(d / 2f * c + w / 2f * s)
        val top = -height / 2f
        val bottom = height / 2f

        fun project(lx: Float, ly: Float, lz: Float, out: FloatArray, idx: Int) {
            val rx = lx * c + lz * s
            val rz = -lx * s + lz * c
            val worldX = centerX + rx
            val worldY = centerY + ly
            val worldZ = zCenter + rz
            val k = cameraDistance / (cameraDistance - worldZ)
            out[idx] = camX + (worldX - camX) * k
            out[idx + 1] = camY + (worldY - camY) * k
        }

        // 표지 아래 그림자 — 표지가 열릴수록 진해진다
        // 면을 그릴지는 투영된 사각형이 앞면을 보이는지(넓이 부호)로 정한다.
        // cosθ 같은 정사영 기준으로 자르면 원근상 아직 폭이 남은 사다리꼴이 한순간에 사라진다.
        project(-w / 2, top, d / 2, dst, 0)
        project(w / 2, top, d / 2, dst, 2)
        project(w / 2, bottom, d / 2, dst, 4)
        project(-w / 2, bottom, d / 2, dst, 6)
        if (quadArea(dst) > 0f) {
            if (openness > 0.01f) {
                shadowPaint.setShadowLayer(
                    height * 0.06f, 0f, height * 0.025f + shadowDropPx,
                    android.graphics.Color.argb((openness * SHADOW_MAX_ALPHA).toInt(), SHADOW_R, SHADOW_G, SHADOW_B)
                )
                fillQuad(canvas, dst, shadowPaint)
            }
            drawFace(canvas, book.front, c)
        }

        // 책등 — 왼쪽 가장자리가 뒤표지 쪽, 오른쪽이 앞표지 쪽
        project(-w / 2, top, -d / 2, dst, 0)
        project(-w / 2, top, d / 2, dst, 2)
        project(-w / 2, bottom, d / 2, dst, 4)
        project(-w / 2, bottom, -d / 2, dst, 6)
        if (quadArea(dst) > 0f) {
            drawFace(canvas, book.spine, s)
        }
    }

    /** 화면 좌표(y 아래 방향)에서 TL→TR→BR→BL 순서일 때 양수 = 앞면이 보인다 */
    private fun quadArea(p: FloatArray): Float {
        var sum = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            sum += p[i * 2] * p[j * 2 + 1] - p[j * 2] * p[i * 2 + 1]
        }
        return sum / 2f
    }

    private fun drawFace(canvas: android.graphics.Canvas, bitmap: Bitmap, facing: Float) {
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        src[0] = 0f; src[1] = 0f
        src[2] = bw; src[3] = 0f
        src[4] = bw; src[5] = bh
        src[6] = 0f; src[7] = bh
        if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) return
        canvas.drawBitmap(bitmap, matrix, imagePaint)

        // 카메라에서 비껴갈수록 어둡게 — 빛이 정면에서 온다고 가정
        val shade = ((1f - facing) * 0.6f).coerceIn(0f, 1f)
        if (shade > 0.005f) {
            shadePaint.alpha = (shade * 255).toInt()
            fillQuad(canvas, dst, shadePaint)
        }
    }

    private fun fillQuad(canvas: android.graphics.Canvas, pts: FloatArray, paint: Paint) {
        path.rewind()
        path.moveTo(pts[0], pts[1])
        path.lineTo(pts[2], pts[3])
        path.lineTo(pts[4], pts[5])
        path.lineTo(pts[6], pts[7])
        path.close()
        canvas.drawPath(path, paint)
    }
}
