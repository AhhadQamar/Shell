package app.minimalshell

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private sealed interface Item {
    data class Calc(val result: String) : Item
    data class Frequent(val apps: List<AppEntry>) : Item
    data class Header(val letter: Char) : Item
    data class App(val app: AppEntry) : Item
    data class Web(val query: String) : Item
}

private class Model(val items: List<Item>, val index: Map<Char, Int>, val first: AppEntry?)

/** 0 = starts with query, 1 = a word starts with it, 2 = contains it, 3 = matches word initials ("gm" -> Google Maps). */
private fun matchRank(label: String, q: String): Int {
    val l = label.lowercase()
    val s = q.lowercase()
    val words = l.split(' ', '-', '.', '_').filter { it.isNotEmpty() }
    return when {
        l.startsWith(s) -> 0
        words.any { it.startsWith(s) } -> 1
        l.contains(s) -> 2
        words.joinToString("") { it.take(1) }.startsWith(s) -> 3
        else -> -1
    }
}

private fun buildModel(apps: List<AppEntry>, q: String, s: ShellState): Model {
    val items = mutableListOf<Item>()
    val index = linkedMapOf<Char, Int>()
    val first: AppEntry?

    if (q.isEmpty()) {
        val visible = apps.filter { it.pkg !in s.hidden }
        if (s.showFrequent) {
            val f = frequentApps(visible, s)
            if (f.isNotEmpty()) items += Item.Frequent(f)
        }
        visible.groupBy { it.letter() }
            .toSortedMap(compareBy<Char> { if (it == '#') '\u0000' else it })
            .forEach { (letter, list) ->
                index[letter] = items.size
                items += Item.Header(letter)
                list.forEach { items += Item.App(it) }
            }
        first = visible.firstOrNull()
    } else {
        Calc.eval(q)?.let { items += Item.Calc(it) }
        val matches = apps
            .mapNotNull { a -> matchRank(a.label, q).takeIf { it >= 0 }?.let { a to it } }
            .sortedWith(
                compareBy<Pair<AppEntry, Int>>(
                    { it.second },
                    { -(s.counts[it.first.pkg] ?: 0) },
                    { it.first.label.lowercase() },
                )
            )
            .map { it.first }
        matches.forEach { items += Item.App(it) }
        items += Item.Web(q)
        first = matches.firstOrNull()
    }
    return Model(items, index, first)
}

@Composable
fun Drawer(apps: List<AppEntry>, state: ShellState, accent: Color, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val model = remember(apps, q, state.hidden, state.counts, state.showFrequent) {
        buildModel(apps, q, state)
    }
    val calcResult = (model.items.firstOrNull() as? Item.Calc)?.result

    fun open(app: AppEntry) {
        state.launch(app)
        onClose()
    }

    fun copy(text: String) {
        copyToClipboard(ctx, text)
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
    }

    // dismiss the keyboard as soon as the user starts scrolling
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            keyboard?.hide()
            focus.clearFocus()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xF0101214))
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))
            .padding(start = 20.dp, end = 8.dp)
    ) {
        // handle: tap or swipe down to close
        Box(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .pointerInput(Unit) {
                    var d = 0f
                    detectVerticalDragGestures(
                        onDragStart = { d = 0f },
                        onDragEnd = { if (d > 60.dp.toPx()) onClose() },
                        onVerticalDrag = { _, dy -> d += dy },
                    )
                }
                .clickable { onClose() },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.width(36.dp).height(4.dp).background(Color.White.copy(0.25f), RoundedCornerShape(2.dp)))
        }

        // search
        Row(
            Modifier
                .fillMaxWidth()
                .padding(end = 12.dp)
                .background(Color.White.copy(0.06f), RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("›", color = accent, fontFamily = Mono, fontSize = 20.sp)
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp, fontFamily = Mono),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    when {
                        calcResult != null -> copy(calcResult)
                        model.first != null -> open(model.first)
                        q.isNotEmpty() -> { webSearch(ctx, q); onClose() }
                    }
                }),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) Text("search apps, or type a sum", color = Dim, fontFamily = Mono, fontSize = 15.sp)
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                Text(
                    "×", color = Dim, fontSize = 22.sp,
                    modifier = Modifier.clickable { query = "" }.padding(start = 12.dp),
                )
            }
        }

        Row(Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(end = 4.dp, bottom = 24.dp),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                items(
                    count = model.items.size,
                    key = { i ->
                        when (val it = model.items[i]) {
                            is Item.Calc -> "calc"
                            is Item.Frequent -> "freq"
                            is Item.Header -> "h${it.letter}"
                            is Item.App -> it.app.component.flattenToString()
                            is Item.Web -> "web"
                        }
                    },
                ) { i ->
                    when (val item = model.items[i]) {
                        is Item.Calc -> CalcRow(item.result, accent) { copy(item.result) }
                        is Item.Frequent -> FrequentRow(item.apps, state) { open(it) }
                        is Item.Header -> Text(
                            item.letter.toString(),
                            color = accent, fontFamily = Mono, fontSize = 13.sp,
                            modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp),
                        )
                        is Item.App -> AppRow(
                            item.app, state, accent,
                            highlight = q.isNotEmpty() && item.app == model.first,
                        ) { open(item.app) }
                        is Item.Web -> WebRow(item.query, accent) {
                            webSearch(ctx, item.query)
                            onClose()
                        }
                    }
                }
            }

            if (q.isEmpty() && model.index.isNotEmpty()) {
                AlphaRail(model.index.keys.toList(), accent) { letter ->
                    model.index[letter]?.let { target -> scope.launch { listState.scrollToItem(target) } }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppEntry, state: ShellState, accent: Color, highlight: Boolean, onOpen: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val hidden = app.pkg in state.hidden
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .pressable(onClick = onOpen, onLongClick = { menu = true })
                .clip(RoundedCornerShape(14.dp))
                .background(if (highlight) accent.copy(0.14f) else Color.Transparent)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(app, 40.dp)
            Spacer(Modifier.width(16.dp))
            Text(
                app.label,
                color = Color.White.copy(if (hidden) 0.5f else 1f),
                fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (hidden) Text("hidden", color = accent, fontFamily = Mono, fontSize = 11.sp)
        }
        AppMenu(app, state, menu) { menu = false }
    }
}

@Composable
private fun FrequentRow(apps: List<AppEntry>, state: ShellState, onOpen: (AppEntry) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(Color.White.copy(0.05f), RoundedCornerShape(18.dp))
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        apps.forEach { app ->
            var menu by remember { mutableStateOf(false) }
            Box {
                Column(
                    Modifier.width(72.dp).pressable(onClick = { onOpen(app) }, onLongClick = { menu = true }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AppIcon(app, 48.dp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        app.label, color = Color.White.copy(0.8f), fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                }
                AppMenu(app, state, menu) { menu = false }
            }
        }
    }
}

@Composable
private fun CalcRow(result: String, accent: Color, onCopy: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .pressable(onClick = onCopy)
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(0.14f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("=", color = accent, fontFamily = Mono, fontSize = 22.sp)
        Spacer(Modifier.width(12.dp))
        Text(
            result, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Light,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Text("tap to copy", color = Dim, fontFamily = Mono, fontSize = 11.sp)
    }
}

@Composable
private fun WebRow(query: String, accent: Color, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick = onOpen)
            .clip(RoundedCornerShape(14.dp))
            .padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
            Text("↗", color = accent, fontFamily = Mono, fontSize = 20.sp)
        }
        Spacer(Modifier.width(16.dp))
        Text(
            "search the web for \u201C$query\u201D",
            color = Dim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A-Z fast scroller: tap or drag along the right edge to jump between sections. */
@Composable
private fun AlphaRail(letters: List<Char>, accent: Color, onJump: (Char) -> Unit) {
    var height by remember { mutableFloatStateOf(1f) }
    var active by remember { mutableIntStateOf(-1) }
    val haptic = LocalHapticFeedback.current
    val jumpTo by rememberUpdatedState(onJump)

    fun jump(y: Float) {
        val i = (y / height * letters.size).toInt().coerceIn(0, letters.lastIndex)
        if (i != active) {
            active = i
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            jumpTo(letters[i])
        }
    }

    Column(
        Modifier
            .fillMaxHeight()
            .width(24.dp)
            .onSizeChanged { height = it.height.toFloat().coerceAtLeast(1f) }
            .pointerInput(letters) {
                detectTapGestures(onPress = { o ->
                    jump(o.y)
                    tryAwaitRelease()
                    active = -1
                })
            }
            .pointerInput(letters) {
                detectVerticalDragGestures(
                    onDragStart = { o -> jump(o.y) },
                    onDragEnd = { active = -1 },
                    onDragCancel = { active = -1 },
                    onVerticalDrag = { c, _ -> jump(c.position.y) },
                )
            },
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        letters.forEachIndexed { i, l ->
            Text(
                l.toString(),
                fontSize = 11.sp, fontFamily = Mono,
                color = if (i == active) accent else Dim,
                fontWeight = if (i == active) FontWeight.Medium else FontWeight.Normal,
            )
        }
    }
}
