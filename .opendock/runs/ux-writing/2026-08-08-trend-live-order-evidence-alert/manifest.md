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

- Korean: use 해요체 and explain the mismatch before the action
- English: not applicable
- Terms: show the diagnostic code only after the plain-language explanation
- Error messages: state what differed, what to compare in Bybit, and that live execution must remain off
- Buttons and CTAs: not applicable
- Empty/loading/success states: not applicable
- Naming: retain the established H4 live-trading alert title

## Rewrites

| Before | After | Reason |
| --- | --- | --- |
| 내부 중단 코드만 표시 | 주문·체결·포지션 중 무엇이 일치하지 않는지 쉬운 말로 표시 | 운영자가 로그를 열기 전에 장애 범위를 알 수 있게 함 |
| 다음 행동 없음 | Bybit 주문·체결·포지션 비교와 확인 전 재가동 금지 안내 | 불명확한 상태에서 실거래를 다시 켜는 일을 막음 |

## Exceptions

- None.

## Verification

- Korean halt-alert regression test: passed
- Gradle `test lint build`: passed (96 tasks)
- Node regression suite: passed (517/517)
- Backend, Business, and UX Writing workspace harnesses: passed
