package dev.yaklede.bybittrader.engine.strategy

import java.time.Instant

enum class VolumeConfirmedTrendLiveApprovalStatus {
    NOT_APPROVED,
    APPROVED,
}

data class VolumeConfirmedTrendLiveApprovalReceipt(
    val schemaVersion: Int,
    val status: VolumeConfirmedTrendLiveApprovalStatus,
    val approvalId: String?,
    val protocolId: String,
    val candidateId: String,
    val protocolSha256: String,
    val policyId: String,
    val policySha256: String,
    val shadowSessionId: String?,
    val shadowEvidenceSha256: String?,
    val approvalReportSha256: String?,
    val approvedAt: Instant?,
    val approvedBy: String?,
    val liveExecutionAllowed: Boolean,
    val reasonCode: String,
) {
    init {
        require(schemaVersion == 1) { "Unsupported trend live approval receipt schema." }
        require(protocolId.isNotBlank() && candidateId.isNotBlank() && policyId.isNotBlank()) {
            "Trend live approval identities must not be blank."
        }
        require(protocolSha256.isSha256() && policySha256.isSha256()) {
            "Trend live approval protocol and policy fingerprints must be lowercase SHA-256 values."
        }
        require(reasonCode.isNotBlank()) { "Trend live approval reason must not be blank." }
        if (status == VolumeConfirmedTrendLiveApprovalStatus.APPROVED) {
            require(!approvalId.isNullOrBlank() && !shadowSessionId.isNullOrBlank() && !approvedBy.isNullOrBlank()) {
                "Approved trend live receipt identities must not be blank."
            }
            require(approvedAt != null) { "Approved trend live receipt requires its approval time." }
            require(shadowEvidenceSha256.isSha256() && approvalReportSha256.isSha256()) {
                "Approved trend live receipt requires evidence fingerprints."
            }
            require(liveExecutionAllowed) { "Approved trend live receipt must explicitly allow live execution." }
        } else {
            require(!liveExecutionAllowed) { "A non-approved trend live receipt cannot allow live execution." }
        }
    }
}

enum class VolumeConfirmedTrendLiveApprovalFailure {
    RECEIPT_NOT_APPROVED,
    RECEIPT_LIVE_EXECUTION_DISABLED,
    FORWARD_REPORT_NOT_READY,
    PROTOCOL_ID_MISMATCH,
    CANDIDATE_ID_MISMATCH,
    PROTOCOL_SHA256_MISMATCH,
    POLICY_ID_MISMATCH,
    POLICY_SHA256_MISMATCH,
    SHADOW_SESSION_MISMATCH,
    SHADOW_EVIDENCE_SHA256_MISMATCH,
    APPROVAL_REPORT_SHA256_MISMATCH,
}

data class VolumeConfirmedTrendLiveApprovalValidation(
    val liveExecutionAllowed: Boolean,
    val failures: List<VolumeConfirmedTrendLiveApprovalFailure>,
)

object VolumeConfirmedTrendLiveApprovalValidator {
    fun validate(
        receipt: VolumeConfirmedTrendLiveApprovalReceipt,
        report: VolumeConfirmedTrendApprovalReport,
        actualShadowEvidenceSha256: String?,
        actualApprovalReportSha256: String?,
    ): VolumeConfirmedTrendLiveApprovalValidation {
        val failures = mutableListOf<VolumeConfirmedTrendLiveApprovalFailure>()
        if (receipt.status != VolumeConfirmedTrendLiveApprovalStatus.APPROVED) {
            failures += VolumeConfirmedTrendLiveApprovalFailure.RECEIPT_NOT_APPROVED
        }
        if (!receipt.liveExecutionAllowed) {
            failures += VolumeConfirmedTrendLiveApprovalFailure.RECEIPT_LIVE_EXECUTION_DISABLED
        }
        if (!report.readyForHumanReview || report.status != VolumeConfirmedTrendApprovalStatus.READY_FOR_HUMAN_REVIEW) {
            failures += VolumeConfirmedTrendLiveApprovalFailure.FORWARD_REPORT_NOT_READY
        }
        if (receipt.protocolId != report.protocolId) {
            failures += VolumeConfirmedTrendLiveApprovalFailure.PROTOCOL_ID_MISMATCH
        }
        if (receipt.candidateId != report.candidateId) {
            failures += VolumeConfirmedTrendLiveApprovalFailure.CANDIDATE_ID_MISMATCH
        }
        if (receipt.protocolSha256 != report.protocolSha256) {
            failures += VolumeConfirmedTrendLiveApprovalFailure.PROTOCOL_SHA256_MISMATCH
        }
        if (receipt.policyId != report.policyId) {
            failures += VolumeConfirmedTrendLiveApprovalFailure.POLICY_ID_MISMATCH
        }
        if (receipt.policySha256 != report.policySha256) {
            failures += VolumeConfirmedTrendLiveApprovalFailure.POLICY_SHA256_MISMATCH
        }
        if (receipt.shadowSessionId != report.sessionId) {
            failures += VolumeConfirmedTrendLiveApprovalFailure.SHADOW_SESSION_MISMATCH
        }
        if (receipt.shadowEvidenceSha256 != actualShadowEvidenceSha256 || !actualShadowEvidenceSha256.isSha256()) {
            failures += VolumeConfirmedTrendLiveApprovalFailure.SHADOW_EVIDENCE_SHA256_MISMATCH
        }
        if (receipt.approvalReportSha256 != actualApprovalReportSha256 || !actualApprovalReportSha256.isSha256()) {
            failures += VolumeConfirmedTrendLiveApprovalFailure.APPROVAL_REPORT_SHA256_MISMATCH
        }
        return VolumeConfirmedTrendLiveApprovalValidation(
            liveExecutionAllowed = failures.isEmpty(),
            failures = failures,
        )
    }
}

private fun String?.isSha256(): Boolean = this?.matches(Regex("[0-9a-f]{64}")) == true
