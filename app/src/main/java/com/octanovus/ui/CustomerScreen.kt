package com.octanovus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.octanovus.data.TableInfo
import com.octanovus.viewmodel.CartViewModel

private const val MIN_PHONE_DIGITS = 7
private const val MAX_PHONE_DIGITS = 10
private const val MAX_NAME_LENGTH = 60
private const val MAX_OCCUPANTS = 99

/**
 * Shown once, between Tables and the menu, when the waiter starts a new order on a free table.
 * Both fields are optional. "Skip" goes straight to the menu. Extra rounds on an occupied table
 * never show this screen.
 */
@Composable
fun CustomerScreen(
    table: TableInfo,
    onDone: () -> Unit,
    onBack: () -> Unit,
    /** True when opened from View order to change the details, not at the start of an order. */
    fromViewOrder: Boolean = false,
    cartVm: CartViewModel = viewModel(),
) {
    val cart by cartVm.state.collectAsState()
    val focusManager = LocalFocusManager.current

    // Start from what was entered before, if the waiter comes back to this table's order.
    val sameTable = cart.tableId == table.tableId
    var name by rememberSaveable { mutableStateOf(if (sameTable) cart.customerName else "") }
    var phone by rememberSaveable { mutableStateOf(if (sameTable) cart.customerPhone else "") }

    var occupants by rememberSaveable { mutableStateOf(if (sameTable) cart.occupants?.toString().orEmpty() else "") }

    val phoneTooShort = phone.isNotEmpty() && phone.length < MIN_PHONE_DIGITS
    // The guests can't be more than the seats at the table, when the desktop knows the seats.
    val maxOccupants = table.capacity ?: MAX_OCCUPANTS
    val occupantsValue = occupants.toIntOrNull()
    val occupantsInvalid = occupants.isNotEmpty() && occupantsValue !in 1..maxOccupants
    val seats = table.capacity
    val occupantsHint: String? = when {
        occupantsInvalid && seats != null -> "Table ${table.number} seats $seats. Enter 1 to $seats, or clear the field."
        occupantsInvalid -> "Enter a number from 1 to $MAX_OCCUPANTS, or clear the field."
        seats != null -> "This table seats $seats."
        else -> null
    }
    val hasInput = name.isNotBlank() || phone.isNotEmpty() || occupants.isNotEmpty()
    val canContinue = hasInput && !phoneTooShort && !occupantsInvalid

    fun save() {
        if (!canContinue) return
        cartVm.setCustomer(table, name, phone, occupantsValue)
        onDone()
    }

    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Canvas),
    ) {
        TableHeader("Customer details", table.number, onBack)

        // Scrolls when the keyboard takes space.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Add the guest's details for this order. Every field is optional.",
                color = Muted,
                fontSize = 14.sp,
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_NAME_LENGTH) },
                label = { Text("Customer name") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) }),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it.filter(Char::isDigit).take(MAX_PHONE_DIGITS) },
                label = { Text("Phone number") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors(),
                supportingText = if (phoneTooShort) {
                    { Text("Enter at least $MIN_PHONE_DIGITS digits, or clear the field.") }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) }),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = occupants,
                onValueChange = { occupants = it.filter(Char::isDigit).take(2) },
                label = { Text("Number of occupants") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors(),
                isError = occupantsInvalid,
                supportingText = occupantsHint?.let { hint -> { Text(hint) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Pinned above the keyboard.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PrimaryButton(
                label = "Continue",
                enabled = canContinue,
                onClick = { save() },
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = {
                    cartVm.skipCustomer(table)
                    onDone()
                },
            ) {
                Text(if (fromViewOrder) "Remove details" else "Skip", color = Ink, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color.White,
    unfocusedContainerColor = Color.White,
    focusedBorderColor = Ink,
    unfocusedBorderColor = Color(0xFFD9D9D4),
)
