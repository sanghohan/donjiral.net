package net.donjiral.pingpong.maskfall

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.*
import jakarta.servlet.http.HttpServletRequest
import net.donjiral.pingpong.config.RateLimitFilter
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 마스크폴 난이도 단계(1~10). 앱이 출격할 때마다 GET /api/maskfall/tune?vc=<versionCode>&t=<시험 코드> 로 묻는다.
 * 고르는 순서: 시험 코드가 맞으면 시험 단계 → 그 버전에 정한 단계 → 기본 단계.
 * 단계별 실제 배율은 앱(src/qf-tune.js)에 들어 있고, 여기서는 숫자 하나만 고른다.
 * 보스 체력 %(30~200, 100 = 앱 그대로)도 같은 답에 실어 보낸다 — 마지막 보스 체력에만 곱한다. 시험 코드가 맞으면 시험용 %.
 * 난이도·보스별 %(bossHpBy, [일반·정예·악몽] × MF-01~MF-10)는 전체 % 위에 한 번 더 곱해진다.
 * 바꾸기(관리자 페이지 donjiral.net/apps/maskfall/tune/)는 X-Admin-Secret 헤더 = app.admin.secret 일 때만.
 */
@Entity
@Table(name = "maskfall_tune")
class MaskfallTune(
    @Id var id: Int = 1,
    var defaultLevel: Int = 5,
    @Column(length = 2000) var versions: String = "{}",   // {"134":5,"135":6}
    @Column(length = 40) var testCode: String = "",
    var testLevel: Int = 5,
    // 나중에 더한 열: 이미 있는 행에는 null 로 들어오므로 nullable (null = 100)
    var bossHp: Int? = 100,
    var testBossHp: Int? = 100,
    @Column(length = 400) var bossHpBy: String? = null,   // [[일반 MF-01~10],[정예],[악몽]]
    var updatedAt: Instant = Instant.now()
)

@Entity
@Table(name = "maskfall_tune_log")
class MaskfallTuneLog(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    var at: Instant = Instant.now(),
    @Column(length = 2400) var snapshot: String = ""
)

interface MaskfallTuneRepository : JpaRepository<MaskfallTune, Int>
interface MaskfallTuneLogRepository : JpaRepository<MaskfallTuneLog, Long>

data class TuneSettings(
    val defaultLevel: Int = 5,
    val versions: Map<String, Int> = emptyMap(),
    val testCode: String = "",
    val testLevel: Int = 5,
    val bossHp: Int = 100,
    val testBossHp: Int = 100,
    val bossHpBy: List<List<Int>> = List(3) { List(10) { 100 } },
    val updatedAt: String? = null
)

data class TuneLogEntry(val at: String, val settings: TuneSettings)

@Service
class MaskfallTuneService(
    private val repo: MaskfallTuneRepository,
    private val logRepo: MaskfallTuneLogRepository,
    private val mapper: ObjectMapper
) {
    private val levels = 1..10
    private val bossHpRange = 30..200
    private val versionsType = object : TypeReference<Map<String, Int>>() {}
    private val tableType = object : TypeReference<List<List<Int>>>() {}
    private fun bossHpByOf(t: MaskfallTune): List<List<Int>> =
        runCatching { mapper.readValue(t.bossHpBy ?: "", tableType) }.getOrNull()
            ?.takeIf { it.size == 3 && it.all { row -> row.size == 10 } } ?: List(3) { List(10) { 100 } }

    private fun row(): MaskfallTune = repo.findById(1).orElseGet { MaskfallTune() }
    private fun versionsOf(t: MaskfallTune): Map<String, Int> =
        runCatching { mapper.readValue(t.versions, versionsType) }.getOrDefault(emptyMap())

    @Transactional(readOnly = true)
    fun resolve(vc: Int?, code: String?): Map<String, Any> {
        val t = row()
        val test = t.testCode.isNotEmpty() && code != null &&
            MessageDigest.isEqual(code.trim().toByteArray(), t.testCode.toByteArray())
        val byVersion = vc?.let { versionsOf(t)[it.toString()] }
        return when {
            test -> mapOf("level" to t.testLevel, "source" to "test", "bossHp" to (t.testBossHp ?: 100), "bossHpBy" to bossHpByOf(t))
            byVersion != null -> mapOf("level" to byVersion, "source" to "version", "bossHp" to (t.bossHp ?: 100), "bossHpBy" to bossHpByOf(t))
            else -> mapOf("level" to t.defaultLevel, "source" to "default", "bossHp" to (t.bossHp ?: 100), "bossHpBy" to bossHpByOf(t))
        }
    }

    @Transactional(readOnly = true)
    fun settings(): TuneSettings = row().let {
        TuneSettings(it.defaultLevel, versionsOf(it), it.testCode, it.testLevel, it.bossHp ?: 100, it.testBossHp ?: 100, bossHpByOf(it), it.updatedAt.toString())
    }

    @Transactional(readOnly = true)
    fun history(limit: Int): List<TuneLogEntry> =
        logRepo.findAll(PageRequest.of(0, limit, org.springframework.data.domain.Sort.by("id").descending())).content
            .mapNotNull { log -> runCatching { TuneLogEntry(log.at.toString(), mapper.readValue(log.snapshot, TuneSettings::class.java)) }.getOrNull() }

    @Transactional
    fun save(req: TuneSettings): TuneSettings {
        fun bad(msg: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, msg)
        if (req.defaultLevel !in levels || req.testLevel !in levels) bad("단계는 1~10")
        if (req.bossHp !in bossHpRange || req.testBossHp !in bossHpRange) bad("보스 체력은 30~200%")
        if (req.bossHpBy.size != 3 || req.bossHpBy.any { row -> row.size != 10 || row.any { it !in bossHpRange } }) bad("보스별 체력은 난이도 3 × 보스 10, 각 30~200%")
        if (req.versions.size > 50) bad("버전은 50개까지")
        req.versions.forEach { (k, v) -> if (!k.matches(Regex("\\d{1,6}")) || v !in levels) bad("버전 $k: 버전 번호(숫자)와 단계 1~10") }
        val code = req.testCode.trim()
        if (code.isNotEmpty() && !code.matches(Regex("[A-Za-z0-9_-]{4,32}"))) bad("시험 코드는 영문·숫자 4~32자")
        val t = row()
        t.defaultLevel = req.defaultLevel
        t.versions = mapper.writeValueAsString(req.versions.toSortedMap(compareBy { it.toInt() }))
        t.testCode = code
        t.testLevel = req.testLevel
        t.bossHp = req.bossHp
        t.testBossHp = req.testBossHp
        t.bossHpBy = mapper.writeValueAsString(req.bossHpBy)
        t.updatedAt = Instant.now()
        repo.save(t)
        val saved = settings()
        logRepo.save(MaskfallTuneLog(snapshot = mapper.writeValueAsString(saved.copy(updatedAt = null))))
        return saved
    }
}

@RestController
@RequestMapping("/api/maskfall")
class MaskfallTuneController(
    private val service: MaskfallTuneService,
    private val ipOf: RateLimitFilter,
    @Value("\${app.admin.secret:}") private val adminSecret: String,
    @Value("\${app.maskfall.app-key:}") private val appKey: String
) {
    // IP당 1시간에 암호 10번 틀리면 그 시간 동안 막는다 (분당 120회 전체 제한과 별개)
    private val failures = ConcurrentHashMap<String, LongArray>()

    // 앱 키(MASKFALL_APP_KEY)를 정해 두면 X-QF-Key 헤더가 같은 요청만 답한다. 비어 있으면 누구나.
    // 키는 앱 안에 들어 있어 뜯어 보면 나온다 — 지나가는 호출을 막는 정도이고, 답에는 비밀이 없다.
    @GetMapping("/tune")
    fun tune(@RequestParam(required = false) vc: Int?, @RequestParam(required = false) t: String?,
             @RequestHeader("X-QF-Key", required = false) key: String?): Map<String, Any> {
        if (appKey.isNotBlank() && (key == null || !MessageDigest.isEqual(key.toByteArray(), appKey.toByteArray())))
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden")
        return service.resolve(vc, t)
    }

    @GetMapping("/admin/tune")
    fun get(@RequestHeader("X-Admin-Secret", required = false) key: String?, req: HttpServletRequest): Map<String, Any> {
        auth(key, req)
        return mapOf("settings" to service.settings(), "history" to service.history(30))
    }

    @PutMapping("/admin/tune")
    fun put(@RequestHeader("X-Admin-Secret", required = false) key: String?, req: HttpServletRequest,
            @RequestBody body: TuneSettings): Map<String, Any> {
        auth(key, req)
        return mapOf("settings" to service.save(body), "history" to service.history(30))
    }

    private fun auth(key: String?, req: HttpServletRequest) {
        val ip = ipOf.clientIp(req)
        val hour = System.currentTimeMillis() / 3_600_000
        val f = failures.compute(ip) { _, v -> if (v == null || v[0] != hour) longArrayOf(hour, 0) else v }!!
        if (f[1] >= 10) throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "잠시 후 다시 시도하세요")
        val ok = adminSecret.isNotBlank() && key != null &&
            MessageDigest.isEqual(key.toByteArray(), adminSecret.toByteArray())
        if (!ok) {
            synchronized(f) { f[1]++ }
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "관리자 암호가 맞지 않습니다")
        }
    }
}
