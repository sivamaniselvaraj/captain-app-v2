package com.octanovus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas as DrawCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.octanovus.data.OrderItem
import com.octanovus.viewmodel.SheetOrder
import com.octanovus.viewmodel.TableOrdersState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TimeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

/** A small receipt outline: the "view orders" symbol on a busy table. */
@Composable
internal fun ReceiptIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    DrawCanvas(modifier.size(18.dp)) {
        val s = size.width / 24f
        val stroke = Stroke(width = 2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val body = Path().apply {
            moveTo(6f * s, 3f * s)
            lineTo(18f * s, 3f * s)
            lineTo(18f * s, 21f * s)
            lineTo(15f * s, 19f * s)
            lineTo(12f * s, 21f * s)
            lineTo(9f * s, 19f * s)
            lineTo(6f * s, 21f * s)
            close()
        }
        drawPath(body, color, style = stroke)
        val lines = Path().apply {
            moveTo(9f * s, 8f * s); lineTo(15f * s, 8f * s)
            moveTo(9f * s, 12f * s); lineTo(15f * s, 12f * s)
        }
        drawPath(lines, color, style = stroke)
    }
}

/**
 * Bottom sheet listing every order on a busy table: dishes, kitchen notes and kitchen step,
 * with the table total. "Add items" goes to the table's menu.
 */
@Composable
fun TableOrdersSheet(
    s: TableOrdersState,
    subtitle: String,
    onAddItems: () -> Unit,
    onClose: () -> Unit,
) {
    val table = s.table ?: return
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
                .clickable(enabled = false) {}
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFFD8D8D2)),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Table ${table.number} orders", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(subtitle, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                }
                Text(formatMoney(table.total), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    s.loading -> Text("Loading orders...", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 24.dp))
                    s.error != null -> Text(s.error, color = BadRed, fontSize = 14.sp, modifier = Modifier.padding(vertical = 24.dp))
                    s.orders.isEmpty() -> Text("No open orders on this table.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 24.dp))
                    else -> s.orders.forEach { order -> OrderBlock(order, s) }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(Ink)
                        .clickable(onClick = onAddItems),
                    contentAlignment = Alignment.Center,
                ) { Text("Add items", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                Box(
                    modifier = Modifier
                        .widthIn(min = 110.dp)
                        .height(52.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(Color.White)
                        .border(1.5.dp, Ink, RoundedCornerShape(26.dp))
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Text("Close", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun OrderBlock(order: SheetOrder, s: TableOrdersState) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("ORDER #${order.orderNumber}", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            order.placedAt?.let { Text(formatTime(it), color = Muted, fontSize = 12.sp) }
        }
        order.items.forEach { item ->
            Divider(color = Color(0xFFEFEFEB), thickness = 1.dp, modifier = Modifier.padding(top = 10.dp))
            ItemRow(item, order.hasSteps, s)
        }
    }
}

@Composable
private fun ItemRow(item: OrderItem, showStep: Boolean, s: TableOrdersState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text("${item.quantity}×", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 28.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (item.note.isNotBlank()) {
                Text(item.note, color = Muted, fontSize = 12.sp, fontStyle = FontStyle.Italic, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (showStep && item.step.isNotBlank()) {
            val step = s.steps.firstOrNull { it.code == item.step }
            Text(
                step?.name ?: item.step.replaceFirstChar { it.uppercase() },
                color = parseColor(step?.color, Muted),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFF2F2EE))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

private fun formatTime(at: Instant): String = TimeFormat.format(at.atZone(ZoneId.systemDefault()))
