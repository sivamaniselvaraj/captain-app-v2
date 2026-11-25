package com.octanovus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas as DrawCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A small shopping-bag outline, the takeaway symbol. */
@Composable
internal fun BagIcon(modifier: Modifier = Modifier, color: Color = Ink) {
    DrawCanvas(modifier.size(22.dp)) {
        val s = size.width / 24f
        val stroke = Stroke(width = 2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val body = Path().apply {
            moveTo(5f * s, 8f * s)
            lineTo(19f * s, 8f * s)
            lineTo(18f * s, 20f * s)
            lineTo(6f * s, 20f * s)
            close()
        }
        drawPath(body, color, style = stroke)
        val handle = Path().apply {
            moveTo(9f * s, 8f * s)
            lineTo(9f * s, 6f * s)
            arcTo(Rect(9f * s, 3f * s, 15f * s, 9f * s), 180f, 180f, false)
            lineTo(15f * s, 8f * s)
        }
        drawPath(handle, color, style = stroke)
    }
}

/**
 * Bottom sheet shown when the waiter taps the bag on a table's menu. Starting a takeaway opens the
 * takeaway menu for this table. The dine-in cart stays as it is, and the takeaway has its own cart.
 */
@Composable
fun TakeawayConfirmSheet(
    tableNumber: String,
    dineInItems: Int,
    onStart: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
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
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Pale),
                    contentAlignment = Alignment.Center,
                ) { BagIcon() }
                Column {
                    Text("Takeaway for Table $tableNumber", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Packed to go, billed separately from the table's dine-in order.",
                        fontSize = 12.sp,
                        color = Muted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Text(
                if (dineInItems > 0) {
                    "Your $dineInItems dine-in ${if (dineInItems == 1) "item stays" else "items stay"} on Table $tableNumber. The takeaway has its own cart."
                } else {
                    "The takeaway has its own cart."
                },
                fontSize = 12.sp,
                color = Muted,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Canvas)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
            PrimaryButton(
                label = "Start takeaway order",
                enabled = true,
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Cancel",
                fontSize = 14.sp,
                color = Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClose)
                    .padding(vertical = 10.dp),
            )
        }
    }
}
