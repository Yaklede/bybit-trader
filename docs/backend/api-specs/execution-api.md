# Execution API

All execution endpoints require `Authorization: Bearer $BOT_CONTROL_TOKEN`.

## POST /execution/evaluate-and-submit

Evaluates the runtime aggressive M5 strategy and submits a private Bybit market
order only when `BOT_PRIVATE_EXECUTION_ENABLED=true` and the bot state is
`RUNNING`.

Request:

```json
{
  "symbol": "BTCUSDT",
  "timeframe": "M5",
  "candleLimit": 18000
}
```

Response fields:

- `status`: `DISABLED`, `SKIPPED_BY_MODE`, `NO_TRADE`, `REJECTED`, or
  `SUBMITTED`.
- `clientOrderId`: local idempotency id sent to Bybit as `orderLinkId`.
- `exchangeOrderId`: Bybit `orderId` when Bybit accepts the order request.
- `entryPrice`, `takeProfit`, `stopLoss`, `quantity`, `intendedRisk`: decimal
  strings used for the submitted order.

The create-order response is not treated as a fill. Use reconciliation to check
open orders, positions, and executions.

## POST /execution/reconcile

Queries Bybit open orders, position list, and recent executions for a symbol.

Request:

```json
{
  "symbol": "BTCUSDT"
}
```

## POST /execution/orders/cancel

Cancels an open Bybit order by `exchangeOrderId` or `clientOrderId`.

Request:

```json
{
  "symbol": "BTCUSDT",
  "clientOrderId": "bt-BTCUSDT-1719705600000-1-B"
}
```

Errors from private exchange calls return:

```json
{
  "code": "EXCHANGE_EXECUTION_UNAVAILABLE",
  "message": "Private exchange execution provider is unavailable."
}
```
