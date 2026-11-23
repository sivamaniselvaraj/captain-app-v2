package com.octanovus.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.octanovus.viewmodel.MAX_ITEM_NOTE_LENGTH


private val QuickNotes = listOf("Less spicy", "No onion", "Extra spicy", "Pack separately")

/**
 * Bottom sheet to write a note for one item of the order. One note covers the whole quantity.
 * An empty note removes it.
 */
@Composable
fun NoteSheet(
    itemName: String,
    quantity: Int,
    initial: String,
    onSave: (String) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    var text by remember { mutableStateOf(initial) }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x73000000))
                .clickable(onClick = onClose),
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
            Column {
                Text("Note for $itemName", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (quantity > 1) "The kitchen sees this on the ticket. Applies to all $quantity."
                    else "The kitchen sees this on the ticket.",
                    fontSize = 12.sp,
                    color = Muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MAX_ITEM_NOTE_LENGTH) },
                label = { Text("Note") },
                minLines = 3,
                maxLines = 3,
                supportingText = { Text("${text.length}/$MAX_ITEM_NOTE_LENGTH", fontSize = 11.sp) },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Ink,
                    unfocusedBorderColor = Color(0xFFD9D9D4),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QuickNotes.forEach { phrase ->
                    Box(
                        modifier = Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Pale)
                            .clickable {
                                // Adds the phrase after what is already written.
                                val joined = if (text.isBlank()) phrase else "${text.trim().trimEnd(',')}, $phrase"
                                text = joined.take(MAX_ITEM_NOTE_LENGTH)
                            }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(phrase, fontSize = 13.sp)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineButton(
                    label = if (initial.isBlank()) "Cancel" else "Remove note",
                    enabled = true,
                    onClick = { if (initial.isBlank()) onClose() else onSave("") },
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    label = "Save note",
                    enabled = true,
                    onClick = { onSave(text) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
