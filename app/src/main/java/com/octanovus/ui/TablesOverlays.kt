package com.octanovus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.octanovus.data.NotificationHub
import com.octanovus.viewmodel.MyTable
import com.octanovus.viewmodel.MyTablesState


private val Scrim = Color(0x73000000)

/** The burger menu: a panel from the left over the Tables screen. */
@Composable
internal fun MenuDrawer(
    userName: String?,
    onClose: () -> Unit,
    onNewTakeaway: () -> Unit,
    onMyOrders: () -> Unit,
    onNotifications: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val unread by NotificationHub.unread.collectAsState()
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Scrim)
                .clickable(onClick = onClose),
        )
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(300.dp)
                .background(Color.White)
                .padding(horizontal = 16.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                userName?.takeIf { it.isNotBlank() } ?: "Captain",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Text(
                "Signed in on this phone",
                fontSize = 12.sp,
                color = Muted,
                modifier = Modifier.padding(start = 8.dp, bottom = 16.dp),
            )
            DrawerItem("New takeaway order", highlighted = true, onClick = onNewTakeaway)
            DrawerItem("My orders", onClick = onMyOrders)
            DrawerItem(
                if (unread > 0) "Notifications ($unread new)" else "Notifications",
                onClick = onNotifications,
            )
        }
    }
}

@Composable
private fun DrawerItem(label: String, highlighted: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (highlighted) Ink else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            label,
            fontSize = 15.sp,
            fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
            color = if (highlighted) Color.White else Ink,
        )
    }
}

/**
 * Bottom sheet that starts a takeaway order. The waiter picks the table the order is delivered to
 * from a dropdown of tables they have an active order on. A table is required.
 */
@Composable
internal fun TakeawaySheet(
    mine: MyTablesState,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onContinue: (MyTable) -> Unit,
) {
    BackHandler(onBack = onClose)
    var chosenId by rememberSaveable { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf(false) }
    // Keep the choice only while that table is still in the list.
    val chosen = mine.tables.firstOrNull { it.tableId == chosenId }
        ?: mine.tables.singleOrNull()

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Scrim)
                .clickable(onClick = onClose),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Color.White)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column {
                Text("New takeaway order", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Billed separately from the table's dine-in order.",
                    fontSize = 12.sp,
                    color = Muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            when {
                mine.loading -> Text("Finding your tables...", color = Muted, fontSize = 14.sp)
                mine.error != null -> {
                    Text(mine.error, color = BadRed, fontSize = 13.sp)
                    OutlineButton("Try again", enabled = true, onClick = onRetry, modifier = Modifier.fillMaxWidth())
                }

                mine.tables.isEmpty() -> Text(
                    "You have no active tables. Take a dine-in order first.",
                    color = Muted,
                    fontSize = 14.sp,
                )

                else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Deliver to table", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Box {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.5.dp, if (open) Ink else Color(0xFFD9D9D4), RoundedCornerShape(14.dp))
                                .clickable { open = true }
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                chosen?.label ?: "Choose a table",
                                fontSize = 16.sp,
                                color = if (chosen == null) Muted else Ink,
                            )
                            Text(if (open) "⌃" else "⌄", fontSize = 14.sp)
                        }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            mine.tables.forEach { table ->
                                DropdownMenuItem(
                                    text = { Text(table.label, fontSize = 15.sp) },
                                    onClick = { chosenId = table.tableId; open = false },
                                )
                            }
                        }
                    }
                    Text(
                        "Only tables where you have an active order.",
                        fontSize = 12.sp,
                        color = Muted,
                    )
                }
            }

            PrimaryButton(
                label = "Continue to menu",
                enabled = chosen != null && !mine.loading,
                onClick = { chosen?.let(onContinue) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
