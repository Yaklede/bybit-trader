# Volume Flow Tuning Log

## 2026-07-01 BTCUSDT 1-year replay

Source note: measured against
`build/runtime-test/bybit-trader-1y-backtest.sqlite`, covering
`2025-06-30T10:38:00Z` to `2026-06-30T10:37:00Z`. These numbers are local
backtest outputs, not live-trading return guarantees.

Target note: the current daily objective is `0.5%` to `2%`. The best candidate
below returns `37.12981%` over 365 observed calendar days, which is about
`0.10172%` simple average return per observed day. The target is not met.

## Baseline

The previous three-leg composite was:

- `trend_down_retest`: M5 `TREND_DOWN` breakout continuation with retest
  confirmation.
- `trend_down_close`: M5 `TREND_DOWN` breakout continuation with close
  confirmation.
- `range_failed_break`: M5 `RANGE` failed-break reversal.

Baseline result:

| Net return | Max drawdown | Trades | Profit factor | Expectancy |
| --- | --- | --- | --- | --- |
| `26.83583%` | `2.10462%` | `26` | `6.99393` | `0.46243R` |

## Current Candidate

The current best candidate adds:

- `trend_down_retest_runner`: relaxed M5 `TREND_DOWN` retest leg using
  `RUNNER` exit, `relativeVolumeThreshold=3.5`,
  `volumeZScoreThreshold=0.5`, `runnerTrailActivationR=0.8`,
  `runnerTrailDistanceR=0.75`.
- `range_failed_break_loose`: relaxed M5 `RANGE` failed-break leg using
  `relativeVolumeThreshold=2.5`, `targetR=0.8`, `minBodyRatio=0.35`,
  `minDirectionalCloseStrength=0.6`, `minRejectionWickRatio=0.2`.

Candidate result:

| Net return | Max drawdown | Trades | Profit factor | Expectancy | Active days |
| --- | --- | --- | --- | --- | --- |
| `37.12981%` | `2.10462%` | `29` | `8.42085` | `0.55443R` | `29` |

Per-leg accepted performance:

| Leg | Trades | Net PnL | Profit factor | Expectancy |
| --- | ---: | ---: | ---: | ---: |
| `trend_down_retest` | 11 | `1813.03547` | `9.56224` | `0.75567R` |
| `trend_down_close` | 5 | `242.80777` | `14.71319` | `0.20631R` |
| `range_failed_break` | 9 | `369.93765` | `2.66562` | `0.17512R` |
| `trend_down_retest_runner` | 1 | `1031.50000` | n/a | `4.06695R` |
| `range_failed_break_loose` | 3 | `255.70061` | `6.24096` | `0.36381R` |

Risk note: the added runner leg contributes one accepted trade in this replay:
`2026-06-25T13:30:00Z`, short side, `+4.06695R`, exit reason
`TRAILING_STOP`. This improves the annual replay but should be treated as a
candidate, not a production-ready strategy, until walk-forward and monthly
stability checks are added.

Monthly accepted PnL:

| Month | PnL | Trades |
| --- | ---: | ---: |
| `2025-08` | `219.19` | 1 |
| `2025-10` | `211.73` | 1 |
| `2025-11` | `62.69` | 2 |
| `2025-12` | `791.07` | 8 |
| `2026-01` | `271.54` | 3 |
| `2026-02` | `258.85` | 3 |
| `2026-03` | `613.51` | 3 |
| `2026-04` | `287.43` | 3 |
| `2026-05` | `-128.41` | 1 |
| `2026-06` | `1125.39` | 4 |

## Rejected Paths

- M5 setup-close breakout continuation increased trade count to 35 but lowered
  composite net return to `24.63207%`, raised max drawdown to `3.63243%`, and
  produced only `1.04259` profit factor on the added leg.
- M1 trend-down breakout close-confirmation increased trade count to 41 but
  lowered composite net return to `25.11653%`, raised max drawdown to
  `3.95224%`, and added a weak leg with only `1.06242` profit factor.
- M1 range failed-break and M5 volume rejection had negative train-period
  results, so they were rejected before composite adoption.
- M5 `TREND_UP` long breakout had negative train-period results, so it remains
  excluded.

## Reproduction Body

POST this body to `/backtests/volume-flow/composite/run` on a local server that
uses the 1-year runtime database:

```json
{
  "symbol": "BTCUSDT",
  "m1Limit": 525600,
  "m5Limit": 105120,
  "m15Limit": 35040,
  "initialEquity": 10000.0,
  "dailyTargetPct": null,
  "dailyStopPct": 1.0,
  "minTradesPerDay": 1,
  "maxTradesPerDay": 5,
  "maxConsecutiveLosses": 3,
  "legs": [
    {
      "id": "trend_down_retest",
      "riskFraction": 0.02,
      "setupMode": "BREAKOUT_CONTINUATION",
      "entryMode": "RETEST_CONFIRMATION",
      "sideMode": "BOTH",
      "setupTimeframe": "M5",
      "relativeVolumeThreshold": 5.0,
      "volumeZScoreThreshold": 1.5,
      "setupRangeLookback": 12,
      "requireM5Vwap": false,
      "requireContextVwap": true,
      "requireContextTrend": true,
      "allowedMarketRegimes": ["TREND_DOWN"],
      "requireRegimeSideAlignment": true,
      "requireKeyLevelProximity": true,
      "keyLevelTolerancePct": 0.0025,
      "avoidRangeMiddle": true,
      "minBodyRatio": 0.45,
      "minDirectionalCloseStrength": 0.70,
      "minEntryRiskPct": 0.008,
      "maxEntryRiskPct": 0.015,
      "targetR": 1.2,
      "exitMode": "FIXED_TARGET",
      "maxHoldM1Candles": 30
    },
    {
      "id": "trend_down_close",
      "riskFraction": 0.02,
      "setupMode": "BREAKOUT_CONTINUATION",
      "entryMode": "CLOSE_CONFIRMATION",
      "sideMode": "BOTH",
      "setupTimeframe": "M5",
      "relativeVolumeThreshold": 3.5,
      "volumeZScoreThreshold": 0.5,
      "setupRangeLookback": 8,
      "requireM5Vwap": false,
      "requireContextVwap": true,
      "requireContextTrend": true,
      "allowedMarketRegimes": ["TREND_DOWN"],
      "requireRegimeSideAlignment": true,
      "requireKeyLevelProximity": true,
      "keyLevelTolerancePct": 0.0025,
      "avoidRangeMiddle": true,
      "minBodyRatio": 0.55,
      "minDirectionalCloseStrength": 0.70,
      "minEntryRiskPct": 0.008,
      "maxEntryRiskPct": 0.015,
      "targetR": 1.2,
      "exitMode": "FIXED_TARGET",
      "maxHoldM1Candles": 15
    },
    {
      "id": "range_failed_break",
      "riskFraction": 0.02,
      "setupMode": "FAILED_BREAK_REVERSAL",
      "entryMode": "CLOSE_CONFIRMATION",
      "sideMode": "BOTH",
      "setupTimeframe": "M5",
      "relativeVolumeThreshold": 3.5,
      "volumeZScoreThreshold": 0.5,
      "setupRangeLookback": 12,
      "requireM5Vwap": false,
      "requireContextVwap": false,
      "requireContextTrend": false,
      "allowedMarketRegimes": ["RANGE"],
      "requireRegimeSideAlignment": false,
      "requireKeyLevelProximity": true,
      "keyLevelTolerancePct": 0.0025,
      "avoidRangeMiddle": true,
      "minBodyRatio": 0.25,
      "minDirectionalCloseStrength": 0.70,
      "minRejectionWickRatio": 0.25,
      "entryLookaheadM1Candles": 5,
      "minEntryRiskPct": 0.008,
      "maxEntryRiskPct": 0.015,
      "targetR": 1.0,
      "exitMode": "FIXED_TARGET",
      "maxHoldM1Candles": 30
    },
    {
      "id": "trend_down_retest_runner",
      "riskFraction": 0.02,
      "setupMode": "BREAKOUT_CONTINUATION",
      "entryMode": "RETEST_CONFIRMATION",
      "sideMode": "BOTH",
      "setupTimeframe": "M5",
      "relativeVolumeThreshold": 3.5,
      "volumeZScoreThreshold": 0.5,
      "setupRangeLookback": 8,
      "requireM5Vwap": false,
      "requireContextVwap": true,
      "requireContextTrend": true,
      "allowedMarketRegimes": ["TREND_DOWN"],
      "requireRegimeSideAlignment": true,
      "requireKeyLevelProximity": true,
      "keyLevelTolerancePct": 0.0025,
      "avoidRangeMiddle": true,
      "minBodyRatio": 0.45,
      "minDirectionalCloseStrength": 0.70,
      "minRejectionWickRatio": 0.25,
      "entryLookaheadM1Candles": 5,
      "minEntryRiskPct": 0.008,
      "maxEntryRiskPct": 0.015,
      "maxEstimatedFeeR": 0.2,
      "targetR": 1.2,
      "exitMode": "RUNNER",
      "runnerTrailActivationR": 0.8,
      "runnerTrailDistanceR": 0.75,
      "breakevenTriggerR": null,
      "maxHoldM1Candles": 30
    },
    {
      "id": "range_failed_break_loose",
      "riskFraction": 0.02,
      "setupMode": "FAILED_BREAK_REVERSAL",
      "entryMode": "CLOSE_CONFIRMATION",
      "sideMode": "BOTH",
      "setupTimeframe": "M5",
      "relativeVolumeThreshold": 2.5,
      "volumeZScoreThreshold": 0.5,
      "setupRangeLookback": 12,
      "requireM5Vwap": false,
      "requireContextVwap": false,
      "requireContextTrend": false,
      "allowedMarketRegimes": ["RANGE"],
      "requireRegimeSideAlignment": false,
      "requireKeyLevelProximity": true,
      "keyLevelTolerancePct": 0.0025,
      "avoidRangeMiddle": true,
      "minBodyRatio": 0.35,
      "minDirectionalCloseStrength": 0.60,
      "minRejectionWickRatio": 0.20,
      "entryLookaheadM1Candles": 5,
      "minEntryRiskPct": 0.008,
      "maxEntryRiskPct": 0.015,
      "maxEstimatedFeeR": 0.2,
      "targetR": 0.8,
      "exitMode": "FIXED_TARGET",
      "runnerTrailActivationR": 1.0,
      "runnerTrailDistanceR": 0.5,
      "breakevenTriggerR": null,
      "maxHoldM1Candles": 30
    }
  ]
}
```

## Tuning Gate

The composite backtest response now returns `monthlyPerformance` so the next
tuning loop can reject candidates that only improve the annual total through one
or two isolated trades. The next missing validation is a walk-forward gate that
compares multiple contiguous train/test slices instead of one `60/40` split.

Do not raise per-trade risk just to force the daily target until a candidate
passes the monthly and walk-forward stability gates. Raising risk before then
would only scale the same sparse-return profile.
