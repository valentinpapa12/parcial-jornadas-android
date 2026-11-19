package ar.jornadas.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Lo que muestra la app: siempre sale de Room, nunca directo de la respuesta de red. */
data class Cartelera(val hayEdicion: Boolean, val charlas: List<Charla>)

class AgendaRepository(private val api: ProgramaApi, private val dao: CarteleraDao) {
    val cartelera: Flow<Cartelera> =
        combine(dao.observarHayEdicion(), dao.observar()) { hay, charlas -> Cartelera(hay, charlas) }

    /** Primero la red; solo si responde bien se toca la base (una falla deja todo como estaba). */
    suspend fun sincronizar() {
        val fichas = api.consultar().map {
            Charla(it.id, it.title, it.track, it.day, it.time, it.room, it.speaker, it.summary)
        }
        dao.reemplazarPrograma(fichas)
    }

    suspend fun cambiarFavorita(id: String) = dao.alternar(id)
}
