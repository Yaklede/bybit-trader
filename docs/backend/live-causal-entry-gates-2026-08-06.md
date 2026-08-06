# Live causal entry gates

## Scope

This milestone prevents the live/testnet execution path from submitting an
automatic order more than once for the same closed candle or from submitting an
order after the causal entry window has passed. It does not approve a strategy
for live trading and does not change the rejected production profile.

## Contract

For an automatic decision at time `t` and timeframe `f`:

1. Only candles whose `openedAt` is before the boundary containing `t` are
   visible to the strategy.
2. The latest visible candle must open exactly one timeframe before that
   boundary. A gap returns `LATEST_CLOSED_CANDLE_MISSING`.
3. Evaluation must start no later than `maximumEntryDelay` after the boundary.
   A late evaluation returns `ENTRY_WINDOW_EXPIRED`.
4. The execution service derives `SIGNAL_AT_<latest-opened-at>` and persists it
   with every accepted or rejected signal. Strategy-authored reason text is not
   trusted as the idempotency key.
5. A previously accepted key for the same strategy and symbol returns
   `DUPLICATE_SIGNAL`, regardless of side.
6. Evaluation and submission are serialized within the service process so the
   duplicate check and order submission cannot race with another API or loop
   invocation.

`BOT_EXECUTION_MAX_ENTRY_DELAY_SECONDS` configures the entry window and defaults
to `30`. The process still fails closed when market sync cannot provide the
latest closed candle.

## Verification

- A currently open candle remains invisible.
- A missing latest closed candle is rejected.
- A complete latest candle evaluated after the configured delay is rejected.
- A strategy that emits no timestamp reason code receives a service-derived
  decision key.
- A second evaluation of the same candle submits no additional order.

This gate reduces stale and duplicate execution risk. It is not evidence of
positive expectancy, execution parity for dynamic exits, or production
readiness.
