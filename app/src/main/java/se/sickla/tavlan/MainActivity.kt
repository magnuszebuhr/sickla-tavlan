package se.sickla.tavlan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val Bg = Color(0xFF0B0F14)
private val Panel = Color(0xFF151B23)
private val Amber = Color(0xFFFFC233)
private val Dim = Color(0xFF8A96A6)
private val WarnBg = Color(0xFF3A2A06)
private val CritBg = Color(0xFF4A1212)

class MainActivity : ComponentActivity() {
    private val vm: BoardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Panel, primary = Amber)) {
                App(vm)
            }
        }
    }

    override fun onStart() { super.onStart(); vm.start() }
    override fun onStop() { super.onStop(); vm.stop() }
}

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
                        Icon(Icons.Default.Refresh, "Uppdatera", tint = Amber)
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
            if (state.boards.isEmpty()) {
                if (state.loading) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = Amber) }
                return@Column
            }
            val boards = state.boards
            val idx = tab.coerceIn(0, boards.size)
            // Flik 0 = Alla, sedan en flik per hållplats
            ScrollableTabRow(selectedTabIndex = idx, containerColor = Bg, contentColor = Amber, edgePadding = 8.dp) {
                Tab(selected = idx == 0, onClick = { tab = 0 }, text = { Text("Alla") })
                boards.forEachIndexed { i, b ->
                    Tab(
                        selected = idx == i + 1, onClick = { tab = i + 1 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(b.site.name)
                                if (b.disruptions.isNotEmpty()) {
                                    Spacer(Modifier.width(4.dp))
                                    Icon(Icons.Default.Warning, null, tint = Amber, modifier = Modifier.size(14.dp))
                                }
                            }
                        },
                    )
                }
            }
            val shown = if (idx == 0) boards else listOf(boards[idx - 1])
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(shown, key = { it.site.id }) { SiteBoard(it) }
            }
        }
    }
}

@Composable
private fun SiteBoard(board: Board) {
    Column(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(12.dp),
    ) {
        Text(board.site.name.uppercase(), color = Amber, fontWeight = FontWeight.Bold, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(8.dp))

        board.disruptions.forEach { DisruptionCard(it) }

        board.error?.let { Text("Kunde inte hämta avgångar: $it", color = Color(0xFFFF7A7A)) }

        if (board.error == null && board.departures.isEmpty()) {
            Text("Inga avgångar den närmaste timmen", color = Dim)
        }

        // Gruppera per trafikslag / läge, som på skyltarna
        board.departures
            .groupBy { modeName(it.mode) + (it.platform?.let { p -> " · Läge $p" } ?: "") }
            .toSortedMap()
            .forEach { (group, deps) ->
                Text(group, color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                deps.take(8).forEach { DepartureRow(it) }
            }
    }
}

@Composable
private fun DisruptionCard(d: Disruption) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .background(if (d.importance >= 7) CritBg else WarnBg, RoundedCornerShape(8.dp))
            .clickable { open = !open }
            .animateContentSize()
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Warning, null, tint = Amber, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(d.header.ifBlank { "Störning" }, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                if (d.lines.isNotBlank()) Text("Linje ${d.lines}", color = Amber, fontSize = 12.sp)
            }
        }
        if (open && d.details.isNotBlank() && d.details != d.header) {
            Spacer(Modifier.height(6.dp))
            Text(d.details, color = Color(0xFFE6E6E6), fontSize = 13.sp)
        } else if (d.details.isNotBlank() && d.details != d.header) {
            Text("Tryck för detaljer", color = Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp, start = 26.dp))
        }
    }
}

@Composable
private fun DepartureRow(d: Departure) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.widthIn(min = 44.dp).background(modeColor(d.mode), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(d.line, color = Color.White, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                d.destination,
                color = if (d.cancelled) Dim else Color.White,
                textDecoration = if (d.cancelled) TextDecoration.LineThrough else null,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                if (d.cancelled) "Inställd" else d.display,
                color = if (d.cancelled) Color(0xFFFF7A7A) else Amber,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
            )
        }
        d.notes.forEach {
            Text("⚠ $it", color = Amber, fontSize = 11.sp, modifier = Modifier.padding(start = 54.dp, top = 2.dp))
        }
    }
}

private fun modeName(m: String) = when (m.uppercase()) {
    "BUS" -> "Buss"
    "TRAM" -> "Spårväg / lokalbana"
    "METRO" -> "Tunnelbana"
    "TRAIN" -> "Tåg"
    "SHIP", "FERRY" -> "Båt"
    else -> m.ifBlank { "Övrigt" }
}

private fun modeColor(m: String) = when (m.uppercase()) {
    "BUS" -> Color(0xFFD71D24)
    "TRAM" -> Color(0xFFE07B1F)
    "METRO" -> Color(0xFF0089CA)
    "TRAIN" -> Color(0xFFEC619F)
    "SHIP", "FERRY" -> Color(0xFF00A0B0)
    else -> Color(0xFF555F6D)
}
