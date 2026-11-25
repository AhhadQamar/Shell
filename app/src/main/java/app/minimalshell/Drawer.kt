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
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.border
import kotlin.math.roundToInt
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.LazyListState

private sealed interface Item {
    data class Calc(val result: String) : Item
    data class Frequent(val apps: List<AppEntry>) : Item
    data class Header(val letter: Char, val count: Int) : Item
    data class App(val app: AppEntry) : Item
    data class Web(val query: String) : Item
    data class Setting(val entry: SettingsEntry) : Item
    data class BangSearch(val bang: SearchBang, val query: String) : Item
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
 * What the list shows, in the order it is drawn.
 * Browsing: the frequent apps first, then each letter as a header followed by its apps, so it reads A to Z downward.
 * Searching: the results, best first. The list is drawn bottom-up while searching (see reverseLayout in the Drawer),
 * so item 0, the best match, sits right above the search line, under the thumb.
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
                index[letter] = items.size // scrolling here puts the section's header at the top of the view
                items += Item.Header(letter, list.size)
                list.forEach { items += Item.App(it) }
            }
        first = visible.firstOrNull()
    } else {
        Calc.eval(q)?.let { items += Item.Calc(it) }
        SearchBangs.parse(q)?.let { (bang, rest) -> items += Item.BangSearch(bang, rest) }
        val folded = q.fold() // once per keystroke, not once per app
        // hidden apps still show up in search, unless the user asked for them not to
        val pool = if (s.hideHiddenInSearch) apps.filter { it.key !in s.hidden } else apps
        val matches = pool
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
        SystemSettings.match(folded).forEach { items += Item.Setting(it) }
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

    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val model = remember(apps, q, state.hidden, state.counts, state.showFrequent, state.hideHiddenInSearch) {
        buildModel(apps, q, state)
    }
    val calcResult = (model.items.firstOrNull() as? Item.Calc)?.result
    val bangItem = model.items.firstOrNull { it is Item.BangSearch } as? Item.BangSearch
    val firstSetting = model.items.firstOrNull { it is Item.Setting } as? Item.Setting
    val engine = SearchEngines.byId(state.searchEngine)
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

    fun openSetting(entry: SettingsEntry) {
        openSettingsPage(ctx, entry.action)
        drawer.snapClosed()
    }

    fun runBang(item: Item.BangSearch) {
        searchUrl(ctx, item.bang.url, item.query)
        drawer.snapClosed()
    }

    fun searchWeb(text: String) {
        webSearch(ctx, text, engine)
        drawer.snapClosed()
    }

    // optional "instant launch": when exactly one app matches and nothing else does, open it after a short pause
    val soleApp = if (!browsing && calcResult == null && bangItem == null && firstSetting == null && appCount == 1) model.first else null
    LaunchedEffect(soleApp?.key, q) {
        if (state.instantLaunch && soleApp != null && q.trim().length >= 2) {
            delay(600) // typing on cancels this, so a longer name can still be finished
            open(soleApp)
        }
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
        // a full-screen page that rises over Home, on a calm solid surface: nothing sits behind the words to compete
        // with them, and Home's clock and apps never show through the list.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                // slides in and out by translation only: no layout or recomposition while it moves
                .graphicsLayer { translationY = (1f - drawer.progress) * size.height }
                .onSizeChanged { drawer.travelPx = it.height.toFloat() }
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { } } // taps on the page itself don't close it
        ) {
            Box(Modifier.matchParentSize().background(Sea.copy(alpha = if (state.shellBackdrop) 1f else 0.94f)))
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))
                .padding(start = 28.dp, end = 12.dp, top = 8.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(end = 14.dp, top = 12.dp, bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (browsing) "ALL APPS" else "RESULTS", style = ink(11.sp, Dim, mono = true, tracking = 2.sp))
                Text(countLabel, style = ink(11.sp, Dim, mono = true, tracking = 1.sp))
            }
            Box(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth()) {
                    LazyColumn(
                        state = listState,
                        // browsing reads A to Z from the top; search results stack up from the bottom, so the best
                        // match is the one next to your thumb and the search line
                        reverseLayout = !browsing,
                        contentPadding = PaddingValues(top = 36.dp, bottom = 12.dp, end = 4.dp),
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
                                    is Item.Setting -> "set:${it.entry.action}"
                                    is Item.BangSearch -> "bang"
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
                                is Item.Web -> WebRow(item.query, engine.name, accent) { searchWeb(item.query) }
                                is Item.Setting -> SettingRow(item.entry, accent) { openSetting(item.entry) }
                                is Item.BangSearch -> BangRow(item.bang, item.query, accent) { runBang(item) }
                            }
                        }
                    }

                    if (browsing && model.index.isNotEmpty()) {
                        Scrubber(model.index.keys.toList(), listState, accent) { letter ->
                            model.index[letter]?.let { target -> scope.launch { listState.scrollToItem(target) } }
                        }
                    }
                }
            }

            // the query: a bare line at the bottom, within thumb reach
            Spacer(Modifier.height(6.dp))
            Column(Modifier.fillMaxWidth().padding(end = 14.dp, bottom = 20.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("›", style = ink(22.sp, accent, mono = true))
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    // While the drawer is parked off-screen the field must not exist as far as focus is concerned,
                    // otherwise it can keep focus and make Android pop the keyboard up over Home.
                    enabled = showing,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Sand, fontSize = 16.sp,
                        shadow = Lift,
                    ),
                    cursorBrush = SolidColor(accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        when {
                            calcResult != null -> copy(calcResult)
                            bangItem != null -> runBang(bangItem)
                            model.first != null -> open(model.first)
                            firstSetting != null -> openSetting(firstSetting.entry)
                            q.isNotEmpty() -> searchWeb(q)
                        }
                    }),
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) {
                                Text(
                                    "apps, settings, sums, \u201Cy cats\u201D", style = ink(14.sp, Dim),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
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
                Hairline(Modifier.fillMaxWidth())
            }
        }
        }
    }
}

@Composable
private fun SectionHeader(letter: Char, accent: Color) {
    Row(Modifier.fillMaxWidth().padding(top = 30.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(letter.toString(), style = ink(12.sp, accent, mono = true, tracking = 2.sp))
        Spacer(Modifier.width(12.dp))
        Hairline(Modifier.weight(1f))
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
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(22.dp)
                    .background(if (highlight) accent else Color.Transparent, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(12.dp))
            if (state.drawerIcons) {
                ShellIcon(app, 40.dp, label = null)
                Spacer(Modifier.width(14.dp))
            }
            Text(
                app.label,
                style = ink(
                    20.sp,
                    Sand.copy(if (hidden) 0.5f else 0.96f),
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
            Text("OFTEN", style = ink(11.sp, Dim, mono = true, tracking = 2.sp))
            Spacer(Modifier.width(10.dp))
            Hairline(Modifier.weight(1f))
        }
        if (state.drawerIcons) {
            Row(Modifier.fillMaxWidth()) {
                apps.forEach { app ->
                    key(app.key) {
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            Column(
                                Modifier
                                    .width(76.dp)
                                    .pressable(onClick = { onOpen(app) }, onLongClick = { menu = true }),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                ShellIcon(app, 56.dp, label = null)
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
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                apps.forEach { app ->
                    key(app.key) {
                        var menu by remember { mutableStateOf(false) }
                        Box(Modifier.weight(1f, fill = false)) {
                            Text(
                                app.label, style = ink(18.sp, Sand.copy(0.96f), FontWeight.Normal),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.pressable(onClick = { onOpen(app) }, onLongClick = { menu = true }),
                            )
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
private fun WebRow(query: String, engineName: String, accent: Color, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressableRow(onClick = onOpen).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(49.dp), contentAlignment = Alignment.Center) {
            Text("\u2197", style = ink(20.sp, accent, mono = true))
        }
        Spacer(Modifier.width(14.dp))
        Text(
            "search $engineName for \u201C$query\u201D",
            style = ink(15.sp, Dim), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A system settings screen that matched the query, e.g. "wifi". */
@Composable
private fun SettingRow(entry: SettingsEntry, accent: Color, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressableRow(onClick = onOpen).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(49.dp), contentAlignment = Alignment.Center) {
            Text("\u203A", style = ink(22.sp, accent, mono = true))
        }
        Spacer(Modifier.width(14.dp))
        Text(
            entry.label, style = ink(18.sp),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Text("SETTINGS", style = ink(11.sp, Dim, mono = true, tracking = 1.sp))
    }
}

/** A bang such as "y cats": shows the prefix that was recognised and what it will search. */
@Composable
private fun BangRow(bang: SearchBang, query: String, accent: Color, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressableRow(onClick = onOpen).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(49.dp), contentAlignment = Alignment.Center) {
            Text(bang.key, style = ink(16.sp, accent, mono = true))
        }
        Spacer(Modifier.width(14.dp))
        Text(
            "${bang.name} \u201C$query\u201D",
            style = ink(16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A single thin line down the right edge that shows where you are in the list. Touch it and drag to scrub: the list
 * jumps section by section and a big letter shows next to your finger. When idle it is almost invisible.
 */
@Composable
private fun Scrubber(letters: List<Char>, listState: LazyListState, accent: Color, onJump: (Char) -> Unit) {
    var height by remember { mutableFloatStateOf(1f) }
    var active by remember { mutableIntStateOf(-1) } // index into letters while a finger is down; 0 = top
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

    Box(
        Modifier
            .fillMaxHeight()
            .width(36.dp)
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
        // the track, and a short handle at your position in the list (read while drawing, so scrolling only redraws)
        Canvas(Modifier.fillMaxHeight().width(6.dp)) {
            val touching = active >= 0
            val x = size.width - 1.dp.toPx()
            drawLine(
                Sand.copy(alpha = if (touching) 0.30f else 0.12f),
                Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx(),
            )
            val total = listState.layoutInfo.totalItemsCount.coerceAtLeast(1)
            val handle = 36.dp.toPx()
            val y = listState.firstVisibleItemIndex / total.toFloat() * (size.height - handle)
            drawLine(
                accent.copy(alpha = if (touching) 1f else 0.7f),
                Offset(x, y), Offset(x, y + handle),
                strokeWidth = (if (touching) 3 else 2).dp.toPx(), cap = StrokeCap.Round,
            )
        }
        if (active >= 0) {
            Text(
                letters[active].toString(),
                style = ink(64.sp, accent, FontWeight.Light),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset {
                        IntOffset(
                            -48.dp.roundToPx(),
                            ((active + 0.5f) / letters.size * height).toInt() - 36.dp.roundToPx(),
                        )
                    },
            )
        }
    }
}
