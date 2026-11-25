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
