package com.octanovus.ui

import android.graphics.Color.parseColor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import com.octanovus.data.MyOrder
import com.octanovus.data.OrderItem
import com.octanovus.data.TableInfo
import com.octanovus.data.levelAt
import com.octanovus.viewmodel.OrdersState
import com.octanovus.viewmodel.OrdersViewModel
import com.octanovus.viewmodel.TableGroup
import com.octanovus.viewmodel.TablesState
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DateFormat = DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm", Locale.ENGLISH)
    .withZone(ZoneId.systemDefault())
private val Divider = Color(0xFFEFEFEB)
private val Outline = Color(0xFFD9D9D4)
private val ServedBlue = Color(0xFF34495E)
private val StepGrey = Color(0xFF7F8C8D)

/**
 * The orders this phone placed, one card per table, until the table is settled.
 * Each item shows its step. Items at "ready" have a "Ready to serve" switch: switching it on tells
 * the desktop, then the item shows served and the switch goes away. Follows the light mock.
 * Refreshes every 5 seconds while the screen is visible.
 */
@Composable
fun MyOrdersScreen(
    onBack: () -> Unit,
    onAddItems: (TableInfo) -> Unit,
    onUnpaired: () -> Unit,
    vm: OrdersViewModel = viewModel(),
) {
    val s by vm.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.pollLoop() }
    }
    LaunchedEffect(s.unauthorised) {
        if (s.unauthorised) onUnpaired()
    }
    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Canvas),
    ) {
        Header(s, onBack)

        s.error?.let {
            Text(
                if (s.loaded) "$it Showing the last list." else it,
                color = BadRed,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        s.actionError?.let {
            Text(
                "$it Tap to dismiss.",
                color = BadRed,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable { vm.dismissActionError() }
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        when {
            !s.loaded && s.error == null -> CenterMessage("Loading your orders...")
            !s.loaded -> Box(Modifier.weight(1f))
            s.orders.isEmpty() -> CenterMessage("No orders yet. Orders you place show here until the table is settled.")
            else -> {
                ServingCard(s)
                Filters(s, onFloor = vm::selectFloor, onTable = vm::selectTable)
                if (s.groups.isEmpty()) {
                    CenterMessage("No orders for this filter.")
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(s.groups, key = { it.tableId }) { group ->
                            TableCard(
                                group = group,
                                s = s,
                                expanded = group.tableId !in s.collapsed,
                                onToggle = { vm.toggleCollapsed(group.tableId) },
                                onServe = vm::markServed,
                                onAddItems = { onAddItems(group.toTableInfo(s)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(s: OrdersState, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RoundIconButton("‹", "Back", onBack)
        Column {
            Text("My orders", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(DateFormat.format(s.now), fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

/** "Tables you are serving": every table this phone has an order on. */
@Composable
private fun ServingCard(s: OrdersState) {
    val numbers = s.orders.map { it.tableNumber }.distinct()
        .sortedWith(compareBy<String>({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it }))
    Column(
        modifier = Modifier
            .padding(start = 20.dp, end = 20.dp, top = 14.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text("Tables you are serving", fontSize = 12.sp, color = Muted)
        Row(
            modifier = Modifier
                .padding(top = 8.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            numbers.forEach { number ->
                Box(
                    modifier = Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Ink)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Table $number", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun Filters(s: OrdersState, onFloor: (String?) -> Unit, onTable: (String?) -> Unit) {
    Row(
        modifier = Modifier
            .padding(start = 20.dp, end = 20.dp, top = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (s.floors.size >= 2) {
            FilterField(
                label = "Floor",
                selected = s.activeFloor ?: "All",
                options = listOf<Pair<String?, String>>(null to "All") + s.floors.map { it to it },
                onSelect = onFloor,
                modifier = Modifier.weight(1f),
            )
        }
        FilterField(
            label = "Table",
            selected = s.activeTable ?: "All",
            options = listOf<Pair<String?, String>>(null to "All") + s.tableNumbers.map { it to it },
            onSelect = onTable,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun FilterField(
    label: String,
    selected: String,
    options: List<Pair<String?, String>>,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Box(Modifier.weight(1f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, Outline, RoundedCornerShape(10.dp))
                    .clickable { open = true }
                    .padding(horizontal = 12.dp)
                    .semantics { contentDescription = "$label filter, $selected" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(selected, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                Text("▾", fontSize = 14.sp)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            onSelect(value)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TableCard(
    group: TableGroup,
    s: OrdersState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onServe: (orderId: String, itemId: String) -> Unit,
    onAddItems: () -> Unit,
) {
    val pending = group.pendingMinutes
    val level = pending?.let { s.levels.levelAt(it) }
    val accent = if (pending == null) ServedBlue else parseColor(level?.color, Ink)
    val summary = listOfNotNull(
        if (group.itemCount == 1) "1 item ordered" else "${group.itemCount} items ordered",
        level?.name ?: if (pending == null) "All served" else null,
        formatMoney(group.total),
    ).joinToString(" · ")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            // The coloured edge shows the overdue level of the table.
            .drawBehind { drawRect(accent, size = Size(4.dp.toPx(), size.height)) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFF2F2EF)),
                contentAlignment = Alignment.Center,
            ) {
                Text("🍴", fontSize = 18.sp)
            }
            Column(Modifier.weight(1f)) {
                val place = group.floor.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                Text("Table ${group.tableNumber}$place", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(summary, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
            }
            Text(if (expanded) "⌃" else "⌄", fontSize = 16.sp, color = Muted)
        }

        if (expanded) {
            group.orders.forEach { order ->
                if (group.orders.size > 1) {
                    Text(
                        "Order #${order.orderNumber} · " +
                            formatElapsed(elapsedMinutes(order.placedAt, s.now)),
                        fontSize = 12.sp,
                        color = Muted,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 2.dp),
                    )
                }
                order.items.forEach { item ->
                    ItemRow(order, item, s, onServe)
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Row(
                    modifier = Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Ink)
                        .clickable(onClick = onAddItems)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("+", color = Color.White, fontSize = 16.sp)
                    Text("Add items", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ItemRow(
    order: MyOrder,
    item: OrderItem,
    s: OrdersState,
    onServe: (orderId: String, itemId: String) -> Unit,
) {
    val isReady = item.step == READY_STEP
    Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            StepChip(item.step, s)
        }
        Text("${item.quantity}×", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        if (isReady) {
            Switch(
                checked = item.id in s.serving,
                onCheckedChange = { if (it) onServe(order.orderId, item.id) },
                enabled = item.id !in s.serving,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = OkGreen,
                    checkedThumbColor = Color.White,
                    checkedBorderColor = Color.Transparent,
                    disabledCheckedTrackColor = OkGreen,
                    disabledCheckedThumbColor = Color.White,
                    disabledCheckedBorderColor = Color.Transparent,
                    uncheckedTrackColor = Outline,
                    uncheckedThumbColor = Color.White,
                    uncheckedBorderColor = Color.Transparent,
                ),
                modifier = Modifier.semantics { contentDescription = "Mark ${item.name} served" },
            )
        }
    }
}

/** Step name and colour come from the desktop. "ready" reads "Ready to serve", as in the mock. */
@Composable
private fun StepChip(code: String, s: OrdersState) {
    val step = s.steps.firstOrNull { it.code == code }
    val label = when {
        code == READY_STEP -> "Ready to serve"
        step != null -> step.name
        code.isBlank() -> "New"
        else -> code.replaceFirstChar { it.uppercase() }
    }
    val background = parseColor(step?.color, if (code == READY_STEP) OkGreen else StepGrey)
    Box(
        modifier = Modifier
            .padding(top = 6.dp)
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (background.luminance() > 0.5f) Ink else Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Table details the menu needs to start an extra round on this table. */
private fun TableGroup.toTableInfo(s: OrdersState): TableInfo = TableInfo(
    tableId = tableId,
    number = tableNumber,
    floor = floor.ifBlank { TablesState.DEFAULT_FLOOR },
    status = TableInfo.STATUS_OCCUPIED,
    total = total,
    since = orders.mapNotNull { it.placedAt }.minOrNull(),
    openRounds = orders.size,
)

private const val READY_STEP = "ready"
