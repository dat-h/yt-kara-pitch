package dev.ytkara.server

import android.content.res.AssetManager
import android.util.Log
import dev.ytkara.KaraSession
import dev.ytkara.Phase
import dev.ytkara.extract.YouTubeUrls
import dev.ytkara.player.PlayerSnapshot
import dev.ytkara.queue.QueueEntry
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.FileNotFoundException

@Serializable
data class AddRequest(val url: String, val singer: String? = null, val key: Int? = null)

@Serializable
data class UpdateRequest(val singer: String? = null, val key: Int? = null)

@Serializable
data class MoveRequest(val index: Int)

@Serializable
data class UpNextDto(val entryId: String, val secondsLeft: Int)

@Serializable
data class StateResponse(
    val version: Long,
    /** idle | upnext | playing */
    val phase: String,
    val upNext: UpNextDto? = null,
    val nowPlaying: QueueEntry? = null,
    val player: PlayerSnapshot? = null,
    val queue: List<QueueEntry>,
)

@Serializable
data class ErrorResponse(val error: String)

/** The LAN API and the web UI (served from assets/web). */
class KaraServer(
    private val session: KaraSession,
    private val assets: AssetManager,
    private val port: Int = PORT,
) {
    private var server: EmbeddedServer<*, *>? = null

    fun start() {
        if (server != null) return
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false })
            }
            install(CORS) {
                anyHost()
                allowMethod(HttpMethod.Patch)
                allowMethod(HttpMethod.Delete)
                allowHeader(HttpHeaders.ContentType)
            }
            routing {
                get("/api/state") {
                    val q = session.repo.state.value
                    val withPlayer = call.request.queryParameters["player"] == "1"
                    if (!withPlayer && call.request.queryParameters["since"]?.toLongOrNull() == q.version) {
                        call.respond(HttpStatusCode.NotModified)
                        return@get
                    }
                    val phase = session.phase.value
                    call.respond(
                        StateResponse(
                            version = q.version,
                            phase = when (phase) {
                                Phase.Idle -> "idle"
                                is Phase.UpNext -> "upnext"
                                is Phase.Playing -> "playing"
                            },
                            upNext = (phase as? Phase.UpNext)?.let { UpNextDto(it.entry.id, it.secondsLeft) },
                            nowPlaying = q.nowPlaying,
                            player = if (withPlayer && q.nowPlaying != null) session.player.value else null,
                            queue = q.queue,
                        ),
                    )
                }
                post("/api/queue") {
                    val req = call.receive<AddRequest>()
                    val url = YouTubeUrls.normalize(req.url)
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("That isn't a YouTube video link"))
                    try {
                        call.respond(HttpStatusCode.Created, session.repo.add(url, req.singer, req.key))
                    } catch (e: IllegalArgumentException) {
                        call.respond(HttpStatusCode.Conflict, ErrorResponse(e.message ?: "Can't add"))
                    }
                }
                patch("/api/queue/{id}") {
                    val req = call.receive<UpdateRequest>()
                    val entry = session.repo.update(call.parameters["id"]!!, req.singer, req.key)
                    if (entry != null) call.respond(entry) else call.notFound()
                }
                post("/api/queue/{id}/move") {
                    val req = call.receive<MoveRequest>()
                    if (session.repo.move(call.parameters["id"]!!, req.index)) {
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        call.notFound()
                    }
                }
                delete("/api/queue/{id}") {
                    if (session.repo.remove(call.parameters["id"]!!)) {
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        call.notFound()
                    }
                }
                get("/{path...}") {
                    val path = call.parameters.getAll("path").orEmpty().joinToString("/").ifEmpty { "index.html" }
                    if (".." in path) return@get call.notFound()
                    val bytes = try {
                        assets.open("web/$path").use { it.readBytes() }
                    } catch (_: FileNotFoundException) {
                        return@get call.notFound()
                    }
                    call.respondBytes(bytes, contentTypeOf(path))
                }
            }
        }.start(wait = false)
        Log.i("ytkara", "web server listening on :$port")
    }

    fun stop() {
        server?.stop(500, 1_000)
        server = null
    }

    private suspend fun RoutingCall.notFound() = respond(HttpStatusCode.NotFound, ErrorResponse("Not found"))

    private fun contentTypeOf(path: String): ContentType = when (path.substringAfterLast('.', "")) {
        "html" -> ContentType.Text.Html
        "css" -> ContentType.Text.CSS
        "js" -> ContentType.Text.JavaScript
        "svg" -> ContentType.Image.SVG
        "png" -> ContentType.Image.PNG
        "json", "webmanifest" -> ContentType.Application.Json
        else -> ContentType.Application.OctetStream
    }

    companion object {
        const val PORT = 8080
    }
}
