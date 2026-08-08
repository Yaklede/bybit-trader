# UX Writing Run Manifest

Status: complete

## Target Files

- `modules/bot-app/src/main/kotlin/dev/yaklede/bybittrader/app/VolumeConfirmedTrendApprovalAlertPolicy.kt`

## Writing Contract

- WRITING.md reviewed: yes
- TERMS.md reviewed: yes
- Locale: ko
- Product concept: on-prem futures trading bot operations
- Tone: professional, direct, action-oriented 해요체

## Copy Review

- Korean: 상태, 핵심 수치, 남은 조건, 다음 행동을 한 메시지에 배치했다.
- English: 해당 없음.
- Terms: H4, PF, gate 같은 내부 축약어를 사용자 문구에서 제거했다.
- Error messages: 실패·지연 원인 범주와 운영자가 확인할 행동을 함께 적었다.
- Buttons and CTAs: 해당 없음.
- Empty/loading/success states: 검토 준비 상태에서도 자동 주문 차단과 사람 승인을 명시했다.
- Naming: “4시간 전략”을 공개 명칭으로 일관되게 사용했다.

## Rewrites

| Before | After | Reason |
| --- | --- | --- |
| H4 전진 검증 검토 준비 완료 | 4시간 전략 검토 준비 완료 | 내부 축약어를 제거했다. |
| 종료 거래 PF | 종료 거래 손익비 | 운영자가 바로 이해할 수 있는 말로 바꿨다. |
| 미통과 게이트 | 남은 조건 | 개발 용어를 줄였다. |

## Exceptions

없음.
