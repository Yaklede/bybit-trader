# UX Writing Run Manifest

Status: complete

## Target Files

- `modules/bot-app/src/main/kotlin/dev/yaklede/bybittrader/app/Application.kt`
- `modules/bot-app/src/main/kotlin/dev/yaklede/bybittrader/app/VolumeConfirmedTrendLiveAlertPolicy.kt`
- `modules/bot-app/src/test/kotlin/dev/yaklede/bybittrader/app/VolumeConfirmedTrendLiveAlertPolicyTest.kt`
- `docs/backend/tdd/volume-confirmed-trend-live-execution.md`

## Writing Contract

- WRITING.md reviewed: yes
- TERMS.md reviewed: yes
- Locale: ko
- Product concept: private automatic-trading operations
- Tone: direct, calm, and action-oriented

## Copy Review

- Korean: use 해요체 and explain the operator's next action
- English: not applicable
- Terms: retain exchange identifiers only where they are required for incident recovery
- Error messages: include what was blocked, preserved recovery evidence, and what to check next
- Buttons and CTAs: not applicable
- Empty/loading/success states: not applicable
- Naming: retain the established H4 live-trading alert titles

## Rewrites

| Before | After | Reason |
| --- | --- | --- |
| 승인 실패 항목만 표시 | 승인 실패와 기존 중단·주문·포지션 증거를 함께 표시 | 승인 상실이 미해결 주문 원인을 숨기지 않게 함 |
| 다음 행동 없음 | 대시보드와 Bybit 확인 및 재활성화 금지 안내 | 운영자가 바로 복구 판단을 할 수 있게 함 |

## Exceptions

- Owner: backend maintainer
- Scope: pre-existing technical prose in `docs/backend/tdd/volume-confirmed-trend-live-execution.md`
- Reason: the explicit UX harness scan reports 15 existing diagnostics for code-contract terms such as `null`, `payload`, `token`, and `schema`, plus technical failure descriptions outside this change. Rewriting those terms would weaken the TDD contract and is outside this alert-copy change.
- Mitigation: the changed Korean alert body is asserted directly in `VolumeConfirmedTrendLiveAlertPolicyTest`; the repository UX harness still passes its configured active scope.

## Verification

- Alert policy regression tests: passed
- Kotlin style check: passed
- Full Gradle `test lint build`: passed
- Node regression tests: 517 passed
- Backend and Business OpenDock harnesses: passed
- Repository UX harness: passed
- Explicit TDD-file UX scan: exception documented above
