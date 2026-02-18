// composeApp/src/jvmMain/kotlin/org/example/project/Components.kt
package org.example.project

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ... imports ...

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TooltipIconButton(
    tooltip: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit // これにより { Icon(...) } が書けるようになります
) {
    if (enabled) {
        TooltipArea(
            tooltip = {
                Surface(
                    modifier = Modifier.shadow(4.dp),
                    color = Color(0xFF2B2D30),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, Color(0xFF56585A))
                ) {
                    Text(
                        text = tooltip,
                        color = Color(0xFFE0E0E0),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            },
            delayMillis = 500,
            tooltipPlacement = TooltipPlacement.CursorPoint(
                alignment = Alignment.BottomEnd,
                offset = DpOffset(0.dp, 16.dp)
            )
        ) {
            IconButton(onClick = onClick, enabled = enabled) {
                content()
            }
        }
    } else {
        IconButton(onClick = onClick, enabled = enabled) {
            content()
        }
    }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TooltipIconButton(
    painter: androidx.compose.ui.graphics.painter.Painter, // Vectorの代わりにPainterを受け取る
    tooltip: String,
    tint: Color = Color.Unspecified, // SVGの色を生かすならUnspecified
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    if (enabled) {
        TooltipArea(
            tooltip = {
                Surface(
                    modifier = Modifier.shadow(4.dp),
                    color = Color(0xFF2B2D30),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, Color(0xFF56585A))
                ) {
                    Text(
                        text = tooltip,
                        color = Color(0xFFE0E0E0),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            },
            delayMillis = 500,
            tooltipPlacement = TooltipPlacement.CursorPoint(
                alignment = Alignment.BottomEnd,
                offset = DpOffset(0.dp, 16.dp)
            )
        ) {
            // ツールチップ対象のボタン
            IconButton(onClick = onClick, enabled = enabled) {
                Icon(
                    painter = painter,
                    contentDescription = tooltip,
                    tint = tint,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    } else {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(painter = painter, contentDescription = tooltip, tint = tint, modifier = Modifier.size(24.dp))
        }
    }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TooltipIconButton(
    icon: ImageVector,
    tooltip: String,
    tint: Color = Color.Gray,
    enabled: Boolean = true, // ★追加: 無効化制御用
    onClick: () -> Unit
) {
    // ボタンが無効なときはツールチップを出さない、あるいは "Disabled" と出すなどの制御も可能ですが
    // ここではシンプルに「有効なときだけツールチップを出す」ようにします。
    if (enabled) {
        TooltipArea(
            tooltip = {
                Surface(
                    modifier = Modifier.shadow(4.dp),
                    color = Color(0xFF2B2D30),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, Color(0xFF56585A))
                ) {
                    Text(
                        text = tooltip,
                        color = Color(0xFFE0E0E0),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            },
            delayMillis = 500,
            tooltipPlacement = TooltipPlacement.CursorPoint(
                alignment = Alignment.BottomEnd,
                offset = DpOffset(0.dp, 16.dp)
            )
        ) {
            // ツールチップ対象のボタン
            IconButton(onClick = onClick, enabled = enabled) {
                Icon(
                    imageVector = icon,
                    contentDescription = tooltip,
                    tint = tint, // 無効時の色はIconButtonが自動でalphaをかけてくれますが、tintで明示も可
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    } else {
        // 無効時はツールチップなしのただのボタンとして描画
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(
                imageVector = icon,
                contentDescription = tooltip,
                tint = tint,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}