package io.shellify.app.domain.usecase

import io.shellify.app.domain.model.Category
import io.shellify.app.domain.model.WebApp
import org.junit.Assert.assertEquals
import org.junit.Test

class ResolveIsolationIdUseCaseTest {

    private val useCase = ResolveIsolationIdUseCase()

    private fun app(isolationId: String = "app-uuid"): WebApp =
        WebApp(id = 1, name = "Gmail", url = "https://mail.google.com", isolationId = isolationId)

    @Test
    fun `returns category shared id when category has shared space`() {
        val category = Category(id = 7, name = "Google", sharedSpace = true)

        val result = useCase(app(), category)

        assertEquals("cat_7", result)
    }

    @Test
    fun `returns app isolation id when category does not share space`() {
        val category = Category(id = 7, name = "Google", sharedSpace = false)

        val result = useCase(app("app-uuid"), category)

        assertEquals("app-uuid", result)
    }

    @Test
    fun `returns app isolation id when app has no category`() {
        val result = useCase(app("app-uuid"), null)

        assertEquals("app-uuid", result)
    }

    @Test
    fun `all apps in a shared category resolve to the same id`() {
        val category = Category(id = 3, name = "Google", sharedSpace = true)

        val first = useCase(app("uuid-a"), category)
        val second = useCase(app("uuid-b"), category)

        assertEquals(first, second)
        assertEquals("cat_3", first)
    }
}
