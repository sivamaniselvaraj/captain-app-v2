package com.octanovus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.Instant

/** Pieces shared by the Tables, Menu, View order, Order sent and My orders screens. */

/** Page background used by every screen after login. */

/** Round white button with one glyph, for the back button and the header actions. */
@Composable
internal fun RoundIconButton(glyph: String, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = 18.sp)
    }
}

/** A colour sent by the desktop, such as "#f1c40f". Falls back when it is missing or not a colour. */
internal fun parseColor(hex: String?, fallback: Color): Color =
    try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (e: Exception) {
        fallback
    }

/** Whole minutes from [since] to [now]. Zero when there is no start time. */
internal fun elapsedMinutes(since: Instant?, now: Instant): Int =
    if (since == null) 0 else Duration.between(since, now).toMinutes().coerceAtLeast(0).toInt()

/** "14 min", or "1 h 5 min" from an hour on. */
internal fun formatElapsed(minutes: Int): String =
    if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
