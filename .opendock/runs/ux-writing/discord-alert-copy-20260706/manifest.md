# UX Writing Run Manifest

Status: updated

## Target Files

- `apps/dashboard/src/App.jsx`
- `modules/bot-alerts/src/main/kotlin/dev/yaklede/bybittrader/alerts/DiscordWebhookAlertSink.kt`
- `modules/bot-api/src/main/kotlin/dev/yaklede/bybittrader/api/operations/OperationsSmokeRoutes.kt`
- `modules/bot-app/src/main/kotlin/dev/yaklede/bybittrader/app/Application.kt`

## Writing Contract

- WRITING.md reviewed: yes.
- TERMS.md reviewed: yes.
- Locale: ko.
- Product concept: Bybit 선물 매매봇 운영 알림.
- Tone: 해요체, calm operational copy, clear before clever.

## Copy Review

- Korean: Discord 알림 제목, 본문, 테스트 메시지를 한국어로 정리.
- English: API 이름과 상품명만 유지.
- Terms: auth, payload, endpoint 같은 내부 용어를 사용자 알림에서 사용하지 않음.
- Error messages: 루프 오류는 로그 확인이라는 다음 행동을 포함.
- Buttons and CTAs: 기존 대시보드 버튼명은 유지하고 전송 메시지만 수정.
- Empty/loading/success states: 해당 없음.
- Naming: smoke-test, startup, shutdown 같은 내부 코드명 노출을 제거.

## Rewrites

| Before | After | Reason |
| --- | --- | --- |
| `[INFO] startup` | `[안내] 봇 시작` | Discord에서 바로 이해할 수 있는 운영 알림으로 변경. |
| `Bybit Trader TESTNET smoke alert` | `Bybit Trader 테스트넷 알림 테스트예요.` | 테스트 알림 목적을 한국어로 명확히 표현. |
| `Execution loop failed with ...` | `실거래 루프에서 오류가 발생했어요. 로그에서 ... 내용을 확인해 주세요.` | 오류와 다음 행동을 함께 안내. |

## Exceptions

- Bybit Trader, BTCUSDT, API는 제품명 또는 허용된 기술 용어로 유지.
