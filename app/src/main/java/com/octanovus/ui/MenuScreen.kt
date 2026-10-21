package com.octanovus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.octanovus.data.MenuItem
import com.octanovus.data.TableInfo
import com.octanovus.viewmodel.CartState
import com.octanovus.viewmodel.CartViewModel
import com.octanovus.viewmodel.MenuState
import com.octanovus.viewmodel.MenuViewModel


/**
 * Menu for the selected table: category chips, a search box, items with a quantity stepper,
 * and a "View order" bar once something is in the cart. Follows the light mock.
 * The menu is refreshed every minute while the screen is visible.
 */
@Composable
fun MenuScreen(
    table: TableInfo,
    onBack: () -> Unit,
    onViewOrder: () -> Unit,
    onUnpaired: () -> Unit,
    menuVm: MenuViewModel = viewModel(),
    cartVm: CartViewModel = viewModel(),
) {
    val menu by menuVm.state.collectAsState()
    val cart by cartVm.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusManager = LocalFocusManager.current

    LaunchedEffect(table.tableId) { cartVm.startFor(table) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { menuVm.pollLoop() }
    }
    LaunchedEffect(menu.unauthorised) {
        if (menu.unauthorised) onUnpaired()
    }
    BackHandler(onBack = onBack)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Canvas),
    ) {
        Column(Modifier.fillMaxSize()) {
            TableHeader("Restaurant menu", table.number, onBack)
            SearchBox(
                query = menu.query,
                onChange = menuVm::onQueryChange,
                onSearch = { focusManager.clearFocus() },
            )

            if (menu.stale) {
                Notice("Menu may be out of date.")
            }
            menu.error?.let { Notice(if (menu.loaded) "$it Showing the last menu." else it, error = true) }

            when {
                !menu.loaded && menu.error == null -> CenterMessage("Loading menu...")
                !menu.loaded -> Box(Modifier.weight(1f))
                menu.items.isEmpty() -> CenterMessage("The menu is empty. Add items on the desktop.")
                else -> {
                    if (!menu.searching) CategoryChips(menu, onSelect = menuVm::selectCategory)
                    ItemList(
                        menu = menu,
                        cart = cart,
                        onAdd = cartVm::add,
                        onRemove = cartVm::remove,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (cart.totalQuantity > 0) {
            ViewOrderBar(
                count = cart.totalQuantity,
                onClick = onViewOrder,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
internal fun TableHeader(title: String, tableNumber: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIconButton("‹", "Back", onBack)
        Text(
            title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .height(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Ink)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Table $tableNumber", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SearchBox(query: String, onChange: (String) -> Unit, onSearch: () -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("Search items") },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White,
            focusedBorderColor = Ink,
            unfocusedBorderColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

@Composable
private fun Notice(text: String, error: Boolean = false) {
    Text(
        text,
        color = if (error) BadRed else Muted,
        fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

@Composable
private fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun CategoryChips(menu: MenuState, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        (listOf(MenuState.ALL_CATEGORIES) + menu.categories).forEach { category ->
            val selected = category == menu.activeCategory
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (selected) Ink else Color.White)
                    .clickable { onSelect(category) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (category == MenuState.ALL_CATEGORIES) "All" else category,
                    color = if (selected) Color.White else Ink,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ItemList(
    menu: MenuState,
    cart: CartState,
    onAdd: (MenuItem) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown = menu.visibleItems
    if (shown.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                if (menu.searching) "No items match \"${menu.query.trim()}\"." else "No items in this category.",
                color = Muted,
                fontSize = 14.sp,
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        // Space at the bottom so the last item is not hidden behind the View order bar.
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // With "All" or a search, items are grouped under their category name.
        val groups = if (menu.groupedByCategory) shown.groupBy { it.categoryName } else mapOf("" to shown)
        groups.forEach { (category, group) ->
            if (menu.groupedByCategory) {
                item(key = "category-$category") {
                    Text(
                        category,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Muted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            items(group, key = { it.id }) { item ->
                ItemRow(
                    item = item,
                    quantity = cart.quantityOf(item.id),
                    canAdd = cart.canAdd(item.id),
                    onAdd = { onAdd(item) },
                    onRemove = { onRemove(item.id) },
                )
            }
        }
    }
}

@Composable
private fun ItemRow(
    item: MenuItem,
    quantity: Int,
    canAdd: Boolean,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Placeholder for the optional photo: the first letter on a pale circle.
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color(0xFFE6E6E1)),
            contentAlignment = Alignment.Center,
        ) {
            Text(item.name.take(1).uppercase(), color = Muted, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VegMarker(item.isVeg)
                Text(
                    item.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Text(
                listOfNotNull(formatMoney(item.price), item.taxLabel).joinToString(" "),
                fontSize = 12.sp,
                color = Muted,
                modifier = Modifier.padding(top = 2.dp),
            )
            item.description?.let {
                Text(
                    it,
                    fontSize = 12.sp,
                    color = Muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StepButton("−", "Remove one ${item.name}", filled = false, enabled = quantity > 0, onClick = onRemove)
            Text(
                quantity.toString(),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 16.dp),
            )
            StepButton("+", "Add one ${item.name}", filled = true, enabled = canAdd, onClick = onAdd)
        }
    }
}

@Composable
internal fun StepButton(glyph: String, description: String, filled: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = CircleShape
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(shape)
            .then(
                if (filled) Modifier.background(if (enabled) Ink else Dot)
                else Modifier
                    .background(Color.White)
                    .border(1.dp, if (enabled) Ink else Dot, shape)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            fontSize = 16.sp,
            color = if (filled) Color.White else if (enabled) Ink else Dot,
        )
    }
}

/** Green dot in a green square for veg, red for non-veg. */
@Composable
internal fun VegMarker(isVeg: Boolean) {
    val color = if (isVeg) OkGreen else BadRed
    Box(
        modifier = Modifier
            .size(14.dp)
            .border(1.5.dp, color, RoundedCornerShape(3.dp))
            .semantics { contentDescription = if (isVeg) "Vegetarian" else "Non-vegetarian" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}

@Composable
private fun ViewOrderBar(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Ink)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            if (count == 1) "1 item selected" else "$count items selected",
            color = Color.White,
            fontSize = 14.sp,
        )
        Text("View order", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
