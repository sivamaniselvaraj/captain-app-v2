package com.octanovus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.octanovus.viewmodel.SetPinViewModel

/**
 * Shown once after pairing. The waiter sets a 4-digit PIN for the device.
 * The PIN is then entered on the splash screen each time the app opens.
 */
@Composable
fun SetPinScreen(onDone: () -> Unit, vm: SetPinViewModel = viewModel()) {
    val s by vm.state.collectAsState()

    LaunchedEffect(s.done) {
        if (s.done) onDone()
    }

    val title = if (s.step == 1) "Set your PIN" else "Confirm your PIN"
    val hint = if (s.step == 1) {
        "Choose a 4-digit PIN. You will enter it each time you open the app."
    } else {
        "Enter the same PIN again."
    }
    val entered = if (s.step == 1) s.first.length else s.confirm.length

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(hint, color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)

        Spacer(Modifier.height(16.dp))
        PinDots(length = entered)

        s.error?.let {
            Text(it, color = BadRed, fontSize = 14.sp, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(16.dp))
        Keypad(onDigit = vm::onDigit, onDelete = vm::onDelete)

        if (s.busy) {
            Text("Saving...", color = Muted, fontSize = 14.sp)
        }
    }
}
