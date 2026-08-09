package dev.yaklede.bybittrader.app

import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalGateStatus
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalService
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class VolumeConfirmedTrendRuntimeDefinitionTest :
    StringSpec({
        "loads only the frozen protocol and matching deterministic bootstrap" {
            val runtime =
                loadVolumeConfirmedTrendRuntimeDefinition(
                    protocolPath = trendRepositoryFile("config/volume-confirmed-trend-ensemble-v1.json"),
                    bootstrapPath = trendRepositoryFile("config/volume-confirmed-trend-ensemble-v1-bootstrap.json"),
                )

            runtime.protocol.protocolId shouldBe "volume-confirmed-trend-ensemble-v1"
            runtime.protocol.candidateId shouldBe "vcte_4h_majority_001"
            runtime.bootstrap.sourceH4BarCount shouldBe 13_947
            runtime.bootstrap.indicatorState.processedBars shouldBe 13_947
            runtime.bootstrap.indicatorState.targetSide shouldBe Side.SELL
            runtime.bootstrap.indicatorState.recentVolumes.size shouldBe 42
        }

        "loads the frozen protocol without requiring Shadow bootstrap state" {
            val protocol =
                loadVolumeConfirmedTrendProtocolDefinition(
                    trendRepositoryFile("config/volume-confirmed-trend-ensemble-v1.json"),
                )

            protocol.protocolId shouldBe "volume-confirmed-trend-ensemble-v1"
            protocol.candidateId shouldBe "vcte_4h_majority_001"
            protocol.symbol.value shouldBe "BTCUSDT"
        }

        "rejects a modified protocol even when its schema remains valid" {
            val temporary = Files.createTempFile("trend-protocol-tampered", ".json")
            val source = Files.readString(trendRepositoryFile("config/volume-confirmed-trend-ensemble-v1.json"))
            Files.writeString(temporary, source.replace("\"minimumMajorityVotes\": 3", "\"minimumMajorityVotes\": 4"))

            shouldThrow<IllegalArgumentException> {
                loadVolumeConfirmedTrendProtocolDefinition(temporary)
            }.message shouldBe "Trend protocol fingerprint is not approved by this runtime."
        }

        "rejects a modified bootstrap before restoring indicator state" {
            val temporary = Files.createTempFile("trend-bootstrap-tampered", ".json")
            val source = Files.readString(trendRepositoryFile("config/volume-confirmed-trend-ensemble-v1-bootstrap.json"))
            Files.writeString(temporary, source.replace("\"processedBars\": 13947", "\"processedBars\": 13946"))

            shouldThrow<IllegalArgumentException> {
                loadVolumeConfirmedTrendRuntimeDefinition(
                    protocolPath = trendRepositoryFile("config/volume-confirmed-trend-ensemble-v1.json"),
                    bootstrapPath = temporary,
                )
            }.message shouldBe "Trend bootstrap fingerprint is not approved by this runtime."
        }

        "loads only frozen historical parity and forward approval evidence" {
            val approval =
                loadVolumeConfirmedTrendApprovalDefinition(
                    protocolPath = trendRepositoryFile("config/volume-confirmed-trend-ensemble-v1.json"),
                )

            approval.historicalEvidence.externalVenuePassed shouldBe true
            approval.historicalEvidence.kotlinCoreParityPassed shouldBe true
            approval.historicalEvidence.runtimeReplayParityPassed shouldBe true
            approval.historicalEvidence.liveRiskPolicyParityPassed shouldBe true
            approval.liveRiskPolicy.maximumDailyLossFraction shouldBe null
            approval.liveRiskPolicy.maximumAccountDrawdownFraction.toPlainString() shouldBe "0.35"
            approval.liveRiskPolicy.maximumConsecutiveLosses shouldBe null
            approval.liveRiskPolicy.riskStateMaximumAge shouldBe Duration.ofMinutes(10)
            approval.liveRiskPolicy.walletReconciliationMaximumAge shouldBe Duration.ofMinutes(10)
            approval.liveRiskPolicy.walletReconciliationConfirmedMismatchCount shouldBe 2
            approval.forwardPolicy.minimumCalendarDays shouldBe 90
            approval.forwardPolicy.minimumClosedTrades shouldBe 5
            approval.forwardPolicy.maximumDrawdownPct shouldBe 35.0
        }

        "repository Live risk evidence passes while fresh Shadow remains required" {
            val approval =
                loadVolumeConfirmedTrendApprovalDefinition(
                    protocolPath = trendRepositoryFile("config/volume-confirmed-trend-ensemble-v1.json"),
                )
            val report =
                VolumeConfirmedTrendApprovalService(
                    historicalEvidence = approval.historicalEvidence,
                    forwardPolicy = approval.forwardPolicy,
                    shadowReportProvider = { null },
                ).evaluate()

            report.status shouldBe VolumeConfirmedTrendApprovalStatus.SHADOW_DISABLED
            report.gates.single { it.id == "LIVE_RISK_POLICY_PARITY" }.status shouldBe
                VolumeConfirmedTrendApprovalGateStatus.PASS
            report.readyForHumanReview shouldBe false
            report.liveExecutionAllowed shouldBe false
        }

        "rejects modified runtime parity evidence" {
            val temporary = Files.createTempFile("trend-runtime-parity-tampered", ".json")
            val source = Files.readString(trendRepositoryFile("config/volume-confirmed-trend-ensemble-v1-runtime-parity-result.json"))
            Files.writeString(temporary, source.replace("\"mismatchCount\": 0", "\"mismatchCount\": 1"))

            shouldThrow<IllegalArgumentException> {
                loadVolumeConfirmedTrendApprovalDefinition(
                    protocolPath = trendRepositoryFile("config/volume-confirmed-trend-ensemble-v1.json"),
                    runtimeParityResultPath = temporary,
                )
            }.message shouldBe "Frozen trend runtime parity result fingerprint mismatch."
        }

        "rejects modified Live risk parity evidence" {
            val temporary = Files.createTempFile("trend-live-risk-parity-tampered", ".json")
            val source =
                Files.readString(
                    trendRepositoryFile("config/volume-confirmed-trend-ensemble-v1-live-risk-parity-result.json"),
                )
            Files.writeString(temporary, source.replace("\"maximumConsecutiveLosses\": null", "\"maximumConsecutiveLosses\": 4"))

            shouldThrow<IllegalArgumentException> {
                loadVolumeConfirmedTrendApprovalDefinition(
                    protocolPath = trendRepositoryFile("config/volume-confirmed-trend-ensemble-v1.json"),
                    liveRiskParityResultPath = temporary,
                )
            }.message shouldBe "Frozen trend live risk parity result fingerprint mismatch."
        }
    })

private fun trendRepositoryFile(relativePath: String): Path =
    generateSequence(Path.of("").toAbsolutePath().normalize()) { current -> current.parent }
        .map { root -> root.resolve(relativePath) }
        .firstOrNull(Files::isRegularFile)
        ?: error("Could not locate repository file: $relativePath")
