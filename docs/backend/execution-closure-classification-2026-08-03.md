# 실거래 종료 원인 분류

## 목적

Bybit `closed-pnl` 응답은 포지션 종료 손익을 제공하지만 종료 원인 자체를 충분히 설명하지 않는다. 종료 주문과 연결된 체결 이력의 `createType`, `stopOrderType`, `execType`을 함께 사용해 원장과 Discord 알림의 종료 사유를 구체화한다.

## 분류 우선순위

1. 체결 `createType` 또는 `stopOrderType`의 ADL 표기: `ADL`
2. 청산 또는 takeover 표기: `LIQUIDATION`
3. trailing 표기: `TRAILING_STOP`
4. take-profit 표기: `TAKE_PROFIT`
5. stop-loss 표기: `STOP_LOSS`
6. 봇의 `time-` client order id: `TIME_EXIT`
7. 봇의 `close-` 또는 `manual-` client order id: `MANUAL_EXIT`
8. 근거가 없으면 `UNKNOWN`

`CLOSED_PNL`은 종료 원인으로 더 이상 사용하지 않는다. 포지션이 닫혔다는 사실과 어떻게 닫혔는지는 별개의 정보이므로, 근거가 없는 경우를 `UNKNOWN`으로 표시해 오판을 막는다.

## 체결 연결

`closed-pnl.orderId`를 체결 이력의 `orderId`와 우선 연결하고, order id가 없을 때만 `orderLinkId`를 사용한다. Bybit 주문 하나에 여러 체결이 발생할 수 있으므로 체결 메타데이터는 단일 행으로 덮어쓰지 않고 원래 체결 목록을 보존한다.

종료 라이프사이클의 `fillVwap`는 진입가가 아니라 종료 체결의 `avgExitPrice`를 기록한다. 거래 원장의 `entryPrice`와 `exitPrice`는 각각 진입·종료 가격으로 유지한다.

## 운영 한계

private WebSocket `execution` 스트림이 종료 체결을 관찰하면 reconciliation loop를 즉시 깨운다. 원장 기록과 Discord 알림은 기존 REST reconciliation 경로가 수행하므로, WebSocket 이벤트 자체를 원장에 직접 기록하지 않는다. REST 경로는 재연결·재시작·이벤트 누락을 복구하는 at-least-once 경로로 유지된다.

`BOT_PRIVATE_EXECUTION_STREAM_ENABLED`의 기본값은 private execution이 활성화된 경우 `true`이며, 장애 조사나 단계적 롤아웃이 필요할 때만 명시적으로 `false`로 끌 수 있다. private stream은 중복 `execId`를 프로세스 내에서 억제하지만, 최종 중복 방지는 SQLite closure 식별자가 담당한다.

관련 Bybit 계약:

- https://bybit-exchange.github.io/docs/v5/order/execution
- https://bybit-exchange.github.io/docs/v5/websocket/private/execution
- https://bybit-exchange.github.io/docs/api-explorer/v5/position/close-pnl
