package com.octanovus

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.octanovus.data.AppPrefs
import com.octanovus.data.TableInfo
import com.octanovus.ui.CaptainTheme
import com.octanovus.ui.CustomerScreen
import com.octanovus.ui.MenuScreen
import com.octanovus.ui.MyOrdersScreen
import com.octanovus.ui.PairScreen
import com.octanovus.ui.SetPinScreen
import com.octanovus.ui.SplashScreen
import com.octanovus.ui.TablesScreen
import com.octanovus.ui.ViewOrderScreen
import com.octanovus.viewmodel.PlacedOrder
import com.octanovus.ui.OrderSentScreen
import com.octanovus.viewmodel.CartViewModel

/** The screens so far, in order for a new phone. */
enum class Stage { PAIR, SET_PIN, PIN_LOGIN, TABLES, MENU, VIEW_ORDER, ORDER_SENT, MY_ORDERS, CUSTOMER }

private const val REMOVED_NOTICE = "This phone was removed from the desktop. Pair it again to continue."

class MainActivity : ComponentActivity() {

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw edge to edge so the keyboard height is reported to Compose (imePadding via
        // safeDrawingPadding). Content then moves up above the keyboard on every screen.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            CaptainTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                        val prefs = remember { AppPrefs(this@MainActivity) }
                        var stage by remember {
                            mutableStateOf(
                                when {
                                    prefs.deviceToken == null -> Stage.PAIR
                                    !prefs.pinSet -> Stage.SET_PIN
                                    else -> Stage.PIN_LOGIN
                                }
                            )
                        }
                        var notice by remember { mutableStateOf<String?>(null) }
                        var selectedTable by remember { mutableStateOf<TableInfo?>(null) }
                        var placedOrder by remember { mutableStateOf<PlacedOrder?>(null) }
                        val cartVm: CartViewModel = viewModel()
                        when (stage) {
                            Stage.PAIR -> PairScreen(
                                onPaired = { notice = null; stage = Stage.SET_PIN },
                                notice = notice,
                            )
                            Stage.SET_PIN -> SetPinScreen(onDone = { stage = Stage.PIN_LOGIN })
                            Stage.PIN_LOGIN -> SplashScreen(
                                onSignedIn = { stage = Stage.TABLES },
                                onUnpaired = {
                                    notice = REMOVED_NOTICE
                                    stage = Stage.PAIR
                                },
                            )
                            Stage.CUSTOMER -> selectedTable?.let { table ->
                                CustomerScreen(
                                    table = table,
                                    onDone = { stage = Stage.MENU },
                                    onBack = { stage = Stage.TABLES },
                                )
                            }
                            Stage.MENU -> selectedTable?.let { table ->
                                MenuScreen(
                                    table = table,
                                    onBack = { stage = Stage.TABLES },
                                    onViewOrder = { stage = Stage.VIEW_ORDER },
                                    onUnpaired = {
                                        notice = REMOVED_NOTICE
                                        stage = Stage.PAIR
                                    },
                                )
                            }
                            Stage.VIEW_ORDER -> selectedTable?.let { table ->
                                ViewOrderScreen(
                                    table = table,
                                    onBack = { stage = Stage.MENU },
                                    onPlaced = {
                                        placedOrder = it
                                        stage = Stage.ORDER_SENT
                                    },
                                    onUnpaired = {
                                        notice = REMOVED_NOTICE
                                        stage = Stage.PAIR
                                    },
                                )
                            }
                            Stage.ORDER_SENT -> placedOrder?.let { order ->
                                OrderSentScreen(
                                    order = order,
                                    onBackToMenu = { stage = Stage.MENU },
                                    onMyOrders = { stage = Stage.MY_ORDERS },
                                )
                            }
                            Stage.MY_ORDERS -> MyOrdersScreen(
                                onBack = { stage = Stage.TABLES },
                                onAddItems = { table ->
                                    selectedTable = table
                                    stage = Stage.MENU
                                },
                                onUnpaired = {
                                    notice = REMOVED_NOTICE
                                    stage = Stage.PAIR
                                },
                            )
                            Stage.TABLES -> TablesScreen(
                                onTableSelected = { table ->
                                    selectedTable = table
                                    // Customer details are asked once, for a new order on a free table.
                                    stage = if (cartVm.state.value.needsCustomerStep(table)) Stage.CUSTOMER
                                    else Stage.MENU
                                },
                                onMyOrders = { stage = Stage.MY_ORDERS },
                                onProfile = { toast("Settings screen comes next.") },
                                onUnpaired = {
                                    notice = REMOVED_NOTICE
                                    stage = Stage.PAIR
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
