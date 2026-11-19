# Cambios · Guardia de mantenimiento

## 41 · Programa sin señal

**Problema.** Ante una falla, `Catalogo` mostraba el aviso **en lugar** de la grilla: se perdía la lista aunque Room tuviera la edición anterior. Además, un programa vacío (v4) no se distinguía de "nunca descargué", porque las dos cosas son una tabla `charlas` vacía.

**Cambio.**
- Nueva entidad `Edicion` (tabla `edicion`, fila única) que se escribe en la misma transacción que el programa. Indica que hay al menos una edición válida, aunque tenga cero charlas.
- El repositorio expone `Cartelera(hayEdicion, charlas)`, siempre leída de Room (fuente única de verdad).
- El ViewModel guarda `fallo` (resultado del último intento). La UI decide:
  - Hay edición y falló: muestra la lista, el detalle y las favoritas, más "Sin conexión: se están usando los datos guardados" y Reintentar.
  - No hay edición y falló: "No se pudo descargar el programa…" y Reintentar.
  - Hay edición sin charlas: "Sin charlas programadas.", también al reabrir sin señal.
- Se capturan `IOException`, `HttpException` y `JsonParseException`, no `Exception`, para no tragar la cancelación de corrutinas.

**Migración.** La base pasa de la versión 1 a la 2 con `MIGRACION_1_2`: crea `edicion` y, si ya había charlas, registra que hay edición. **No** se usa `fallbackToDestructiveMigration`, así que quien actualiza conserva sus charlas y marcas.

## 42 · Agenda personal

**Problema.** `guardar()` hacía `@Insert(REPLACE)` con `favorita = false`, lo que **borraba las marcas** en cada actualización (en v2 la 11 perdía su marca). Además nunca borraba charlas, así que en v3 la 11 seguía en el catálogo y en Mi agenda, y en v4 no se vaciaba nada.

**Cambio.** `CarteleraDao.reemplazarPrograma()` es una `@Transaction` que:
1. lee los ids marcados **dentro de la transacción**, en el momento de escribir;
2. borra todas las charlas;
3. inserta la instantánea nueva copiando la marca solo a los ids que siguen vigentes;
4. registra la edición.

Resultado:
- v1→v2: la 11 conserva la marca con el título y la sala nuevos (los ids son estables).
- v3: la 11 sale del catálogo y de Mi agenda, y entra la 90 sin marcar.
- Una charla retirada que regresa vuelve sin marcar, porque su fila (y su marca) se borró.
- Una marca agregada o quitada **durante** la petición se respeta. `alternar` es una escritura en Room, y Room serializa las escrituras: la transacción ve la marca ya aplicada, porque se lee recién cuando llega la respuesta y no antes de pedir.
- El orden es red primero y escritura después: si la red falla, la base no se toca.
- Al ser una transacción, el `Flow` emite una sola vez, sin lista vacía intermedia.

## 43 · Volver al punto de lectura

**Problema.** El filtro de tema era `remember` dentro de `Catalogo`: se perdía al abrir una ficha (`Catalogo` sale de la composición) y al rotar. El día vivía en el ViewModel, que sobrevive a la rotación pero **no** a la muerte del proceso.

**Cambio.**
- `dia` y `tema` se guardan en `SavedStateHandle` (`getStateFlow`, `elegirDia`, `elegirTema`). Sobreviven a la rotación, a la navegación y a la muerte del proceso.
- Una tarea nueva arranca sin filtros, porque ahí no hay estado guardado.
- La ficha abierta ya usaba `rememberSaveable` en `Portada`, así que se recupera.
- La cartelera arranca en `null` mientras Room no emite, para no mostrar "vacío" ni "ya no existe" por un instante tras recrear el proceso.
- Si la charla abierta se retira, se muestra "Esta charla ya no forma parte del programa."

## 44 · Control de descargas

**Problema.** Se descargaba en `onStart` (en cada rotación y cada vuelta del fondo) **y** en un `LaunchedEffect(Unit)` de `Catalogo` (en cada vuelta de una ficha y cada cambio de pestaña). Además `recargar()` no evitaba peticiones simultáneas. Encima, cada ViewModel creaba su propio `Servicios`, con otra base Room y otro contador.

**Cambio.**
- `Servicios` pasa a ser **una instancia por proceso** (`Servicios.de(context)`, con `applicationContext`) y guarda `cargaAutomaticaPendiente`.
- `MainActivity.onCreate` llama a `model.cargaAutomatica()`, que descarga solo la primera vez en el proceso, después de aplicar el extra `servicio_disponible` para que la primera petición lo capture.
- Se borraron la recarga de `onStart` y el `LaunchedEffect`.
- `actualizar()` no lanza otra petición si el `Job` anterior sigue activo: dos toques durante una petición cuentan un solo intento. Al terminar, con éxito o con falla, se puede volver a pedir (`finally`).
- Los filtros no se bloquean durante la espera.

## Validación manual (pasos y resultado esperado)

| Caso | Pasos | Resultado |
|---|---|---|
| Arranque vacío sin señal | `adb shell pm clear ar.jornadas` y `adb shell am start -n ar.jornadas/.MainActivity --ez servicio_disponible false` | "No se pudo descargar…" + Reintentar. Mesa: 1 petición. |
| Falla con edición | v1 OK; marcar 11 y 21; Mesa: servicio off; Actualizar | Lista y marcas intactas + "se están usando los datos guardados". |
| Marcas v1→v2→v3 | marcar 11 y 21; v2 + Actualizar; v3 + Actualizar; v1 + Actualizar | v2: 11 marcada, "Compose en producción avanzada", "Sala Puerto". v3: sin 11, entra 90, 21 sigue marcada. v1: 11 regresa sin marcar. |
| Marca durante la petición | demora larga ON; Actualizar; en los 8 s marcar 22 | Al llegar la respuesta, 22 sigue marcada. |
| v4 | v4 + Actualizar; servicio off; matar la app y reabrir | "Sin charlas programadas." + aviso de datos guardados. |
| Punto de lectura | Día 2 + Datos; abrir "Cachés para viajeros"; volver; rotar | 2 resultados de Datos. |
| Proceso muerto | con la ficha abierta: Inicio; `adb shell am kill ar.jornadas`; volver desde Recientes | Se recupera la ficha; al volver, Día 2 + Datos. |
| Contador | abrir la app (1); rotar, entrar a una ficha, cambiar de pestaña (+0); Actualizar dos veces seguidas (+1) | Mesa: 2 peticiones. |

## Compilación

**Problema.** El proyecto no compilaba: `ServicioLocal` usa `toMediaType()` y `toResponseBody()`, que son extensiones de OkHttp 4, pero Retrofit 2.11 trae OkHttp 3.14 como dependencia transitiva.

**Cambio.** Se agregó `com.squareup.okhttp3:okhttp:4.12.0` en `app/build.gradle.kts`. `./gradlew assembleDebug` termina bien.
