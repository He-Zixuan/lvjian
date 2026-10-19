// 细线图标集：把设计稿 preview.js 里的 stroke SVG 逐条移植为 Compose ImageVector
// 统一规范：24x24 viewport、stroke 1.65、圆头端点；颜色由使用处 tint 控制
package com.lvjian.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** 圆（cx,cy,r）→ path 数据（两段弧线） */
private fun circlePath(cx: Float, cy: Float, r: Float): String =
    "M${cx - r},$cy a$r,$r 0 1,0 ${2 * r},0 a$r,$r 0 1,0 ${-2 * r},0"

/** 圆角矩形（x,y,w,h,rx）→ path 数据 */
private fun rectPath(x: Float, y: Float, w: Float, h: Float, rx: Float): String =
    "M${x + rx},$y h${w - 2 * rx} a$rx,$rx 0 0 1 $rx,$rx v${h - 2 * rx} a$rx,$rx 0 0 1 -$rx,$rx h${-(w - 2 * rx)} a$rx,$rx 0 0 1 -$rx,-$rx v${-(h - 2 * rx)} a$rx,$rx 0 0 1 $rx,-$rx z"

/** 构造一个细线图标：可含多条 path（全部 stroke 风格） */
private fun strokeIcon(vararg paths: String): ImageVector {
    val builder = ImageVector.Builder(
        name = "lv",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    )
    for (d in paths) {
        builder.addPath(
            pathData = PathParser().parsePathString(d).toNodes(),
            stroke = SolidColor(Color.Black), // 占位黑，实际显示由 Icon(tint) 统一染色
            strokeLineWidth = 1.65f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        )
    }
    return builder.build()
}

object LvIcons {
    val List = strokeIcon("M8 6h13M8 12h13M8 18h13M3.5 6h.01M3.5 12h.01M3.5 18h.01")
    val Filter = strokeIcon("M4 5h16l-6.5 7.5v5.5l-3 2v-7.5L4 5z")
    val Grid = strokeIcon(
        rectPath(4f, 4f, 7f, 7f, 2f), rectPath(13f, 4f, 7f, 7f, 2f),
        rectPath(4f, 13f, 7f, 7f, 2f), rectPath(13f, 13f, 7f, 7f, 2f)
    )
    val Cal = strokeIcon(rectPath(4f, 5f, 16f, 15f, 3f), "M4 10h16M8.5 3v4M15.5 3v4")
    val Sparkle = strokeIcon("M12 3.5c.8 4.3 3.7 7.2 8 8-4.3.8-7.2 3.7-8 8-.8-4.3-3.7-7.2-8-8 4.3-.8 7.2-3.7 8-8z")
    val Gear = strokeIcon(
        circlePath(12f, 12f, 3.2f),
        "M19 12a7 7 0 0 0-.15-1.4l2-1.5-2-3.4-2.3.9a7 7 0 0 0-2.4-1.4L13.7 2h-3.4l-.45 2.6a7 7 0 0 0-2.4 1.4l-2.3-.9-2 3.4 2 1.5a7 7 0 0 0 0 2.8l-2 1.5 2 3.4 2.3-.9a7 7 0 0 0 2.4 1.4l.45 2.6h3.4l.45-2.6a7 7 0 0 0 2.4-1.4l2.3.9 2-3.4-2-1.5c.1-.45.15-.92.15-1.4z"
    )
    val ChevronDown = strokeIcon("M6 9.5l6 6 6-6")
    val Check = strokeIcon("M4.5 12.5l5 5 10-11")
    val Trash = strokeIcon("M4 7h16M9 7V4.5h6V7m-8.5 0l1 13h9l1-13")
    // 无盖垃圾桶：已划去入口专用（与有盖的 Trash「删除」区分——无盖=扔进去还能捡回来）；字形垂直居中，与旁边齿轮中线对齐
    val BinOpen = strokeIcon("M4.5 6h15", "M6.3 6l1.1 11.2a1.5 1.5 0 0 0 1.5 1.3h6.2a1.5 1.5 0 0 0 1.5-1.3L17.7 6", "M10 9.5v6M14 9.5v6")
    val Back = strokeIcon("M15 5l-7 7 7 7")
    val Right = strokeIcon("M9 5l7 7-7 7")
    val Link = strokeIcon("M10 14a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-1.5 1.5M14 10a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l1.5-1.5")
    val Search = strokeIcon(circlePath(11f, 11f, 7f), "M20.5 20.5L16 16")
    val Close = strokeIcon("M6 6l12 12M18 6L6 18")
    val Plus = strokeIcon("M12 5.5v13M5.5 12h13")
    val Minus = strokeIcon("M5.5 12h13")
    val Share = strokeIcon(
        circlePath(6f, 12f, 2.5f), circlePath(17f, 6f, 2.5f), circlePath(17f, 18f, 2.5f),
        "M8.2 10.8l6.6-3.6M8.2 13.2l6.6 3.6"
    )
    val Download = strokeIcon("M12 5v11m0 0l-4-4m4 4l4-4M5 19h14")
    val External = strokeIcon("M14 4h6v6M20 4L11 13M9 5H6a2 2 0 0 0-2 2v11a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2v-3")
    val Info = strokeIcon(circlePath(12f, 12f, 9f), "M12 10.5V17M12 7.2v.1")
    val Photo = strokeIcon(rectPath(3.5f, 5f, 17f, 14f, 3f), circlePath(9f, 10f, 1.6f), "M4.5 17.5l4.5-4.5 3 3 3.5-3.5 4 4")
    val Camera = strokeIcon(
        "M4 8h3l2-2.5h6L17 8h3a1.5 1.5 0 0 1 1.5 1.5V18a1.5 1.5 0 0 1-1.5 1.5H4A1.5 1.5 0 0 1 2.5 18V9.5A1.5 1.5 0 0 1 4 8z",
        circlePath(12f, 13.5f, 3.2f)
    )
    val Doc = strokeIcon("M7 3.5h7l4 4V20a1.5 1.5 0 0 1-1.5 1.5h-9A1.5 1.5 0 0 1 6 20V5A1.5 1.5 0 0 1 7.5 3.5z", "M14 3.5V8h4.5")
    val Send = strokeIcon("M12 19.5v-13M12 6.5l-5.5 5.5M12 6.5l5.5 5.5")
    val Empty = strokeIcon(rectPath(4f, 5f, 16f, 14f, 3f), "M4 10h16")
    val Chat = strokeIcon("M21 12a8 8 0 0 1-8 8c-1.4 0-2.8-.3-4-1l-5 1.5L5.5 16A8 8 0 1 1 21 12z")
    val Upload = strokeIcon("M12 16V5m0 0l-4 4m4-4l4 4M5 19h14")
}
