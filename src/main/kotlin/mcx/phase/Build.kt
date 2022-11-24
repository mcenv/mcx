package mcx.phase

import kotlinx.coroutines.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import mcx.ast.*
import org.eclipse.lsp4j.Position
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import kotlin.io.path.*

class Build(
  private val root: Path,
) {
  private val src: Path = root.resolve("src")
  private val texts: ConcurrentMap<ModuleLocation, String> = ConcurrentHashMap()
  private val parseResults: ConcurrentMap<ModuleLocation, Trace<Parse.Result>> = ConcurrentHashMap()
  private val elaborateResults: ConcurrentMap<ModuleLocation, Trace<Elaborate.Result>> = ConcurrentHashMap()
  private val signatures: ConcurrentMap<ModuleLocation, Trace<Elaborate.Result>> = ConcurrentHashMap()

  fun changeText(
    location: ModuleLocation,
    text: String,
  ) {
    closeText(location)
    texts[location] = text
  }

  fun closeText(
    location: ModuleLocation,
  ) {
    texts -= location
    parseResults -= location
    elaborateResults -= location
    signatures -= location
  }

  // TODO: track external modification?
  suspend fun fetchText(
    location: ModuleLocation,
  ): String? {
    return when (val text = texts[location]) {
      null -> withContext(Dispatchers.IO) {
        src
          .resolve(location.parts.joinToString("/", postfix = ".mcx"))
          .let { path ->
            if (path.exists()) {
              return@withContext path
                .readText()
                .also {
                  texts[location] = it
                }
            }
          }
        STD_SRC
          .resolve(location.parts.joinToString("/", postfix = ".mcx"))
          .let { path ->
            if (path.exists()) {
              return@withContext path
                .readText()
                .also {
                  texts[location] = it
                }
            }
          }
        null
      }
      else -> text
    }
  }

  suspend fun fetchSurface(
    context: Context,
    location: ModuleLocation,
  ): Parse.Result? =
    coroutineScope {
      val text = fetchText(location) ?: return@coroutineScope null
      val newHash = text.hashCode()
      val parseResult = parseResults[location]
      if (parseResult == null || parseResult.hash != newHash) {
        Parse(context, location, text).also {
          parseResults[location] = Trace(it, newHash)
        }
      } else {
        parseResult.value
      }
    }

  suspend fun fetchSignature(
    context: Context,
    location: ModuleLocation,
  ): Elaborate.Result? =
    coroutineScope {
      val surface = fetchSurface(context, location) ?: return@coroutineScope null
      val newHash = surface.hashCode()
      val signature = signatures[location]
      if (signature == null || signature.hash != newHash) {
        Elaborate(context, emptyList(), surface, true).also {
          signatures[location] = Trace(it, newHash)
        }
      } else {
        signature.value
      }
    }

  suspend fun fetchCore(
    context: Context,
    location: ModuleLocation,
    position: Position? = null,
  ): Elaborate.Result =
    coroutineScope {
      val surface = fetchSurface(context, location)!!
      val dependencies =
        surface.module.imports
          .map { async { Elaborate.Dependency(it.value, fetchSignature(context, it.value)?.module, it.range) } }
          .plus(async { Elaborate.Dependency(PRELUDE, fetchSignature(context, PRELUDE)!!.module, null) })
          .plus(async { Elaborate.Dependency(location, fetchSignature(context, location)!!.module, null) })
          .awaitAll()
      val newHash = Objects.hash(surface, dependencies)
      val elaborateResult = elaborateResults[location]
      if (elaborateResult == null || elaborateResult.hash != newHash || position != null) {
        Elaborate(context, dependencies, surface, false, position).also {
          elaborateResults[location] = Trace(it, newHash)
        }
      } else {
        elaborateResult.value
      }
    }

  suspend fun fetchStaged(
    context: Context,
    location: DefinitionLocation,
  ): List<Core.Definition> =
    coroutineScope {
      val core = fetchCore(context, location.module)
      val definition = core.module.definitions.find { it.name == location }!!
      val dependencies =
        fetchSurface(context, location.module)!!.module.imports
          .map { async { fetchCore(context, it.value).module.definitions } }
          .plus(async { fetchCore(context, PRELUDE).module.definitions })
          .awaitAll()
          .flatten()
          .plus(core.module.definitions)
          .associateBy { it.name }
      Stage(context, dependencies, definition)
    }

  suspend fun fetchLifted(
    context: Context,
    location: DefinitionLocation,
  ): List<Lifted.Definition> =
    coroutineScope {
      val staged = fetchStaged(context, location)
      staged.flatMap { Lift(context, it) }
    }

  suspend fun fetchPacked(
    context: Context,
    location: DefinitionLocation,
  ): List<Packed.Definition> =
    coroutineScope {
      val lifted = fetchLifted(context, location)
      lifted
        .map { async { Pack(context, it) } }
        .awaitAll()
    }

  suspend fun fetchGenerated(
    context: Context,
    location: DefinitionLocation,
  ): Map<String, String> =
    coroutineScope {
      val packed = fetchPacked(context, location)
      packed
        .map { async { Generate(context, it) } }
        .awaitAll()
        .toMap()
    }

  @OptIn(ExperimentalSerializationApi::class)
  suspend operator fun invoke(): List<Pair<Path, List<Diagnostic>>> {
    val serverProperties = Properties().apply {
      load(
        root
          .resolve("server.properties")
          .inputStream()
          .buffered()
      )
    }
    val levelName = serverProperties.getProperty("level-name")
    val datapacks =
      root
        .resolve(levelName)
        .resolve("datapacks")
        .also { it.createDirectories() }

    val context =
      root
        .resolve("pack.json")
        .inputStream()
        .buffered()
        .use {
          Json.decodeFromStream<Context>(it)
        }

    fun Path.toModuleLocation(): ModuleLocation =
      ModuleLocation(
        src
          .relativize(this)
          .invariantSeparatorsPathString
          .dropLast(".mcx".length)
          .split('/')
      )

    val diagnosticsByPath = Collections.synchronizedList(mutableListOf<Pair<Path, List<Diagnostic>>>())

    val datapackRoot = datapacks
      .resolve(context.name)
      .also { it.createDirectories() }

    // TODO: generate dispatcher
    val outputModules = runBlocking {
      Files
        .walk(src)
        .filter { it.extension == "mcx" }
        .map { path ->
          async {
            val core = fetchCore(context, path.toModuleLocation())
            diagnosticsByPath += path to core.diagnostics
            core.module.definitions
              .map { async { fetchGenerated(context, it.name) } }
              .awaitAll()
          }
        }
        .toList()
        .awaitAll()
        .asSequence()
        .flatten()
        .plus(mapOf(Generate(context, Pack.packDispatch(context.liftedFunctions))))
        .map { it.mapKeys { (name, _) -> datapackRoot.resolve(name) } }
        .reduce { acc, map -> acc + map }
        .onEach { (name, definition) ->
          name
            .also { it.parent.createDirectories() }
            .bufferedWriter()
            .use { it.write(definition) }
        }
        .toMap()
    }

    Files
      .walk(datapackRoot)
      .filter { it.isRegularFile() }
      .forEach {
        if (it !in outputModules) {
          it.deleteExisting()
        }
      }

    return diagnosticsByPath
  }

  private data class Trace<V>(
    val value: V,
    val hash: Int,
  )

  companion object {
    private val STD_SRC: Path

    init {
      val uri =
        Build::class.java
          .getResource("/std/src")!!
          .toURI()
      FileSystems.newFileSystem(uri, emptyMap<String, Nothing>())
      STD_SRC = Paths.get(uri)
    }
  }
}
