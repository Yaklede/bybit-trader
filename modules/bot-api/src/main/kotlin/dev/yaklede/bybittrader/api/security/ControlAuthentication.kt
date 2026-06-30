package dev.yaklede.bybittrader.api.security

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.bearer

fun Application.configureControlAuthentication(controlCredential: String?) {
    install(Authentication) {
        bearer("control") {
            authenticate { bearerCredential ->
                val presentedCredential = bearerCredential.run { token }
                if (controlCredential != null && presentedCredential == controlCredential) {
                    UserIdPrincipal("operator")
                } else {
                    null
                }
            }
        }
    }
}
