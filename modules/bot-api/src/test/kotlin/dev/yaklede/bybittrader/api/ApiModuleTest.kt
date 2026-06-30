package dev.yaklede.bybittrader.api

import dev.yaklede.bybittrader.domain.BotMode
import dev.yaklede.bybittrader.engine.control.BotControlService
import dev.yaklede.bybittrader.engine.control.BotRuntimeStatus
import dev.yaklede.bybittrader.engine.control.BotStateStore
import dev.yaklede.bybittrader.engine.control.ControlEvent
import dev.yaklede.bybittrader.engine.control.ControlEventRecorder
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.Instant

class ApiModuleTest :
    StringSpec({
        "health endpoint is public" {
            testApplication {
                application {
                    val stateStore = InMemoryStateStore()
                    configureApi(
                        stateStore = stateStore,
                        controlService = BotControlService(stateStore, InMemoryControlEventRecorder()),
                        controlCredential = "test-control-credential",
                    )
                }

                client.get("/health").status shouldBe HttpStatusCode.OK
            }
        }

        "control endpoints require bearer guard" {
            testApplication {
                application {
                    val stateStore = InMemoryStateStore()
                    configureApi(
                        stateStore = stateStore,
                        controlService = BotControlService(stateStore, InMemoryControlEventRecorder()),
                        controlCredential = "test-control-credential",
                    )
                }

                client
                    .post("/control/pause-all") {
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody("""{"reason":"manual"}""")
                    }.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        "authorized pause all request changes bot mode" {
            testApplication {
                val stateStore = InMemoryStateStore()
                application {
                    configureApi(
                        stateStore = stateStore,
                        controlService = BotControlService(stateStore, InMemoryControlEventRecorder()),
                        controlCredential = "test-control-credential",
                    )
                }

                client
                    .post("/control/pause-all") {
                        bearerAuth("test-control-credential")
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody("""{"reason":"manual"}""")
                    }.status shouldBe HttpStatusCode.OK

                stateStore.current().mode shouldBe BotMode.PAUSE_ALL
            }
        }
    })

private class InMemoryStateStore : BotStateStore {
    private var status =
        BotRuntimeStatus(
            mode = BotMode.RUNNING,
            updatedAt = Instant.parse("2026-06-30T00:00:00Z"),
            heartbeatAt = null,
        )

    override suspend fun current(): BotRuntimeStatus = status

    override suspend fun update(status: BotRuntimeStatus) {
        this.status = status
    }
}

private class InMemoryControlEventRecorder : ControlEventRecorder {
    override suspend fun record(event: ControlEvent) = Unit
}
