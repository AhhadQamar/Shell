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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
    val l = label.fold()
    val s = q.fold()
    val words = l.split(' ', '-', '.', '_').filter { it.isNotEmpty() }
    return when {
        l.startsWith(s) -> 0
        words.any { it.startsWith(s) } -> 1
        l.contains(s) -> 2
        words.joinToString("") { it.take(1) }.startsWith(s) -> 3
        else -> -1
    }
}

/**
 * The list is drawn bottom-up (reverseLayout), so item 0 sits right above the search line, under the thumb.
 * That means: in search mode the best match is item 0; in browse mode the frequent apps are item 0.
 * Because a reversed list shows items bottom-to-top, each letter group is emitted last-app-first, header last,
 * so on screen it still reads: header, then apps A-Z downward.
 */
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
                index[letter] = items.size // scrolling here puts the group's bottom edge at the bottom of the view
                list.asReversed().forEach { items += Item.App(it) }
                items += Item.Header(letter)
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

/**
 * Bottom-anchored panel: it is only as tall as its content (max 75% of the screen), so the wallpaper stays
 * visible above it. Tap or swipe down on the clear area, or press back, to close.
 */
@Composable
fun Drawer(apps: List<AppEntry>, state: ShellState, accent: Color, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val panelMax = (LocalConfiguration.current.screenHeightDp * 0.75f).dp

    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val model = remember(apps, q, state.hidden, state.counts, state.showFrequent) {
        buildModel(apps, q, state)
    }
    val calcResult = (model.items.firstOrNull() as? Item.Calc)?.result
    val browsing = q.isEmpty()

    fun open(app: AppEntry) {
        state.launch(app)
        onClose()
    }

    fun copy(text: String) {
        copyToClipboard(ctx, text)
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
    }

    // optional "type to search": open the keyboard as soon as the drawer appears
    LaunchedEffect(Unit) {
        if (state.autoKeyboard) {
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
    }

    // a new query always starts from the best result, at the bottom
    LaunchedEffect(q) { listState.scrollToItem(0) }

    // dismiss the keyboard as soon as the user starts scrolling
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            keyboard?.hide()
            focus.clearFocus()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onClose() } }
            .pointerInput(Unit) {
                var d = 0f
                detectVerticalDragGestures(
                    onDragStart = { d = 0f },
                    onDragEnd = { if (d > 60.dp.toPx()) onClose() },
                    onVerticalDrag = { _, dy -> d += dy },
                )
            }
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = panelMax)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.3f to Color.Black.copy(0.72f),
                        1f to Color.Black.copy(0.9f),
                    )
                )
                .pointerInput(Unit) { detectTapGestures { } } // taps on the panel itself don't close it
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))
                .padding(start = 24.dp, end = 10.dp)
        ) {
            Box(Modifier.weight(1f, fill = false)) {
                Row(Modifier.fillMaxWidth()) {
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        contentPadding = PaddingValues(top = 36.dp, bottom = 6.dp, end = 4.dp),
                        modifier = Modifier.weight(1f).fadeTop(36.dp),
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
                                is Item.Header -> SectionHeader(item.letter, accent)
                                is Item.App -> AppRow(
                                    item.app, state, accent,
                                    highlight = !browsing && item.app == model.first,
                                ) { open(item.app) }
                                is Item.Web -> WebRow(item.query, accent) {
                                    webSearch(ctx, item.query)
                                    onClose()
                                }
                            }
                        }
                    }

                    if (browsing && model.index.isNotEmpty()) {
                        SpineRail(model.index.keys.toList(), accent) { letter ->
                            model.index[letter]?.let { target -> scope.launch { listState.scrollToItem(target) } }
                        }
                    }
                }
            }

            // the "horizon" of the drawer: an accent stub + hairline that the query sits on
            Row(Modifier.fillMaxWidth().padding(end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(28.dp).height(2.dp).background(accent))
                Hairline(Modifier.weight(1f))
            }
            Row(
                Modifier.fillMaxWidth().padding(end = 14.dp, top = 12.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("›", style = ink(22.sp, accent, mono = true))
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Color.White, fontSize = 17.sp,
                        fontFamily = Mono, shadow = Lift,
                    ),
                    cursorBrush = SolidColor(accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        when {
                            calcResult != null -> copy(calcResult)
                            model.first != null -> open(model.first)
                            q.isNotEmpty() -> { webSearch(ctx, q); onClose() }
                        }
                    }),
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) Text("search apps, or type a sum", style = ink(15.sp, Dim, mono = true))
                            inner()
                        }
                    },
                )
                if (query.isNotEmpty()) {
                    Text(
                        "×", style = ink(24.sp, Dim),
                        modifier = Modifier.clickable { query = "" }.padding(start = 8.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(letter: Char, accent: Color) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(letter.toString(), style = ink(12.sp, accent, mono = true, tracking = 1.sp))
        Spacer(Modifier.width(10.dp))
        Hairline(Modifier.weight(1f))
    }
}

/** Text-first row. The best search match gets a 3dp accent tick instead of a filled background. */
@Composable
private fun AppRow(app: AppEntry, state: ShellState, accent: Color, highlight: Boolean, onOpen: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val hidden = app.pkg in state.hidden
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .pressableRow(onClick = onOpen, onLongClick = { menu = true })
                .padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(22.dp)
                    .background(if (highlight) accent else Color.Transparent, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(12.dp))
            AppIcon(app, 34.dp, label = null)
            Spacer(Modifier.width(14.dp))
            Text(
                app.label,
                style = ink(
                    17.sp,
                    Color.White.copy(if (hidden) 0.5f else 0.92f),
                    if (highlight) FontWeight.Medium else FontWeight.Normal,
                ),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (hidden) Text("hidden", style = ink(11.sp, accent, mono = true))
        }
        AppMenu(app, state, menu) { menu = false }
    }
}

@Composable
private fun FrequentRow(apps: List<AppEntry>, state: ShellState, onOpen: (AppEntry) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp)) {
        apps.forEach { app ->
            var menu by remember { mutableStateOf(false) }
            Box {
                Column(
                    Modifier.width(76.dp).pressable(onClick = { onOpen(app) }, onLongClick = { menu = true }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AppIcon(app, 48.dp, label = null)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        app.label, style = ink(11.sp, Color.White.copy(0.85f)),
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
        Modifier.fillMaxWidth().pressableRow(onClick = onCopy).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("=", style = ink(26.sp, accent, mono = true))
        Spacer(Modifier.width(14.dp))
        Text(
            result, style = ink(32.sp, weight = FontWeight.Light),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Text("copy", style = ink(11.sp, Dim, mono = true))
    }
}

@Composable
private fun WebRow(query: String, accent: Color, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressableRow(onClick = onOpen).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(49.dp), contentAlignment = Alignment.Center) {
            Text("↗", style = ink(20.sp, accent, mono = true))
        }
        Spacer(Modifier.width(14.dp))
        Text(
            "search the web for \u201C$query\u201D",
            style = ink(15.sp, Dim), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The drawer's spine: a 2dp line on the right edge with tiny letters beside it, A at the bottom (matching the
 * bottom-up list). The visible part is thin, but the touch target is 40dp wide. While scrubbing, the active
 * letter is shown large to the left of the thumb.
 */
@Composable
private fun SpineRail(letters: List<Char>, accent: Color, onJump: (Char) -> Unit) {
    var height by remember { mutableFloatStateOf(1f) }
    var active by remember { mutableIntStateOf(-1) } // index into letters; 0 = bottom
    val haptic = LocalHapticFeedback.current
    val jumpTo by rememberUpdatedState(onJump)

    fun jump(y: Float) {
        val i = ((height - y) / height * letters.size).toInt().coerceIn(0, letters.lastIndex)
        if (i != active) {
            active = i
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            jumpTo(letters[i])
        }
    }

    Box(
        Modifier
            .fillMaxHeight()
            .width(40.dp)
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
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(Modifier.fillMaxHeight().width(2.dp).background(Hair))
        Column(
            Modifier.fillMaxHeight().padding(end = 8.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.End,
        ) {
            for (i in letters.indices.reversed()) {
                Text(letters[i].toString(), style = ink(10.sp, if (i == active) accent else Dim, mono = true))
            }
        }
        if (active >= 0) {
            Text(
                letters[active].toString(),
                style = ink(48.sp, accent, FontWeight.Light, mono = true),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset {
                        IntOffset(
                            -56.dp.roundToPx(),
                            ((letters.size - active - 0.5f) / letters.size * height).toInt() - 36.dp.roundToPx(),
                        )
                    },
            )
        }
    }
}
