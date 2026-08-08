# UX Writing Run Manifest

Status: complete

## Target Files

- `modules/bot-app/src/main/kotlin/dev/yaklede/bybittrader/app/Application.kt`
- `modules/bot-app/src/test/kotlin/dev/yaklede/bybittrader/app/VolumeConfirmedTrendLiveAlertPolicyTest.kt`

## Writing Contract

- WRITING.md reviewed: yes
- TERMS.md reviewed: yes
- Locale: ko
- Product concept: private automatic-trading operations
- Tone: direct, calm, and action-oriented

## Copy Review

- Korean: explain that the cancellation response identity is inconsistent
- English: not applicable
- Terms: keep the provider diagnostic code after the plain-language explanation
- Error messages: distinguish an unsafe cancellation acknowledgement from terminal cancellation confirmation
- Buttons and CTAs: not applicable
- Empty/loading/success states: not applicable
- Naming: retain the established H4 live-trading halt title

## Rewrites

| Before | After | Reason |
| --- | --- | --- |
| 일반 안전 조건 실패 | 주문 취소 응답 ID가 취소 요청과 일치하지 않음 | 운영자가 취소 요청과 terminal 주문 상태를 별도로 확인하게 함 |

## Exceptions

- None.

## Verification

- Targeted engine, Bybit adapter, and Korean alert tests: passed
- Full Gradle `test lint build`: passed (96 tasks)
- Node regression suite: passed (517/517)
- Backend, Business, and UX Writing workspace harnesses: passed
