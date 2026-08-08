# UX Writing Run Manifest

Status: complete

## Target Files

- `modules/bot-app/src/main/kotlin/dev/yaklede/bybittrader/app/Application.kt`
- `modules/bot-app/src/test/kotlin/dev/yaklede/bybittrader/app/VolumeConfirmedTrendLiveAlertPolicyTest.kt`

## Writing Contract

- WRITING.md reviewed: yes
- TERMS.md reviewed: yes
- Locale: ko
- Product concept: on-prem automated trading operations
- Tone: professional, direct, action-oriented hae-yo style

## Copy Review

- Korean: One operational state and one next action are stated in short sentences.
- English: Not applicable to this operator alert.
- Terms: Internal IOC and recovery-state names are not exposed in the alert copy.
- Error messages: The operator is told that cancellation is pending and where to verify it.
- Buttons and CTAs: Not applicable.
- Empty/loading/success states: The alert distinguishes pending cancellation from completion.
- Naming: The title names the current state rather than claiming cancellation succeeded.

## Rewrites

| Before | After | Reason |
| --- | --- | --- |
| No Discord message for exact-order cancellation | `H4 주문 취소 확인 중` | Exposes the unresolved state without claiming success. |
| No next action | `대시보드와 Bybit에서 주문 상태를 확인해 주세요.` | Gives the operator a concrete verification step. |

## Exceptions

None.
