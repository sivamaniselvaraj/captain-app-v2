package com.octanovus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.octanovus.viewmodel.SplashState
import com.octanovus.viewmodel.SplashViewModel

@Composable
fun SplashScreen(
    onSignedIn: () -> Unit = {},
    onUnpaired: () -> Unit = {},
    vm: SplashViewModel = viewModel(),
) {
    val s by vm.state.collectAsState()

    LaunchedEffect(s.signedInAs) {
        if (s.signedInAs != null) onSignedIn()
    }

    LaunchedEffect(s.revoked) {
        if (s.revoked) onUnpaired()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Welcome to",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Restaurant name",
            fontSize = 18.sp,
            color = Muted,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))
        ConnectionStatus(s, onRetry = vm::checkDesktop)

        DesktopSetup(s, vm)

        if (s.signedInAs != null) {
            Spacer(Modifier.height(24.dp))
            Text("Signed in as ${s.signedInAs}", fontWeight = FontWeight.SemiBold)
            Text("Tables screen comes next.", color = Muted)
            return@Column
        }

        Spacer(Modifier.height(16.dp))
        Text("Enter your PIN", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        PinDots(length = s.pin.length)
        s.pinError?.let {
            Text(it, color = BadRed, textAlign = TextAlign.Center)
        }

        Keypad(onDigit = vm::onDigit, onDelete = vm::onDelete)

        Button(
            onClick = vm::submitPin,
            enabled = s.reachable && !s.busy && s.pin.length >= SplashViewModel.MIN_PIN,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = androidx.compose.ui.graphics.Color.White),
        ) {
            Text(if (s.busy) "Checking..." else "Continue", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ConnectionStatus(s: SplashState, onRetry: () -> Unit) {
    when {
        s.checking -> Text("Checking desktop...", color = Muted, textAlign = TextAlign.Center)
        s.reachable -> Text("Desktop connected", color = OkGreen, textAlign = TextAlign.Center)
        else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                s.statusError ?: "Desktop not reachable",
                color = BadRed,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun DesktopSetup(s: SplashState, vm: SplashViewModel) {
    if (!s.editing) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (s.ip.isBlank()) "Desktop not set" else "Desktop ${s.ip}:${s.port}",
                color = Muted,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = vm::startEdit, enabled = !s.busy) { Text("Edit") }
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = s.ipDraft,
            onValueChange = vm::onIpDraftChange,
            label = { Text("Desktop IP address") },
            placeholder = { Text("192.168.1.20") },
            singleLine = true,
            isError = s.ipError != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = s.portDraft,
            onValueChange = vm::onPortDraftChange,
            label = { Text("Port") },
            singleLine = true,
            isError = s.portError != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.saveAddress() }),
            modifier = Modifier.width(104.dp),
        )
    }
    listOfNotNull(s.ipError, s.portError).forEach {
        Text(it, color = BadRed, fontSize = 12.sp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = vm::cancelEdit) { Text("Cancel") }
        TextButton(onClick = vm::saveAddress) { Text("Save and check") }
    }
}

@Composable
internal fun PinDots(length: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(SplashViewModel.MAX_PIN) { i ->
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (i < length) Ink else Dot)
            )
        }
    }
}

@Composable
internal fun Keypad(onDigit: (String) -> Unit, onDelete: () -> Unit) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "⌫"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { key ->
                    when (key) {
                        "" -> Spacer(Modifier.size(72.dp))
                        "⌫" -> TextButton(
                            onClick = onDelete,
                            modifier = Modifier.size(72.dp),
                        ) { Text(key, fontSize = 22.sp, color = Ink) }
                        else -> Button(
                            onClick = { onDigit(key) },
                            modifier = Modifier.size(72.dp),
                            shape = CircleShape,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Pale, contentColor = Ink),
                        ) { Text(key, fontSize = 24.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
    }
}
