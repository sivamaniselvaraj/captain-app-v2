package com.octanovus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.octanovus.viewmodel.PairState
import com.octanovus.viewmodel.PairViewModel


/**
 * Starting screen on a new phone, in two steps so the keyboard never covers the fields.
 *   Step 1: desktop IP, port and the 6-digit code.
 *   Step 2: waiter email and password.
 * The action button is pinned at the bottom and moves up above the keyboard.
 */
@Composable
fun PairScreen(onPaired: () -> Unit, notice: String? = null, vm: PairViewModel = viewModel()) {
    val s by vm.state.collectAsState()
    val focusManager = LocalFocusManager.current
    val emailFocus = remember { FocusRequester() }

    LaunchedEffect(s.paired) {
        if (s.paired) onPaired()
    }
    LaunchedEffect(s.step) {
        if (s.step == 2) emailFocus.requestFocus()
    }
    BackHandler(enabled = s.step == 2 && !s.busy) { vm.back() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
    ) {
        // Scrolls when the keyboard takes space.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 24.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Step ${s.step} of 2", color = Muted, fontSize = 12.sp)
            Text(
                "Pair this phone (${s.deviceName})",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            notice?.let { Text(it, color = BadRed, fontSize = 14.sp) }
            if (s.step == 1) {
                DesktopStep(s, vm, focusManager)
            } else {
                SignInStep(s, vm, focusManager, emailFocus)
            }
        }

        // Pinned above the keyboard.
        Column(
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            s.error?.let {
                Text(it, color = BadRed, fontSize = 14.sp)
            }
            Button(
                onClick = { if (s.step == 1) vm.next() else vm.pair() },
                enabled = if (s.step == 1) s.canGoNext else s.canPair,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White),
            ) {
                if (s.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        if (s.step == 1) "Next" else "Pair phone",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopStep(s: PairState, vm: PairViewModel, focus: FocusManager) {
    Text(
        "Enter the desktop's Wi-Fi address and the 6-digit code shown in its Pair a phone settings. " +
                "The code is valid for 5 minutes and works once.",
        color = Muted,
        fontSize = 14.sp,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = s.ip,
            onValueChange = vm::onIpChange,
            label = { Text("Desktop IP address") },
            placeholder = { Text("192.168.1.20") },
            singleLine = true,
            isError = s.ipError != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = s.port,
            onValueChange = vm::onPortChange,
            label = { Text("Port") },
            singleLine = true,
            isError = s.portError != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }),
            modifier = Modifier.width(104.dp),
        )
    }

    OutlinedTextField(
        value = s.code,
        onValueChange = vm::onCodeChange,
        label = { Text("6-digit code") },
        singleLine = true,
        isError = s.codeError != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { vm.next() }),
        modifier = Modifier.fillMaxWidth(),
    )

    listOfNotNull(s.ipError, s.portError, s.codeError).forEach {
        Text(it, color = BadRed, fontSize = 12.sp)
    }
}

@Composable
private fun SignInStep(
    s: PairState,
    vm: PairViewModel,
    focus: FocusManager,
    emailFocus: FocusRequester,
) {
    var showPassword by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Desktop ${s.ip}:${s.port}",
            color = Muted,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = vm::back, enabled = !s.busy) { Text("Edit") }
    }

    Text(
        "Sign in with your waiter email and password. They are sent to the desktop once " +
                "and are not stored on this phone.",
        color = Muted,
        fontSize = 12.sp,
    )

    OutlinedTextField(
        value = s.email,
        onValueChange = vm::onEmailChange,
        label = { Text("Email") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(emailFocus),
    )

    OutlinedTextField(
        value = s.password,
        onValueChange = vm::onPasswordChange,
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { showPassword = !showPassword }) {
                Text(if (showPassword) "Hide" else "Show")
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { vm.pair() }),
        modifier = Modifier.fillMaxWidth(),
    )
}
