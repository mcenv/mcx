package mcx.util.cache

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream

private val root: Path = Paths
  .get(System.getProperty("user.home"), ".mcx")
  .also { it.createDirectories() }

private val versions: Path = root.resolve("versions")

val JAVA: String = ProcessHandle
  .current()
  .info()
  .command()
  .orElseThrow()

val BUNDLER_REPO_DIR: String = "-DbundlerRepoDir=\"${root.absolutePathString()}\""

fun installServer(
  `package`: Package,
) {
  val download = `package`.downloads.server
  val serverPath = getServerPath(`package`.id)
  fetch(
    download.url
      .openStream()
      .buffered(),
    download.sha1,
  ) { input ->
    serverPath
      .outputStream()
      .buffered()
      .use { output ->
        input.transferTo(output)
      }
  }
}

fun getServerPath(
  id: String,
): Path {
  return getOrCreateServerDirectory(id).resolve("server.jar")
}

private fun getOrCreateServerDirectory(
  id: String,
): Path {
  return versions
    .resolve(id)
    .also {
      it.createDirectories()
    }
}
