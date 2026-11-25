package net.donjiral.pingpong.post

import net.donjiral.pingpong.notify.BoardWriteEvent
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus

@Service
class PostService(
    private val postRepository: PostRepository,
    private val commentRepository: CommentRepository,
    private val events: ApplicationEventPublisher,
    @Value("\${app.admin.secret:}") private val adminSecret: String,
    @Value("\${app.admin.reserved-authors:돈지랄}") reservedRaw: String
) {
    private val encoder = BCryptPasswordEncoder()
    private val reserved = reservedRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    // 공백·특수문자 제거 + 소문자화 (예: " 돈지랄 ", "$$돈지랄$$", "돈 지 랄" → "돈지랄")
    private fun normalize(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
    private fun isReservedName(author: String): Boolean {
        val a = normalize(author)
        return reserved.any { a.contains(normalize(it)) }
    }

    @Transactional(readOnly = true)
    fun list(board: String, category: String?, q: String?, page: Int, size: Int): PageResponse<PostSummaryResponse> {
        val cat = category?.takeIf { it.isNotBlank() && it != "전체" }
        val query = q?.takeIf { it.isNotBlank() }
        val safePage = page.coerceAtLeast(0)
        val safeSize = size.coerceIn(1, 50)
        val result = postRepository.search(board, cat, query, PageRequest.of(safePage, safeSize))
        return PageResponse(
            items = result.content.map { it.toSummary() },
            page = result.number,
            size = result.size,
            totalPages = result.totalPages,
            totalElements = result.totalElements
        )
    }

    @Transactional
    fun get(id: Long): PostDetailResponse {
        val post = find(id)
        post.views += 1
        return post.toDetail()
    }

    @Transactional
    fun create(req: PostCreateRequest): PostDetailResponse {
        val author = req.author?.trim()?.takeIf { it.isNotEmpty() } ?: "익명"
        // 예약 이름(돈지랄 등)은 관리자 암호가 맞아야만 사용 가능 (공백·특수문자 우회 차단)
        if (isReservedName(author) && (adminSecret.isBlank() || req.adminPassword != adminSecret)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "‘$author’ 은(는) 관리자만 사용할 수 있는 이름이에요")
        }
        val post = Post(
            board = req.board?.trim()?.takeIf { it.isNotEmpty() } ?: "pingpong",
            category = req.category,
            title = req.title.trim(),
            author = author,
            content = req.content,
            media = req.media?.trim()?.takeIf { it.isNotEmpty() },
            passwordHash = encoder.encode(req.password)
        )
        val saved = postRepository.save(post)
        events.publishEvent(
            BoardWriteEvent(BoardWriteEvent.Kind.POST, saved.board ?: "pingpong", saved.id, saved.title, saved.author, saved.content)
        )
        return saved.toDetail()
    }

    @Transactional
    fun update(id: Long, req: PostUpdateRequest): PostDetailResponse {
        val post = find(id)
        verify(post, req.password)
        post.title = req.title.trim()
        post.content = req.content
        post.media = req.media?.trim()?.takeIf { it.isNotEmpty() }
        return post.toDetail()
    }

    @Transactional
    fun delete(id: Long, password: String) {
        val post = find(id)
        verify(post, password)
        postRepository.delete(post)
    }

    @Transactional
    fun like(id: Long): Int {
        val post = find(id)
        post.likes += 1
        return post.likes
    }

    @Transactional
    fun addComment(id: Long, req: CommentCreateRequest): CommentResponse {
        val post = find(id)
        val comment = Comment(
            post = post,
            author = req.author?.trim()?.takeIf { it.isNotEmpty() } ?: "익명",
            content = req.content
        )
        post.comments.add(comment)
        val saved = commentRepository.save(comment)
        events.publishEvent(
            BoardWriteEvent(BoardWriteEvent.Kind.COMMENT, post.board ?: "pingpong", post.id, post.title, saved.author, saved.content)
        )
        return CommentResponse(saved.id, saved.author, saved.content, saved.createdAt)
    }

    private fun find(id: Long): Post =
        postRepository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "글을 찾을 수 없습니다")
        }

    private fun verify(post: Post, password: String) {
        // 관리자 암호는 모든 글의 수정/삭제 마스터 암호로 인정 (글 비밀번호 분실 대비)
        if (adminSecret.isNotBlank() && password == adminSecret) return
        if (!encoder.matches(password, post.passwordHash)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "비밀번호가 일치하지 않습니다")
        }
    }
}
