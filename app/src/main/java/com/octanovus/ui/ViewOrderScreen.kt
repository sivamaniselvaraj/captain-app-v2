package com.octanovus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.octanovus.data.TableInfo
import com.octanovus.viewmodel.CartLine
import com.octanovus.viewmodel.CartState
import com.octanovus.viewmodel.CartViewModel
import com.octanovus.viewmodel.MAX_NOTES_LENGTH
import com.octanovus.viewmodel.PlacedOrder

/**
 * Review the cart for the selected table, change quantities, add an instruction for the kitchen
 * and place the order. Follows the light mock ("View order").
 *
 * The per-item "Modify" link from the mock is left out: the desktop's order API takes only the
 * item and quantity for each line, plus one note for the whole order.
 */
@Composable
fun ViewOrderScreen(
    table: TableInfo,
    onBack: () -> Unit,
    onPlaced: (PlacedOrder) -> Unit,
    onEditCustomer: () -> Unit,
    onUnpaired: () -> Unit,
    cartVm: CartViewModel = viewModel(),
) {
    val cart by cartVm.state.collectAsState()
    val place by cartVm.place.collectAsState()

    LaunchedEffect(table.tableId) { cartVm.startFor(table) }
    LaunchedEffect(place.placed) {
        place.placed?.let {
            cartVm.consumePlaced()
            onPlaced(it)
        }
    }
    LaunchedEffect(place.unauthorised) {
        if (place.unauthorised) onUnpaired()
    }
    BackHandler(enabled = !place.placing, onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Canvas),
    ) {
        TableHeader("View order", table.number, onBack)

        if (cart.lines.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Your order is empty.", color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
            ActionBar {
                OutlineButton("Back to menu", enabled = true, onClick = onBack, modifier = Modifier.weight(1f))
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(cart.lines.values.toList(), key = { it.item.id }) { line ->
                    LineCard(
                        line = line,
                        canAdd = cart.canAdd(line.item.id) && !place.placing,
                        canRemove = !place.placing,
                        onAdd = { cartVm.add(line.item) },
                        onRemove = { cartVm.remove(line.item.id) },
                    )
                }
                // Guest details belong to a new order. An extra round on an occupied table has none to edit.
                if (table.startsNewOrder) {
                    item(key = "customer") {
                        CustomerCard(cart, enabled = !place.placing, onEdit = onEditCustomer)
                    }
                }
                item(key = "instruction") {
                    InstructionBox(cart.notes, enabled = !place.placing, onChange = cartVm::setNotes)
                }
                item(key = "totals") { Totals(cart) }
            }

            place.error?.let {
                Text(
                    it,
                    color = BadRed,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            ActionBar {
                OutlineButton("Add more", enabled = !place.placing, onClick = onBack, modifier = Modifier.weight(1f))
                PrimaryButton(
                    label = if (place.placing) "Placing..." else "Place order",
                    detail = if (place.placing) null else "",//formatMoney(cart.total),
                    enabled = !place.placing,
                    onClick = cartVm::placeOrder,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun LineCard(
    line: CartLine,
    canAdd: Boolean,
    canRemove: Boolean,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
) {
    val item = line.item
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VegMarker(item.isVeg)
                Text(
                    item.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Text(
                listOfNotNull("${formatMoney(item.price)} each", item.taxLabel).joinToString(" "),
                fontSize = 12.sp,
                color = Muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(formatMoney(line.lineTotal), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StepButton("−", "Remove one ${item.name}", filled = false, enabled = canRemove, onClick = onRemove)
                Text(
                    line.quantity.toString(),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(min = 16.dp),
                )
                StepButton("+", "Add one ${item.name}", filled = true, enabled = canAdd, onClick = onAdd)
            }
        }
    }
}

/** The guest details for this order, with a link to change them. Shows "Add" when there are none. */
@Composable
private fun CustomerCard(cart: CartState, enabled: Boolean, onEdit: () -> Unit) {
    val details = listOfNotNull(
        cart.customerName.takeIf { it.isNotBlank() },
        cart.customerPhone.takeIf { it.isNotBlank() },
        cart.occupants?.let { if (it == 1) "1 guest" else "$it guests" },
    ).joinToString(" \u00B7 ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .clickable(enabled = enabled, onClick = onEdit)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Customer", fontSize = 12.sp, color = Muted)
            Text(
                details.ifEmpty { "No customer details" },
                fontSize = 15.sp,
                fontWeight = if (details.isEmpty()) FontWeight.Normal else FontWeight.SemiBold,
                color = if (details.isEmpty()) Muted else Ink,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            if (details.isEmpty()) "Add" else "Edit",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) Ink else Dot,
            modifier = Modifier.semantics { contentDescription = if (details.isEmpty()) "Add customer details" else "Edit customer details" },
        )
    }
}

@Composable
private fun InstructionBox(value: String, enabled: Boolean, onChange: (String) -> Unit) {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Additional instruction", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            placeholder = { Text("e.g. instruction for your order") },
            minLines = 3,
            maxLines = 3,
            supportingText = { Text("${value.length}/$MAX_NOTES_LENGTH", fontSize = 11.sp) },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                disabledContainerColor = Color.White,
                focusedBorderColor = Ink,
                unfocusedBorderColor = Color(0xFFD9D9D4),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Totals(cart: CartState) {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AmountRow("Subtotal", formatMoney(cart.subtotal))
        cart.taxLines.forEach { AmountRow(it.title, formatMoney(it.amount)) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Total", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(formatMoney(cart.total), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Text("Estimate. The desktop works out the final bill.", fontSize = 11.sp, color = Muted)
    }
}

@Composable
private fun AmountRow(label: String, amount: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = Muted)
        Text(amount, fontSize = 13.sp, color = Muted)
    }
}

@Composable
internal fun ActionBar(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
internal fun OutlineButton(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color.White)
            .border(1.dp, if (enabled) Ink else Dot, RoundedCornerShape(28.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) Ink else Dot)
    }
}

@Composable
internal fun PrimaryButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(if (enabled) Ink else Dot)
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        detail?.let { Text(it, fontSize = 15.sp, color = Color.White.copy(alpha = 0.85f)) }
    }
}
