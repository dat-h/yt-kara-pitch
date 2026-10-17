package dev.ytkara

import android.app.Application
import dev.ytkara.extract.OkHttpDownloader
import dev.ytkara.extract.StreamResolver
import dev.ytkara.queue.QueueRepository
import dev.ytkara.server.KaraServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe

class KaraApp : Application() {
    lateinit var session: KaraSession
        private set

    override fun onCreate() {
        super.onCreate()
        NewPipe.init(OkHttpDownloader())

        val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo = QueueRepository(io, resolveMeta = { url -> withContext(Dispatchers.IO) { StreamResolver.meta(url) } })
        session = KaraSession(repo, MainScope())

        val server = KaraServer(session, assets)
        io.launch { server.start() }
    }
}
