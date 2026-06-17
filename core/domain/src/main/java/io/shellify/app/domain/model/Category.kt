package io.shellify.app.domain.model

data class Category(
    val id: Long = 0,
    val name: String,
    val sortIndex: Int = 0,
    val icon: String = "folder",
    val color: String = "#6D28D9",
    val sharedSpace: Boolean = false,
) {
    /**
     * Isolation key shared by every app in this category when [sharedSpace] is on.
     * Derived from the stable autoincrement id, so it never collides with a per-app
     * UUID and survives as long as the category exists. See [io.shellify.app.domain.usecase.ResolveIsolationIdUseCase].
     */
    val sharedIsolationId: String get() = "cat_$id"
}
