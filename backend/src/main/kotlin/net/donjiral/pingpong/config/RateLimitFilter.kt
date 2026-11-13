package net.donjiral.pingpong.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * 간단한 인메모리 속도 제한 (단일 인스턴스 기준).
 * - 모든 /api: IP당 분당 제한 (스팸/플러딩 완화)
 * - /api/uploads: IP당 시간/일 제한 (S3 저장 비용 폭탄 방지)
 *
 * Caddy 뒤에 있으므로 X-Forwarded-For 로 실제 IP를 봅니다.
 * 단, XFF 는 클라이언트가 마음대로 위조할 수 있으므로
 * (1) 신뢰하는 프록시(로컬/사설망)에서 들어온 요청일 때만 참고하고
 * (2) 프록시가 직접 덧붙인 "맨 오른쪽" 값을 사용합니다.
 * 이렇게 하지 않으면 헤더만 매 요청 바꿔서 속도 제한을 전부 우회할 수 있습니다.
 */
@Component
class RateLimitFilter(
    @Value("\${app.ratelimit.api-per-min:120}") private val apiPerMin: Int,
    @Value("\${app.ratelimit.upload-per-hour:20}") private val uploadPerHour: Int,
    @Value("\${app.ratelimit.upload-per-day:50}") private val uploadPerDay: Int,
    @Value("\${app.ratelimit.upload-global-per-day:2000}") private val uploadGlobalPerDay: Int,
    @Value("\${app.ratelimit.visit-per-day:20}") private val visitPerDay: Int
) : OncePerRequestFilter() {

    // key -> [windowId, count]
    private val minuteApi = ConcurrentHashMap<String, LongArray>()
    private val hourUpload = ConcurrentHashMap<String, LongArray>()
    private val dayUpload = ConcurrentHashMap<String, LongArray>()
    private val dayVisit = ConcurrentHashMap<String, LongArray>()
    private val globalUpload = LongArray(2) // [dayId, count]

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val path = req.requestURI
        if (!path.startsWith("/api") || req.method == "OPTIONS") {
            chain.doFilter(req, res); return
        }
        val ip = clientIp(req)
        val nowSec = System.currentTimeMillis() / 1000
        val minWin = nowSec / 60
        val hourWin = nowSec / 3600
        val dayWin = nowSec / 86400

        if (!allow(minuteApi, ip, minWin, apiPerMin)) {
            reject(res, "요청이 너무 많아요. 잠시 후 다시 시도해주세요."); return
        }

        // 업로드는 S3 저장 비용에 직결되므로 별도로 더 촘촘히 막습니다.
        if (path.startsWith("/api/uploads") && req.method == "POST") {
            if (!allowGlobal(globalUpload, dayWin, uploadGlobalPerDay)) {
                reject(res, "오늘 업로드 한도에 도달했어요. 내일 다시 이용해주세요."); return
            }
            if (!allow(hourUpload, ip, hourWin, uploadPerHour)) {
                reject(res, "업로드가 너무 잦아요. 잠시 후 다시 시도해주세요."); return
            }
            if (!allow(dayUpload, ip, dayWin, uploadPerDay)) {
                reject(res, "오늘 업로드 가능 횟수를 모두 사용했어요. 내일 다시 이용해주세요."); return
            }
        }

        // 방문 카운터는 POST 마다 디스크에 씁니다.
        // 막지 않으면 누구나 무한히 DB(EBS)를 부풀릴 수 있습니다.
        // 한도를 넘으면 에러 대신 조용히 GET(읽기 전용)으로 바꿔서 흘려보냅니다.
        // 이러면 방문자 화면에는 정상적인 숫자가 그대로 보이고, 쓰기만 멈춥니다.
        var forward: HttpServletRequest = req
        if (path.startsWith("/api/visits") && req.method == "POST" &&
            !allow(dayVisit, ip, dayWin, visitPerDay)
        ) {
            forward = object : HttpServletRequestWrapper(req) {
                override fun getMethod() = "GET"
            }
        }

        // 맵 과다 증가 방지
        if (minuteApi.size > 50000) minuteApi.clear()
        if (hourUpload.size > 50000) hourUpload.clear()
        if (dayUpload.size > 100000) dayUpload.clear()
        if (dayVisit.size > 100000) dayVisit.clear()

        chain.doFilter(forward, res)
    }

    private fun allow(map: ConcurrentHashMap<String, LongArray>, key: String, window: Long, limit: Int): Boolean {
        val c = map.computeIfAbsent(key) { longArrayOf(window, 0) }
        synchronized(c) {
            if (c[0] != window) { c[0] = window; c[1] = 0 }
            c[1]++
            return c[1] <= limit
        }
    }

    private fun allowGlobal(slot: LongArray, window: Long, limit: Int): Boolean {
        synchronized(slot) {
            if (slot[0] != window) { slot[0] = window; slot[1] = 0 }
            slot[1]++
            return slot[1] <= limit
        }
    }

    /**
     * 실제 클라이언트 IP.
     * 직접 연결한 상대(remoteAddr)가 신뢰할 수 있는 프록시일 때만 XFF 를 봅니다.
     * XFF 는 "위조값1, 위조값2, 진짜값" 형태가 되므로 맨 오른쪽을 씁니다.
     */
    // 관리자 암호 실패 횟수(마스크폴 난이도)도 같은 실제 IP 로 센다
    fun clientIp(req: HttpServletRequest): String {
        val remote = req.remoteAddr ?: "unknown"
        if (!isTrustedProxy(remote)) return remote
        val xff = req.getHeader("X-Forwarded-For")
        if (xff.isNullOrBlank()) return remote
        return xff.split(",").map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: remote
    }

    private fun isTrustedProxy(addr: String): Boolean = try {
        val a = InetAddress.getByName(addr)
        a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress
    } catch (e: Exception) {
        false
    }

    private fun reject(res: HttpServletResponse, msg: String) {
        res.status = 429
        res.contentType = "application/json;charset=UTF-8"
        res.writer.write("""{"message":"$msg"}""")
    }
}
