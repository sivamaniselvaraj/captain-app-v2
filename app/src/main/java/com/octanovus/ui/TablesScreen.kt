package com.octanovus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.octanovus.data.OverdueLevel
import com.octanovus.data.TableInfo
import com.octanovus.data.levelAt
import com.octanovus.viewmodel.TablesState
import com.octanovus.viewmodel.TablesViewModel
import java.time.Duration
import java.time.Instant

private val CompletedBlue = Color(0xFF34495E)

/**
 * Home screen after PIN login: the table grid for one floor at a time.
 * Free tables are dashed. Occupied tables use the colour of their overdue level
 * and show the minutes since the first order and the running total.
 * Polls the desktop every 3 seconds while the screen is visible.
 */
@Composable
fun TablesScreen(
    onTableSelected: (TableInfo) -> Unit,
    onMyOrders: () -> Unit,
    onProfile: () -> Unit,
    onUnpaired: () -> Unit,
    vm: TablesViewModel = viewModel(),
) {
    val s by vm.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Poll only while the screen is on show.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.pollLoop() }
    }
    LaunchedEffect(s.unauthorised) {
        if (s.unauthorised) onUnpaired()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Canvas)
            .padding(horizontal = 20.dp),
    ) {
        Header(onProfile = onProfile, onMyOrders = onMyOrders)

        s.error?.let {
            Text(
                if (s.loaded) "$it Showing the last known tables." else it,
                color = BadRed,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        when {
            !s.loaded && s.error == null -> CenterMessage("Loading tables...")
            s.loaded && s.tables.isEmpty() -> CenterMessage("No tables yet. Add tables on the desktop.")
            s.loaded -> {
                FloorChips(s, onSelect = vm::selectFloor)
                TableGrid(s, onTableSelected, modifier = Modifier.weight(1f))
                Legend(s.levels)
            }

            else -> Box(Modifier.weight(1f))
        }
    }
}

@Composable
private fun Header(onProfile: () -> Unit, onMyOrders: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIconButton("☺", "Profile", onProfile)
        Text(
            "Select table",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        RoundIconButton("☰", "My orders", onMyOrders)
    }
}

@Composable
private fun RoundIconButton(glyph: String, description: String, onClick: () -> Unit) {
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

@Composable
private fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun FloorChips(s: TablesState, onSelect: (String) -> Unit) {
    if (s.floors.size < 2) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        (listOf(TablesState.ALL_FLOORS) + s.floors).forEach { floor ->
            val selected = floor == s.activeFloor
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (selected) Ink else Color.White)
                    .clickable { onSelect(floor) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (floor == TablesState.ALL_FLOORS) "All" else floor,
                    color = if (selected) Color.White else Ink,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun TableGrid(
    s: TablesState,
    onTableSelected: (TableInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.padding(top = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(s.visibleTables, key = { it.tableId }) { table ->
            val minutes = elapsedMinutes(table.since, s.now)
            TableTile(
                table = table,
                minutes = minutes,
                level = s.levels.levelAt(minutes),
                onClick = { onTableSelected(table) },
            )
        }
    }
}

@Composable
private fun TableTile(
    table: TableInfo,
    minutes: Int,
    level: OverdueLevel?,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val base = Modifier
        .fillMaxWidth()
        .height(116.dp)
        .clip(shape)

    when {
        table.isOccupied -> {
            val background = parseColor(level?.color, Ink)
            val textColor = if (background.luminance() > 0.5f) Ink else Color.White
            val label = level?.name?.uppercase()
            val summary = listOfNotNull(label, formatMoney(table.total)).joinToString(" · ")
            Column(
                modifier = base
                    .background(background)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .semantics {
                        contentDescription = "Table ${table.number}, occupied, " +
                                "${level?.name ?: "no level"}, ${formatElapsed(minutes)}, " +
                                "${formatMoney(table.total)} rupees"
                    },
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(table.number, color = textColor, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(formatElapsed(minutes), color = textColor, fontSize = 12.sp)
                }
                Text(summary, color = textColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        table.isCompleted -> Column(
            modifier = base
                .background(CompletedBlue)
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .semantics { contentDescription = "Table ${table.number}, completed" },
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(table.number, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("COMPLETED", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }

        // Available, and any status the phone does not know: shown as free.
        else -> Column(
            modifier = base
                .background(Color.White)
                .dashedBorder(Dot)
                .clickable(onClick = onClick)
                .semantics { contentDescription = "Table ${table.number}, available" },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            Text(table.number, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("AVAILABLE", color = Muted, fontSize = 11.sp, letterSpacing = 0.6.sp)
        }
    }
}

@Composable
private fun Legend(levels: List<OverdueLevel>) {
    if (levels.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
    ) {
        levels.forEach { level ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(parseColor(level.color, Ink))
                )
                Text(level.name, color = Muted, fontSize = 12.sp)
            }
        }
    }
}

private fun Modifier.dashedBorder(color: Color): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(16.dp.toPx()),
        style = Stroke(
            width = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
        ),
    )
}

private fun parseColor(hex: String?, fallback: Color): Color =
    try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (e: Exception) {
        fallback
    }

private fun elapsedMinutes(since: Instant?, now: Instant): Int =
    if (since == null) 0 else Duration.between(since, now).toMinutes().coerceAtLeast(0).toInt()

private fun formatElapsed(minutes: Int): String =
    if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
