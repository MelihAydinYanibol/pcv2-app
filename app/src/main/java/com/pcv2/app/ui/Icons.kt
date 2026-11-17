package com.pcv2.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The dashboard's inline SVG icons (24x24, stroke-only, round caps), built
 * from the same path data so the app looks the same.
 */
object Ic {
    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0"

    private fun icon(name: String, vararg paths: String, stroke: Float = 2f): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (p in paths) {
            b.addPath(
                pathData = addPathNodes(p),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    private const val SHIELD = "M12 3l7 3v5c0 4.5-3 8.3-7 10-4-1.7-7-5.5-7-10V6l7-3z"

    val brand = icon("brand", SHIELD, "M12 8v4l2.5 1.5", stroke = 2.2f)
    val lockShield = icon(
        "lockShield", SHIELD,
        "M10 10.5h4a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1v-3a1 1 0 0 1 1-1z",
        "M10.5 10.5V9a1.5 1.5 0 0 1 3 0v1.5", stroke = 2.2f,
    )
    val queue = icon("queue", "M12 8v4l3 3", "M3.05 11a9 9 0 1 1 .5 4", "M3 4v7h7")
    val settings = icon(
        "settings", circle(12f, 12f, 3f),
        "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z",
    )
    val moon = icon("moon", "M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z")
    val sun = icon("sun", circle(12f, 12f, 4f), "M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4")
    val refresh = icon("refresh", "M21 12a9 9 0 1 1-3-6.7L21 8", "M21 3v5h-5")
    val today = icon("today", circle(12f, 12f, 9f), "M12 7v5l3 2")
    val limits = icon("limits", "M5 22h14M5 2h14M17 22v-4.2a2 2 0 0 0-.6-1.4L12 12l-4.4 4.4a2 2 0 0 0-.6 1.4V22M7 2v4.2a2 2 0 0 0 .6 1.4L12 12l4.4-4.4a2 2 0 0 0 .6-1.4V2")
    val gift = icon("gift", "M20 12v9H4v-9M2 7h20v5H2zM12 21V7M12 7H7.5a2.5 2.5 0 0 1 0-5C11 2 12 7 12 7zM12 7h4.5a2.5 2.5 0 0 0 0-5C13 2 12 7 12 7z")
    val usage = icon("usage", "M3 3v18h18", "M8 17V11M13 17V7M18 17v-4")
    val warning = icon("warning", "M10.3 3.9L1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z", "M12 9v4M12 17h.01")
    val success = icon("success", "M20 6L9 17l-5-5", stroke = 2.2f)
    val error = icon("error", circle(12f, 12f, 9f), "M15 9l-6 6M9 9l6 6", stroke = 2.2f)
    val info = icon("info", circle(12f, 12f, 9f), "M12 16v-4M12 8h.01", stroke = 2.2f)
    val question = icon("question", circle(12f, 12f, 9f), "M9.1 9a3 3 0 0 1 5.8 1c0 2-3 3-3 3M12 17h.01")
    val trash = icon("trash", "M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14")
    val clock = icon("clock", circle(12f, 12f, 9f), "M12 7v5l3 2")
    val edit = icon("edit", "M12 20h9", "M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4 12.5-12.5z")
    val close = icon("close", "M18 6L6 18M6 6l12 12")
    val eye = icon("eye", "M1 12s4-8 11-8 11 8 11 8-4 8-11 8S1 12 1 12z", circle(12f, 12f, 3f))
    val eyeOff = icon("eyeOff", "M17.9 17.9A10.1 10.1 0 0 1 12 20c-7 0-11-8-11-8a18.5 18.5 0 0 1 5.1-5.9M9.9 4.2A9.1 9.1 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.2 3.2M14.1 14.1a3 3 0 1 1-4.2-4.2M1 1l22 22")
    val chevronDown = icon("chevronDown", "M6 9l6 6 6-6")
    val calendar = icon("calendar", "M5 4h14a2 2 0 0 1 2 2v13a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M16 2v4M8 2v4M3 10h18")
    val server = icon("server", "M4 3h16a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1zM4 13h16a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-6a1 1 0 0 1 1-1z", "M7 7h.01M7 17h.01")
}
