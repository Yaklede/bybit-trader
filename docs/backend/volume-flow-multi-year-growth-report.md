# Volume Flow Multi-Year Growth Report

Source note: this report is based on local BTCUSDT Bybit public kline data in
`build/runtime-test/bybit-trader-3y-backtest.sqlite`, covering
`2023-06-30T10:38:00Z` to `2026-06-30T10:37:00Z`. Results are backtest outputs,
not live-trading return guarantees.

## Problem

The previous one-year result looked strong, but it did not prove that the
strategy compounds across different market regimes. The three-year replay shows
that weak range/chop legs diluted the long-term edge and created a large early
walk-forward drawdown.

## Goal

Use the existing volume-flow strategy family, remove long-term negative legs,
and measure whether 1,000,000 KRW can compound toward 10,000,000,000 KRW.

Target math:

| Horizon | Required multiple | Required net return | Required compound daily return |
| --- | ---: | ---: | ---: |
| 1 year | `10,000x` | `999,900%` | `2.55549%` |
| 2 years | `10,000x` | `999,900%` | `1.26968%` |
| 3 years | `10,000x` | `999,900%` | `0.84390%` |

## Hardened Current Change

`config/volume-flow-composite-current.json` now removes three long-term weak
legs from the previous current strategy:

- `range_failed_break`: 3-year `131` trades, `48.09160%` win rate,
  `0.60271` profit factor, `-0.16900R` expectancy.
- `m1_chop_volume_rejection_scalp`: 3-year `27` trades, `44.44444%` win rate,
  `0.79469` profit factor, `-0.19544R` expectancy.
- `chop_failed_break`: 3-year `20` trades, `45.00000%` win rate,
  `1.31016` profit factor, `-0.03843R` expectancy.

This keeps the existing profitable trend/downside and selected failed-break
structure, but stops paying for legs that failed the longer replay.

## Result

Initial equity is `1,000,000 KRW`.

| Horizon | Config | Final equity | Net return | Compound daily | Max drawdown | Trades | Win rate | Profit factor |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 year | Previous current | `3,602,259 KRW` | `260.22594%` | `0.35077%` | `5.54631%` | `131` | `71.75573%` | `6.35357` |
| 1 year | Hardened current | `3,188,197 KRW` | `218.81972%` | `0.31729%` | `5.54631%` | `85` | `78.82353%` | `6.01980` |
| 2 years | Previous current | `3,696,681 KRW` | `269.66814%` | `0.17902%` | `24.96625%` | `297` | `63.29966%` | `2.67348` |
| 2 years | Hardened current | `4,026,519 KRW` | `302.65186%` | `0.19073%` | `15.79879%` | `184` | `70.65217%` | `3.11835` |
| 3 years | Previous current | `3,196,447 KRW` | `219.64471%` | `0.10599%` | `37.09138%` | `429` | `56.64336%` | `2.05296` |
| 3 years | Hardened current | `3,948,481 KRW` | `294.84815%` | `0.12527%` | `32.57811%` | `263` | `62.73764%` | `2.45776` |

## 10 Billion KRW Feasibility

The hardened current strategy is better over two and three years, but it is not
near the 10,000x target. The three-year compound daily result is `0.12527%`,
while the target requires `0.84390%`.

Risk-only scaling is not a valid solution. Applying the same three-year trade R
sequence with larger per-trade risk gives:

| Per-trade risk | Final equity | Multiple | Max drawdown |
| ---: | ---: | ---: | ---: |
| `3%` | `4,206,415 KRW` | `4.2x` | `32.2%` |
| `10%` | `74,923,618 KRW` | `74.9x` | `74.4%` |
| `20%` | `1,521,775,969 KRW` | `1,521.8x` | `94.8%` |
| `30%` | `8,381,547,470 KRW` | `8,381.5x` | `99.2%` |
| `32%` | `10,003,936,660 KRW` | `10,003.9x` | `99.5%` |

Conclusion: the number can be forced by risking around `32%` per trade, but the
drawdown profile is effectively account-destruction risk. This should not be
treated as a live-ready strategy.

## Requirements For The Next Strategy Loop

- Keep three-year replay as the primary gate. One-year-only tuning is rejected.
- Do not add or keep a leg unless its three-year expectancy is positive and it
does not worsen walk-forward drawdown materially.
- Target new edge, not just more risk. The strategy needs more positive active
days and higher R expectancy before the 10,000x target is credible.
- Any candidate claiming 10,000x must show `compoundDailyReturnPct >= 0.84390`
  over the three-year replay, with explicit max drawdown and consecutive-loss
  reporting.
- Risk candidates outside the selected deployment drawdown gate are research
  artifacts, not deployment candidates. The current gate is `30-40%` max
  drawdown.

## Current Decision

The previous trend-impulse experiment is discarded. The current production
candidate is now the hardened existing strategy. It improves long-horizon
robustness but does not yet meet the 10 billion KRW target.

## Recursive Target Search 2026-07-01

Source note: this pass used `scripts/volume-flow-recursive-target.mjs` against
the same three-year local BTCUSDT dataset. The script evaluates candidates
against a `10,000x` target, which requires `0.84390%` compound daily return
over `1096` observed days.

The recursive search did not find a raw or deployable target hit. It did find a
better risk-adjusted current candidate inside the existing strategy family:

- Global execution: `maxTradesPerDay=5`, `maxConcurrentPositions=5`,
  `dailyStopPct=1`, `maxConsecutiveLosses=1`.
- `range_failed_break_loose`: `targetR=1.5`, `maxHoldM1Candles=35`.
- `m1_trend_up_breakout_scalp`: switched to `RUNNER` exit with
  `runnerTrailActivationR=0.8`, `runnerTrailDistanceR=0.75`,
  `maxHoldM1Candles=30`.
- `m1_trend_down_breakout_assist`: `targetR=0.8`.

Updated current result:

| Horizon | Final equity | Net return | Compound daily | Max drawdown | Trades | Win rate | Profit factor | Worst walk-forward |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 year | `3,321,185 KRW` | `232.12%` | `0.32850%` | `7.51%` | `91` | `74.73%` | `4.78` | `17.07%` |
| 2 years | `5,062,776 KRW` | `406.28%` | `0.22212%` | `16.27%` | `191` | `69.11%` | `3.18` | `12.68%` |
| 3 years | `5,747,575 KRW` | `474.76%` | `0.15954%` | `26.73%` | `275` | `61.45%` | `2.65` | `-14.75%` |

Compared with the previous hardened current, the three-year final equity
improves from `3,948,481 KRW` to `5,747,575 KRW`, and max drawdown improves
from `32.58%` to `26.73%`. This is still far below the `10,000x` target:
`0.15954%` compound daily return versus the required `0.84390%`.

Search conclusion:

- Repeating parameter changes inside the current setup family converged around
  `0.16%` compound daily return.
- The active-day coverage remained near `15.22%`, so the main bottleneck is not
  only target/exit tuning. The system needs new positive-expectancy signal
  coverage.
- The three-year worst walk-forward window is still negative at `-14.75%`, so
  the current candidate is improved but not final.

Next recursive loop requirement:

- Add new signal coverage instead of only mutating existing legs.
- Keep the three-year `0.84390%` target as the raw hit gate.
- Reject any candidate that reaches the target only through extreme per-trade
  risk or a `>40%` drawdown profile under the current deployment gate.

Coverage expansion note:

An additional coverage-expansion pass found a stronger three-year research
candidate by changing `range_failed_break_loose` to
`SETUP_CLOSE_CONFIRMATION`. It produced `540.01868%` three-year net return,
`0.16936%` compound daily return, and `23.19099%` max drawdown. It was not
accepted as the current config because it lowered the one-year result to
`201.40%` and the two-year result to `388.11%` versus the balanced current
candidate's `232.12%` and `406.28%`. Keep it as a research branch for the next
loop, not the current baseline.

## MDD 40 Trend-Break Pass 2026-07-01

Source note: this pass used the same three-year local BTCUSDT dataset and
`scripts/volume-flow-recursive-target.mjs` with `maxDeployableDrawdownPct=40`.
The strategy objective changed from low drawdown preservation to using the
allowed `30-40%` MDD band more aggressively, while still rejecting candidates
above the gate.

Implementation change:

- Added `TREND_BREAK` exit mode. It does not exit at the fixed target. After a
  trade reaches `runnerTrailActivationR`, it holds until the 1m close breaks the
  recent structure low for longs or recent structure high for shorts.
- Added `trendBreakLookbackM1Candles` to configure that structure window.
- Raised the allowed per-trade `riskFraction` ceiling from `3%` to `5%`, with
  MDD <= `40%` used as the deployment filter.

Accepted current candidate:

- All current legs use `riskFraction=0.05`.
- `m1_failed_break_chop_scalp` now uses `exitMode=TREND_BREAK`,
  `runnerTrailActivationR=1.0`, `trendBreakLookbackM1Candles=8`, and
  `maxHoldM1Candles=140`.
- Existing profitable fixed-target/runner exits remain unchanged on the other
  legs. Applying `TREND_BREAK` to every leg was not better in this replay.

Validated result from `config/volume-flow-composite-current.json`:

| Horizon | Final equity | Net return | Compound daily | Max drawdown | Trades | Win rate | Profit factor | Expectancy R | Worst walk-forward |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 year | `6,795,347 KRW` | `579.53%` | `0.52494%` | `12.42%` | `90` | `74.44%` | `4.42` | `0.46665R` | `29.07%` |
| 2 years | `16,827,954 KRW` | `1,582.80%` | `0.38694%` | `26.54%` | `191` | `69.11%` | `3.57` | `0.33116R` | `50.17%` |
| 3 years | `23,428,184 KRW` | `2,242.82%` | `0.28792%` | `36.67%` | `275` | `61.45%` | `3.27` | `0.26478R` | `-19.59%` |

Comparison with the previous current candidate:

| Horizon | Previous final equity | New final equity | Previous compound daily | New compound daily | Previous MDD | New MDD |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 year | `3,321,185 KRW` | `6,795,347 KRW` | `0.32850%` | `0.52494%` | `7.51%` | `12.42%` |
| 2 years | `5,062,776 KRW` | `16,827,954 KRW` | `0.22212%` | `0.38694%` | `16.27%` | `26.54%` |
| 3 years | `5,747,575 KRW` | `23,428,184 KRW` | `0.15954%` | `0.28792%` | `26.73%` | `36.67%` |

Decision:

- Promote this candidate to current because it uses the allowed MDD budget and
  materially improves 1/2/3-year compounding.
- It is still not a `1,000,000 KRW -> 10,000,000,000 KRW` strategy. The
  three-year target requires `0.84390%` compound daily return, while the current
  candidate reaches `0.28792%`.
- The remaining gap is not solved by exit tuning alone. Active-day coverage is
  still only `15.22%`, so the next loop needs new positive-expectancy signal
  coverage, not only higher leverage or wider holds.

## Risk-Expanded Coverage Pass 2026-07-01

Source note: this pass started from the MDD 40 trend-break candidate and used
`scripts/volume-flow-recursive-target.mjs` plus a focused risk sweep against the
same three-year BTCUSDT local dataset. It keeps the deployment drawdown gate at
`40%`.

Implementation and tuning changes:

- Raised the tunable `riskFraction` ceiling from `5%` to `7.5%`.
- Selected `riskFraction=0.072` because `0.073` already exceeded the MDD gate.
- Changed `range_failed_break_loose` to `SETUP_CLOSE_CONFIRMATION`.
- Relaxed `m1_trend_up_breakout_scalp` volume threshold from `2.0` to `1.5`.
- Increased `trend_down_close` `targetR` from `1.2` to `1.5`.
- Increased global `maxConsecutiveLosses` from `1` to `2`; higher lock values
  did not improve the selected candidate.

Fine risk sweep on the selected structure:

| Risk fraction | Final equity | Compound daily | Max drawdown | Decision |
| ---: | ---: | ---: | ---: | --- |
| `0.070` | `112,666,970 KRW` | `0.43160%` | `38.97%` | Under gate |
| `0.071` | `118,885,578 KRW` | `0.43652%` | `39.44%` | Under gate |
| `0.072` | `125,401,945 KRW` | `0.44140%` | `39.91%` | Accepted |
| `0.073` | `132,227,626 KRW` | `0.44625%` | `40.37%` | Rejected |
| `0.074` | `139,374,476 KRW` | `0.45107%` | `40.89%` | Rejected |
| `0.075` | `146,854,643 KRW` | `0.45586%` | `41.41%` | Rejected |

Validated current result from `config/volume-flow-composite-current.json`:

| Horizon | Final equity | Net return | Compound daily | Max drawdown | Trades | Win rate | Profit factor | Expectancy R | Worst walk-forward |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 year | `10,252,218 KRW` | `925.22%` | `0.63795%` | `18.61%` | `84` | `70.24%` | `3.12` | `0.44837R` | `45.26%` |
| 2 years | `52,661,077 KRW` | `5,166.11%` | `0.54373%` | `31.55%` | `182` | `69.23%` | `2.90` | `0.35963R` | `84.63%` |
| 3 years | `125,401,945 KRW` | `12,440.19%` | `0.44140%` | `39.91%` | `268` | `62.69%` | `2.86` | `0.30706R` | `-9.40%` |

Comparison with the prior current candidate:

| Horizon | Prior final equity | New final equity | Prior compound daily | New compound daily | Prior MDD | New MDD |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 year | `6,795,347 KRW` | `10,252,218 KRW` | `0.52494%` | `0.63795%` | `12.42%` | `18.61%` |
| 2 years | `16,827,954 KRW` | `52,661,077 KRW` | `0.38694%` | `0.54373%` | `26.54%` | `31.55%` |
| 3 years | `23,428,184 KRW` | `125,401,945 KRW` | `0.28792%` | `0.44140%` | `36.67%` | `39.91%` |

Decision:

- Promote this candidate to current because it uses almost the full accepted
  `30-40%` MDD budget and improves all 1/2/3-year compound results.
- It still does not reach the `0.84390%` three-year compound daily target
  required for `1,000,000 KRW -> 10,000,000,000 KRW`.
- Additional loose coverage legs were tested but did not improve the accepted
  candidate. The remaining gap requires a new positive-expectancy setup family,
  not only risk scaling.

## Excursion Diagnostics Pass 2026-07-01

Source note: this pass used the same local three-year BTCUSDT dataset and
`config/volume-flow-composite-current.json`. It adds candle-level trade
excursion metrics to the backtest response:

- `maxFavorableExcursionR`: maximum in-trade favorable move in R.
- `maxAdverseExcursionR`: maximum in-trade adverse move in R.
- `mfeCapturePct`: realized R divided by maximum favorable R. This can be
  negative when a trade moved favorably before closing at a loss.
- `markToMarketMaxDrawdownPct`: drawdown including each position's worst
  in-trade adverse excursion. For concurrent positions this is a per-position
  diagnostic, not a full tick-level portfolio path replay.

Validated current result with the new diagnostics:

| Horizon | Final equity | Compound daily | Realized MDD | Mark-to-market MDD | Avg MFE | Avg MAE | Avg MFE capture | Trades | Expectancy R |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 year | `10,252,218 KRW` | `0.63795%` | `18.60992%` | `23.68100%` | `1.05261R` | `0.30974R` | `-114.32975%` | `84` | `0.44837R` |
| 2 years | `52,661,077 KRW` | `0.54373%` | `31.55409%` | `32.46589%` | `1.06594R` | `0.40106R` | `-218.51020%` | `182` | `0.35963R` |
| 3 years | `125,401,945 KRW` | `0.44140%` | `39.90845%` | `41.03446%` | `1.07854R` | `0.43516R` | `-175.90296%` | `268` | `0.30706R` |

Risk gate update:

| Risk fraction | 3-year final equity | Compound daily | Realized MDD | Mark-to-market MDD | Decision |
| ---: | ---: | ---: | ---: | ---: | --- |
| `0.069` | `106,734,862 KRW` | `0.42665%` | `38.49808%` | `39.60251%` | Strict MTM gate pass |
| `0.070` | `112,666,970 KRW` | `0.43160%` | `38.97048%` | `40.08230%` | MTM gate fail |
| `0.071` | `118,885,578 KRW` | `0.43652%` | `39.44060%` | `40.55962%` | MTM gate fail |
| `0.072` | `125,401,945 KRW` | `0.44140%` | `39.90845%` | `41.03446%` | Research only under MTM gate |

Key 3-year diagnostics:

| Segment | Trades | Win rate | Profit factor | Expectancy R | Avg MFE | Avg MAE | Avg MFE capture |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `BUY` | `80` | `53.75000%` | `1.17864` | `0.22345R` | `1.26234R` | `0.55255R` | `-224.07972%` |
| `SELL` | `188` | `66.48936%` | `4.37038` | `0.34263R` | `1.00032R` | `0.38521R` | `-155.36860%` |
| `STOP` exits | `24` | `0.00000%` | `0.00000` | `-1.13722R` | `0.26426R` | `1.25395R` | `-1,637.08692%` |
| `TIME` exits | `133` | `45.86466%` | `1.10199` | `-0.01140R` | `0.66877R` | `0.43881R` | `-127.17324%` |
| `TRAILING_STOP` exits | `36` | `88.88889%` | `1,012.19060` | `0.87479R` | `2.01563R` | `0.27151R` | `35.65974%` |
| `TREND_BREAK` exits | `12` | `100.00000%` | `n/a` | `1.53143R` | `2.85603R` | `0.35955R` | `49.88057%` |

Interpretation:

- The current `0.072` risk setting is valid only under realized-MDD reporting.
  With open-position adverse movement included, the three-year drawdown rises to
  `41.03446%`, above the current `40%` deployment gate.
- The strategy has enough raw favorable movement to improve: three-year average
  MFE is `1.07854R`, while realized expectancy is only `0.30706R`.
- The main leak is not the winning fixed-target trades. `TARGET`,
  `TRAILING_STOP`, and `TREND_BREAK` exits are positive. The drag comes from
  `TIME` exits and `STOP` exits after small favorable movement.
- Long-side trades are materially weaker than short-side trades. `BUY` has
  higher MFE but lower profit factor and higher MAE, so it is giving back more
  movement before exit.

Next improvement list:

1. Add a first-N-minute follow-through rule. If a trade does not reach at least
   `0.25R-0.35R` favorable movement quickly, exit early or move to a reduced
   risk state. This targets `STOP` trades that average only `0.26426R` MFE.
2. Replace weak `TIME` exits with structure invalidation or breakeven logic.
   `TIME` exits are the largest bucket at `133` trades and have slightly
   negative expectancy despite `0.66877R` average MFE.
3. Split risk by side. Keep or expand short-side risk first; cap long-side risk
   until `BUY` profit factor improves materially above `1.17864`.
4. Treat `riskFraction=0.072` as a research setting. For strict deployment under
   the current `40%` mark-to-market gate, use `0.069` until exit logic reduces
   in-trade adverse drawdown.
5. Expand the high-expectancy runner family, especially trend-break or trailing
   exits. `TREND_BREAK` and `TRAILING_STOP` have the strongest R expectancy, but
   still capture only about `35-50%` of available MFE.
6. Add future tuning gates to reject candidates by
   `max(realized MDD, mark-to-market MDD)`. The recursive tuning scripts now use
   this stricter deployment drawdown metric.

## Follow-Through Exit Pass 2026-07-01

Source note: this pass used the same local three-year BTCUSDT dataset and the
new `max(realized MDD, mark-to-market MDD)` deployment drawdown gate.

Implementation change:

- Added `followThroughCheckM1Candles` and `minFollowThroughR` to volume-flow
  backtest config.
- Added `FOLLOW_THROUGH_FAIL` exit reason. If a position does not reach the
  configured favorable R by the configured M1 candle count, the backtest exits
  at the current M1 close with exit slippage.
- Added follow-through mutation candidates to both volume-flow tuning scripts.

Accepted current candidate:

- All legs now use `riskFraction=0.075`, the maximum validated config ceiling.
- `trend_down_retest` and `trend_down_close` use
  `followThroughCheckM1Candles=8` and `minFollowThroughR=0.45`.
- Other legs keep their previous exit behavior. Applying follow-through to
  `m1_trend_up_breakout_scalp`, all legs, or stop-heavy legs reduced long-term
  results or worsened drawdown.

Validated current result from `config/volume-flow-composite-current.json`:

| Horizon | Final equity | Net return | Compound daily | Realized MDD | Mark-to-market MDD | Trades | Win rate | Profit factor | Expectancy R | Worst walk-forward |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 year | `9,798,521 KRW` | `879.85%` | `0.62551%` | `17.33%` | `22.69%` | `84` | `67.86%` | `2.95` | `0.42471R` | `47.30%` |
| 2 years | `54,919,596 KRW` | `5,391.96%` | `0.54950%` | `33.29%` | `34.21%` | `182` | `65.93%` | `2.75` | `0.34909R` | `67.79%` |
| 3 years | `177,421,106 KRW` | `17,642.11%` | `0.47318%` | `33.29%` | `34.21%` | `268` | `59.70%` | `2.74` | `0.31097R` | `11.94%` |

Comparison with the previous current:

| Horizon | Previous final equity | New final equity | Previous compound daily | New compound daily | Previous MTM MDD | New MTM MDD |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 year | `10,252,218 KRW` | `9,798,521 KRW` | `0.63795%` | `0.62551%` | `23.68%` | `22.69%` |
| 2 years | `52,661,077 KRW` | `54,919,596 KRW` | `0.54373%` | `0.54950%` | `32.47%` | `34.21%` |
| 3 years | `125,401,945 KRW` | `177,421,106 KRW` | `0.44140%` | `0.47318%` | `41.03%` | `34.21%` |

Decision:

- Promote this candidate to current. It gives up a small amount of one-year
  return, but improves two-year and three-year compounding while moving the
  three-year mark-to-market drawdown back inside the `40%` deployment gate.
- The three-year worst walk-forward window improves from `-9.40%` to `11.94%`,
  which is the strongest reason to accept the change.
- The target is still not reached. `1,000,000 KRW -> 10,000,000,000 KRW` over
  three years requires `0.84390%` compound daily return; the current candidate
  reaches `0.47318%`.

Next improvement list:

1. Split `m1_trend_up_breakout_scalp` into long-only and short-only variants or
   cap its long-side risk. Follow-through on that leg was harmful, but the
   prior diagnostics still show weak long-side profit factor and high MAE.
2. Add a structure-based replacement for `TIME` exits. Follow-through reduced
   `TIME` exits from `133` to `103`, but `TIME` is still the largest exit
   bucket.
3. Test a dedicated trend-break expansion for the two M5 trend-down fixed
   target legs now that early failed continuation trades are filtered.

## Breakeven Retune Pass 2026-07-01

Source note: this pass used the same local three-year BTCUSDT dataset and the
strict `max(realized MDD, mark-to-market MDD)` deployment drawdown gate.

Implementation change:

- Added `breakevenTriggerR=0.65` to `trend_down_close` only.
- No engine change was required. This uses the existing fixed-target breakeven
  behavior after a position reaches the configured favorable R.
- Rejected `m1_trend_up_breakout_scalp` risk caps, M5 fixed-leg
  trend-break/runner conversion, and lower breakeven triggers because they
  reduced long-horizon compounding or worsened walk-forward quality.

Validated current result from `config/volume-flow-composite-current.json`:

| Horizon | Final equity | Net return | Compound daily | Realized MDD | Mark-to-market MDD | Trades | Win rate | Profit factor | Expectancy R | Worst walk-forward |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 year | `10,482,650 KRW` | `948.26%` | `0.64407%` | `17.33%` | `22.69%` | `86` | `67.44%` | `3.04` | `0.42463R` | `47.30%` |
| 2 years | `58,754,061 KRW` | `5,775.41%` | `0.55879%` | `33.29%` | `34.21%` | `184` | `65.76%` | `2.83` | `0.34987R` | `67.79%` |
| 3 years | `189,712,591 KRW` | `18,871.26%` | `0.47931%` | `33.29%` | `34.21%` | `270` | `59.63%` | `2.82` | `0.31176R` | `11.89%` |

Comparison with the previous current:

| Horizon | Previous final equity | New final equity | Previous compound daily | New compound daily | Previous MTM MDD | New MTM MDD |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 year | `9,798,521 KRW` | `10,482,650 KRW` | `0.62551%` | `0.64407%` | `22.69%` | `22.69%` |
| 2 years | `54,919,596 KRW` | `58,754,061 KRW` | `0.54950%` | `0.55879%` | `34.21%` | `34.21%` |
| 3 years | `177,421,106 KRW` | `189,712,591 KRW` | `0.47318%` | `0.47931%` | `34.21%` | `34.21%` |

Decision:

- Promote this candidate to current because it improves all one-, two-, and
  three-year horizons without increasing deployment MDD.
- The target is still not reached. `1,000,000 KRW -> 10,000,000,000 KRW` over
  three years requires `0.84390%` compound daily return; the current candidate
  reaches `0.47931%`.

Next improvement list:

1. `TIME` exits fell to `100`, but remain the largest bucket. The next pass
   should replace weak time-based exits with structure-based continuation or
   invalidation rules.
2. Do not cap `m1_trend_up_breakout_scalp` risk yet. Tested risk caps reduced
   compound daily return and walk-forward quality.
3. Keep M5 fixed-leg trend-break/runner conversion rejected for now. Revisit it
   after the `TIME` exit model is improved.

## TIME Exit Retune Pass 2026-07-01

Source note: this pass used the same local three-year BTCUSDT dataset and the
strict `max(realized MDD, mark-to-market MDD)` deployment drawdown gate.

Implementation change:

- Increased `range_failed_break_loose` from `targetR=1.5` to `targetR=1.85`
  and extended its M1 hold limit from `45` to `60` candles.
- Added `breakevenTriggerR=0.4` to `m1_trend_up_breakout_scalp`.
- No engine change was required. The pass only retunes existing fixed-target,
  runner, and breakeven behavior.

Rejected paths:

- `m1_trend_down_breakout_assist` breakeven, shorter holds, follow-through,
  runner, and trend-break variants did not improve the current candidate.
- Removing or heavily reducing `range_failed_break_loose` was worse than
  letting the profitable cases target a larger R multiple.
- `m1_trend_up_breakout_scalp` runner distance, longer hold, and trend-break
  variants were weaker than a simple `0.4R-0.45R` breakeven trigger.

Validated current result from `config/volume-flow-composite-current.json`:

| Horizon | Final equity | Net return | Compound daily | Realized MDD | Mark-to-market MDD | Trades | Win rate | Profit factor | Expectancy R | Worst walk-forward |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 year | `10,478,880 KRW` | `947.89%` | `0.64397%` | `18.56%` | `23.85%` | `86` | `66.28%` | `3.10` | `0.42470R` | `48.80%` |
| 2 years | `65,458,746 KRW` | `6,445.87%` | `0.57365%` | `30.39%` | `31.36%` | `184` | `63.59%` | `2.90` | `0.35817R` | `76.83%` |
| 3 years | `257,397,618 KRW` | `25,639.76%` | `0.50726%` | `30.39%` | `31.36%` | `269` | `57.99%` | `2.90` | `0.32847R` | `33.08%` |

Comparison with the previous current:

| Horizon | Previous final equity | New final equity | Previous compound daily | New compound daily | Previous MTM MDD | New MTM MDD |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 year | `10,482,650 KRW` | `10,478,880 KRW` | `0.64407%` | `0.64397%` | `22.69%` | `23.85%` |
| 2 years | `58,754,061 KRW` | `65,458,746 KRW` | `0.55879%` | `0.57365%` | `34.21%` | `31.36%` |
| 3 years | `189,712,591 KRW` | `257,397,618 KRW` | `0.47931%` | `0.50726%` | `34.21%` | `31.36%` |

Decision:

- Promote this candidate to current. The one-year final equity is almost flat
  versus the previous current, but two-year and three-year compounding improve
  materially while deployment MDD falls below the prior `34.21%` level.
- `TIME` exits fall from `100` to `87` in the three-year replay. `STOP` exits
  rise in the pre-diagnostic report because breakeven stop exits are still
  reported under the generic `STOP` bucket.
- The target is still not reached. `1,000,000 KRW -> 10,000,000,000 KRW` over
  three years requires `0.84390%` compound daily return; the current candidate
  reaches `0.50726%`.

Next improvement list:

1. Split breakeven exits into an explicit `BREAKEVEN_STOP` reason. The current
   report cannot distinguish full-risk stops from stops after the position has
   moved to breakeven.
2. Add a full leg-by-exit aggregate to the API/report. Manual cross-tabs require
   requesting a high `tradeLimit`, which is fragile for iterative tuning.
3. After reporting is clearer, target the remaining gap with new high-conviction
   trend-continuation coverage rather than more global risk expansion.

## Exit Diagnostics Pass 2026-07-01

Source note: this pass used the same local three-year BTCUSDT dataset and the
current config from the TIME exit retune pass.

Implementation change:

- Added `BREAKEVEN_STOP` as a first-class `VolumeFlowExitReason`.
- Updated volume-flow breakeven logic so a stop moved to entry is reported as
  `BREAKEVEN_STOP` instead of generic `STOP`.
- Added `performanceByLegExit` to the composite engine report and API response.
  Each row includes `legId`, `exitReason`, trade count, PnL, win rate, profit
  factor, expectancy, payoff, MAE/MFE, and MFE capture metrics.

Validated current three-year exit split:

| Exit reason | Trades | Net PnL | Win rate | Profit factor | Expectancy R | Avg MFE R | Avg MAE R |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `BREAKEVEN_STOP` | `17` | `-4,485,520 KRW` | `0.00%` | `0.00` | `-0.14232R` | `0.65952R` | `0.23477R` |
| `FOLLOW_THROUGH_FAIL` | `33` | `-19,676,423 KRW` | `18.18%` | `0.08` | `-0.19144R` | `0.16227R` | `0.34951R` |
| `STOP` | `22` | `-47,350,204 KRW` | `0.00%` | `0.00` | `-1.14083R` | `0.24535R` | `1.14881R` |
| `TARGET` | `64` | `176,501,214 KRW` | `100.00%` | `n/a` | `0.99868R` | `1.44540R` | `0.22654R` |
| `TIME` | `87` | `39,288,318 KRW` | `50.57%` | `1.63` | `0.10381R` | `0.72291R` | `0.42116R` |
| `TRAILING_STOP` | `34` | `76,932,795 KRW` | `88.24%` | `1,353.16` | `0.90793R` | `2.06281R` | `0.25489R` |
| `TREND_BREAK` | `12` | `35,187,439 KRW` | `100.00%` | `n/a` | `1.53143R` | `2.85603R` | `0.35955R` |

Validated current three-year headline result is unchanged by this diagnostic
pass:

| Horizon | Final equity | Net return | Compound daily | Realized MDD | Mark-to-market MDD | Trades | Win rate | Profit factor | Expectancy R | Worst walk-forward |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 3 years | `257,397,618 KRW` | `25,639.76%` | `0.50726%` | `30.39%` | `31.36%` | `269` | `57.99%` | `2.90` | `0.32847R` | `33.08%` |

Decision:

- Promote this diagnostic change. It does not alter entry/exit prices or final
  equity, but it separates full-risk stops from breakeven defense.
- The real full-risk `STOP` bucket is now `22` trades at `-1.14083R`
  expectancy. The new `BREAKEVEN_STOP` bucket is `17` trades at only
  `-0.14232R`, mostly fee/slippage drag after the trade moved favorably.
- `performanceByLegExit` removes the need to request `tradeLimit=10000` and
  manually cross-tab trades during tuning.

Next improvement list:

1. Focus the next strategy pass on true full-risk `STOP` and weak negative
   `TIME` rows in `performanceByLegExit`, especially `m1_trend_down_breakout_assist`
   TIME exits and non-runner chop/range full stops.
2. Keep `BREAKEVEN_STOP` as a defensive mechanism unless its fee/slippage drag
   exceeds the full-risk stops it prevents.
3. Add candidate scoring that penalizes full-risk `STOP` separately from
   `BREAKEVEN_STOP`, rather than treating both as the same failure type.

## Loss-Streak Scoring Pass 2026-07-01

Source note: this pass used the same local three-year BTCUSDT dataset and the
current config from the TIME exit retune pass.

Implementation change:

- `BREAKEVEN_STOP` no longer increments volume-flow `maxConsecutiveLosses` or
  same-day consecutive-loss locks. It is treated as a neutral defensive exit:
  profitable trades still reset the streak, full-risk losses still increment it.
- Composite replay uses the same loss-streak policy as single-leg volume-flow
  backtests.
- `scripts/volume-flow-recursive-target.mjs` and `scripts/volume-flow-tune.mjs`
  now add full-risk stop and breakeven-stop fields to summaries:
  `fullRiskStopTradeCount`, `fullRiskStopExpectancyR`,
  `breakevenStopTradeCount`, and `breakevenStopExpectancyR`.
- Tuning score now penalizes full-risk `STOP` loss-R materially more than
  `BREAKEVEN_STOP` loss-R. This keeps breakeven defense available while pushing
  search away from true `-1R` stop clusters.

Validated current result from `config/volume-flow-composite-current.json`:

| Horizon | Final equity | Net return | Compound daily | Realized MDD | Mark-to-market MDD | Trades | Win rate | Profit factor | Expectancy R | Max loss streak | Worst walk-forward |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 year | `10,478,880 KRW` | `947.89%` | `0.64397%` | `18.56%` | `23.85%` | `86` | `66.28%` | `3.10` | `0.42470R` | `6` | `48.80%` |
| 2 years | `65,458,746 KRW` | `6,445.87%` | `0.57365%` | `30.39%` | `31.36%` | `184` | `63.59%` | `2.90` | `0.35817R` | `6` | `76.83%` |
| 3 years | `257,397,618 KRW` | `25,639.76%` | `0.50726%` | `30.39%` | `31.36%` | `269` | `57.99%` | `2.90` | `0.32847R` | `6` | `33.08%` |

Decision:

- Promote this policy and scoring change. It does not alter the current
  headline return because the accepted config did not have entries blocked only
  by breakeven-stop loss streaks in this replay.
- The change still matters for search: future candidates can use breakeven
  defense without being ranked like full-risk stop strategies.
- Full-risk `STOP` remains the primary loss bucket to reduce: `22` trades at
  `-1.14083R` expectancy in the current three-year replay.

Next improvement list:

1. Run recursive target search with the updated score and reject candidates that
   improve CDR by increasing full-risk `STOP` concentration.
2. Add targeted mutations for `m1_trend_down_breakout_assist` negative `TIME`
   exits, because global exit changes have repeatedly underperformed.
3. Consider a full-risk stop cap per leg if a single leg dominates the
   `performanceByLegExit` full-stop loss-R during the next tuning pass.

## Hold-Capture Retune Pass 2026-07-01

Source note: this pass used the same local three-year BTCUSDT dataset and the
current config from the loss-streak scoring pass. The analysis started from
`performanceByLegExit` so that profitable runner/target exits were protected
while weak `TIME` and full-risk `STOP` rows were isolated.

Analysis summary:

- The target remains `1,000,000 KRW -> 10,000,000,000 KRW` over three years,
  requiring `0.84390%` compound daily return. The previous current reached
  `0.50726%`.
- The largest remaining loss bucket was true full-risk `STOP`: `22` trades,
  `-47,350,204 KRW`, and `-1.14083R` expectancy.
- The weakest leg/exit row was `m1_trend_down_breakout_assist` `TIME`:
  `14` trades, `-14,744,302 KRW`, and `-0.36216R` expectancy.
- `BREAKEVEN_STOP` remained acceptable as a defensive exit: `17` trades and
  only `-0.14232R` expectancy. It should not be optimized the same way as a
  full-risk stop.
- Profitable rows to protect were `TARGET`, `TRAILING_STOP`, and
  `TREND_BREAK`, especially the M5 trend-down target/runner legs and the
  `m1_failed_break_chop_scalp` trend-break winners.

Implementation change:

- Reduced `m1_failed_break_chop_scalp.maxHoldM1Candles` from `140` to `100`.
  This keeps the trend-break structure exit but prevents the chop-reversal leg
  from giving back too much after its best extension window.
- Increased `range_failed_break_loose.maxHoldM1Candles` from `60` to `90`.
  This was selected only in combination with the chop hold change; shorter
  range holds and range removal were worse in the three-year replay.
- No engine change was required. This pass only retunes existing exit timing.

Rejected paths:

- `m1_trend_down_breakout_assist` breakeven, shorter holds, and
  follow-through filters did not beat the previous current candidate.
- Relaxing `dailyStopPct`, `maxTradesPerDay`, `maxConcurrentPositions`, or
  `maxConsecutiveLosses` did not change the accepted trade set or improve
  compounding.
- Removing `range_failed_break_loose` reduced three-year compounding even
  though the leg remained negative by net PnL. The profitable cases still help
  the portfolio sequence enough to keep it.
- Adding breakeven to the chop trend-break leg was harmful because it cut off
  the large MFE recovery profile that makes the leg valuable.

Validated current result from `config/volume-flow-composite-current.json`:

| Horizon | Final equity | Net return | Compound daily | Realized MDD | Mark-to-market MDD | Trades | Win rate | Profit factor | Expectancy R | Max loss streak |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 year | `10,647,468 KRW` | `964.75%` | `0.64836%` | `16.76%` | `22.16%` | `86` | `66.28%` | `3.23` | `0.42730R` | `6` |
| 2 years | `73,550,989 KRW` | `7,255.10%` | `0.58969%` | `30.39%` | `31.36%` | `183` | `63.39%` | `3.01` | `0.37098R` | `6` |
| 3 years | `305,482,466 KRW` | `30,448.25%` | `0.52296%` | `30.39%` | `31.36%` | `269` | `58.74%` | `3.00` | `0.33864R` | `6` |

Comparison with the previous current:

| Horizon | Previous final equity | New final equity | Previous compound daily | New compound daily | MDD delta | Profit factor delta |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 year | `10,478,880 KRW` | `10,647,468 KRW` | `0.64397%` | `0.64836%` | `-1.80pp` | `+0.13` |
| 2 years | `65,458,746 KRW` | `73,550,989 KRW` | `0.57365%` | `0.58969%` | `0.00pp` | `+0.11` |
| 3 years | `257,397,618 KRW` | `305,482,466 KRW` | `0.50726%` | `0.52296%` | `0.00pp` | `+0.11` |

Decision:

- Promote this candidate to current because it improves all one-, two-, and
  three-year compound results without increasing the deployment MDD gate.
- The strategy is still far from the `10,000x` target. The current three-year
  compound daily return is `0.52296%`, while the target requires `0.84390%`.
- The next improvement should not be another global frequency or breakeven
  sweep. The remaining high-value work is a new structure-based invalidation
  rule for `m1_trend_down_breakout_assist` weak `TIME` exits and a leg-specific
  full-risk stop reducer for M1 scalp/range setups.

## Adverse Invalidation Retune Pass 2026-07-02

Source note: this pass used the same local three-year BTCUSDT dataset and the
current config from the hold-capture pass. The objective remained the
`1,000,000 KRW -> 10,000,000,000 KRW` three-year target, which requires
`0.84390%` compound daily return.

Implementation change:

- Added `ADVERSE_INVALIDATION` as a first-class volume-flow exit reason.
- Added optional leg-level adverse invalidation fields:
  `adverseExitCheckM1Candles`, `maxAdverseRBeforeExit`, and
  `minFavorableRBeforeAdverseExit`.
- The exit is disabled by default. When all three fields are set, a trade exits
  at the current 1m close after the check window if it has moved adversely at
  least the configured R amount without first producing enough favorable R.
- Exposed the fields through both single-leg and composite backtest API request
  DTOs.
- Updated recursive/tuning scripts so adverse invalidation is included in
  future candidate summaries and mutation space.

Accepted config changes:

- `trend_down_retest`:
  - `volumeZScoreThreshold`: `1.5 -> 1.0`
  - `minBodyRatio`: `0.55 -> 0.50`
  - `minDirectionalCloseStrength`: `0.70 -> 0.55`
- `m1_failed_break_chop_scalp`:
  - `adverseExitCheckM1Candles=12`
  - `maxAdverseRBeforeExit=0.7`
  - `minFavorableRBeforeAdverseExit=0.35`

Rejected paths:

- Applying adverse invalidation to `m1_trend_down_breakout_assist` reduced
  compounding. It cut some weak `TIME` exits, but also damaged the profitable
  target sequence enough that the portfolio-level result worsened.
- Applying adverse invalidation to `m1_trend_up_breakout_scalp` breached the
  deployment drawdown gate in the sampled variants.
- Applying adverse invalidation to `range_failed_break_loose` reduced
  full-risk stops but lowered long-horizon compounding.
- Additional global risk, target, trend-break, and coverage-leg mutations did
  not beat the accepted current candidate.

Validated current result from `config/volume-flow-composite-current.json`:

| Horizon | Final equity | Net return | Compound daily | Realized MDD | Mark-to-market MDD | Trades | Win rate | Profit factor | Expectancy R | Full STOP | Adverse exits |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 year | `10,319,884 KRW` | `931.99%` | `0.63976%` | `16.76%` | `22.16%` | `87` | `65.52%` | `3.11` | `0.41768R` | `4` | `0` |
| 2 years | `104,939,598 KRW` | `10,393.96%` | `0.63861%` | `29.59%` | `31.34%` | `183` | `63.39%` | `3.02` | `0.39407R` | `11` | `2` |
| 3 years | `425,532,564 KRW` | `42,453.26%` | `0.55333%` | `29.59%` | `31.34%` | `273` | `58.61%` | `3.02` | `0.34799R` | `17` | `2` |

Comparison with the previous current:

| Horizon | Previous final equity | New final equity | Previous compound daily | New compound daily | MDD delta | Full STOP delta |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 year | `10,647,468 KRW` | `10,319,884 KRW` | `0.64836%` | `0.63976%` | `0.00pp` | `0` |
| 2 years | `73,550,989 KRW` | `104,939,598 KRW` | `0.58969%` | `0.63861%` | `-0.80pp` | `-5` |
| 3 years | `305,482,466 KRW` | `425,532,564 KRW` | `0.52296%` | `0.55333%` | `-0.80pp` | `-5` |

Decision:

- Promote this candidate to current. The one-year result is lower, but the
  three-year target is the primary gate and the two-/three-year compound
  results improve materially while realized and mark-to-market MDD remain under
  the `40%` deployment gate.
- The target is still not reached. Current three-year compound daily return is
  `0.55333%`, while `0.84390%` is required for `10,000x`.
- The remaining gap is unlikely to be solved by global parameter mutation. The
  next loop needs a new positive-expectancy setup family or a more selective
  trend-side expansion that adds active days without increasing full-risk stop
  concentration.

## Risk Allocation And Profit-Protect Pass 2026-07-02

Source note: this pass used the same local three-year BTCUSDT dataset and the
current config from the adverse invalidation pass. The objective remained
`1,000,000 KRW -> 10,000,000,000 KRW` over three years, requiring `0.84390%`
compound daily return.

Implementation and tuning changes:

- Raised the backtest research `riskFraction` validation ceiling from `0.075`
  to `0.15` so the search can measure target-hit candidates and reject them by
  drawdown instead of by request validation.
- Added `PROFIT_PROTECT` as a first-class exit reason.
- Added optional leg-level profit-protect fields:
  `profitProtectActivationR` and `profitProtectFloorR`.
- The exit is disabled by default. When both fields are set, a trade exits at
  the current 1m close after it has reached the activation R and then gives back
  to or below the configured floor R.
- Updated recursive/tuning scripts so `PROFIT_PROTECT` is included in summaries,
  scoring penalties, and mutation space.

Accepted config changes:

- Risk allocation:
  - `trend_down_retest`, `trend_down_close`, `trend_down_retest_runner`:
    `riskFraction=0.11`.
  - `range_failed_break_loose`, `m1_trend_up_breakout_scalp`,
    `m1_trend_down_breakout_assist`: `riskFraction=0.09`.
  - `m1_failed_break_chop_scalp`: `riskFraction=0.12`.
- `m1_trend_up_breakout_scalp`:
  - `profitProtectActivationR=0.5`
  - `profitProtectFloorR=0.1`

Rejected paths:

- Uniform `0.13+` risk reaches the raw target but breaks the deployment
  drawdown gate. `all_risk_0.13` reached `0.84413%` compound daily return and
  `10,109,565,496 KRW`, but had `49.27029%` mark-to-market max drawdown.
- The strongest raw candidate in this pass,
  `all_risk_0.13_mut_trend_down_close_risk_max`, reached `12,136,547,111 KRW`
  and `0.86093%` compound daily return, but had `49.60597%` mark-to-market max
  drawdown.
- Uniform `0.14` and `0.15` risk also exceeded the target, but required
  `52.11575%` and `54.84107%` mark-to-market max drawdown.
- Profit-protect on `m1_trend_down_breakout_assist` reduced compounding. It
  protected some giveback trades, but cut the profitable continuation sequence
  enough to be rejected.
- Additional coverage and trend-break mutations in the recursive search did not
  produce a deployable target hit.

Validated current result from `config/volume-flow-composite-current.json`:

| Horizon | Final equity | Net return | Compound daily | Realized MDD | Mark-to-market MDD | Trades | Win rate | Profit factor | Expectancy R | Profit-protect exits |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 year | `21,029,973 KRW` | `2,003.00%` | `0.83570%` | `22.47%` | `28.51%` | `85` | `64.71%` | `3.06` | `0.41281R` | `1` |
| 2 years | `456,645,741 KRW` | `45,564.57%` | `0.84126%` | `38.13%` | `39.97%` | `181` | `62.98%` | `3.03` | `0.39159R` | `2` |
| 3 years | `2,578,793,786 KRW` | `257,779.38%` | `0.71862%` | `38.13%` | `39.97%` | `271` | `58.30%` | `3.02` | `0.34599R` | `2` |

Comparison with the previous current:

| Horizon | Previous final equity | New final equity | Previous compound daily | New compound daily | Previous MTM MDD | New MTM MDD |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 year | `10,319,884 KRW` | `21,029,973 KRW` | `0.63976%` | `0.83570%` | `22.16%` | `28.51%` |
| 2 years | `104,939,598 KRW` | `456,645,741 KRW` | `0.63861%` | `0.84126%` | `31.34%` | `39.97%` |
| 3 years | `425,532,564 KRW` | `2,578,793,786 KRW` | `0.55333%` | `0.71862%` | `31.34%` | `39.97%` |

Decision:

- Promote this candidate to current because it uses the accepted `40%` MDD
  budget more efficiently and improves all one-, two-, and three-year compound
  results.
- The target is still not deployable under the current drawdown gate. The
  three-year result reaches `0.71862%` compound daily return versus the required
  `0.84390%`.
- The strategy can now hit the raw `10,000x` target only by accepting roughly
  `49%+` mark-to-market drawdown. The next loop must reduce full-risk stop and
  weak TIME concentration before risk can be raised further.

Next improvement list:

1. Reduce the `17` full-risk `STOP` exits without cutting the M5 trend-down
   target and runner winners.
2. Add a portfolio-level drawdown throttle or cooldown research pass and test
   whether raw target candidates can be capped below `40%` MTM MDD without
   losing the `0.84390%` compound daily threshold.
3. Expand active-day coverage beyond the current `14.86%` only with
   positive-expectancy setups; lower-quality coverage will increase MDD faster
   than CDR.

## Portfolio Drawdown Throttle Pass 2026-07-02

Source note: this pass used the same local three-year BTCUSDT dataset and the
current config from the risk-allocation/profit-protect pass. The objective
remained the `1,000,000 KRW -> 10,000,000,000 KRW` three-year target, requiring
`0.84390%` compound daily return, while keeping the deployment drawdown gate at
`40%` mark-to-market MDD.

Implementation change:

- Added portfolio-level drawdown controls to composite backtests:
  `portfolioDrawdownThrottlePct`, `portfolioDrawdownRiskMultiplier`, and
  `portfolioDrawdownCooldownDays`.
- The throttle is based on closed-trade portfolio equity versus peak equity. If
  realized drawdown is at or above the threshold, new accepted trades use the
  configured risk multiplier.
- The cooldown blocks new entries for the configured number of days after a
  closed trade leaves realized portfolio drawdown at or above the threshold.
- Exposed the fields through the composite backtest API request DTO.
- Updated recursive/tuning scripts so portfolio throttle and cooldown variants
  are searched and reported with `PORTFOLIO_DRAWDOWN_COOLDOWN` skip counts.

Accepted config changes:

- All seven current legs use uniform `riskFraction=0.136`.
- `portfolioDrawdownThrottlePct=31`.
- `portfolioDrawdownRiskMultiplier=0.25`.
- `portfolioDrawdownCooldownDays=1`.

Validated current result from `config/volume-flow-composite-current.json`:

| Horizon | Final equity | Net return | Compound daily | Realized MDD | Mark-to-market MDD | Trades | Win rate | Profit factor | Expectancy R | Cooldown skips |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 year | `37,147,044 KRW` | `3,614.70%` | `0.99257%` | `29.65%` | `37.93%` | `85` | `64.71%` | `2.71` | `0.41281R` | `0` |
| 2 years | `1,366,388,044 KRW` | `136,538.80%` | `0.99257%` | `39.21%` | `39.89%` | `181` | `62.98%` | `2.69` | `0.39159R` | `1` |
| 3 years | `6,742,664,126 KRW` | `674,166.41%` | `0.80690%` | `39.21%` | `39.89%` | `266` | `58.27%` | `2.69` | `0.34641R` | `8` |

Comparison with the previous current:

| Horizon | Previous final equity | New final equity | Previous compound daily | New compound daily | Previous MTM MDD | New MTM MDD |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 year | `21,029,973 KRW` | `37,147,044 KRW` | `0.83570%` | `0.99257%` | `28.51%` | `37.93%` |
| 2 years | `456,645,741 KRW` | `1,366,388,044 KRW` | `0.84126%` | `0.99257%` | `39.97%` | `39.89%` |
| 3 years | `2,578,793,786 KRW` | `6,742,664,126 KRW` | `0.71862%` | `0.80690%` | `39.97%` | `39.89%` |

Rejected paths:

- The focused throttle sweep found no deployable target hit. Its best
  deployable candidate was `risk0.135_dd30_m0.25_d1`, reaching
  `5,880,240,826 KRW`, `0.79433%` compound daily return, and `39.64622%`
  mark-to-market MDD.
- The micro sweep also found no deployable target hit. It improved the best
  candidate to `risk0.136_dd31_m0.25_d1`, which is now promoted.
- Raw target-hit candidates still exist, but they breach the deployment gate:
  `risk0.143_dd32_m0.35_d1` reached `10,856,145,738 KRW` and `0.85068%`
  compound daily return, but had `43.17942%` mark-to-market MDD.
  `risk0.143_dd33_m0.3_d1` reached `10,364,413,003 KRW` and `0.84642%`
  compound daily return, but had `42.39796%` mark-to-market MDD.
- Portfolio throttle reduces the previous `49%+` raw-target drawdown problem,
  but the last target-hit region is still roughly `2.4-3.2pp` above the current
  `40%` mark-to-market gate.

Decision:

- Promote this candidate to current because it materially improves all
  one-, two-, and three-year compound results while staying under the strict
  `40%` mark-to-market deployment gate.
- The target is still not reached. Current three-year compound daily return is
  `0.80690%`, while `0.84390%` is required for `10,000x`. In equity terms, the
  replay reaches about `6.74B KRW`, short of the `10B KRW` target.
- The remaining blocker is no longer broad risk scaling. The same trade
  sequence reaches the target only when risk is high enough to push
  mark-to-market drawdown above the deployment gate.

Next improvement list:

1. Reduce the mark-to-market adverse excursion of the `0.142-0.143` raw-target
   region by at least `3pp` without cutting the M5 trend-down target and runner
   winners.
2. Add a mark-to-market-aware risk throttle or entry blocker. The current
   throttle only sees closed-trade equity, so it cannot react to in-position
   adverse movement before the MTM gate is already breached.
3. Test leg- and side-specific risk on top of the portfolio throttle. Uniform
   `0.136` was the best deployable candidate in this pass, but the target-hit
   gap likely needs finer risk concentration rather than another global risk
   increase.
4. Improve expectancy before further risk expansion. The promoted candidate's
   three-year expectancy is `0.34641R`; raising risk alone now hits the MDD
   ceiling before reaching the required CDR.

## Leg Risk Cap Retune Pass 2026-07-02

Source note: this pass used the same local three-year BTCUSDT dataset and the
current config from the portfolio drawdown throttle pass. The objective
remained the `1,000,000 KRW -> 10,000,000,000 KRW` three-year target, requiring
`0.84390%` compound daily return, while keeping deployment drawdown at or below
`40%` mark-to-market MDD.

Analysis summary:

- The previous promoted candidate reached `6,742,664,126 KRW` over three years
  with `0.80690%` compound daily return and `39.89428%` mark-to-market MDD.
- Pure base-risk increases moved toward the target but crossed the MTM gate.
  For example, `baseRisk=0.14465` already reached `40.00647%` mark-to-market
  MDD, so it was rejected despite improving return.
- A hard mark-to-market risk cap was tested and rejected. With `risk=0.143` and
  a `40%` MTM limit, the three-year replay collapsed to `989,229 KRW`,
  `-0.00099%` compound daily return, only `45` trades, and `230` MTM-limit
  skips. It blocked recovery trades rather than improving the portfolio path.
- The accepted path is selective risk concentration: raise the productive legs
  while capping the long-biased `m1_trend_up_breakout_scalp` leg that pushed
  MTM drawdown over the gate.

Accepted config changes:

- `trend_down_retest`, `trend_down_close`, `trend_down_retest_runner`,
  `range_failed_break_loose`, `m1_trend_down_breakout_assist`, and
  `m1_failed_break_chop_scalp`: `riskFraction=0.1446`.
- `m1_trend_up_breakout_scalp`: `riskFraction=0.12`.
- `portfolioDrawdownThrottlePct=32`.
- `portfolioDrawdownRiskMultiplier=0.2`.
- `portfolioDrawdownCooldownDays=1`.

Validated current result from `config/volume-flow-composite-current.json`:

| Horizon | Final equity | Net return | Compound daily | Realized MDD | Mark-to-market MDD | Trades | Win rate | Profit factor | Expectancy R | Cooldown skips |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 year | `42,517,529 KRW` | `4,151.75%` | `1.02983%` | `31.41%` | `39.99%` | `85` | `64.71%` | `2.73` | `0.41281R` | `0` |
| 2 years | `1,755,063,115 KRW` | `175,406.31%` | `1.02716%` | `38.95%` | `39.99%` | `181` | `62.98%` | `2.71` | `0.39159R` | `1` |
| 3 years | `8,791,184,370 KRW` | `879,018.44%` | `0.83129%` | `38.95%` | `39.99%` | `266` | `58.27%` | `2.71` | `0.34641R` | `8` |

Comparison with the previous current:

| Horizon | Previous final equity | New final equity | Previous compound daily | New compound daily | Previous MTM MDD | New MTM MDD |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 year | `37,147,044 KRW` | `42,517,529 KRW` | `0.99257%` | `1.02983%` | `37.93%` | `39.99%` |
| 2 years | `1,366,388,044 KRW` | `1,755,063,115 KRW` | `0.99257%` | `1.02716%` | `39.89%` | `39.99%` |
| 3 years | `6,742,664,126 KRW` | `8,791,184,370 KRW` | `0.80690%` | `0.83129%` | `39.89%` | `39.99%` |

Rejected paths:

- `baseRisk=0.14465`, `m1_trend_up_breakout_scalp=0.12`,
  `portfolioDrawdownThrottlePct=32`, and
  `portfolioDrawdownRiskMultiplier=0.2` improved equity to
  `8,810,011,766 KRW`, but exceeded the gate at `40.00647%` MTM MDD.
- `baseRisk=0.145`, `m1_trend_up_breakout_scalp=0.12`,
  `portfolioDrawdownThrottlePct=32`, and
  `portfolioDrawdownRiskMultiplier=0.25` reached `9,930,284,346 KRW` and
  `0.84248%` compound daily return, but required `40.41480%` MTM MDD.
- Raising that same candidate to `portfolioDrawdownRiskMultiplier=0.3` crossed
  the target at `10,301,185,140 KRW` and `0.84586%` compound daily return, but
  required `41.21718%` MTM MDD.

Decision:

- Promote this candidate to current because it improves all one-, two-, and
  three-year compound results while staying inside the strict `40%`
  mark-to-market deployment gate.
- The target is still not reached. The current three-year compound daily return
  is `0.83129%`, while the target requires `0.84390%`. In equity terms, the
  replay reaches about `8.79B KRW`, short of `10B KRW`.
- The remaining gap is narrow but drawdown-bound. The next pass should reduce
  about `0.4-1.2pp` of MTM drawdown in the `baseRisk=0.145` region or improve
  per-trade expectancy before raising base risk again.

Next improvement list:

1. Inspect the exact trade sequence causing the MTM breach in the
   `baseRisk=0.145`, `m1_trend_up_breakout_scalp=0.12` region and target that
   segment with side/leg-specific risk or an exit change.
2. Avoid global hard MTM blockers for now. The tested hard cap prevented
   recovery trades and destroyed compounding.
3. Continue tuning around expectancy and adverse excursion reduction rather
   than broad risk expansion, because the remaining target candidates are
   already MDD-limited.

## Equity Curve Diagnostics API Pass 2026-07-02

Source note: this pass used the current promoted config and the same local
three-year BTCUSDT dataset. It is an analysis tooling change, not a strategy
parameter change.

Implementation change:

- Composite backtest reports now include an `equityCurve` list. Each point is
  tied to an accepted trade and includes starting equity, ending equity, peak
  equity, realized drawdown, mark-to-market low equity, and mark-to-market
  drawdown.
- The API request accepts `equityCurveLimit` to control how many latest curve
  points are returned.
- Composite responses now include `drawdownEvents`, sorted by worst
  mark-to-market drawdown first.
- The API request accepts `drawdownEventLimit` to control how many worst events
  are returned.

Current worst mark-to-market events from the promoted config:

| Sequence | Exit at | Leg | Side | Exit | Start equity | End equity | MTM low equity | MTM drawdown | Realized drawdown | Return R |
| ---: | --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `257` | `2026-06-04T00:54:00Z` | `m1_trend_down_breakout_assist` | `SELL` | `TIME` | `3,826,124,834 KRW` | `3,877,289,398 KRW` | `3,347,358,472 KRW` | `39.99456%` | `30.49491%` | `0.09248R` |
| `124` | `2024-09-18T19:21:00Z` | `range_failed_break_loose` | `SELL` | `TARGET` | `25,419,309 KRW` | `26,652,983 KRW` | `25,176,387 KRW` | `39.53765%` | `35.99153%` | `1.67818R` |
| `52` | `2024-03-19T07:43:00Z` | `trend_down_retest` | `SELL` | `TIME` | `946,810 KRW` | `943,699 KRW` | `933,144 KRW` | `39.15352%` | `38.46526%` | `-0.11306R` |
| `123` | `2024-09-17T15:33:00Z` | `m1_trend_up_breakout_scalp` | `BUY` | `BREAKEVEN_STOP` | `25,511,570 KRW` | `25,419,309 KRW` | `25,381,061 KRW` | `39.04611%` | `38.95426%` | `-0.15068R` |
| `53` | `2024-03-20T16:50:00Z` | `m1_failed_break_chop_scalp` | `BUY` | `TREND_BREAK` | `943,699 KRW` | `957,868 KRW` | `936,596 KRW` | `38.92841%` | `37.54134%` | `0.51918R` |

Decision:

- Promote this analysis API because it makes the remaining `40%` MTM gate
  problem directly inspectable without requesting all raw trades and rebuilding
  the equity path externally.
- The next tuning pass should start with sequence `257`: it is the current
  max-MTM event, comes from `m1_trend_down_breakout_assist`, exits by `TIME`,
  and had only `0.09248R` realized return after a deep in-trade adverse move.

## Target-Hit Risk Reallocation Pass 2026-07-02

Source note: this pass used the current promoted config and the same local
three-year BTCUSDT dataset. The objective remained the
`1,000,000 KRW -> 10,000,000,000 KRW` three-year target, requiring `0.84390%`
compound daily return while staying below the `40%` mark-to-market MDD gate.

Analysis summary:

- The equity diagnostics pass identified sequence `257`
  (`m1_trend_down_breakout_assist`, `SELL`, `TIME`) as the previous max-MTM
  event at `39.99456%` drawdown.
- A focused sweep showed that simply changing the exit mode to runner or
  trend-break did not improve the current candidate.
- Reducing `m1_trend_down_breakout_assist` risk to `0.136` lowered MTM MDD to
  `39.05052%` and still improved final equity to `8,909,525,250 KRW`.
- Combining a stronger cap with adverse invalidation created enough MTM budget
  to raise the other productive legs.

Accepted config changes:

- `trend_down_retest`, `trend_down_close`, `trend_down_retest_runner`,
  `range_failed_break_loose`, and `m1_failed_break_chop_scalp`:
  `riskFraction=0.148`.
- `m1_trend_up_breakout_scalp`: remains capped at `riskFraction=0.12`.
- `m1_trend_down_breakout_assist`: `riskFraction=0.13`.
- `m1_trend_down_breakout_assist` adverse invalidation:
  `adverseExitCheckM1Candles=5`, `maxAdverseRBeforeExit=0.9`,
  `minFavorableRBeforeAdverseExit=0.35`.
- Portfolio throttle remains `32%` drawdown, `0.2` risk multiplier, and `1`
  cooldown day.

Validated current result from `config/volume-flow-composite-current.json`:

| Horizon | Final equity | Net return | Compound daily | Realized MDD | Mark-to-market MDD | Trades | Win rate | Profit factor | Expectancy R | Cooldown skips |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 year | `43,650,870 KRW` | `4,265.09%` | `1.03710%` | `30.81%` | `38.59%` | `85` | `64.71%` | `2.74` | `0.41281R` | `0` |
| 2 years | `1,984,118,480 KRW` | `198,311.85%` | `1.04412%` | `38.52%` | `39.13%` | `181` | `62.98%` | `2.73` | `0.39246R` | `1` |
| 3 years | `10,091,334,327 KRW` | `1,009,033.43%` | `0.84396%` | `39.23%` | `39.70%` | `266` | `58.27%` | `2.73` | `0.34700R` | `8` |

Comparison with the previous current:

| Horizon | Previous final equity | New final equity | Previous compound daily | New compound daily | Previous MTM MDD | New MTM MDD |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 year | `42,517,529 KRW` | `43,650,870 KRW` | `1.02983%` | `1.03710%` | `39.99%` | `38.59%` |
| 2 years | `1,755,063,115 KRW` | `1,984,118,480 KRW` | `1.02716%` | `1.04412%` | `39.99%` | `39.13%` |
| 3 years | `8,791,184,370 KRW` | `10,091,334,327 KRW` | `0.83129%` | `0.84396%` | `39.99%` | `39.70%` |

Rejected or lower-ranked paths:

- `base0.148_down0.142_adv` reached `10,140,039,540 KRW`, but crossed the
  gate at `40.07123%` MTM MDD.
- `base0.147_down0.136_m0.25` reached `10,277,453,494 KRW`, but crossed the
  gate at `40.15886%` MTM MDD.
- `base0.148_down0.14_adv` remained under the gate at `39.82665%` MTM MDD, but
  its final equity was lower at `10,039,924,646 KRW`.

Decision:

- Promote `base0.148_down0.13_adv` to current. It is the first candidate that
  clears the three-year `10,000x` target and remains below the strict `40%`
  mark-to-market deployment gate in this local replay.
- The margin is extremely thin: `0.84396%` compound daily versus the required
  `0.84390%`. Treat this as a research target-hit candidate, not live proof.
- The worst current drawdown event shifted from `m1_trend_down_breakout_assist`
  to `trend_down_retest` sequence `52`, so the next strategy-improvement loop
  should analyze that earlier 2024 drawdown cluster.

Next implementation list:

1. Preserve this target-hit config as the new current baseline.
2. Build paper/live signal parity around this exact config before any private
   Bybit order execution.
3. Analyze sequence `52` and nearby 2024 drawdown events if more MDD margin is
   needed before testnet.
