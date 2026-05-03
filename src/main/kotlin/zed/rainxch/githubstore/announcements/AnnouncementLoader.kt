package zed.rainxch.githubstore.announcements

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText

// Reads announcement JSON files from a directory. Each file is one
// AnnouncementDto. Source resolution order:
//
//   1. ANNOUNCEMENTS_DIR env var (filesystem path) -- ops override.
//   2. Classpath resource directory `announcements/` -- baked into the JAR.
//
// Any individual file that fails to parse or validate is logged and dropped
// (single-file failures must never take the whole feed offline). Cross-item
// rules (duplicate id) are enforced after per-file load and reject the whole
// payload, falling back to an empty list.
class AnnouncementLoader(
    private val classpathDir: String = "announcements",
    // Explicit filesystem override -- tests pass a tmp dir directly. In
    // production this is null and the loader falls through to ANNOUNCEMENTS_DIR
    // env then the bundled classpath dir.
    private val explicitDir: Path? = null,
) {
    private val log = LoggerFactory.getLogger(AnnouncementLoader::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun load(): List<AnnouncementDto> {
        val resolvedDir = explicitDir
            ?: System.getenv("ANNOUNCEMENTS_DIR")?.takeIf { it.isNotBlank() }?.let(Paths::get)
        return if (resolvedDir != null) {
            if (!Files.isDirectory(resolvedDir)) {
                log.warn("ANNOUNCEMENTS_DIR={} not a directory, falling back to classpath", resolvedDir)
                loadFromClasspath()
            } else {
                loadFromPath(resolvedDir)
            }
        } else {
            loadFromClasspath()
        }.also { items ->
            val dupErr = AnnouncementValidator.checkDuplicates(items)
            if (dupErr != null) {
                log.error("Announcements rejected: {}", dupErr)
                return emptyList()
            }
            log.info("Announcements loaded: {} item(s)", items.size)
        }
    }

    private fun loadFromClasspath(): List<AnnouncementDto> {
        val url = javaClass.classLoader.getResource(classpathDir)
        if (url == null) {
            log.info("Classpath dir '{}' missing, returning empty list", classpathDir)
            return emptyList()
        }
        // Walk both file:// (local IDE / tests) and jar:// (deployed fat-jar).
        // The jar branch must close its FileSystem to avoid leaking handles
        // across reloads.
        return when (url.protocol) {
            "file" -> loadFromPath(Paths.get(url.toURI()))
            "jar" -> {
                val uri = URI.create(url.toString())
                openJarFs(uri).use { fs ->
                    val root = fs.getPath("/$classpathDir")
                    loadFromPath(root)
                }
            }
            else -> {
                log.warn("Unsupported resource protocol '{}' for {}", url.protocol, url)
                emptyList()
            }
        }
    }

    private fun openJarFs(uri: URI): FileSystem = try {
        FileSystems.getFileSystem(uri)
    } catch (_: Exception) {
        FileSystems.newFileSystem(uri, emptyMap<String, Any>())
    }

    private fun loadFromPath(dir: Path): List<AnnouncementDto> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.walk(dir, 1).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.extension == "json" }
                .toList()
                .mapNotNull { parseFile(it) }
        }
    }

    private fun parseFile(file: Path): AnnouncementDto? {
        return try {
            val raw = file.readText()
            val item = json.decodeFromString(AnnouncementDto.serializer(), raw)
            val errs = AnnouncementValidator.validate(item)
            if (errs.isNotEmpty()) {
                log.warn("Dropping {} (invalid): {}", file.name, errs.joinToString("; "))
                null
            } else {
                item
            }
        } catch (e: Exception) {
            log.warn("Dropping {} (parse error): {}", file.name, e.message)
            null
        }
    }
}
