package dev.ytkara

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
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

class KaraApp : Application(), SingletonImageLoader.Factory {
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

    /** Search thumbnails: a small memory cache, the stick has little RAM to spare. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder().maxSizeBytes(32L * 1024 * 1024).build() }
            .components { add(OkHttpNetworkFetcherFactory()) }
            .build()
}
