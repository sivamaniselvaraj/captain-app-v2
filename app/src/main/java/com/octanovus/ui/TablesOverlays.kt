package com.octanovus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.octanovus.data.NotificationHub
import com.octanovus.viewmodel.AddTableState
import com.octanovus.viewmodel.TablesState


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

/** The dashed tile at the end of the grid that opens "Add a table". */
@Composable
internal fun NewTableTile(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(116.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .dashedBorder(Ink)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Text("+", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("New table", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Bottom sheet to add a table to a floor. The waiter types the table number.
 * The floor is one that exists, or a new name typed in.
 */
@Composable
internal fun AddTableSheet(
    s: TablesState,
    add: AddTableState,
    onClose: () -> Unit,
    onSave: (number: String, floor: String, seats: Int) -> Unit,
) {
    BackHandler(onBack = onClose)

    val floors = s.floors
    val startFloor = s.selectedFloor?.takeIf { it in floors } ?: floors.firstOrNull() ?: TablesState.DEFAULT_FLOOR
    var number by remember { mutableStateOf("") }
    var floor by remember { mutableStateOf(startFloor) }
    var newFloor by remember { mutableStateOf(floors.isEmpty()) }
    var floorDraft by remember { mutableStateOf(if (floors.isEmpty()) TablesState.DEFAULT_FLOOR else "") }
    var seats by remember { mutableStateOf(4) }

    val chosenFloor = (if (newFloor) floorDraft else floor).trim()
    val clash = number.isNotBlank() && s.tables.any {
        it.number.equals(number.trim(), ignoreCase = true) &&
            it.floor.ifBlank { TablesState.DEFAULT_FLOOR }.equals(chosenFloor, ignoreCase = true)
    }
    val canSave = number.isNotBlank() && chosenFloor.isNotEmpty() && !clash && !add.busy
    fun save() { if (canSave) onSave(number, chosenFloor, seats) }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Scrim)
                .clickable(enabled = !add.busy, onClick = onClose),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Color.White)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Add a table", fontSize = 20.sp, fontWeight = FontWeight.Bold)

            OutlinedTextField(
                value = number,
                onValueChange = { number = it.take(10) },
                label = { Text("Table number") },
                singleLine = true,
                isError = clash,
                supportingText = if (clash) {
                    { Text("Table ${number.trim()} already exists on $chosenFloor.") }
                } else null,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Floor", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                floors.forEach { name ->
                    SheetChip(name, selected = !newFloor && floor == name) {
                        newFloor = false
                        floor = name
                    }
                }
                SheetChip("+ New floor", selected = newFloor) { newFloor = true }
            }
            if (newFloor) {
                OutlinedTextField(
                    value = floorDraft,
                    onValueChange = { floorDraft = it.take(30) },
                    label = { Text("Floor name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Seats", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    StepperButton("−", enabled = seats > 1) { seats -= 1 }
                    Text(seats.toString(), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    StepperButton("+", enabled = seats < 99) { seats += 1 }
                }
            }

            add.error?.let { Text(it, color = BadRed, fontSize = 13.sp) }

            Button(
                onClick = ::save,
                enabled = canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White),
            ) {
                Text(if (add.busy) "Adding..." else "Add table", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SheetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Ink else Pale)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Color.White else Ink,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun StepperButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (enabled) Ink else Pale)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = if (enabled) Color.White else Muted, fontSize = 20.sp)
    }
}
