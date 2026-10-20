package com.octanovus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.octanovus.viewmodel.PlacedOrder

/** Confirms the order reached the desktop. Back goes to the menu, as does "Back to menu". */
@Composable
fun OrderSentScreen(
    order: PlacedOrder,
    onBackToMenu: () -> Unit,
    onMyOrders: () -> Unit,
) {
    BackHandler(onBack = onBackToMenu)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Canvas),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(OkGreen),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            }
            Text("Order sent", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                "Table ${order.tableNumber} · Order #${order.orderNumber}",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "The kitchen ticket is printing on the desktop.",
                fontSize = 14.sp,
                color = Muted,
                textAlign = TextAlign.Center,
            )
            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Pale)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text("New", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Text("Status updates appear in My orders", fontSize = 12.sp, color = Muted)
            }
        }

        ActionBar {
            OutlineButton("Back to menu", enabled = true, onClick = onBackToMenu, modifier = Modifier.weight(1f))
            PrimaryButton("My orders", enabled = true, onClick = onMyOrders, modifier = Modifier.weight(1f))
        }
    }
}
