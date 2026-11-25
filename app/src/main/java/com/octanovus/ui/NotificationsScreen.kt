package com.octanovus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.octanovus.data.AppNotification
import com.octanovus.data.NotificationHub
import kotlinx.coroutines.delay
import java.time.Instant

private val DelayColor = Color(0xFFE67E22)

/**
 * Recent overdue and status notifications for this phone's orders, newest first.
 * Tapping one opens that table. Opening the screen clears the unread count on the bell.
 */
@Composable
fun NotificationsScreen(
    onBack: () -> Unit,
    onOpenTable: (tableNumber: String) -> Unit,
) {
    val items by NotificationHub.items.collectAsState()
    val connection by NotificationHub.connection.collectAsState()

    // Entries newer than this were unread when the screen opened. They keep their dot until it closes.
    val seenWhenOpened = remember { NotificationHub.seenId }
    LaunchedEffect(items.size) { NotificationHub.markAllSeen() }

    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = Instant.now()
        }
    }
    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Canvas),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RoundIconButton("‹", "Back", onBack)
            Text("Notifications", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        when (connection) {
            NotificationHub.Connection.CONNECTED ->
                StatusLine("Live updates on", OkGreen)
            NotificationHub.Connection.CONNECTING ->
                StatusLine("Connecting to the desktop...", Muted)
            NotificationHub.Connection.OFFLINE ->
                StatusLine("Live updates off. They resume when the desktop is reachable.", BadRed)
        }

        if (items.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No notifications yet. Overdue alerts and status updates for your orders show here.",
                    color = Muted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items, key = { it.id }) { n ->
                    NotificationRow(
                        n = n,
                        unread = n.id > seenWhenOpened,
                        now = now,
                        onClick = { n.tableNumber?.let(onOpenTable) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusLine(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

@Composable
private fun NotificationRow(n: AppNotification, unread: Boolean, now: Instant, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .clickable(enabled = n.tableNumber != null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(if (n.type == "delay") DelayColor else OkGreen)
        )
        Column(Modifier.weight(1f)) {
            Text(
                n.title,
                fontSize = 15.sp,
                fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold,
            )
            Text(n.message, fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
            Text(timeAgo(n.at, now), fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
        }
        if (unread) {
            Box(
                Modifier
                    .padding(top = 5.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(BadRed)
            )
        }
    }
}

private fun timeAgo(at: Instant?, now: Instant): String {
    if (at == null) return ""
    val minutes = elapsedMinutes(at, now)
    return if (minutes < 1) "Just now" else "${formatElapsed(minutes)} ago"
}
