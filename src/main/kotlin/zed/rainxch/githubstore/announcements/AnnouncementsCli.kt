package zed.rainxch.githubstore.announcements

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.system.exitProcess

// Standalone validator for draft announcement files. Author / translator
// runs `./gradlew validateAnnouncements` (or `validateAnnouncements --dir
// path/to/dir`) before opening a PR; CI runs the same task as a gate so
// invalid drafts never reach main.
//
// Exit codes:
//   0 -- every file in the dir parses + validates, no duplicate ids
//   1 -- one or more files are malformed, OR duplicate ids exist
//
// Default directory is `src/main/resources/announcements/` relative to the
// gradle project root, matching the runtime classpath layout.
fun main(args: Array<String>) {
    val dir = parseDirArg(args) ?: Paths.get("src/main/resources/announcements")
    if (!Files.isDirectory(dir)) {
        System.err.println("validateAnnouncements: '$dir' is not a directory")
        exitProcess(1)
    }

    var failures = 0
    val items = mutableListOf<AnnouncementDto>()

    Files.walk(dir, 1).use { stream ->
        stream
            .filter { Files.isRegularFile(it) && it.extension == "json" }
            .sorted()
            .toList()
            .forEach { file ->
                val name = file.name
                val raw = try {
                    file.readText()
                } catch (e: Exception) {
                    System.err.println("$name: read error: ${e.message}")
                    failures++
                    return@forEach
                }
                val item = try {
                    AnnouncementsJson.decodeFromString(AnnouncementDto.serializer(), raw)
                        .normalizeOptionals()
                } catch (e: Exception) {
                    System.err.println("$name: parse error: ${e.message}")
                    failures++
                    return@forEach
                }
                val errs = AnnouncementValidator.validate(item)
                if (errs.isEmpty()) {
                    println("$name: OK")
                    items += item
                } else {
                    System.err.println("$name: ${errs.size} error(s)")
                    errs.forEach { System.err.println("  - $it") }
                    failures++
                }
            }
    }

    AnnouncementValidator.checkDuplicates(items)?.let { dupErr ->
        System.err.println("cross-file: $dupErr")
        failures++
    }

    if (failures > 0) {
        System.err.println("validateAnnouncements: $failures failure(s)")
        exitProcess(1)
    }
    println("validateAnnouncements: ${items.size} file(s), all OK")
}

private fun parseDirArg(args: Array<String>): Path? {
    val idx = args.indexOf("--dir")
    if (idx >= 0 && idx + 1 < args.size) return Paths.get(args[idx + 1])
    return null
}
