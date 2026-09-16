package com.artbrain.dbap

import android.os.Build
import android.os.Bundle
import android.view.RoundedCorner
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.artbrain.dbap.ui.theme.DbapAmber
import com.artbrain.dbap.ui.theme.DbapDarkOrange
import com.artbrain.dbap.ui.theme._23DBAPTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 드래그 가능 범위 — 이 바깥으로는 아예 움직이지 않는다 */
private const val DRAG_MIN = 0.13f
private const val DRAG_MAX = 0.87f

/**
 * 스냅 규칙 — (손을 뗀 지점이 이 구간 안이면) to (붙을 목표값)
 * 스냅 지점 0.15 / 0.5 / 0.85.
 * 자유 구간은 0.30~0.40, 0.60~0.70 두 군데만 남는다.
 */
private val SNAP_RULES = listOf(
    DRAG_MIN..0.30f to 0.15f,
    0.40f..0.60f to 0.50f,
    0.70f..DRAG_MAX to 0.85f
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            _23DBAPTheme {
                DualPaneScreen()
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
 * 손을 뗀 지점이 0.2 / 0.5 / 0.8 의 ±0.05 안이면 해당 지점으로 스냅된다.
 */
@Composable
fun DualPaneScreen() {
    val density = LocalDensity.current

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
    val scope = rememberCoroutineScope()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(outerMargin)
    ) {
        val usableHeight = maxHeight - gap
        val usablePx = with(density) { usableHeight.toPx() }
        val topHeight = usableHeight * topFraction
        val bottomHeight = usableHeight - topHeight

        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(topHeight)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(DbapAmber)
            )
            Spacer(modifier = Modifier.height(gap))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(bottomHeight)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(DbapDarkOrange)
            ) {
                CornerDot(
                    cornerRadius = cornerRadius,
                    fraction = topFraction
                )
            }
        }

        // 간격 위에 겹쳐두는 투명 드래그 핸들.
        // 시각적 간격은 8dp 그대로 두되, 손가락으로 잡을 영역만 넉넉하게 확보한다.
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

/**
 * 셀 우측 상단의 흰색 원.
 *
 * 모서리 라운딩과 동심원이 되도록 배치한다.
 * 라운딩 반경이 R이면 그 호(arc)의 중심은 모서리에서 안쪽으로 (R, R) 지점이므로,
 * 원의 중심도 같은 지점에 두면 두 곡선이 동심원이 된다.
 */
@Composable
private fun BoxScope.CornerDot(
    cornerRadius: Dp,
    fraction: Float,
    dotDiameter: Dp = 48.dp
) {
    val dotRadius = dotDiameter / 2
    val density = LocalDensity.current

    // 글자 크기 = 원 지름의 1/3.
    // Dp를 Sp로 변환하므로 사용자 글꼴 배율과 무관하게 원 대비 비율이 유지된다.
    val fontSize = with(density) { (dotDiameter / 3).toSp() }

    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            // TopEnd 기준으로 원의 중심을 (R, R) 지점까지 밀어 넣는다
            .offset(
                x = -(cornerRadius - dotRadius),
                y = cornerRadius - dotRadius
            )
            .size(dotDiameter)
            .shadow(elevation = 6.dp, shape = CircleShape)
            .background(Color.White, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = (fraction * 100).roundToInt().toString().padStart(2, '0'),
            color = Color.Black,
            textAlign = TextAlign.Center,
            style = TextStyle(
                fontSize = fontSize,
                fontWeight = FontWeight.Light,
                lineHeight = fontSize,
                // 기본 폰트 여백을 끄지 않으면 글자가 살짝 아래로 치우친다
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            )
        )
    }
}

@Preview(showBackground = true, device = "spec:width=1084px,height=2412px,dpi=395")
@Composable
fun DualPaneScreenPreview() {
    _23DBAPTheme {
        DualPaneScreen()
    }
}
