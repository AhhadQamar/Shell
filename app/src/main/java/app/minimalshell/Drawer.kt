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
import androidx.compose.runtime.key
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
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.sin

private sealed interface Item {
    data class Calc(val result: String) : Item
    data class Frequent(val apps: List<AppEntry>) : Item
    data class Header(val letter: Char, val count: Int) : Item
    data class App(val app: AppEntry) : Item
    data class Web(val query: String) : Item
}

private class Model(val items: List<Item>, val index: Map<Char, Int>, val first: AppEntry?)

/**
 * 0 = starts with query, 1 = a word starts with it, 2 = contains it, 3 = matches word initials ("gm" -> Google Maps).
 * [s] is the already-folded query; the app's folded label, words and initials were computed once at load time.
 */
private fun matchRank(a: AppEntry, s: String): Int = when {
    a.folded.startsWith(s) -> 0
    a.words.any { it.startsWith(s) } -> 1
    a.folded.contains(s) -> 2
    a.initials.startsWith(s) -> 3
    else -> -1
}

/**
 * The list is drawn bottom-up (reverseLayout), so item 0 sits right above the search line, under the thumb.
 * That means: in search mode the best match is item 0; in browse mode the frequent apps are item 0.
 * Because a reversed list shows items bottom-to-top, each letter group is emitted last-app-first, header last,
 * so on screen it still reads: header, then apps A-Z downward.
 */
private fun buildModel(apps: List<AppEntry>, q: String, s: ShellViewModel): Model {
    val items = mutableListOf<Item>()
    val index = linkedMapOf<Char, Int>()
    val first: AppEntry?

    if (q.isEmpty()) {
        val visible = apps.filter { it.key !in s.hidden }
        if (s.showFrequent) {
            val f = frequentApps(visible, s)
            if (f.isNotEmpty()) items += Item.Frequent(f)
        }
        visible.groupBy { it.letter() }
            .toSortedMap(compareBy<Char> { if (it == '#') '\u0000' else it })
            .forEach { (letter, list) ->
                index[letter] = items.size // scrolling here puts the group's bottom edge at the bottom of the view
                list.asReversed().forEach { items += Item.App(it) }
                items += Item.Header(letter, list.size)
            }
        first = visible.firstOrNull()
    } else {
        Calc.eval(q)?.let { items += Item.Calc(it) }
        val folded = q.fold() // once per keystroke, not once per app
        val matches = apps
            .mapNotNull { a -> matchRank(a, folded).takeIf { it >= 0 }?.let { a to it } }
            .sortedWith(
                compareBy<Pair<AppEntry, Int>>(
                    { it.second },
                    { -(s.counts[it.first.key] ?: 0) },
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
 * Lets a downward drag that the list can't use (it is already at its far end, or has nothing to scroll)
 * pull the whole drawer down, and lets an upward drag push it back up before the list moves.
 * The list is bottom-anchored (A at the bottom, Z at the top), so dragging down scrolls toward Z first;
 * only when it runs out does the drawer start to close.
 */
private fun drawerNestedScroll(drawer: DrawerState) = object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (available.y < 0f && drawer.progress < 1f) Offset(0f, drawer.dragBy(available.y)) else Offset.Zero

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        // only a finger drag, never a fling that happens to reach the end of the list
        if (available.y > 0f && source == NestedScrollSource.UserInput) Offset(0f, drawer.dragBy(available.y)) else Offset.Zero

    override suspend fun onPreFling(available: Velocity): Velocity =
        if (drawer.progress < 1f) {
            drawer.settle(available.y) // the panel was pulled away from the list's drag, so it takes the fling too
            available
        } else {
            Velocity.Zero
        }
}

/**
 * Bottom-anchored panel: it is only as tall as its content (max 75% of the screen), so the wallpaper stays
 * visible above it. Swipe down anywhere, tap the clear area, or press back to close.
 */
@Composable
fun Drawer(apps: List<AppEntry>, state: ShellViewModel, accent: Color, drawer: DrawerState) {
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
    val appCount = model.items.count { it is Item.App }
    val countLabel = when {
        browsing -> "$appCount APPS"
        appCount == 0 -> "NO MATCH"
        appCount == 1 -> "1 MATCH"
        else -> "$appCount MATCHES"
    }

    fun open(app: AppEntry) {
        state.launch(app)
        drawer.snapClosed() // no closing animation underneath the app's own launch animation
    }

    fun copy(text: String) {
        copyToClipboard(ctx, text)
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
    }

    val showing = drawer.showing
    val dragState = rememberDraggableState { dy -> drawer.dragBy(dy) }
    val drawerScroll = remember(drawer) { drawerNestedScroll(drawer) }

    LaunchedEffect(drawer.wantsOpen) {
        if (drawer.wantsOpen) {
            // optional "type to search": wait until the drawer has finished sliding in, so the keyboard's own
            // animation doesn't compete with it
            if (state.autoKeyboard) {
                snapshotFlow { drawer.progress }.first { it >= 1f }
                runCatching { focusRequester.requestFocus() }
                keyboard?.show()
            }
        } else {
            keyboard?.hide()
            focus.clearFocus()
        }
    }

    // fully closed: forget the query and go back to the top, ready for next time
    LaunchedEffect(showing) {
        if (!showing) {
            query = ""
            listState.scrollToItem(0)
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
            // While parked off-screen the drawer must not catch touches meant for Home or show up for TalkBack.
            .then(if (showing) Modifier else Modifier.clearAndSetSemantics { })
            .then(
                if (showing) {
                    Modifier
                        .pointerInput(Unit) { detectTapGestures { drawer.close() } } // tap the clear area
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = dragState, // swipe down anywhere that isn't the list: the panel follows the finger
                            onDragStopped = { velocity -> drawer.settle(velocity) },
                        )
                } else {
                    Modifier
                }
            )
            .nestedScroll(drawerScroll) // swipe down on the list once it can't scroll any further
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                // slides in and out by translation only: no layout or recomposition while it moves
                .graphicsLayer { translationY = (1f - drawer.progress) * size.height }
                .onSizeChanged { drawer.travelPx = it.height.toFloat() }
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.3f to Sea.copy(0.78f),
                        1f to Sea.copy(0.94f),
                    )
                )
                .pointerInput(Unit) { detectTapGestures { } } // taps on the panel itself don't close it
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))
                // the height cap sits inside the insets padding, so a visible keyboard pushes the panel up
                // instead of eating into the room the list has
                .heightIn(max = panelMax)
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
                                is Item.Header -> SectionHeader(item.letter, item.count, accent)
                                is Item.App -> AppRow(
                                    item.app, state, accent,
                                    highlight = !browsing && item.app == model.first,
                                ) { open(item.app) }
                                is Item.Web -> WebRow(item.query, accent) {
                                    webSearch(ctx, item.query)
                                    drawer.snapClosed()
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

            // the "horizon" of the drawer: a gently rippling line, with the query below it
            TideLine(accent, Modifier.padding(end = 14.dp))
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
                        color = Sand, fontSize = 17.sp,
                        fontFamily = Mono, shadow = Lift,
                    ),
                    cursorBrush = SolidColor(accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        when {
                            calcResult != null -> copy(calcResult)
                            model.first != null -> open(model.first)
                            q.isNotEmpty() -> { webSearch(ctx, q); drawer.snapClosed() }
                        }
                    }),
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) {
                                Text(
                                    "search apps, or type a sum", style = ink(15.sp, Dim, mono = true),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(countLabel, style = ink(11.sp, Dim, mono = true, tracking = 1.sp))
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
private fun SectionHeader(letter: Char, count: Int, accent: Color) {
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(letter.toString(), style = ink(32.sp, accent, serif = true, italic = true))
        Spacer(Modifier.width(12.dp))
        Hairline(Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Text(count.toString(), style = ink(11.sp, Dim, mono = true)) // how many apps are in this section
    }
}

/** Text-first row. The best search match gets a 3dp accent tick instead of a filled background. */
@Composable
private fun AppRow(app: AppEntry, state: ShellViewModel, accent: Color, highlight: Boolean, onOpen: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val hidden = app.key in state.hidden
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
                    Sand.copy(if (hidden) 0.5f else 0.94f),
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
private fun FrequentRow(apps: List<AppEntry>, state: ShellViewModel, onOpen: (AppEntry) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("often", style = ink(15.sp, Dim, serif = true, italic = true))
            Spacer(Modifier.width(10.dp))
            Hairline(Modifier.weight(1f))
        }
        val swing = 4.dp // how far the thread (and so each pearl) moves up and down
        val wavelength = 120.dp
        Box(Modifier.fillMaxWidth()) {
            // a short thread behind the pearls, like the one on Home
            Canvas(Modifier.matchParentSize()) {
                val amp = swing.toPx()
                val wl = wavelength.toPx()
                val mid = 28.dp.toPx() // the centre of the 56dp pearls
                val step = 4.dp.toPx()
                val p = Path()
                p.moveTo(0f, mid)
                var x = step
                while (x < size.width) {
                    p.lineTo(x, mid + amp * sin(2.0 * PI * x / wl).toFloat())
                    x += step
                }
                drawPath(p, Sand.copy(alpha = 0.3f), style = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round))
            }
            Row(Modifier.fillMaxWidth()) {
                apps.forEachIndexed { i, app ->
                    key(app.key) {
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            Column(
                                Modifier
                                    .width(76.dp)
                                    .offset(y = swing * sin(2.0 * PI * ((76.dp * i + 38.dp) / wavelength)).toFloat())
                                    .pressable(onClick = { onOpen(app) }, onLongClick = { menu = true }),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Porthole(app, 56.dp, label = null, solid = true)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    app.label, style = ink(11.sp, Sand.copy(0.85f)),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                )
                            }
                            AppMenu(app, state, menu) { menu = false }
                        }
                    }
                }
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
        // the same thread as on Home, standing upright: one tick per letter, and while you drag, the thread fills
        // up to the letter you are on, with a bead
        Canvas(Modifier.fillMaxHeight().width(14.dp)) {
            val cx = size.width / 2f
            val amp = 3.5.dp.toPx()
            val wl = 90.dp.toPx()
            val step = 4.dp.toPx()
            fun x(y: Float): Float = cx + amp * sin(2.0 * PI * y / wl).toFloat()
            fun thread(y0: Float, y1: Float): Path {
                val p = Path()
                p.moveTo(x(y0), y0)
                var y = y0 + step
                while (y < y1) {
                    p.lineTo(x(y), y)
                    y += step
                }
                p.lineTo(x(y1), y1)
                return p
            }
            drawPath(thread(0f, size.height), Sand.copy(alpha = 0.3f), style = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round))
            val n = letters.size
            for (i in 0 until n) { // letter i, 0 = bottom
                val y = (n - i - 0.5f) / n * size.height
                drawLine(
                    if (i == active) accent else Sand.copy(alpha = 0.4f),
                    Offset(x(y) - 7.dp.toPx(), y), Offset(x(y) - 2.dp.toPx(), y),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            if (active >= 0) {
                val yActive = (n - active - 0.5f) / n * size.height
                drawPath(thread(yActive, size.height), accent, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
                val center = Offset(x(yActive), yActive)
                drawCircle(accent.copy(alpha = 0.28f), radius = 8.dp.toPx(), center = center)
                drawCircle(accent, radius = 3.6.dp.toPx(), center = center)
            }
        }
        Column(
            Modifier.fillMaxHeight().padding(end = 22.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.End,
        ) {
            for (i in letters.indices.reversed()) {
                Text(letters[i].toString(), style = ink(12.sp, if (i == active) accent else Dim, serif = true, italic = true))
            }
        }
        if (active >= 0) {
            Text(
                letters[active].toString(),
                style = ink(64.sp, accent, FontWeight.Light, serif = true, italic = true),
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
