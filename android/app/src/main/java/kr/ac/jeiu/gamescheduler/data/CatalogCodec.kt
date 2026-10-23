package kr.ac.jeiu.gamescheduler.data

import com.google.gson.*
import java.net.URI
import java.security.MessageDigest
import java.time.Instant

const val PROFILE = "endfield-gryphline-asia"
const val RAW_BASE = "https://raw.githubusercontent.com/pktewon/game-homework-scheduler/main/data/"
const val MANIFEST_URL = RAW_BASE + "manifest.json"
class CatalogException(message: String) : Exception(message)
internal fun demand(ok: Boolean, message: String) { if (!ok) throw CatalogException(message) }
internal fun JsonObject.text(key: String): String {
    val v = get(key)
    demand(v is JsonPrimitive && v.isString && v.asString.isNotBlank(), "필수 문자열 오류: $key")
    return v.asString
}
internal fun JsonObject.number(key: String): Long {
    val v = get(key)
    demand(v is JsonPrimitive && v.isNumber && Regex("[0-9]+").matches(v.asString), "정수 오류: $key")
    return v.asString.toLongOrNull() ?: throw CatalogException("정수 범위 오류: $key")
}
internal fun JsonObject.flag(key: String): Boolean {
    val v = get(key)
    demand(v is JsonPrimitive && v.isBoolean, "참/거짓 오류: $key")
    return v.asBoolean
}
internal fun JsonObject.obj(key: String): JsonObject = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    ?: throw CatalogException("객체 오류: $key")
internal fun JsonObject.items(key: String): List<JsonElement> {
    val v = get(key)
    demand(v is JsonArray && v.size() <= 2000, "목록 오류: $key")
    return v.asJsonArray.toList()
}
internal fun JsonObject.timestamp(key: String): Long? {
    demand(has(key), "시각 필드 누락: $key")
    if (get(key).isJsonNull) return null
    val value = text(key)
    demand(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z").matches(value), "UTC 시각 오류: $key")
    return try { Instant.parse(value).toEpochMilli() } catch (_: Exception) { throw CatalogException("유효하지 않은 시각: $key") }
}
internal fun jsonObject(bytes: ByteArray): JsonObject = try {
    JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
} catch (_: Exception) { throw CatalogException("JSON을 읽을 수 없습니다.") }
fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

data class ManifestEntry(val revision: Long, val url: String, val hash: String, val bytes: Long)
data class ParsedPhase(val event: EventEntity, val checks: List<CheckTemplateEntity>, val tasks: List<DailyTaskEntity>)
data class ParsedCatalog(val revision: Long, val hash: String, val sourceCheckedAt: Long, val phases: List<ParsedPhase>)

object CatalogCodec {
    fun manifest(bytes: ByteArray): ManifestEntry {
        val root = jsonObject(bytes)
        demand(root.number("schemaVersion") == 1L, "지원하지 않는 매니페스트입니다.")
        val matches = root.items("profiles").map { it.asJsonObject }.filter { it.text("profileKey") == PROFILE }
        demand(matches.size == 1, "이용 서버의 배포 정보를 확인할 수 없습니다.")
        val item = matches.single()
        demand(item.number("schemaVersion") == 1L && item.number("releaseSchemaVersion") == 1L, "지원하지 않는 배포 형식입니다.")
        val revision = item.number("releaseRevision")
        val url = item.text("url")
        val hash = item.text("sha256")
        val size = item.number("bytes")
        demand(revision > 0 && url == RAW_BASE + "releases/endfield-asia-v$revision.json", "배포 주소가 올바르지 않습니다.")
        demand(Regex("[a-f0-9]{64}").matches(hash) && size in 1..1_000_000, "배포 무결성 정보 오류")
        return ManifestEntry(revision, url, hash, size)
    }

    private fun noPersonalState(element: JsonElement) {
        if (element.isJsonObject) for ((key, value) in element.asJsonObject.entrySet()) {
            demand(key !in setOf("isDone", "completedAt", "completionRecords", "dailyTaskRecords", "accessToken", "userId"), "공개 피드에 개인 상태가 포함되어 있습니다.")
            noPersonalState(value)
        } else if (element.isJsonArray) element.asJsonArray.forEach(::noPersonalState)
    }

    fun catalog(bytes: ByteArray, manifest: ManifestEntry): ParsedCatalog {
        demand(bytes.size.toLong() == manifest.bytes && sha256(bytes) == manifest.hash, "다운로드 파일의 무결성 검사가 실패했습니다.")
        val root = jsonObject(bytes)
        noPersonalState(root)
        demand(root.number("schemaVersion") == 1L && root.number("releaseSchemaVersion") == 1L, "지원하지 않는 일정 형식입니다.")
        demand(root.number("releaseRevision") == manifest.revision && root.number("catalogRevision") > 0, "배포판 번호가 일치하지 않습니다.")
        demand(root.text("publicationState") == "PUBLISHED" && root.flag("autoImportAllowed") && !root.flag("exampleOnly")
            && root.text("reviewState") == "SOURCE_REVIEWED", "검수된 배포 JSON만 받을 수 있습니다.")
        val profile = root.obj("profile")
        demand(profile.text("profileKey") == PROFILE && profile.text("gameKey") == "endfield"
            && profile.text("publisherKey") == "GRYPHLINE" && profile.text("serverRegion") == "ASIA"
            && profile.text("locale") == "ko-KR" && profile.text("serverUtcOffset") == "+08:00", "이용 서버와 일정 프로필이 다릅니다.")
        val checkedAt = root.obj("source").timestamp("checkedAt") ?: throw CatalogException("원문 확인 시각 누락")
        val contract = root.obj("importContract")
        demand(contract.text("completionMode") == "MANUAL_ONLY" && contract.flag("preserveLocalChecksAndRecords"), "완료 기록 보존 계약이 다릅니다.")
        val phases = mutableListOf<ParsedPhase>()
        val groups = root.items("groups")
        val groupKeys = mutableSetOf<String>()
        val phaseKeys = mutableSetOf<String>()
        for (groupElement in groups) {
            val group = groupElement.asJsonObject
            val groupKey = group.text("groupKey")
            demand(groupKeys.add(groupKey), "중복 이벤트 묶음")
            val groupTitle = group.text("title")
            val notes = group.items("notes").joinToString("\n") { it.asJsonObject.text("text") }
            val members = group.items("phases")
            demand(members.isNotEmpty(), "빈 이벤트 묶음")
            for (member in members) {
                val p = member.asJsonObject
                val key = p.text("sourceKey")
                demand(key.startsWith("$PROFILE:$groupKey:") && phaseKeys.add(key), "일정 식별자 오류 또는 중복")
                val kind = p.text("phaseKind")
                val shape = p.text("timeShape")
                val state = p.text("timeState")
                demand(kind in setOf("PLAY", "ATTENDANCE", "CHALLENGE", "WEB", "SHOP", "CONTENT_UPDATE"), "지원하지 않는 일정 종류")
                demand(p.text("reviewState") == "SOURCE_REVIEWED" && p.number("revision") > 0, "일정 검수 정보 오류")
                val start = p.timestamp("startAt")
                val end = p.timestamp("endAt")
                val occurs = p.timestamp("occursAt")
                val reasons = p.items("reviewReasons").map { it.asString }
                when (shape) {
                    "WINDOW" -> {
                        demand(kind != "CONTENT_UPDATE" && occurs == null && (start == null || end == null || start < end), "기간 순서 또는 종류 오류")
                        val expectedReasons = listOfNotNull(if (start == null) "START_NOT_EXPLICIT" else null, if (end == null) "END_NOT_EXPLICIT" else null)
                        demand(reasons == expectedReasons && state == if (expectedReasons.isEmpty()) "CONFIRMED" else "NEEDS_REVIEW", "기간 확인 상태 불일치")
                    }
                    "INSTANT" -> demand(kind == "CONTENT_UPDATE" && start == null && end == null && occurs != null && state == "CONFIRMED" && reasons.isEmpty(), "콘텐츠 업데이트 시점 오류")
                    else -> throw CatalogException("지원하지 않는 시간 형식")
                }
                val source = p.text("sourceUrl")
                val sourceUri = URI(source)
                demand(sourceUri.scheme == "https" && sourceUri.host == "endfield.gryphline.com"
                    && sourceUri.port == -1 && sourceUri.rawQuery == null && sourceUri.rawFragment == null
                    && Regex("/ko-kr/news/[0-9]+").matches(sourceUri.path), "공식 출처 주소 오류")
                val checkKeys = mutableSetOf<String>()
                val checks = p.items("checkTemplates").mapIndexed { index, e ->
                    val c = e.asJsonObject
                    val itemKey = c.text("sourceItemKey")
                    demand(checkKeys.add(itemKey), "중복 체크 항목")
                    CheckTemplateEntity(key, itemKey, c.text("title"), index)
                }
                val taskKeys = mutableSetOf<String>()
                val tasks = p.items("dailyTaskTemplates").map { e ->
                    val t = e.asJsonObject
                    val taskKey = t.text("sourceTaskKey")
                    demand(taskKeys.add(taskKey) && t.text("recurrence") == "DAILY" && t.text("resetPolicy") == "GAME_RESET"
                        && t.text("templateOrigin") == "SUGGESTED_FROM_NOTICE" && t.text("completionMode") == "MANUAL_ONLY"
                        && !t.flag("autoCreateRecordsAllowed"), "숙제 템플릿 오류")
                    val evidence = t.text("evidenceKind")
                    demand(evidence in setOf("ATTENDANCE", "DAILY_MISSION"), "일일 숙제 근거 오류")
                    if (evidence == "ATTENDANCE") demand(t.number("cumulativeTargetDays") in 1..365, "누적 출석 목표 오류")
                    DailyTaskEntity(key, taskKey, t.text("title"), t.toString())
                }
                demand(shape != "INSTANT" || (checks.isEmpty() && tasks.isEmpty()), "콘텐츠 업데이트를 수행 숙제로 등록할 수 없습니다.")
                phases += ParsedPhase(EventEntity(key, PROFILE, groupKey, groupTitle, p.text("title"), p.number("revision"),
                    kind, shape, occurs, start, end, state, source, p.obj("sourceEvidence").toString(), notes), checks, tasks)
            }
        }
        val summary = root.obj("summary")
        demand(phases.isNotEmpty() && phases.size <= 2000 && summary.number("groups") == groups.size.toLong()
            && summary.number("phases") == phases.size.toLong()
            && summary.number("confirmedPeriods") == phases.count { it.event.timeShape == "WINDOW" && it.event.timeState == "CONFIRMED" }.toLong()
            && summary.number("reviewPeriods") == phases.count { it.event.timeState == "NEEDS_REVIEW" }.toLong()
            && summary.number("confirmedInstants") == phases.count { it.event.timeShape == "INSTANT" }.toLong()
            && summary.number("dailyTasks") == phases.sumOf { it.tasks.size }.toLong(), "일정 집계가 일치하지 않습니다.")
        return ParsedCatalog(manifest.revision, manifest.hash, checkedAt, phases)
    }
}
