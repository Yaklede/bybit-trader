package dev.yaklede.bybittrader.app

import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveApprovalStatus
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class VolumeConfirmedTrendLiveApprovalDefinitionTest :
    StringSpec({
        "repository receipt is explicitly not approved" {
            val receipt =
                loadVolumeConfirmedTrendLiveApprovalReceipt(
                    Path.of("../../config/volume-confirmed-trend-live-approval.json"),
                )

            receipt.status shouldBe VolumeConfirmedTrendLiveApprovalStatus.NOT_APPROVED
            receipt.liveExecutionAllowed shouldBe false
            receipt.approvalId shouldBe null
            receipt.shadowSessionId shouldBe null
        }
    })
