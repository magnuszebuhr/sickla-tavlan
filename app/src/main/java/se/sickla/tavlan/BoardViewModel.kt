package se.sickla.tavlan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class UiState(
    val loading: Boolean = true,
    val boards: List<Board> = emptyList(),
    val updated: String? = null,
    val error: String? = null,
)

class BoardViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var sites: List<Site> = emptyList()
    private var loop: Job? = null

    fun start() {
        if (loop?.isActive == true) return
        loop = viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(30_000)
            }
        }
    }

    fun stop() {
        loop?.cancel()
    }

    fun manualRefresh(resolveAgain: Boolean = false) {
        viewModelScope.launch {
            if (resolveAgain) sites = emptyList()
            refresh(forceResolve = resolveAgain)
        }
    }

    private suspend fun refresh(forceResolve: Boolean = false) {
        _state.value = _state.value.copy(loading = true)
        try {
            if (sites.isEmpty()) sites = SlApi.resolveSites(getApplication(), forceResolve)
            if (sites.isEmpty()) {
                _state.value = UiState(loading = false, error = "Hittade inga hållplatser som heter ${TARGET_STOPS.joinToString()}")
                return
            }

            val devJob = viewModelScope.async {
                runCatching { SlApi.deviations(sites.map { it.id }) }.getOrDefault(emptyMap())
            }
            val boards = sites.map { site ->
                viewModelScope.async {
                    runCatching { SlApi.departures(site) }.fold(
                        onSuccess = { (deps, stopDevs) -> Board(site, deps, stopDevs) },
                        onFailure = { Board(site, emptyList(), emptyList(), it.message ?: "Fel") },
                    )
                }
            }.awaitAll()
            val devs = devJob.await()

            val merged = boards.map { b ->
                val all = (b.disruptions + devs[b.site.id].orEmpty())
                    .distinctBy { it.header.trim().lowercase() }
                    .sortedByDescending { it.importance }
                b.copy(disruptions = all)
            }
            _state.value = UiState(
                loading = false,
                boards = merged,
                updated = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(loading = false, error = e.message ?: "Kunde inte hämta data")
        }
    }
}
