package se.sickla.tavlan

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

private val Bg = Color(0xFF0E1012)
private val Casing = Color(0xFF2A2E33)
private val LedBlack = Color(0xFF050505)
private val Led = Color(0xFFFFA51F)
private val LedDim = Color(0xFF6E460C)
private val Dim = Color(0xFF8A96A6)

class MainActivity : ComponentActivity() {
    private val vm: BoardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Väggskärm: håll skärmen tänd och kör i helskärm
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Bg, primary = Led)) {
                WallScreen(vm)
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
    val towards: String,
    val departures: List<Departure>,
    val messages: List<String>,
)

private fun signsFor(board: Board): List<Sign> =
    board.departures
        .groupBy { if (it.stopPointId >= 0) it.stopPointId.toString() else "${it.stopPoint}|${it.platform}" }
        .map { (key, deps) ->
            val lines = deps.map { it.line }.toSet()
            val msgs = board.disruptions.filter { d ->
                d.lines.isBlank() || d.lines.split(",").map { it.trim() }.any { it in lines }
            }.map { (if (it.lines.isNotBlank()) "Linje ${it.lines}: " else "") + it.header } +
                deps.flatMap { it.notes }
            Sign(
                key = "${board.site.id}-$key",
                siteName = board.site.name,
                platform = deps.first().platform,
                towards = deps.map { it.destination }.distinct().take(2).joinToString(", "),
                departures = deps,
                messages = msgs.map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() },
            )
        }
        .sortedWith(compareBy({ it.platform ?: "~" }, { it.towards }))

/** Antal kolumner så att skyltarna blir så stora som möjligt på en liggande 16:10-skärm. */
private fun columnsFor(n: Int): Int = when {
    n <= 2 -> n.coerceAtLeast(1)
    n <= 4 -> 2
    n <= 9 -> 3
    n <= 12 -> 4
    else -> 5
}

@Composable
private fun WallScreen(vm: BoardViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val signs = state.boards.flatMap { signsFor(it) }

    Column(
        Modifier.fillMaxSize().background(Bg).padding(10.dp)
            .clickable { vm.manualRefresh() }, // tryck var som helst = uppdatera
    ) {
        // Smal statusrad
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("SICKLA · SICKLA BRO · SICKLA STATION", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            state.error?.let { Text(it, color = Color(0xFFFF7A7A), fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(end = 16.dp)) }
            state.boards.filter { it.error != null }.forEach {
                Text("${it.site.name}: kunde inte hämtas", color = Color(0xFFFF7A7A), fontSize = 13.sp, modifier = Modifier.padding(end = 16.dp))
            }
            Text(
                if (state.loading) "Uppdaterar…" else "Uppdaterad ${state.updated ?: "–"}",
                color = Dim, fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(6.dp))

        if (signs.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                if (state.loading) CircularProgressIndicator(color = Led)
                else Text("Inga avgångar den närmaste timmen", color = Dim, fontSize = 22.sp)
            }
            return@Column
        }

        val cols = columnsFor(signs.size)
        val rows = ceil(signs.size / cols.toDouble()).toInt()
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (r in 0 until rows) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (c in 0 until cols) {
                        val i = r * cols + c
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            if (i < signs.size) LedSign(signs[i])
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Clock(style: TextStyle) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = LocalTime.now(); delay(1000) } }
    Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), style = style, maxLines = 1)
}

/** Rutnät ovanpå texten så att den ser ut som en LED-punktmatris. */
private fun Modifier.dotMatrix(pitch: Dp): Modifier = drawWithContent {
    drawContent()
    val step = pitch.toPx()
    val gap = Color.Black.copy(alpha = 0.55f)
    var x = 0f
    while (x < size.width) { drawLine(gap, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
    var y = 0f
    while (y < size.height) { drawLine(gap, Offset(0f, y), Offset(size.width, y), 1f); y += step }
}

@Composable
private fun LedSign(sign: Sign) {
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(Casing, RoundedCornerShape(6.dp))
            .border(1.dp, Color(0xFF3C4249), RoundedCornerShape(6.dp))
            .padding(6.dp)
            .background(LedBlack, RoundedCornerShape(2.dp))
            .clipToBounds(),
    ) {
        val w = maxWidth.value
        val h = maxHeight.value
        val hasTicker = sign.messages.isNotEmpty()

        // Textstorlek: ca 30 tecken per rad, men krymp så att minst 3 avgångar får plats
        val fixedLines = 1.3f + (if (hasTicker) 1.3f else 0f) + 0.9f // rubrik + löptext + marginaler
        var font = w / 30f
        if (floor(h / (font * 1.5f) - fixedLines) < 3) font = h / ((3 + fixedLines) * 1.5f)
        font = min(font, 44f)
        val rowsFit = floor(h / (font * 1.5f) - fixedLines).toInt().coerceIn(1, 8)

        val fs = with(LocalDensity.current) { font.dp.toSp() }
        val style = TextStyle(
            color = Led, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            fontSize = fs, lineHeight = fs * 1.15f,
            shadow = Shadow(color = Led.copy(alpha = 0.7f), offset = Offset.Zero, blurRadius = font * 0.6f),
        )
        val small = style.copy(fontSize = fs * 0.8f)
        val pad = (font * 0.45f).dp

        Column(
            Modifier.fillMaxSize().dotMatrix((font / 7f).coerceAtLeast(2f).dp)
                .padding(horizontal = pad, vertical = pad * 0.7f),
        ) {
            // Rubrik: hållplats, läge, riktning – klocka till höger
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildString {
                        append(sign.siteName)
                        sign.platform?.let { append("  $it") }
                        if (sign.towards.isNotBlank()) append("  → ${sign.towards}")
                    },
                    style = small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(pad))
                Clock(small)
            }
            Box(Modifier.fillMaxWidth().padding(vertical = pad * 0.4f).height(1.dp).background(LedDim))

            // Avgångar
            Column(Modifier.weight(1f)) {
                sign.departures.take(rowsFit).forEach { d ->
                    Row(Modifier.fillMaxWidth().padding(vertical = (font * 0.12f).dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(d.line, style = style, maxLines = 1, modifier = Modifier.width((font * 3.3f).dp))
                        Text(d.destination, style = style, maxLines = 1, overflow = TextOverflow.Clip, modifier = Modifier.weight(1f))
                        Text(
                            if (d.cancelled) "Inställd" else d.display,
                            style = style, maxLines = 1, textAlign = TextAlign.End,
                            modifier = Modifier.padding(start = pad),
                        )
                    }
                }
            }

            // Löptext med störningsinformation
            if (hasTicker) {
                Box(Modifier.fillMaxWidth().padding(vertical = pad * 0.4f).height(1.dp).background(LedDim))
                Text(
                    sign.messages.joinToString("     +++     "),
                    style = style, maxLines = 1,
                    modifier = Modifier.fillMaxWidth().basicMarquee(
                        iterations = Int.MAX_VALUE,
                        animationMode = MarqueeAnimationMode.Immediately,
                        initialDelayMillis = 1000,
                        repeatDelayMillis = 2000,
                        velocity = (font * 2.2f).dp,
                    ),
                )
            }
        }
    }
}
