package se.sickla.tavlan

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** SL:s öppna API:er (Trafiklab) – ingen API-nyckel krävs. */
private const val TRANSPORT = "https://transport.integration.sl.se/v1"
private const val DEVIATIONS = "https://deviations.integration.sl.se/v1/messages"

/** Hållplatserna appen visar. Site-id slås upp vid start via /sites och cachas. */
val TARGET_STOPS = listOf("Sickla", "Sickla bro", "Sickla station")

data class Site(val id: Int, val name: String)

data class Departure(
    val line: String,
    val mode: String,
    val destination: String,
    val display: String,
    val stopPoint: String,
    val stopPointId: Int,
    val platform: String?,
    val direction: Int,
    val cancelled: Boolean,
    val notes: List<String>,
)

data class Disruption(
    val header: String,
    val details: String,
    val importance: Int,
    val lines: String,
)

data class Board(
    val site: Site,
    val departures: List<Departure>,
    val disruptions: List<Disruption>,
    val error: String? = null,
)

object SlApi {

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code från SL")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Slår upp site-id för TARGET_STOPS. Cachas i SharedPreferences. */
    suspend fun resolveSites(ctx: Context, forceRefresh: Boolean = false): List<Site> =
        withContext(Dispatchers.IO) {
            val prefs = ctx.getSharedPreferences("sites", Context.MODE_PRIVATE)
            val cached = prefs.getString("resolved_v1", null)
            if (cached != null && !forceRefresh) {
                val arr = JSONArray(cached)
                return@withContext (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Site(o.getInt("id"), o.getString("name"))
                }
            }

            val all = JSONArray(get("$TRANSPORT/sites?expand=false"))
            val sites = (0 until all.length()).map { all.getJSONObject(it) }
                .map { Site(it.getInt("id"), it.optString("name")) }

            val found = TARGET_STOPS.flatMap { target ->
                val exact = sites.filter { it.name.equals(target, ignoreCase = true) }
                exact.ifEmpty {
                    // t.ex. "Sickla station (Nacka)"
                    sites.filter { it.name.startsWith("$target ", ignoreCase = true) && !TARGET_STOPS.any { t -> t != target && it.name.equals(t, true) } }
                        .take(1)
                }
            }.distinctBy { it.id }

            val out = JSONArray()
            found.forEach { out.put(JSONObject().put("id", it.id).put("name", it.name)) }
            if (found.isNotEmpty()) prefs.edit().putString("resolved_v1", out.toString()).apply()
            found
        }

    /** Hämtar avgångar + hållplatsstörningar för en site. */
    suspend fun departures(site: Site): Pair<List<Departure>, List<Disruption>> =
        withContext(Dispatchers.IO) {
            val root = JSONObject(get("$TRANSPORT/sites/${site.id}/departures?forecast=60"))
            val deps = root.optJSONArray("departures") ?: JSONArray()
            val list = (0 until deps.length()).map { i ->
                val d = deps.getJSONObject(i)
                val line = d.optJSONObject("line") ?: JSONObject()
                val sp = d.optJSONObject("stop_point") ?: JSONObject()
                val devs = d.optJSONArray("deviations") ?: JSONArray()
                Departure(
                    line = line.optString("designation", line.optString("id")),
                    mode = line.optString("transport_mode", ""),
                    destination = d.optString("destination"),
                    display = d.optString("display"),
                    stopPoint = sp.optString("name"),
                    stopPointId = sp.optInt("id", -1),
                    direction = d.optInt("direction_code", 0),
                    platform = sp.optString("designation").takeIf { it.isNotBlank() && it != "null" },
                    cancelled = d.optString("state").equals("CANCELLED", true),
                    notes = (0 until devs.length()).mapNotNull {
                        devs.getJSONObject(it).optString("message").takeIf { m -> m.isNotBlank() }
                    }.distinct(),
                )
            }
            val sd = root.optJSONArray("stop_deviations") ?: JSONArray()
            val stopDevs = (0 until sd.length()).mapNotNull { i ->
                val o = sd.getJSONObject(i)
                val msg = o.optString("message")
                if (msg.isBlank()) null else Disruption(
                    header = msg.lineSequence().first(),
                    details = msg,
                    importance = o.optInt("importance_level", o.optInt("importance", 0)),
                    lines = "",
                )
            }
            list to stopDevs
        }

    /** Störningsmeddelanden från SL Deviations för de givna sites. */
    suspend fun deviations(siteIds: List<Int>): Map<Int, List<Disruption>> =
        withContext(Dispatchers.IO) {
            if (siteIds.isEmpty()) return@withContext emptyMap()
            val q = siteIds.joinToString("&") { "site=$it" }
            val arr = JSONArray(get("$DEVIATIONS?$q"))
            val result = mutableMapOf<Int, MutableList<Disruption>>()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val variants = m.optJSONArray("message_variants") ?: continue
                val v = (0 until variants.length()).map { variants.getJSONObject(it) }
                    .let { vs -> vs.firstOrNull { it.optString("language") == "sv" } ?: vs.firstOrNull() }
                    ?: continue
                val scope = m.optJSONObject("scope") ?: JSONObject()
                val lines = scope.optJSONArray("lines")?.let { la ->
                    (0 until la.length()).map { la.getJSONObject(it).optString("designation") }
                        .filter { it.isNotBlank() }.distinct().joinToString(", ")
                } ?: ""
                val disruption = Disruption(
                    header = v.optString("header"),
                    details = v.optString("details"),
                    importance = m.optJSONObject("priority")?.optInt("importance_level") ?: 0,
                    lines = lines,
                )
                // Koppla meddelandet till de hållplatser det gäller
                val areas = scope.optJSONArray("stop_areas") ?: JSONArray()
                val touched = mutableSetOf<Int>()
                for (a in 0 until areas.length()) {
                    val area = areas.getJSONObject(a)
                    val sid = area.optInt("site_id", -1)
                    if (sid in siteIds) touched += sid
                }
                // Om API:t inte anger site_id: visa för alla efterfrågade
                (touched.ifEmpty { siteIds.toSet() }).forEach {
                    result.getOrPut(it) { mutableListOf() }.add(disruption)
                }
            }
            result
        }
}
