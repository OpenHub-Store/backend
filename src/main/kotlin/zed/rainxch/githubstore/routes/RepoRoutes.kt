package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import zed.rainxch.githubstore.db.RepoRepository

fun Route.repoRoutes() {
    val repoRepository by inject<RepoRepository>()

    get("/repo/{owner}/{name}") {
        val owner = call.parameters["owner"] ?: return@get call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing owner")
        )
        val name = call.parameters["name"] ?: return@get call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing name")
        )

        val repo = repoRepository.findByOwnerAndName(owner, name)
        if (repo == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Repo not found"))
        } else {
            call.respond(repo)
        }
    }
}
