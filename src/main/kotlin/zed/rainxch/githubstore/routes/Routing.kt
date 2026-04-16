package zed.rainxch.githubstore.routes

import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        route("/v1") {
            healthRoutes()
            eventRoutes()
        }
    }
}
