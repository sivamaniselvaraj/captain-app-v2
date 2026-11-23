package com.octanovus

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.octanovus.data.AppPrefs
import com.octanovus.data.NotificationHub
import com.octanovus.data.Session
import com.octanovus.data.TableInfo
import com.octanovus.service.EventService
import com.octanovus.ui.CaptainTheme
import com.octanovus.ui.CustomerScreen
import com.octanovus.ui.MenuScreen
import com.octanovus.ui.MyOrdersScreen
import com.octanovus.ui.NotificationsScreen
import com.octanovus.ui.PairScreen
import com.octanovus.ui.SetPinScreen
import com.octanovus.ui.SplashScreen
import com.octanovus.ui.TablesScreen
import com.octanovus.ui.ViewOrderScreen
import com.octanovus.viewmodel.PlacedOrder
import com.octanovus.ui.OrderSentScreen
import com.octanovus.viewmodel.CartViewModel
import com.octanovus.viewmodel.SplashViewModel
import com.octanovus.viewmodel.TablesViewModel

/** The screens so far, in order for a new phone. */
enum class Stage { PAIR, SET_PIN, PIN_LOGIN, TABLES, MENU, VIEW_ORDER, ORDER_SENT, MY_ORDERS, CUSTOMER, NOTIFICATIONS }

private const val PIN_TIMEOUT_NOTICE = "You were signed out after being idle. Enter your PIN to continue."

private const val REMOVED_NOTICE = "This phone was removed from the desktop. Pair it again to continue."

class MainActivity : ComponentActivity() {

    companion object {
        /** Extra on the intent of a phone alert: the table number to open. */
        const val EXTRA_TABLE = "tableNumber"
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    /** Table number from a tapped phone alert. The screens open that table once they can. */
    private val pendingTable = mutableStateOf<String?>(null)

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* alerts stay off if refused */ }

    private fun startEventService() {
        ContextCompat.startForegroundService(this, Intent(this, EventService::class.java))
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun readTableExtra(intent: Intent?) {
        intent?.getStringExtra(EXTRA_TABLE)?.let { pendingTable.value = it }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readTableExtra(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationHub.init(this)
        readTableExtra(intent)

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
                        // Where the Customer details screen returns to: the menu at the start of an
                        // order, or View order when the waiter is changing the details.
                        var customerReturn by remember { mutableStateOf(Stage.MENU) }
                        // Notifications opens from the burger menu or from My orders, and goes back there.
                        var notificationsBack by remember { mutableStateOf(Stage.MY_ORDERS) }
                        val tablesVm: TablesViewModel = viewModel()
                        val splashVm: SplashViewModel = viewModel()
                        var pinNotice by remember { mutableStateOf<String?>(null) }

                        // The desktop asked for the PIN again (idle too long): go to PIN login with a message.
                        val pinExpired by Session.expired.collectAsState()
                        LaunchedEffect(pinExpired) {
                            if (!pinExpired) return@LaunchedEffect
                            Session.acknowledgeExpired()
                            if (stage !in listOf(Stage.PAIR, Stage.SET_PIN, Stage.PIN_LOGIN)) {
                                splashVm.resetForRelogin()
                                pinNotice = PIN_TIMEOUT_NOTICE
                                stage = Stage.PIN_LOGIN
                            }
                        }
                        val tablesState by tablesVm.state.collectAsState()

                        // Opens a table the same way a tap on the table grid does.
                        fun openTable(table: TableInfo) {
                            selectedTable = table
                            customerReturn = Stage.MENU
                            stage = if (cartVm.state.value.needsCustomerStep(table)) Stage.CUSTOMER
                            else Stage.MENU
                        }

                        // Once the waiter is signed in: ask for alert permission and start the live connection.
                        val signedIn = stage !in listOf(Stage.PAIR, Stage.SET_PIN, Stage.PIN_LOGIN)
                        LaunchedEffect(signedIn) {
                            if (signedIn) {
                                askNotificationPermission()
                                startEventService()
                            }
                        }

                        // A table asked for by a phone alert or a tap in Notifications.
                        val wanted = pendingTable.value
                        LaunchedEffect(wanted, stage, tablesState.tables) {
                            if (wanted == null) return@LaunchedEffect
                            when (stage) {
                                // Not signed in yet: keep it and open after sign-in.
                                Stage.PAIR, Stage.SET_PIN, Stage.PIN_LOGIN -> Unit
                                // The waiter is in the middle of an order: do not pull them away.
                                Stage.MENU, Stage.CUSTOMER, Stage.VIEW_ORDER, Stage.ORDER_SENT ->
                                    pendingTable.value = null
                                // Go to the grid first so the tables load, then open the table.
                                Stage.MY_ORDERS, Stage.NOTIFICATIONS -> stage = Stage.TABLES
                                Stage.TABLES -> {
                                    val table = tablesState.tables.firstOrNull { it.number == wanted }
                                    if (table != null) {
                                        pendingTable.value = null
                                        openTable(table)
                                    } else if (tablesState.loaded) {
                                        pendingTable.value = null
                                    }
                                }
                            }
                        }

                        when (stage) {
                            Stage.NOTIFICATIONS -> NotificationsScreen(
                                onBack = { stage = notificationsBack },
                                onOpenTable = { number -> pendingTable.value = number },
                            )
                            Stage.PAIR -> PairScreen(
                                onPaired = { notice = null; stage = Stage.SET_PIN },
                                notice = notice,
                            )
                            Stage.SET_PIN -> SetPinScreen(onDone = { stage = Stage.PIN_LOGIN })
                            Stage.PIN_LOGIN -> SplashScreen(
                                notice = pinNotice,
                                onSignedIn = { pinNotice = null; stage = Stage.TABLES },
                                onUnpaired = {
                                    notice = REMOVED_NOTICE
                                    stage = Stage.PAIR
                                },
                            )
                            Stage.CUSTOMER -> selectedTable?.let { table ->
                                CustomerScreen(
                                    table = table,
                                    onDone = { stage = customerReturn },
                                    onBack = {
                                        stage = if (customerReturn == Stage.VIEW_ORDER) Stage.VIEW_ORDER
                                        else Stage.TABLES
                                    },
                                    fromViewOrder = customerReturn == Stage.VIEW_ORDER,
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
                                    onEditCustomer = {
                                        customerReturn = Stage.VIEW_ORDER
                                        stage = Stage.CUSTOMER
                                    },
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
                                onNotifications = {
                                    notificationsBack = Stage.MY_ORDERS
                                    stage = Stage.NOTIFICATIONS
                                },
                                onUnpaired = {
                                    notice = REMOVED_NOTICE
                                    stage = Stage.PAIR
                                },
                            )
                            Stage.TABLES -> TablesScreen(
                                // Customer details are asked for a new order on a table with no orders.
                                onTableSelected = ::openTable,
                                onMyOrders = { stage = Stage.MY_ORDERS },
                                onNewTakeaway = { deliverTo ->
                                    // Takeaway reuses the menu and View order. The table it is for goes in the order notes.
                                    selectedTable = TableInfo.takeaway(deliverTo)
                                    customerReturn = Stage.MENU
                                    stage = Stage.MENU
                                },
                                onNotifications = {
                                    notificationsBack = Stage.TABLES
                                    stage = Stage.NOTIFICATIONS
                                },
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
