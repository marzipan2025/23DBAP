package com.artbrain.dbap

import android.os.Build
import android.os.Bundle
import android.view.RoundedCorner
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.artbrain.dbap.ui.theme.DbapAmber
import com.artbrain.dbap.ui.theme.DbapDarkOrange
import com.artbrain.dbap.ui.theme._23DBAPTheme
import kotlinx.coroutines.launch

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

    // 위쪽 셀이 차지하는 비율 (0.5 = 반반). 스냅 애니메이션 때문에 Animatable 사용.
    val topFraction = remember { Animatable(0.5f) }
    val scope = rememberCoroutineScope()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(outerMargin)
    ) {
        val usableHeight = maxHeight - gap
        val topHeight = usableHeight * topFraction.value
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
            )
        }

        // 간격 위에 겹쳐두는 투명 드래그 핸들.
        // 시각적 간격은 8dp 그대로 두되, 손가락으로 잡을 영역만 넉넉하게 확보한다.
        val handleHeight = 48.dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(handleHeight)
                .offset(y = topHeight + gap / 2 - handleHeight / 2)
                .pointerInput(usableHeight) {
                    val usablePx = with(density) { usableHeight.toPx() }
                    detectVerticalDragGestures(
                        onDragEnd = {
                            // 손을 뗀 지점이 스냅 구간 안이면 해당 목표값으로 붙는다
                            val target = SNAP_RULES
                                .firstOrNull { (range, _) -> topFraction.value in range }
                                ?.second
                            if (target != null) {
                                scope.launch {
                                    topFraction.animateTo(
                                        targetValue = target,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioLowBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                }
                            }
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        if (usablePx > 0f) {
                            scope.launch {
                                // 드래그 가능 범위를 0.15~0.85로 제한.
                                // 그 바깥으로는 손가락을 움직여도 셀이 따라가지 않는다.
                                topFraction.snapTo(
                                    (topFraction.value + dragAmount / usablePx)
                                        .coerceIn(DRAG_MIN, DRAG_MAX)
                                )
                            }
                        }
                    }
                }
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
