package ar.jornadas.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import retrofit2.http.GET
import kotlinx.coroutines.flow.Flow

data class FichaRemota(val id: String, val title: String, val track: String, val day: Int,
    val time: String, val room: String, val speaker: String, val summary: String)

interface ProgramaApi {
    @GET("programa") suspend fun consultar(): List<FichaRemota>
}

@Entity(tableName = "charlas")
data class Charla(@PrimaryKey val id: String, val titulo: String, val tema: String, val dia: Int,
    val hora: String, val sala: String, val ponente: String, val resumen: String,
    val favorita: Boolean = false)

/**
 * Marca de que existe al menos una edición descargada con éxito.
 * Hace falta porque un programa vacío (v4) es válido y no se distingue de "nunca descargué"
 * mirando solo la tabla charlas. Fila única (id = 0).
 */
@Entity(tableName = "edicion")
data class Edicion(@PrimaryKey val id: Int = 0, val descargadaEn: Long)

@Dao
abstract class CarteleraDao {
    @Query("SELECT * FROM charlas ORDER BY dia, hora, sala")
    abstract fun observar(): Flow<List<Charla>>

    @Query("SELECT EXISTS(SELECT 1 FROM edicion)")
    abstract fun observarHayEdicion(): Flow<Boolean>

    @Query("UPDATE charlas SET favorita = NOT favorita WHERE id = :id")
    abstract suspend fun alternar(id: String)

    @Query("SELECT id FROM charlas WHERE favorita = 1")
    abstract suspend fun idsFavoritas(): List<String>

    @Query("DELETE FROM charlas")
    abstract suspend fun borrarCharlas()

    @Insert
    abstract suspend fun insertar(fichas: List<Charla>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun registrarEdicion(edicion: Edicion)

    /**
     * Reemplaza la instantánea completa del programa en una sola transacción.
     * Las marcas se leen DENTRO de la transacción, en el momento de escribir, así que un
     * cambio hecho mientras la petición estaba en vuelo ya está aplicado y se respeta.
     * Las charlas retiradas se borran con su marca: si regresan, regresan sin marcar.
     */
    @Transaction
    open suspend fun reemplazarPrograma(fichas: List<Charla>) {
        val favoritas = idsFavoritas().toSet()
        borrarCharlas()
        insertar(fichas.map { it.copy(favorita = it.id in favoritas) })
        registrarEdicion(Edicion(descargadaEn = System.currentTimeMillis()))
    }
}

@Database(entities = [Charla::class, Edicion::class], version = 2, exportSchema = false)
abstract class Archivo : RoomDatabase() {
    abstract fun cartelera(): CarteleraDao
}

/** Conserva los datos de quienes ya tienen la v1 instalada (charlas y marcas). */
val MIGRACION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `edicion` (`id` INTEGER NOT NULL, " +
                "`descargadaEn` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        // Si ya había charlas guardadas, esa instalación tiene una edición previa válida.
        db.execSQL("INSERT INTO edicion (id, descargadaEn) SELECT 0, 0 WHERE EXISTS (SELECT 1 FROM charlas)")
    }
}
