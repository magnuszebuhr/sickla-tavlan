package se.sickla.tavlan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val Bg = Color(0xFF101214)
private val Casing = Color(0xFF2A2E33)
private val LedBlack = Color(0xFF050505)
private val Led = Color(0xFFFFA51F)
private val LedDim = Color(0xFF7A4E0E)
private val Dim = Color(0xFF8A96A6)

private val LedText = TextStyle(
    color = Led,
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 17.sp,
    letterSpacing = 0.5.sp,
    shadow = Shadow(color = Led.copy(alpha = 0.7f), offset = Offset.Zero, blurRadius = 10f),
)

class MainActivity : ComponentActivity() {
    private val vm: BoardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Bg, primary = Led)) {
                App(vm)
            }
        }
    }

    override fun onStart() { super.onStart(); vm.start() }
    override fun onStop() { super.onStop(); vm.stop() }
}

/** En fysisk skylt = ett hållplatsläge (en riktning) vid en hållplats. */
private data class Sign(
    val key: String,
    val siteName: String,
    val platform: String?,
    val departures: List<Departure>,
    val messages: List<Disruption>,
)

private fun signsFor(board: Board): List<Sign> =
    board.departures
        .groupBy { if (it.stopPointId >= 0) it.stopPointId.toString() else "${it.stopPoint}|${it.platform}" }
        .map { (key, deps) ->
            val lines = deps.map { it.line }.toSet()
            // Visa störningar som rör linjer på just den här skylten (eller hela hållplatsen)
            val msgs = board.disruptions.filter { d ->
                d.lines.isBlank() || d.lines.split(",").map { it.trim() }.any { it in lines }
            } + deps.flatMap { it.notes }.distinct().map { Disruption(it, it, 0, "") }
            Sign(
                key = "${board.site.id}-$key",
                siteName = deps.first().stopPoint.ifBlank { board.site.name },
                platform = deps.first().platform,
                departures = deps,
                messages = msgs.distinctBy { it.header.trim().lowercase() },
            )
        }
        .sortedWith(compareBy({ it.platform ?: "~" }, { it.siteName }))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(vm: BoardViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = Color.White),
                title = {
                    Column {
                        Text("Sickla avgångar", fontWeight = FontWeight.Bold)
                        Text(
                            if (state.loading) "Uppdaterar…" else "Uppdaterad ${state.updated ?: "–"}",
                            fontSize = 12.sp, color = Dim,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.manualRefresh() }) {
                        Icon(Icons.Default.Refresh, "Uppdatera", tint = Led)
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            state.error?.let {
                Text(it, color = Color(0xFFFF7A7A), modifier = Modifier.padding(16.dp))
                TextButton(onClick = { vm.manualRefresh(resolveAgain = true) }) { Text("Försök igen") }
            }
            val boards = state.boards
            if (boards.isEmpty()) {
                if (state.loading) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = Led) }
                return@Column
            }
            val idx = tab.coerceIn(0, boards.size)
            ScrollableTabRow(selectedTabIndex = idx, containerColor = Bg, contentColor = Led, edgePadding = 8.dp) {
                Tab(selected = idx == 0, onClick = { tab = 0 }, text = { Text("Alla") })
                boards.forEachIndexed { i, b ->
                    Tab(
                        selected = idx == i + 1, onClick = { tab = i + 1 },
                        text = { Text(b.site.name + if (b.disruptions.isNotEmpty()) " ⚠" else "") },
                    )
                }
            }
            val shown = if (idx == 0) boards else listOf(boards[idx - 1])
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                shown.forEach { board ->
                    item(key = "h-${board.site.id}") { Column {
                        Text(board.site.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        board.error?.let { Text("Kunde inte hämta avgångar: $it", color = Color(0xFFFF7A7A)) }
                        if (board.error == null && board.departures.isEmpty()) {
                            Text("Inga avgångar den närmaste timmen", color = Dim)
                        }
                    } }
                    items(signsFor(board), key = { it.key }) { LedSign(it) }
                }
            }
        }
    }
}

@Composable
private fun Clock(style: TextStyle) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) { now = LocalTime.now(); delay(1000) }
    }
    Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), style = style)
}

/** Ritar ett rutnät ovanpå innehållet så att texten ser ut som en LED-punktmatris. */
private fun Modifier.dotMatrix(): Modifier = drawWithContent {
    drawContent()
    val step = 2.6.dp.toPx()
    val gap = Color.Black.copy(alpha = 0.55f)
    var x = 0f
    while (x < size.width) {
        drawLine(gap, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f); x += step
    }
    var y = 0f
    while (y < size.height) {
        drawLine(gap, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f); y += step
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LedSign(sign: Sign) {
    var open by remember { mutableStateOf(false) }
    val directions = sign.departures.map { it.destination }.distinct().take(3).joinToString(", ")

    Column(Modifier.fillMaxWidth().animateContentSize()) {
        // Etikett ovanför skylten så man ser vilken riktning den gäller
        Text(
            buildString {
                append(sign.siteName)
                sign.platform?.let { append(" · Läge $it") }
                if (directions.isNotBlank()) append(" · mot $directions")
            },
            color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        // Skyltens hölje
        Box(
            Modifier
                .fillMaxWidth()
                .background(Casing, RoundedCornerShape(6.dp))
                .border(1.dp, Color(0xFF3C4249), RoundedCornerShape(6.dp))
                .clickable(enabled = sign.messages.isNotEmpty()) { open = !open }
                .padding(7.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(LedBlack, RoundedCornerShape(2.dp))
                    .dotMatrix()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                // Rubrikrad: hållplats + läge, klocka till höger
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        sign.siteName + (sign.platform?.let { "  $it" } ?: ""),
                        style = LedText.copy(fontSize = 14.sp),
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Clock(LedText.copy(fontSize = 14.sp))
                }
                Box(Modifier.fillMaxWidth().padding(vertical = 5.dp).height(1.dp).background(LedDim))

                // Avgångsrader
                sign.departures.take(4).forEach { d ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(d.line, style = LedText, modifier = Modifier.width(52.dp), maxLines = 1)
                        Text(
                            d.destination, style = LedText,
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Clip,
                        )
                        Text(
                            if (d.cancelled) "Inställd" else d.display,
                            style = LedText, textAlign = TextAlign.End,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }

                // Löptext med störningsinformation, som på stationsskyltarna
                if (sign.messages.isNotEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(top = 5.dp).height(1.dp).background(LedDim))
                    Text(
                        sign.messages.joinToString("   +++   ") { m ->
                            (if (m.lines.isNotBlank()) "Linje ${m.lines}: " else "") + m.header
                        },
                        style = LedText.copy(fontSize = 15.sp),
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 5.dp)
                            .basicMarquee(
                                iterations = Int.MAX_VALUE,
                                animationMode = MarqueeAnimationMode.Immediately,
                                initialDelayMillis = 800,
                                repeatDelayMillis = 1500,
                                velocity = 45.dp,
                            ),
                    )
                }
            }
        }

        // Fullständig störningstext när man trycker på skylten
        if (open) {
            Column(
                Modifier.fillMaxWidth().padding(top = 6.dp)
                    .background(Color(0xFF1E2329), RoundedCornerShape(8.dp)).padding(12.dp),
            ) {
                sign.messages.forEach { m ->
                    Text(m.header, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    if (m.details.isNotBlank() && m.details != m.header) {
                        Text(m.details, color = Color(0xFFDDDDDD), fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        } else if (sign.messages.isNotEmpty()) {
            Text("Tryck på skylten för hela störningstexten", color = Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}
