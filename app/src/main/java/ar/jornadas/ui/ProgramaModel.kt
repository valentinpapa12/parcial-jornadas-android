package ar.jornadas.ui

import android.app.Application
import androidx.lifecycle.*
import ar.jornadas.Servicios
import ar.jornadas.data.Cartelera
import com.google.gson.JsonParseException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException

class ProgramaModel(app: Application, private val estado: SavedStateHandle) : AndroidViewModel(app) {
    private val servicios = Servicios.de(app)
    val mesa = servicios.mesa
    private val repo = servicios.agenda

    /** null = todavía no leímos la base (evita mostrar "vacío" o "no existe" por un instante). */
    val cartelera: StateFlow<Cartelera?> =
        repo.cartelera.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Filtros en SavedStateHandle: sobreviven a rotación y a la muerte del proceso,
    // pero una tarea nueva arranca sin filtros.
    val dia: StateFlow<Int> = estado.getStateFlow("dia", 0)
    val tema: StateFlow<String> = estado.getStateFlow("tema", "Todos")
    fun elegirDia(valor: Int) { estado["dia"] = valor }
    fun elegirTema(valor: String) { estado["tema"] = valor }

    val cargando = MutableStateFlow(false)
    /** true si el último intento de descarga falló. */
    val fallo = MutableStateFlow(false)

    private var descarga: Job? = null

    /** Se llama desde onCreate; solo descarga la primera vez en este proceso. */
    fun cargaAutomatica() {
        if (servicios.cargaAutomaticaPendiente.compareAndSet(true, false)) actualizar()
    }

    /** Actualización explícita. Si ya hay una petición en curso, no lanza otra. */
    fun actualizar() {
        if (descarga?.isActive == true) return
        descarga = viewModelScope.launch {
            cargando.value = true
            try {
                repo.sincronizar()
                fallo.value = false
            } catch (e: IOException) {
                fallo.value = true
            } catch (e: HttpException) {
                fallo.value = true
            } catch (e: JsonParseException) {
                fallo.value = true
            } finally {
                cargando.value = false
            }
        }
    }

    fun marcar(id: String) { viewModelScope.launch { repo.cambiarFavorita(id) } }
}
