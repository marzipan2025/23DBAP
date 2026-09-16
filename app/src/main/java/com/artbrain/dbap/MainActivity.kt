package com.artbrain.dbap

import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.view.RoundedCorner
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.max
import com.artbrain.dbap.data.LibraryRepository
import com.artbrain.dbap.ui.theme.DbapAmber
import com.artbrain.dbap.ui.theme.DbapDarkOrange
import com.artbrain.dbap.ui.theme._23BKSFTheme
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 드래그 가능 범위 — 이 바깥으로는 아예 움직이지 않는다.
 *  최소 스냅(0.24)보다 살짝 더 당길 수 있게 여유를 둔다. */
private const val DRAG_MIN = 0.22f
private const val DRAG_MAX = 0.87f

/** 위 셀이 이 비율 이상일 때 책 제목을 보여준다 */
private const val TITLE_MIN_FRACTION = 0.40f

/**
 * 스냅 규칙 — (손을 뗀 지점이 이 구간 안이면) to (붙을 목표값)
 * 스냅 지점 0.24(위 셀 최소 높이) / 0.5 / 0.85.
 * 자유 구간은 0.33~0.40, 0.60~0.70 두 군데만 남는다.
 */
private val SNAP_RULES = listOf(
    DRAG_MIN..0.33f to 0.24f,
    0.40f..0.60f to 0.50f,
    0.70f..DRAG_MAX to 0.85f
)

/** 흰 원 버튼 지름 (처음 48dp의 60%) / 손가락이 닿는 영역 */
private val DOT_DIAMETER = 28.8.dp
private val DOT_TOUCH_SIZE = 48.dp

/** 흰 원과 셀 가장자리 사이 여백의 최소값 */
private val DOT_EDGE_MIN_INSET = 8.dp

/** 흰 원이 48dp였을 때의 반지름 — 이때의 원-가장자리 여백을 입력창에서도 유지한다 */
private val ORIGINAL_DOT_RADIUS = 24.dp

/** 입력창 글자 크기 */
private const val INPUT_TEXT_SIZE_DP = 18f

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 디버그 빌드 전용: adb 로는 한글을 칠 수 없어서 검색어를 인텐트로 넣어 시험한다
        //   adb shell am start -n com.artbrain.dbap/.MainActivity --es debug_query "구토"
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val debugQuery = intent.getStringExtra("debug_query")?.takeIf { debuggable }
        setContent {
            _23BKSFTheme {
                DualPaneScreen(debugQuery = debugQuery)
            }
        }
    }
}

/**
 * 기기 화면의 실제 라운딩 반경을 읽어온다.
 * Android 12(API 31)부터 WindowInsets.getRoundedCorner()로 제공됨.
 * 못 읽으면 fallback 값 사용.
 */
@Composable
private fun rememberScreenCornerRadius(fallback: Dp = 32.dp): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    return remember(view) {
        val px: Int? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.rootWindowInsets
                ?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
                ?.radius
        } else {
            null
        }
        if (px != null && px > 0) with(density) { px.toDp() } else fallback
    }
}

/**
 * 위아래로 배치된 두 개의 라운딩 셀.
 * 사이의 간격을 잡고 드래그하면 두 셀의 높이 비율이 바뀐다.
 * 손을 뗀 지점에 따라 0.24 / 0.5 / 0.85 로 스냅된다 ([SNAP_RULES]).
 *
 * 아래 셀의 + 버튼을 누르면 아래 셀이 키패드 바로 위의 입력창으로 줄어들고,
 * 위 셀에는 검색 결과 표지가 나열된다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DualPaneScreen(debugQuery: String? = null) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember {
        BookshelfController(LibraryRepository(context.applicationContext), scope)
    }
    LaunchedEffect(Unit) {
        controller.load()
        if (debugQuery != null) {
            controller.enterInput()
            controller.query = debugQuery
            controller.submit()
        }
    }

    // 바깥쪽 검은 여백
    val outerMargin: Dp = 4.dp

    // 셀 사이 간격
    val gap: Dp = 8.dp

    // 라운딩 = 기기 화면 라운딩값 - 바깥 여백.
    // 안쪽으로 4dp 들어갔으므로 반경도 4dp 줄여야 화면 곡률과 동심원이 된다.
    val cornerRadius = (rememberScreenCornerRadius() - outerMargin)
        .coerceAtLeast(0.dp)

    // 위쪽 셀이 차지하는 비율 (0.5 = 반반).
    // 드래그 중에는 코루틴 없이 즉시 갱신한다 — Animatable.snapTo()는 suspend 함수라
    // 터치 이벤트마다 코루틴을 띄우게 되고, 그만큼 손가락보다 한 프레임씩 밀린다.
    var topFraction by remember { mutableFloatStateOf(0.5f) }

    // 0 = 책장, 1 = 입력 모드. 셀 높이를 이 값으로 보간한다.
    val inputProgress by animateFloatAsState(
        targetValue = if (controller.inputMode) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "inputProgress"
    )

    // 입력 모드에 들어가면 키패드를 띄우고, 나오면 내린다
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(controller.inputMode) {
        if (controller.inputMode) {
            awaitFrame()
            focusRequester.requestFocus()
            keyboard?.show()
        } else {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }
    BackHandler(enabled = controller.inputMode) { controller.exitInput() }

    // 키패드를 내리면(뒤로가기·키패드의 내리기 버튼) 입력을 취소한다.
    // 입력 모드에 들어온 직후에는 키패드가 아직 안 올라왔으므로, 한 번 보인 뒤부터 본다.
    val imeVisible = WindowInsets.isImeVisible
    var imeShownInInput by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible, controller.inputMode) {
        when {
            !controller.inputMode -> imeShownInInput = false
            imeVisible -> imeShownInInput = true
            imeShownInInput -> controller.exitInput()
        }
    }

    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // 키패드가 올라오면 그만큼 전체 높이가 줄어든다
            .imePadding()
            .padding(outerMargin)
    ) {
        val usableHeight = maxHeight - gap
        val usablePx = with(density) { usableHeight.toPx() }
        // 입력창 = 흰 원 + 위아래 여백. 여백은 원이 48dp였을 때의 원-가장자리 거리를 유지한다.
        // 입력창은 높이의 절반만큼 둥근 알약 모양이 되고, 원은 그 끝 곡선과 동심원이 된다.
        val dotEdgeInset = max(cornerRadius - ORIGINAL_DOT_RADIUS, DOT_EDGE_MIN_INSET)
        val inputCellHeight = max(DOT_DIAMETER + dotEdgeInset * 2, DOT_TOUCH_SIZE)
        // 원 중심이 셀 오른쪽 위 모서리에서 떨어진 거리 — 책장 모드는 라운딩 중심(R, R)
        val dotCenterInset = lerp(cornerRadius, inputCellHeight / 2, inputProgress)
        val topHeight = lerp(
            usableHeight * topFraction,
            (usableHeight - inputCellHeight).coerceAtLeast(0.dp),
            inputProgress
        )
        val bottomHeight = usableHeight - topHeight
        val showTitle = usableHeight > 0.dp && topHeight / usableHeight >= TITLE_MIN_FRACTION

        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(topHeight)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(DbapAmber)
            ) {
                val session = controller.search
                if (session == null) {
                    BookShelf(
                        items = controller.shelfItems,
                        state = controller.shelfState,
                        modifier = Modifier.fillMaxSize(),
                        // 셀이 작아졌을 때 책이 상태바와 겹치지 않게 한다
                        topInset = topInset,
                        showTitle = showTitle,
                        onEmptyTap = if (controller.inputMode) controller::exitInput else null
                    )
                } else {
                    key(session) {
                        SearchResultsPane(
                            session = session,
                            controller = controller,
                            topInset = topInset,
                            showTitle = showTitle
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(gap))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(bottomHeight)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(DbapDarkOrange)
            ) {
                if (controller.inputMode || inputProgress > 0f) {
                    TitleInput(
                        value = controller.query,
                        onValueChange = { controller.query = it },
                        onSubmit = controller::submit,
                        focusRequester = focusRequester,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxWidth()
                            .padding(
                                start = max(dotCenterInset, 24.dp),
                                end = dotCenterInset + DOT_DIAMETER / 2 + 12.dp
                            )
                            .graphicsLayer { alpha = inputProgress }
                    )
                }
                ActionDot(
                    centerInset = dotCenterInset,
                    arrow = controller.inputMode,
                    onClick = {
                        if (controller.inputMode) controller.submit() else controller.enterInput()
                    }
                )
            }
        }

        // 간격 위에 겹쳐두는 투명 드래그 핸들. 입력 모드에서는 쓰지 않는다.
        // 시각적 간격은 8dp 그대로 두되, 손가락으로 잡을 영역만 넉넉하게 확보한다.
        if (!controller.inputMode) {
            val handleHeight = 48.dp
            val handlePx = with(density) { handleHeight.toPx() }
            val gapPx = with(density) { gap.toPx() }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(handleHeight)
                    // 위치 계산을 레이아웃 단계로 미룬다.
                    // offset(y: Dp)는 값이 바뀔 때마다 리컴포지션을 유발하지만,
                    // offset { } 람다는 레이아웃에서만 다시 읽으므로 훨씬 가볍다.
                    .offset {
                        IntOffset(
                            x = 0,
                            y = (usablePx * topFraction + gapPx / 2f - handlePx / 2f).roundToInt()
                        )
                    }
                    .pointerInput(usablePx) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                // 손을 뗀 지점이 스냅 구간 안이면 해당 목표값으로 붙는다
                                val target = SNAP_RULES
                                    .firstOrNull { (range, _) -> topFraction in range }
                                    ?.second
                                if (target != null) {
                                    scope.launch {
                                        animate(
                                            initialValue = topFraction,
                                            targetValue = target,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = Spring.StiffnessMedium
                                            )
                                        ) { value, _ -> topFraction = value }
                                    }
                                }
                            }
                        ) { change, dragAmount ->
                            change.consume()
                            if (usablePx > 0f) {
                                // 드래그 범위 제한. 코루틴 없이 그 자리에서 바로 반영된다.
                                topFraction = (topFraction + dragAmount / usablePx)
                                    .coerceIn(DRAG_MIN, DRAG_MAX)
                            }
                        }
                    }
            )
        }
    }
}

/** 검색 결과 — 표지를 평면으로 나열하고, 롱탭으로 등록한다 */
@Composable
private fun SearchResultsPane(
    session: SearchSession,
    controller: BookshelfController,
    topInset: Dp,
    showTitle: Boolean
) {
    val items = session.items
    Box(modifier = Modifier.fillMaxSize()) {
        if (items.isNotEmpty()) {
            BookShelf(
                items = items,
                state = session.shelfState,
                modifier = Modifier.fillMaxSize(),
                flat = true,
                topInset = topInset,
                showTitle = showTitle,
                onEmptyTap = controller::exitInput,
                onLongPress = controller::register,
                onNearEnd = controller::onNearEnd
            )
        } else {
            // 결과가 없는 동안에는 어디를 탭해도 취소
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { controller.exitInput() } }
            )
        }

        val message = when (session.status) {
            SearchStatus.Loading -> "찾는 중…"
            SearchStatus.Empty -> "표지와 책등이 모두 있는 책을 찾지 못했어요"
            SearchStatus.Registering -> "책장에 꽂는 중…"
            SearchStatus.Error, SearchStatus.Ready -> null
        } ?: session.notice

        if (message != null) {
            val size = with(LocalDensity.current) { 14.dp.toSp() }
            val inList = items.isNotEmpty()
            Text(
                text = message,
                color = TITLE_COLOR,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    fontFamily = TITLE_FONT,
                    fontSize = size,
                    lineHeight = size,
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                ),
                modifier = Modifier
                    .align(if (inList) Alignment.TopCenter else Alignment.Center)
                    .padding(top = if (inList) topInset + 12.dp else 0.dp)
                    .padding(horizontal = 24.dp)
            )
        }
    }
}

/** 아래 셀이 입력창일 때의 제목 입력 */
@Composable
private fun TitleInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val size = with(LocalDensity.current) { INPUT_TEXT_SIZE_DP.dp.toSp() }
    val style = TextStyle(
        color = Color.White,
        fontFamily = TITLE_FONT,
        fontSize = size,
        lineHeight = size,
        platformStyle = PlatformTextStyle(includeFontPadding = false)
    )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(Color.White),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }, onDone = { onSubmit() }),
        modifier = modifier.focusRequester(focusRequester),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text("책 제목", style = style.copy(color = Color.White.copy(alpha = 0.45f)))
                }
                inner()
            }
        }
    )
}

/**
 * 셀 우측 상단의 흰색 원 버튼. 평소에는 +, 입력 모드에서는 → (엔터 역할).
 *
 * 모서리 라운딩과 동심원이 되도록 배치한다.
 * 라운딩 반경이 R이면 그 호(arc)의 중심은 모서리에서 안쪽으로 (R, R) 지점이므로,
 * 원의 중심을 [centerInset] = R 에 두면 두 곡선이 동심원이 된다.
 * 원이 작아도 누르기 쉽도록 [touchSize] 만큼의 투명한 터치 영역을 둔다.
 */
@Composable
private fun BoxScope.ActionDot(
    centerInset: Dp,
    arrow: Boolean,
    onClick: () -> Unit,
    dotDiameter: Dp = DOT_DIAMETER,
    touchSize: Dp = DOT_TOUCH_SIZE
) {
    val touchRadius = touchSize / 2
    // + 의 세로줄 위·아래 절반이 → 의 화살촉 두 줄로 접히는 모핑
    val morph by animateFloatAsState(
        targetValue = if (arrow) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "plusToArrow"
    )

    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            // TopEnd 기준으로 원의 중심을 (inset, inset) 지점까지 밀어 넣는다
            .offset(
                x = -(centerInset - touchRadius),
                y = centerInset - touchRadius
            )
            .size(touchSize)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(dotDiameter)
                .shadow(elevation = 4.dp, shape = CircleShape)
                .background(Color.White, CircleShape)
        )
        // 아이콘 크기 = 원 지름의 1/3
        Canvas(modifier = Modifier.size(dotDiameter / 3)) {
            val a = size.minDimension / 2f
            val h = a * 0.6f
            val c = center
            val stroke = 0.8.dp.toPx()
            fun line(from: Offset, to: Offset) = drawLine(
                color = Color.Black,
                start = c + from,
                end = c + to,
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            // 가로줄은 그대로
            line(Offset(-a, 0f), Offset(a, 0f))
            // 세로줄 위쪽 절반 → 화살촉 위쪽
            line(
                lerp(Offset(0f, -a), Offset(a - h, -h), morph),
                lerp(Offset(0f, 0f), Offset(a, 0f), morph)
            )
            // 세로줄 아래쪽 절반 → 화살촉 아래쪽
            line(
                lerp(Offset(0f, a), Offset(a - h, h), morph),
                lerp(Offset(0f, 0f), Offset(a, 0f), morph)
            )
        }
    }
}

@Preview(showBackground = true, device = "spec:width=1084px,height=2412px,dpi=395")
@Composable
fun DualPaneScreenPreview() {
    _23BKSFTheme {
        DualPaneScreen()
    }
}
