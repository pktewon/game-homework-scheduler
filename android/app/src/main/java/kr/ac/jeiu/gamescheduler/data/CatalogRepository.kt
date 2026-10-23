package kr.ac.jeiu.gamescheduler.data

import androidx.room.withTransaction
import java.io.ByteArrayOutputStream
import java.net.URL
import java.time.Clock
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

fun interface CatalogTransport { fun get(url: String, limit: Int): ByteArray }

class HttpsCatalogTransport : CatalogTransport {
    override fun get(url: String, limit: Int): ByteArray {
        demand(url == MANIFEST_URL || Regex(Regex.escape(RAW_BASE) + "releases/endfield-asia-v[1-9][0-9]*\\.json").matches(url), "허용되지 않은 배포 주소입니다.")
        val connection = URL(url).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            demand(connection.responseCode == 200, "일정을 내려받지 못했습니다. HTTP ${connection.responseCode}")
            demand(connection.contentLengthLong <= limit, "일정 파일 크기가 너무 큽니다.")
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    demand(output.size() + count <= limit, "일정 파일 크기가 너무 큽니다.")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally { connection.disconnect() }
    }
}

class CatalogClient(private val transport: CatalogTransport = HttpsCatalogTransport()) {
    suspend fun fetch(): ParsedCatalog = withContext(Dispatchers.IO) {
        val entry = CatalogCodec.manifest(transport.get(MANIFEST_URL, 64_000))
        CatalogCodec.catalog(transport.get(entry.url, 1_000_000), entry)
    }
}

data class SyncResult(val revision: Long, val events: Int, val needsReview: Int, val unchanged: Boolean)

class CatalogRepository(
    private val db: SchedulerDatabase,
    private val client: CatalogClient = CatalogClient(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mutex = Mutex()
    private val dao = db.dao()
    val events = dao.observeEvents(PROFILE)
    val syncState = dao.observeSyncState(PROFILE)

    suspend fun refresh(): SyncResult = mutex.withLock {
        val catalog = client.fetch() // Validation finishes before any database write.
        db.withTransaction {
            val previous = dao.syncState(PROFILE)
            demand(previous == null || catalog.revision >= previous.releaseRevision, "이전 배포판은 적용하지 않습니다. 저장된 일정을 유지합니다.")
            if (previous?.releaseRevision == catalog.revision) {
                demand(previous.contentHash == catalog.hash, "같은 배포판 번호의 내용이 바뀌었습니다. 저장된 일정을 유지합니다.")
                dao.putSyncState(previous.copy(lastSuccessAt = clock.millis()))
                return@withTransaction result(catalog, true)
            }
            dao.ensureGame(GameEntity(PROFILE))
            dao.markEventsMissing(PROFILE)
            dao.markChecksMissing(PROFILE)
            dao.markTasksMissing(PROFILE)
            for (phase in catalog.phases) {
                val old = dao.event(phase.event.sourceKey)
                demand(old == null || phase.event.revision >= old.revision, "일정 개정 번호가 이전보다 낮습니다.")
                // Metadata tables are upserted; user records are never replaced or reset.
                dao.putEvent(phase.event)
                phase.checks.forEach { dao.putCheck(it) }
                phase.tasks.forEach { dao.putTask(it) }
            }
            dao.putSyncState(SyncStateEntity(PROFILE, catalog.revision, catalog.hash, catalog.sourceCheckedAt, clock.millis()))
            result(catalog, false)
        }
    }

    private fun result(catalog: ParsedCatalog, same: Boolean) = SyncResult(catalog.revision, catalog.phases.size,
        catalog.phases.count { it.event.timeState == "NEEDS_REVIEW" }, same)

    suspend fun setChecked(eventKey: String, itemKey: String, checked: Boolean) {
        db.withTransaction {
            demand(dao.check(eventKey, itemKey) != null, "체크할 항목이 없습니다.")
            dao.putCheckRecord(CheckRecordEntity(eventKey, itemKey, checked, if (checked) clock.millis() else null))
        }
    }
}
