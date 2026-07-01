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
