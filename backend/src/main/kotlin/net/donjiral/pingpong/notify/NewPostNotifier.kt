package net.donjiral.pingpong.notify

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import java.util.ArrayDeque

/** 게시판에 새 글/댓글이 저장(커밋)되면 발행되는 이벤트 */
data class BoardWriteEvent(
    val kind: Kind,
    val board: String,
    val postId: Long,
    val postTitle: String,
    val author: String,
    val content: String
) {
    enum class Kind { POST, COMMENT }
}

/**
 * 새 글·댓글을 관리자 메일로 알린다 (Gmail SMTP).
 * - MAIL_USERNAME / MAIL_PASSWORD / NOTIFY_EMAIL_TO 가 비어 있으면 아무것도 안 함
 * - 커밋 후 비동기로 보내므로 메일이 실패해도 글쓰기는 영향 없음
 * - 도배 대비: 1시간에 max-per-hour 통까지만 보냄
 */
@Component
class NewPostNotifier(
    private val mailSender: JavaMailSender,
    @Value("\${spring.mail.username:}") private val from: String,
    @Value("\${app.notify.email-to:}") private val to: String,
    @Value("\${app.notify.max-per-hour:30}") private val maxPerHour: Int,
    @Value("\${app.notify.site-base-url:https://www.donjiral.net}") private val siteBase: String,
    @Value("\${app.admin.reserved-authors:돈지랄}") reservedRaw: String
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val sentAt = ArrayDeque<Long>()
    private val reserved = reservedRaw.split(",").map { normalize(it) }.filter { it.isNotEmpty() }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    @Async
    @TransactionalEventListener
    fun on(e: BoardWriteEvent) {
        if (from.isBlank() || to.isBlank()) return
        // 관리자(예약 이름)가 직접 쓴 글은 알리지 않음
        if (reserved.any { normalize(e.author).contains(it) }) return
        if (!allow()) {
            log.warn("알림 메일 시간당 한도({}) 초과 — post={} 건너뜀", maxPerHour, e.postId)
            return
        }
        val link = postUrl(e.board, e.postId)
        val what = if (e.kind == BoardWriteEvent.Kind.POST) "새 글" else "새 댓글"
        val recipients = to.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()
        val msg = SimpleMailMessage().apply {
            setFrom(from)
            setTo(*recipients)
            subject = "[donjiral/${e.board}] $what: ${e.postTitle.take(60)}"
            text = buildString {
                appendLine("게시판: ${e.board}")
                appendLine("글: ${e.postTitle}")
                appendLine("작성자: ${e.author}")
                appendLine()
                appendLine(e.content.take(1000))
                if (e.content.length > 1000) appendLine("…(생략)")
                appendLine()
                appendLine(link)
            }
        }
        try {
            mailSender.send(msg)
        } catch (ex: Exception) {
            log.warn("알림 메일 실패 post={}: {}", e.postId, ex.message)
        }
    }

    private fun postUrl(board: String, id: Long): String {
        val base = siteBase.trimEnd('/')
        return when (board) {
            "ideas", "pingpong", "dev" -> "$base/$board/?post=$id"
            else -> "$base/apps/$board/?post=$id"
        }
    }

    @Synchronized
    private fun allow(): Boolean {
        val now = System.currentTimeMillis()
        while (sentAt.isNotEmpty() && now - sentAt.first() > 3_600_000) sentAt.removeFirst()
        if (sentAt.size >= maxPerHour) return false
        sentAt.addLast(now)
        return true
    }
}
