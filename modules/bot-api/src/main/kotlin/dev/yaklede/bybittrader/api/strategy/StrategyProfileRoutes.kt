package dev.yaklede.bybittrader.api.strategy

import dev.yaklede.bybittrader.engine.backtest.StrategyValidationStatus
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowAggressiveExecutionContract
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowAggressiveProfiles
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

private const val AGGRESSIVE_PROFILE_ID = "volume-flow-aggressive"

fun Route.configureStrategyProfileRoutes(strategyProfileService: StrategyProfileService) {
    authenticate("control") {
        get("/strategy/profiles") {
            call.respond(strategyProfileService.current().toResponse())
        }
        post("/strategy/profiles/active") {
            val request = call.receive<ActivateStrategyProfileRequest>().validated()
            call.respond(strategyProfileService.activate(request.profileId).toResponse())
        }
    }
}

class StrategyProfileService(
    private val statePath: Path,
    runtimeExecutionContract: VolumeFlowAggressiveExecutionContract = VolumeFlowAggressiveProfiles.current().executionContract,
    private val clock: () -> Instant = Instant::now,
) {
    private val aggressiveRuntimeProfile = VolumeFlowAggressiveProfiles.current()
    private val profiles =
        listOf(
            StrategyProfile(
                id = AGGRESSIVE_PROFILE_ID,
                name = "공격형",
                kind = StrategyProfileKind.RUNTIME,
                strategyName = aggressiveRuntimeProfile.strategyName,
                description = "거래량 흡수 구간 이후 돌파를 양방향으로 추적하는 운영 기본 전략이에요.",
                riskNote = "검증 전 전략 · 현실 체결 기준 재검증이 끝날 때까지 주문 한도를 유지해야 해요.",
                backtestEndpoint = "/backtests/volume-flow/aggressive/current/run",
                isDefault = true,
                validationStatus = aggressiveRuntimeProfile.validationStatus.toApiValidationStatus(),
                strategyContractVersion = aggressiveRuntimeProfile.contractVersion,
                expectedExecutionContractFingerprint = aggressiveRuntimeProfile.executionContract.fingerprint,
                runtimeExecutionContractFingerprint = runtimeExecutionContract.fingerprint,
                executionContractMatched = runtimeExecutionContract == aggressiveRuntimeProfile.executionContract,
            ),
            StrategyProfile(
                id = "volume-flow-composite-current",
                name = "안정형 비교",
                kind = StrategyProfileKind.BACKTEST_ONLY,
                strategyName = "volume-flow-composite-current",
                description = "M1/M5/M15 composite 설정을 쓰는 비교용 백테스트 기준이에요.",
                riskNote = "백테스트 비교 전용 · 실주문 루프는 공격형 전략을 유지해요.",
                backtestEndpoint = "/backtests/volume-flow/composite/current/run",
                isDefault = false,
                validationStatus = StrategyProfileValidationStatus.BACKTEST_ONLY,
                strategyContractVersion = null,
                expectedExecutionContractFingerprint = null,
                runtimeExecutionContractFingerprint = null,
                executionContractMatched = null,
            ),
        )

    suspend fun current(): StrategyProfileState =
        StrategyProfileState(
            activeProfile = resolveActiveProfile(),
            runtimeProfile = requireProfile(AGGRESSIVE_PROFILE_ID),
            profiles = profiles,
            updatedAt = readUpdatedAt(),
        )

    suspend fun activate(profileId: String): StrategyProfileState {
        val profile = requireProfile(profileId)
        withContext(Dispatchers.IO) {
            statePath.parent?.let(Files::createDirectories)
            Files.writeString(statePath, profile.id)
        }
        return StrategyProfileState(
            activeProfile = profile,
            runtimeProfile = requireProfile(AGGRESSIVE_PROFILE_ID),
            profiles = profiles,
            updatedAt = clock(),
        )
    }

    private suspend fun resolveActiveProfile(): StrategyProfile {
        val storedId =
            withContext(Dispatchers.IO) {
                if (Files.exists(statePath)) {
                    Files.readString(statePath).trim().takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }
        return storedId?.let { id -> profiles.firstOrNull { it.id == id } } ?: requireProfile(AGGRESSIVE_PROFILE_ID)
    }

    private suspend fun readUpdatedAt(): Instant? =
        withContext(Dispatchers.IO) {
            if (Files.exists(statePath)) {
                Files.getLastModifiedTime(statePath).toInstant()
            } else {
                null
            }
        }

    private fun requireProfile(profileId: String): StrategyProfile =
        profiles.firstOrNull { it.id == profileId }
            ?: throw IllegalArgumentException("지원하지 않는 전략 프로필이에요. 전략 목록을 새로고침한 뒤 다시 선택해 주세요.")
}

data class StrategyProfileState(
    val activeProfile: StrategyProfile,
    val runtimeProfile: StrategyProfile,
    val profiles: List<StrategyProfile>,
    val updatedAt: Instant?,
)

data class StrategyProfile(
    val id: String,
    val name: String,
    val kind: StrategyProfileKind,
    val strategyName: String,
    val description: String,
    val riskNote: String,
    val backtestEndpoint: String,
    val isDefault: Boolean,
    val validationStatus: StrategyProfileValidationStatus,
    val strategyContractVersion: String?,
    val expectedExecutionContractFingerprint: String?,
    val runtimeExecutionContractFingerprint: String?,
    val executionContractMatched: Boolean?,
)

enum class StrategyProfileValidationStatus {
    UNVERIFIED,
    VERIFIED,
    BACKTEST_ONLY,
}

enum class StrategyProfileKind {
    RUNTIME,
    BACKTEST_ONLY,
}

@Serializable
data class ActivateStrategyProfileRequest(
    val profileId: String,
) {
    fun validated(): ActivateStrategyProfileRequest {
        require(profileId.isNotBlank()) {
            "전략 프로필을 선택해 주세요."
        }
        return copy(profileId = profileId.trim())
    }
}

@Serializable
data class StrategyProfileStateResponse(
    val activeProfileId: String,
    val runtimeProfileId: String,
    val activeProfile: StrategyProfileResponse,
    val runtimeProfile: StrategyProfileResponse,
    val profiles: List<StrategyProfileResponse>,
    val updatedAt: String?,
)

@Serializable
data class StrategyProfileResponse(
    val id: String,
    val name: String,
    val kind: String,
    val kindLabel: String,
    val strategyName: String,
    val description: String,
    val riskNote: String,
    val backtestEndpoint: String,
    val defaultProfile: Boolean,
    val runtimeEligible: Boolean,
    val validationStatus: String,
    val liveExpansionAllowed: Boolean,
    val strategyContractVersion: String?,
    val expectedExecutionContractFingerprint: String?,
    val runtimeExecutionContractFingerprint: String?,
    val executionContractMatched: Boolean?,
)

private fun StrategyProfileState.toResponse(): StrategyProfileStateResponse =
    StrategyProfileStateResponse(
        activeProfileId = activeProfile.id,
        runtimeProfileId = runtimeProfile.id,
        activeProfile = activeProfile.toResponse(),
        runtimeProfile = runtimeProfile.toResponse(),
        profiles = profiles.map(StrategyProfile::toResponse),
        updatedAt = updatedAt?.toString(),
    )

private fun StrategyProfile.toResponse(): StrategyProfileResponse =
    StrategyProfileResponse(
        id = id,
        name = name,
        kind = kind.name,
        kindLabel =
            when (kind) {
                StrategyProfileKind.RUNTIME -> "운영"
                StrategyProfileKind.BACKTEST_ONLY -> "백테스트"
            },
        strategyName = strategyName,
        description = description,
        riskNote = riskNote,
        backtestEndpoint = backtestEndpoint,
        defaultProfile = isDefault,
        runtimeEligible = kind == StrategyProfileKind.RUNTIME,
        validationStatus = validationStatus.name,
        liveExpansionAllowed =
            validationStatus == StrategyProfileValidationStatus.VERIFIED && executionContractMatched != false,
        strategyContractVersion = strategyContractVersion,
        expectedExecutionContractFingerprint = expectedExecutionContractFingerprint,
        runtimeExecutionContractFingerprint = runtimeExecutionContractFingerprint,
        executionContractMatched = executionContractMatched,
    )

private fun StrategyValidationStatus.toApiValidationStatus(): StrategyProfileValidationStatus =
    when (this) {
        StrategyValidationStatus.UNVERIFIED -> StrategyProfileValidationStatus.UNVERIFIED
        StrategyValidationStatus.VERIFIED -> StrategyProfileValidationStatus.VERIFIED
    }
