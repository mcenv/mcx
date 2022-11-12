package mcx.phase

import kotlinx.coroutines.*
import mcx.ast.Location
import mcx.ast.Packed
import org.eclipse.lsp4j.Position
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import kotlin.io.path.exists
import kotlin.io.path.readText

class Build(
  private val src: Path,
) {
  private val texts: ConcurrentMap<Location, String> = ConcurrentHashMap()
  private val parseResults: ConcurrentMap<Location, Trace<Parse.Result>> = ConcurrentHashMap()
  private val elaborateResults: ConcurrentMap<Location, Trace<Elaborate.Result>> = ConcurrentHashMap()
  private val signatures: ConcurrentMap<Location, Trace<Elaborate.Result>> = ConcurrentHashMap()

  fun changeText(
    location: Location,
    text: String,
  ) {
    closeText(location)
    texts[location] = text
  }

  fun closeText(
    location: Location,
  ) {
    texts -= location
    parseResults -= location
    elaborateResults -= location
    signatures -= location
  }

  // TODO: track external modification?
  suspend fun fetchText(location: Location): String? {
    return when (val text = texts[location]) {
      null -> withContext(Dispatchers.IO) {
        src
          .resolve("$location.mcx")
          .let { path ->
            if (path.exists()) {
              return@withContext path
                .readText()
                .also { texts[location] = it }
            }
          }
        STD_SRC
          .resolve("$location.mcx")
          .let { path ->
            if (path.exists()) {
              return@withContext path
                .readText()
                .also { texts[location] = it }
            }
          }
        null
      }
      else -> text
    }
  }

  suspend fun fetchSurface(
    config: Config,
    location: Location,
  ): Parse.Result? =
    coroutineScope {
      val text = fetchText(location)
                 ?: return@coroutineScope null
      val newHash = text.hashCode()
      val parseResult = parseResults[location]
      if (parseResult == null || parseResult.hash != newHash) {
        Parse(
          config,
          location,
          text,
        ).also {
          parseResults[location] = Trace(
            it,
            newHash,
          )
        }
      } else {
        parseResult.value
      }
    }

  suspend fun fetchSignature(
    config: Config,
    location: Location,
  ): Elaborate.Result? =
    coroutineScope {
      val surface = fetchSurface(
        config,
        location,
      )
                    ?: return@coroutineScope null
      val newHash = surface.hashCode()
      val signature = signatures[location]
      if (signature == null || signature.hash != newHash) {
        Elaborate(
          config,
          emptyList(),
          surface,
          true,
        ).also {
          signatures[location] = Trace(
            it,
            newHash,
          )
        }
      } else {
        signature.value
      }
    }

  suspend fun fetchCore(
    config: Config,
    location: Location,
    position: Position? = null,
  ): Elaborate.Result? =
    coroutineScope {
      val surface = fetchSurface(
        config,
        location,
      )
                    ?: return@coroutineScope null
      val dependencies =
          surface.root.imports
            .map {
              async {
                val dependency = fetchSignature(
                  config,
                  it.value,
                )
                Elaborate.Dependency(
                  it.value,
                  dependency?.root,
                  it.range,
                )
              }
            }
            .plus(
              async {
                val prelude = fetchSignature(
                  config,
                  PRELUDE,
                )!!
                Elaborate.Dependency(
                  PRELUDE,
                  prelude.root,
                  null,
                )
              }
            )
            .plus(
              async {
                val self = fetchSignature(
                  config,
                  location,
                )!!
                Elaborate.Dependency(
                  location,
                  self.root,
                  null,
                )
              }
            )
            .awaitAll()
      val newHash = Objects.hash(
        surface,
        dependencies
      )
      val elaborateResult = elaborateResults[location]
      if (elaborateResult == null || elaborateResult.hash != newHash || position != null) {
        Elaborate(
          config,
          dependencies,
          surface,
          false,
          position,
        ).also {
          elaborateResults[location] = Trace(
            it,
            newHash,
          )
        }
      } else {
        elaborateResult.value
      }
    }

  suspend fun fetchPacked(
    config: Config,
    location: Location,
  ): Packed.Root? {
    val core = fetchCore(
      config,
      location,
    )
               ?: return null
    if (core.diagnostics.isNotEmpty()) {
      return null
    }
    return Pack(
      config,
      core.root,
    )
  }

  suspend fun fetchGenerated(
    config: Config,
    generator: Generate.Generator,
    location: Location,
  ) {
    val packed = fetchPacked(
      config,
      location,
    )
                 ?: return
    Generate(
      config,
      generator,
      packed,
    )
  }

  private data class Trace<V>(
    val value: V,
    val hash: Int,
  )

  companion object {
    private val STD_SRC: Path
    private val PRELUDE: Location = Location("prelude")

    init {
      val uri =
        Build::class.java
          .getResource("/std/src")!!
          .toURI()
      FileSystems.newFileSystem(
        uri,
        emptyMap<String, Nothing>(),
      )
      STD_SRC = Paths.get(uri)
    }
  }
}
