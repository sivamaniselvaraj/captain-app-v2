package com.octanovus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
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
import com.octanovus.data.AppPrefs
import com.octanovus.data.OverdueLevel
import com.octanovus.data.TableInfo
import com.octanovus.data.levelAt
import com.octanovus.viewmodel.TableOrdersViewModel
import com.octanovus.viewmodel.TablesState
import com.octanovus.viewmodel.TablesViewModel

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
    onNotifications: () -> Unit,
    onProfile: () -> Unit,
    onUnpaired: () -> Unit,
    vm: TablesViewModel = viewModel(),
) {
    val s by vm.state.collectAsState()
    val sheetVm: TableOrdersViewModel = viewModel()
    val sheet by sheetVm.state.collectAsState()
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val userName = remember { AppPrefs(context).pairedUserName }

    val lifecycleOwner = LocalLifecycleOwner.current

    // Poll only while the screen is on show.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.pollLoop() }
    }
    LaunchedEffect(s.unauthorised) {
        if (s.unauthorised) onUnpaired()
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Canvas)
                .padding(horizontal = 20.dp),
        ) {
            Header(onProfile = onProfile, onMenu = { drawerOpen = true })

            if (s.offlineMode) {
                Text(
                    "Desktop offline mode. Ordering still works.",
                    color = Muted,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFFFF1D6))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }

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
                    TableGrid(s, onTableSelected, onViewOrders = { sheetVm.open(it) }, modifier = Modifier.weight(1f))
                    Legend(s.levels)
                }

                else -> Box(Modifier.weight(1f))
            }
        }

        sheet.table?.let { sheetTable ->
            val minutes = elapsedMinutes(sheetTable.since, s.now)
            val count = sheetTable.orders.size
            TableOrdersSheet(
                s = sheet,
                subtitle = listOfNotNull(
                    sheetTable.floor.takeIf { it.isNotBlank() },
                    formatElapsed(minutes),
                    if (count == 1) "1 order" else "$count orders",
                ).joinToString(" \u00B7 "),
                onAddItems = { sheetVm.close(); onTableSelected(sheetTable) },
                onClose = { sheetVm.close() },
            )
        }

        if (drawerOpen) {
            MenuDrawer(
                userName = userName,
                onClose = { drawerOpen = false },
                onMyOrders = { drawerOpen = false; onMyOrders() },
                onNotifications = { drawerOpen = false; onNotifications() },
            )
        }
    }
}
@Composable
private fun Header(onProfile: () -> Unit, onMenu: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIconButton("☰", "Menu", onMenu)
        Text(
            "Select table",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        RoundIconButton("☺", "Profile", onProfile)
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
    if (s.floors.isEmpty()) return
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
    onViewOrders: (TableInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.padding(top = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // One section per floor: a heading with the table count, then that floor's tables.
        val byFloor = s.visibleTables.groupBy { it.floor.ifBlank { TablesState.DEFAULT_FLOOR } }
        byFloor.forEach { (floor, tables) ->
            item(key = "floor-$floor", span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        floor.uppercase(),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.6.sp,
                        color = Muted,
                    )
                    Text(
                        if (tables.size == 1) "1 TABLE" else "${tables.size} TABLES",
                        fontSize = 12.sp,
                        letterSpacing = 1.6.sp,
                        color = Muted,
                    )
                }
            }
            items(tables, key = { it.tableId }) { table ->
                val minutes = elapsedMinutes(table.since, s.now)
                TableTile(
                    table = table,
                    minutes = minutes,
                    level = s.levels.levelAt(minutes),
                    onClick = { onTableSelected(table) },
                    onViewOrders = { onViewOrders(table) },
                )
            }
        }
    }
}

@Composable
private fun TableTile(
    table: TableInfo,
    minutes: Int,
    level: OverdueLevel?,
    onClick: () -> Unit,
    onViewOrders: () -> Unit = {},
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
            Box {
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
                    // The top-right corner belongs to the receipt button, so the time moves down.
                    Text(table.number, color = textColor, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(formatElapsed(minutes), color = textColor, fontSize = 12.sp)
                        Text(summary, color = textColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                // Shortcut: opens this table's orders without leaving the screen.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(textColor.copy(alpha = 0.18f))
                        .clickable(onClick = onViewOrders)
                        .semantics { contentDescription = "View orders for table ${table.number}" },
                    contentAlignment = Alignment.Center,
                ) { ReceiptIcon(color = textColor) }
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

        table.isBlocked -> Column(
            modifier = base
                .background(Color(0xFFEDEDE8))
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .semantics { contentDescription = "Table ${table.number}, ${table.status}" },
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(table.number, color = Muted, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(table.status.uppercase(), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
