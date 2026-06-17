package io.shellify.app.domain.usecase

import io.shellify.app.domain.model.Category
import io.shellify.app.domain.model.WebApp

/**
 * Resolves the effective isolation identity for a launching app.
 *
 * When the app's category has a shared space enabled, every app in that category resolves to the
 * same partition key so logins and storage are shared. Otherwise the app keeps its own private
 * [WebApp.isolationId] — which is never mutated, so disabling sharing or moving the app out of the
 * category instantly restores its isolated session.
 */
class ResolveIsolationIdUseCase {
    operator fun invoke(app: WebApp, category: Category?): String =
        if (category?.sharedSpace == true) category.sharedIsolationId else app.isolationId
}
