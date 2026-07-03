# Volume Flow Tuning Log

## 2026-07-03 Recursive Rolling Robustness Retune

Source note: measured against
`build/runtime-test/bybit-trader-full-history.sqlite` with independent 180-day
and 365-day rolling windows. These numbers are local backtest outputs, not
live-trading return guarantees.

Accepted config changes:

- `trend_down_retest_runner.maxRelativeVolumeThreshold=13`
- `m1_trend_down_breakout_assist.maxContextRangePct=0.015`
- `m1_trend_down_breakout_assist.maxEntryRiskPct=0.0148`
- `m1_trend_up_breakout_scalp.maxHoldM1Candles=20`

Accepted rolling result:

| Gate | Passed | Failed | Pass rate | Worst return | Worst MTM MDD |
| --- | ---: | ---: | ---: | ---: | ---: |
| 180d rolling | `54/58` | `4` | `93.10345%` | `-35.13523%` | `35.17858%` |
| 365d rolling | `17/18` | `1` | `94.44444%` | `-13.11783%` | `33.53815%` |

Rejected after the accepted retune:

- M5 RV caps at `8` improved the known failed 365-day window but lowered full
  rolling validation to `50/58` 180d and `15/18` 365d.
- M1 trend-up context, macro-efficiency, adverse-exit, follow-through, and
  shorter-hold variants reduced selected losses but did not increase rolling
  pass count.
- The strongest combined local candidate
  (`m1adv1_0.5 + m5rv8 + m1downrv13`) regressed full validation to `44/58`
  180d and `14/18` 365d, so it was rejected.

Current diagnosis: the existing four-leg structure is now near the limit of
static threshold tuning. The remaining failed windows need a new independently
positive setup family or market-state selector, not more local retuning of the
same entry/exit thresholds.

## 2026-07-03 Runner Breakeven Micro-Retune

Source note: measured against
`build/runtime-test/bybit-trader-full-history.sqlite`, covering
`2020-03-25T10:36:00Z` to `2026-07-02T05:40:00Z`. These numbers are local
backtest outputs, not live-trading return guarantees.

This pass rejected broad signal filtering and accepted only a narrow exit
quality change:

- Set `trend_down_retest_runner.breakevenTriggerR` from `null` to `0.65`.
- Keep all risk fractions, trade caps, drawdown throttle, and entry filters
  unchanged.

Accepted segmented result:

| Segment | Return | Compound daily return | MDD | MTM MDD | Trades | Profit factor | Expectancy |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `S1 2020-03-25..2021-10-18` | `6.79%` | `0.01146%` | `44.77%` | `45.53%` | `30` | `1.063` | `0.0772R` |
| `S2 2021-10-19..2023-05-28` | `7.35%` | `0.01209%` | `36.62%` | `43.62%` | `135` | `1.031` | `0.0360R` |
| `S3 2023-05-29..2024-12-31` | `8293.95%` | `0.76277%` | `31.04%` | `32.67%` | `129` | `2.903` | `0.3882R` |
| `S4 2025-01-01..2026-07-02` | `4980.97%` | `0.71938%` | `32.70%` | `34.01%` | `111` | `2.430` | `0.3558R` |
| `FULL` | `378668.16%` | `0.36029%` | `44.77%` | `45.53%` | `404` | `2.436` | `0.2364R` |

Compared with the prior current config, FULL CDR improves
`0.35986% -> 0.36029%`, FULL return improves
`374954.62% -> 378668.16%`, and S2 return improves
`5.89% -> 7.35%`. This is a small robustness improvement, not a target hit:
the strategy is still well below the `0.8%` compound daily return target and
still above the preferred 30-40% mark-to-market drawdown band.

Rejected paths from this pass:

- Directional close-strength hardening on `m1_trend_down_breakout_assist`,
  `m1_trend_up_breakout_scalp`, `range_failed_break_loose`, and the M5
  trend-down legs either lowered FULL CDR or made an early stress segment
  negative.
- `range_failed_break_loose` profit protection and breakeven variants improved
  some stress behavior but cut later compounding winners, reducing FULL CDR to
  roughly `0.28%~0.33%`.
- M1 down assist runner or trend-break exits reduced CDR and often made S1/S2
  negative. The leg remains a short fixed-target assist, not a runner.
- M5 fixed trend-down retest/close runner and trend-break conversions reduced
  FULL CDR. The accepted change only protects the already-runner M5 retest leg
  after `0.65R`.

Next diagnosis: the current strategy has likely exhausted simple risk, entry
filter, and exit-parameter retunes. The remaining gap to `0.8%` CDR requires a
new independently positive setup family, especially for S1/S2, rather than more
aggressive tuning of the existing legs.

## 2026-07-03 Chop Cap + Trend Risk Recovery

Source note: measured against
`build/runtime-test/bybit-trader-full-history.sqlite`, covering
`2020-03-25T10:36:00Z` to `2026-07-02T05:40:00Z`. These numbers are local
backtest outputs, not live-trading return guarantees.

This pass keeps the prior stress-recovery structure and applies the smallest
candidate that improved both FULL compound return and the S2 stress bottleneck:

- Lower `m1_failed_break_chop_scalp.maxRelativeVolumeThreshold` from `6` to
  `5.5`. The raw trade diagnostic showed S1/S2 chop losses clustering in the
  `5~8x` relative-volume band while the better chop reversals remained mostly
  below `5.5x`.
- Raise the three M5 trend-down legs from `riskFraction=0.148` to the configured
  model cap of `0.15`: `trend_down_retest`, `trend_down_close`, and
  `trend_down_retest_runner`.

Accepted segmented result:

| Segment | Return | Compound daily return | MDD | Trades | Profit factor | Expectancy |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `S1 2020-03-25..2021-10-18` | `6.79%` | `0.01146%` | `45.53%` | `30` | `1.063` | `0.0772R` |
| `S2 2021-10-19..2023-05-28` | `5.89%` | `0.00975%` | `44.39%` | `135` | `1.024` | `0.0338R` |
| `S3 2023-05-29..2024-12-31` | `8293.95%` | `0.76277%` | `32.67%` | `129` | `2.903` | `0.3882R` |
| `S4 2025-01-01..2026-07-02` | `4981.94%` | `0.71941%` | `33.99%` | `111` | `2.431` | `0.3558R` |
| `FULL` | `374954.62%` | `0.35986%` | `45.53%` | `404` | `2.436` | `0.2356R` |

Compared with the previous current config, FULL CDR improves
`0.35399% -> 0.35986%`, S2 return improves `1.62% -> 5.89%`, and worst
segment return improves `1.62% -> 5.89%`. MDD is essentially unchanged
(`45.40% -> 45.53%`), so the strategy is still below the desired `0.8%` daily
compound target and still above the preferred 30-40% MDD operating band.

Rejected paths from this pass:

- `range_failed_break_loose` hardening (`maxHoldM1Candles=45`,
  `relativeVolumeThreshold=5`, and trend-break exits) improved S1/S2 but reduced
  FULL CDR to roughly `0.34%` or lower. It is defensive, not growth-positive.
- Stronger trend-down follow-through checks lifted S2 but cut S3/S4 winners,
  reducing FULL CDR to roughly `0.25%`.
- Lower chop caps below `5.5` or higher chop relative-volume floors turned at
  least one stress segment negative.
- Looser drawdown throttle multipliers raised headline CDR but pushed MDD into
  the `56%~73%` range or made stress segments negative.

Next diagnosis: the remaining gap to `0.8%` CDR is not from weak S3/S4 behavior;
it comes from S1/S2 still compounding too slowly. The next useful axis is a
new long-side or range-continuation edge that is independently positive in S1
and S2, not additional risk on M1 trend-up or range reversal legs.

## 2026-07-02 Stress-Recovery Throttle Retune

Source note: measured against
`build/runtime-test/bybit-trader-full-history.sqlite`, covering
`2020-03-25T10:36:00Z` to `2026-07-02T05:40:00Z`. These numbers are local
backtest outputs, not live-trading return guarantees.

This pass keeps the volume-flow strategy focused on realized compound equity,
not raw win rate. The accepted config changes are:

- Lower `range_failed_break_loose.riskFraction` from `0.148` to `0.12`.
- Relax M5 fixed trend-down follow-through from `0.45R` to `0.35R` at the
  existing `8` M1 candle check. Tighter checks cut too many eventual winners.
- Move portfolio drawdown throttle from `30% / 0.2x` to `20% / 0.3x` with the
  existing `1` day cooldown.

Accepted segmented result:

| Segment | Return | Compound daily return | MDD | Trades | Profit factor | Expectancy |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `S1 2020-03-25..2021-10-18` | `7.04%` | `0.01187%` | `45.40%` | `30` | `1.065` | `0.0772R` |
| `S2 2021-10-19..2023-05-28` | `1.62%` | `0.00273%` | `47.06%` | `140` | `1.006` | `0.0243R` |
| `S3 2023-05-29..2024-12-31` | `8141.55%` | `0.75961%` | `32.19%` | `129` | `2.900` | `0.3882R` |
| `S4 2025-01-01..2026-07-02` | `4794.77%` | `0.71252%` | `33.74%` | `111` | `2.433` | `0.3558R` |
| `FULL` | `327860.52%` | `0.35399%` | `45.40%` | `403` | `2.438` | `0.2328R` |

Compared with the previous current config, FULL CDR improves
`0.32676% -> 0.35399%`, S1 improves `-0.84% -> 7.04%`, and S2 improves
`-13.92% -> 1.62%`. MDD improves only modestly (`46.41% -> 45.40%`), so the
`0.8%` CDR and 30-40% MDD target is still not complete.

Rejected paths from this pass:

- `SCALE_OUT_RUNNER` style partial scale-out was tested across m1 assist,
  trend-down, trend-only, and all-leg groups. The best full replay remained the
  baseline; broad scale-out lowered CDR or pushed MDD above 50%.
- Tighter m1-down, chop, and range adverse exits generally cut large recovery
  trades faster than they reduced stop losses.
- M5 trend-up mirror and coverage legs increased trade count but lowered edge;
  the best checked mirror still underperformed the current base and several
  variants raised MDD above 50%.
- A stricter throttle (`20% / 0.2x / 1 day`) lowered FULL MDD to `40.33%` but
  left S2 negative and reduced CDR to `0.34209%`. It is a viable defensive
  preset, not the active growth config.

Current diagnosis: the strategy now avoids the previous negative S1/S2
walk-forward result, but FULL CDR remains far below `0.8%`. The next useful
axis is not broader mirrored coverage; it is a higher-quality long-side or
range-continuation setup that proves positive by segment before adding risk.

## 2026-07-02 Full-Candle Segmented Retune

Source note: measured against
`build/runtime-test/bybit-trader-full-history.sqlite`, covering
`2020-03-25T10:36:00Z` to `2026-07-02T05:40:00Z`. These numbers are local
backtest outputs, not live-trading return guarantees.

The current accepted config is stored at
`config/volume-flow-composite-current.json`. This pass replaces the weaker
current file with the best segmented robustness candidate from the S2 loss
diagnostic loop:

- Adds `minContextQuoteVolume=30000000` to every leg to avoid low-liquidity
  context candles.
- Sets `m1_failed_break_chop_scalp` to `relativeVolumeThreshold=3` and
  `maxRelativeVolumeThreshold=6`.
- Sets `m1_trend_up_breakout_scalp` to `minTrendEfficiency=0.45`,
  `minTrendMovePct=0.003`, and `riskFraction=0.14`.
- Sets `m1_trend_down_breakout_assist` to `riskFraction=0.14`.
- Moves `m1_trend_down_breakout_assist` adverse invalidation from `5` to `3`
  M1 candles while keeping `maxAdverseRBeforeExit=0.9` and
  `minFavorableRBeforeAdverseExit=0.35`.
- Moves the portfolio drawdown throttle threshold from `32%` to `30%`.

Accepted segmented result:

| Segment | Return | Compound daily return | MDD | Trades | Profit factor | Expectancy |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `S1 2020-03-25..2021-10-18` | `-0.83672%` | `-0.00147%` | `46.41242%` | `31` | `0.99207` | `0.03898R` |
| `S2 2021-10-19..2023-05-28` | `-13.92410%` | `-0.02554%` | `45.37768%` | `133` | `0.89632` | `0.01445R` |
| `S3 2023-05-29..2024-12-31` | `8958.91333%` | `0.77595%` | `40.47905%` | `132` | `2.15715` | `0.35906R` |
| `S4 2025-01-01..2026-07-02` | `3668.73490%` | `0.66448%` | `33.22107%` | `111` | `2.21163` | `0.34152R` |
| `FULL` | `176040.93346%` | `0.32676%` | `46.41242%` | `404` | `2.20869` | `0.22545R` |

Compared with the previous best research candidate `chop_rv3_6`, FULL CDR is
slightly lower (`0.32773% -> 0.32676%`), but S2 improves materially
(`-28.14829% -> -13.92410%`) and S1 improves (`-4.43397% -> -0.83672%`).
This is a better forward-robustness trade-off, but it still does not meet the
`0.8%` compound daily return target.

Rejected paths from this pass:

- Monthly stop gates (`5%` to `30%`) were rejected for the active config. They
  reduce some loss clusters but also cut same-month recovery trades, causing
  lower compound growth. The best robustness trade-off,
  `chop_rv3_6_monthly_20`, returned `0.31394%` CDR with `46.25825%` MDD.
- M1 trend-up adverse invalidation was rejected. It improved some S2 losses but
  turned S4 negative in the checked candidate.
- Range RV cap combined with trend-efficiency filtering was rejected as the
  primary config. It improved S1/S2 further but lowered FULL CDR to
  `0.29481%`.
- Drawdown-throttle loosening and max-risk combinations were rejected. The best
  checked aggressive CDR was about `0.36715%`, but MDD rose to `82.93%`, which
  is outside the current operating tolerance.

Current diagnosis: the target miss is not primarily caused by insufficient
position sizing. Most legs are already near the configured `0.15` risk ceiling,
and loosening portfolio throttles increases MDD much faster than CDR. The next
useful improvement axis is exit quality: capture more of the large favorable
move on validated winners without increasing full-stop frequency.

## 2026-07-01 BTCUSDT 1-year replay

Source note: measured against
`build/runtime-test/bybit-trader-1y-backtest.sqlite`, covering
`2025-06-30T10:38:00Z` to `2026-06-30T10:37:00Z`. These numbers are local
backtest outputs, not live-trading return guarantees.

Target note: the current daily objective is `0.5%` to `2%`. The best candidate
below returns `189.22322%` across 366 observed days, which is `0.29059%`
compound daily return. This does not meet the lower bound. A `0.5%` compound
daily return over the same 366 observed days requires about `520.55260%` net
return, and active-day coverage is still sparse at `55` days.

Strategy gate note: the next tuning loop is not a win-rate chase. A candidate
with `40%` win rate can be valid only if average winners are large enough to
beat the post-cost breakeven win rate. Volume-flow responses now expose
`averageWinR`, `averageLossR`, `payoffRatio`, `breakevenWinRatePct`, and
`winRateEdgePct`; see `docs/backend/volume-flow-expectancy-strategy.md` and
`docs/backend/volume-flow-target-plan.md`.

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

The current best candidate is stored at
`config/volume-flow-composite-current.json`. Relative to the original three-leg
baseline, it retunes `trend_down_retest` to catch earlier M5 trend-down retests
with `relativeVolumeThreshold=3.5`, `setupRangeLookback=8`, and
`minBodyRatio=0.55`. It extends selected time exits after full-trade analysis
showed that most losses were `TIME` exits rather than hard stops, models up to
`3` concurrent composite positions with a `3`-trade daily cap, and now uses
`riskFraction=0.023` on each accepted leg. It adds:

- `trend_down_retest_runner`: relaxed M5 `TREND_DOWN` retest leg using
  `RUNNER` exit, `relativeVolumeThreshold=3.5`,
  `volumeZScoreThreshold=0.5`, `runnerTrailActivationR=0.8`,
  `runnerTrailDistanceR=0.75`.
- `range_failed_break_loose`: relaxed M5 `RANGE` failed-break leg using
  `relativeVolumeThreshold=2.5`, `targetR=0.8`, `minBodyRatio=0.35`,
  `minDirectionalCloseStrength=0.6`, `minRejectionWickRatio=0.2`.
- `chop_failed_break`: M5 `HIGH_VOLATILITY_CHOP` failed-break leg using
  `relativeVolumeThreshold=2.0`, `targetR=0.8`, `minBodyRatio=0.15`,
  `minDirectionalCloseStrength=0.7`, `minRejectionWickRatio=0.2`.
- `m1_trend_up_breakout_scalp`: M1 `TREND_UP` breakout-continuation scalp using
  close confirmation, `relativeVolumeThreshold=2.0`,
  `volumeZScoreThreshold=0.5`, `minBodyRatio=0.65`,
  `minDirectionalCloseStrength=0.7`, `targetR=0.8`, and
  `riskFraction=0.023`.
- `m1_chop_volume_rejection_scalp`: M1 `HIGH_VOLATILITY_CHOP`
  volume-rejection reversal scalp using close confirmation,
  `relativeVolumeThreshold=2.0`, `volumeZScoreThreshold=0.5`,
  `setupRangeLookback=6`, `minBodyRatio=0.15`,
  `minDirectionalCloseStrength=0.7`, `minRejectionWickRatio=0.20`,
  `targetR=1.0`, `maxHoldM1Candles=20`, and `riskFraction=0.023`.
- `m1_trend_down_breakout_assist`: M1 `TREND_DOWN`
  breakout-continuation assist using close confirmation,
  `relativeVolumeThreshold=3.0`, `volumeZScoreThreshold=0.5`,
  `minBodyRatio=0.65`, `minDirectionalCloseStrength=0.6`, `targetR=0.6`,
  `maxHoldM1Candles=25`, and `riskFraction=0.023`.
- `m1_failed_break_chop_scalp`: M1 `HIGH_VOLATILITY_CHOP` failed-break reversal
  scalp using close confirmation, `relativeVolumeThreshold=2.0`,
  `volumeZScoreThreshold=0.5`, `setupRangeLookback=6`, `minBodyRatio=0.15`,
  `minDirectionalCloseStrength=0.6`, `minRejectionWickRatio=0.20`,
  `targetR=0.8`, and `maxHoldM1Candles=20`.

The accepted time-exit retune changes these existing hold windows:

- `trend_down_retest`: `maxHoldM1Candles=30 -> 35`.
- `trend_down_close`: `maxHoldM1Candles=15 -> 20`.
- `range_failed_break`: `maxHoldM1Candles=30 -> 35`.
- `range_failed_break_loose`: `maxHoldM1Candles=30 -> 20`.
- `m1_chop_volume_rejection_scalp`: `maxHoldM1Candles=10 -> 20`.
- `m1_trend_down_breakout_assist`: `maxHoldM1Candles=10 -> 20`.

The previous accepted volume-flow retune kept the nine-leg structure but
updated two M1 legs:

- `m1_chop_volume_rejection_scalp`: `minRejectionWickRatio=0.25 -> 0.20`
  and `targetR=0.8 -> 1.0`.
- `m1_trend_down_breakout_assist`: `riskFraction=0.0125 -> 0.02` and
  `maxHoldM1Candles=20 -> 25`.

The latest execution-model retune adds:

- `maxConcurrentPositions=3`: allows the bot to use futures-style concurrent
  opportunities instead of forcing one BTCUSDT position across every leg.
- `maxTradesPerDay=3`: keeps the previous `1` to `5` trade/day business target
  range but caps this concurrent candidate at a tighter daily entry limit.

The latest accepted sizing retune changes the previous `142.28953%` candidate
by opening the validated volume-flow backtest risk ceiling from `0.02` to
`0.03`, setting current leg risk to `0.023`, and adding
`m1_failed_break_chop_scalp`. This is the best risk-adjusted candidate from the
latest loop, not a completed daily compounding target.

Candidate result:

| Net return | Compound daily return | Max drawdown | Trades | Win rate | Payoff ratio | Breakeven win rate | Win-rate edge | Expectancy | Active days | Active-day coverage |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `189.22322%` | `0.29059%` | `4.29784%` | `103` | `79.61165%` | `1.77996` | `35.97180%` | `43.63985%` | `0.46269R` | `55` | `15.02732%` |

Current structure note: this accepted candidate is not yet a low-win-rate
`4:6` style system. It currently wins often and also has positive payoff edge:
`averageWinR=0.67886`, `averageLossR=0.38139`, and
`payoffRatio=1.77996`. The main weakness remains sparse active-day coverage and
`maxConsecutiveLosses=5`, not raw expectancy.

Per-leg accepted performance:

| Leg | Trades | Net PnL | Profit factor | Expectancy |
| --- | ---: | ---: | ---: | ---: |
| `trend_down_retest` | 15 | `4223.66` | `11.17461` | `0.76250R` |
| `trend_down_close` | 17 | `3912.03` | `10.39379` | `0.59903R` |
| `range_failed_break` | 9 | `1139.09` | `5.35795` | `0.26689R` |
| `trend_down_retest_runner` | 15 | `4548.99` | `10.11464` | `0.71980R` |
| `range_failed_break_loose` | 9 | `569.49` | `2.81906` | `0.13699R` |
| `chop_failed_break` | 3 | `671.85` | n/a | `0.45958R` |
| `m1_trend_up_breakout_scalp` | 13 | `738.14` | `1.80565` | `0.16886R` |
| `m1_chop_volume_rejection_scalp` | 7 | `1144.53` | `5.81889` | `0.42923R` |
| `m1_trend_down_breakout_assist` | 12 | `1163.40` | `4.53012` | `0.27693R` |
| `m1_failed_break_chop_scalp` | 3 | `811.13` | n/a | `0.56756R` |

Risk note: the runner leg still contributes one accepted trade in this replay:
`2026-06-25T13:30:00Z`, short side, `+4.06695R`, exit reason
`TRAILING_STOP` in the single-position replay. The latest accepted loops improve
the annual candidate from `52.32671%` to `56.78605%`, then to `68.72947%`,
`74.67127%`, `142.28953%`, and now to `189.22322%`. The final change raises max
drawdown from `3.74029%` to `4.29784%`, so it is only valid under the explicit
`riskFraction=0.023` and `maxConcurrentPositions=3` assumptions. The older
`maxConcurrentPositions=5` variant at `riskFraction=0.02` returned
`149.85199%`, but was rejected because max drawdown rose to `4.80814%`, the
worst monthly return fell to `-4.19265%`, and max consecutive losses reached
`6`.

Monthly accepted PnL:

| Month | PnL | Trades |
| --- | ---: | ---: |
| `2025-07` | `235.94` | 2 |
| `2025-08` | `965.73` | 5 |
| `2025-09` | `-153.67` | 1 |
| `2025-10` | `660.01` | 5 |
| `2025-11` | `475.93` | 6 |
| `2025-12` | `4274.36` | 24 |
| `2026-01` | `1318.64` | 10 |
| `2026-02` | `2737.96` | 17 |
| `2026-03` | `2515.40` | 8 |
| `2026-04` | `1947.18` | 9 |
| `2026-05` | `-895.59` | 3 |
| `2026-06` | `4840.43` | 13 |

Walk-forward accepted PnL:

| Window | Return | PnL | Trades | Profit factor | Max drawdown |
| --- | ---: | ---: | ---: | ---: | ---: |
| `WF1:2025-06-30..2025-09-29` | `10.48003%` | `1048.00` | 8 | `7.81989` | `1.37184%` |
| `WF2:2025-09-29..2025-12-29` | `48.97079%` | `5410.29` | 35 | `7.78920` | `2.50441%` |
| `WF3:2025-12-29..2026-03-31` | `39.93124%` | `6572.00` | 35 | `7.52692` | `2.63315%` |
| `WF4:2026-03-31..2026-06-30` | `25.58379%` | `5892.02` | 25 | `5.11766` | `4.29784%` |

Walk-forward note: all four windows remain profitable and all measurable
profit-factor windows now clear `5.1`. `WF4` owns the worst drawdown at
`4.29784%`. The lower-bound daily return objective is not met on a compounding
basis: 103 accepted trades across 366 observed days produce `0.29059%`
compound daily return, with only `55` active days.

## Rejected Paths

- Risk-only sizing was rejected as the primary path. The current nine-leg
  structure at `riskFraction=0.024` returned `187.52936%`, which is only
  `0.28898%` compound daily return, while max drawdown rose to `4.48349%`.
- Higher accepted-leg sizing was rejected for the current target band. The
  ten-leg candidate at `riskFraction=0.030` returned `294.53049%`, which is
  still only `0.37571%` compound daily return, while max drawdown rose to
  `5.59524%` and the worst month fell to `-4.67684%`.
- The M1 `HIGH_VOLATILITY_CHOP` breakout-continuation add-on increased accepted
  trades to `117`, but returned only `146.73934%`, lowered total profit factor
  to `4.63117`, and triggered `5` daily max-trade skips.
- M5 `HIGH_VOLATILITY_CHOP` failed-break and volume-rejection add-ons were
  positive but weaker than the accepted M1 failed-break leg. The best checked
  M5 failed-break add-on returned `149.84020%`; the M5 rejection add-on returned
  `146.63664%`.
- M5 `TREND_UP` breakout sweeps remain excluded. The best sampled M5 trend-up
  close/retest candidates had negative train returns, with the close variant at
  train `-5.998%` and test `0.733%`, and the retest variant at train `-4.922%`
  and test `0.733%`.
- M5 setup-close breakout continuation increased trade count to 35 but lowered
  composite net return to `24.63207%`, raised max drawdown to `3.63243%`, and
  produced only `1.04259` profit factor on the added leg.
- Earlier broad M1 trend-down breakout close-confirmation variants increased
  trade count but lowered composite net return to `25.11653%`, raised max
  drawdown to `3.95224%`, and added a weak leg with only `1.06242` profit
  factor. The later tighter `m1_trend_down_breakout_scalp` reached `49.20519%`
  at the composite level, but was replaced because `m1_chop_volume_rejection_scalp`
  improved return and profit factor with fewer trades.
- M1 range failed-break and M5 volume rejection had negative train-period
  results, so they were rejected before composite adoption.
- M5 `TREND_UP` long breakout still remains excluded. The best checked M5
  close-confirmation variant added 19 trades but lowered composite net return to
  `37.07135%`, raised max drawdown to `3.07149%`, and had `0.61797` profit
  factor on the added leg.
- A more aggressive `m1_trend_up_breakout_scalp` at `riskFraction=0.02` returned
  `47.14192%` with `2.43591%` max drawdown and `5.52380` profit factor. It was
  not selected by itself because the paired M1 trend-up and chop-rejection
  configuration plus trend-down assist returned more (`56.78605%`)
  while keeping profit factor above `4.0`.
- Earlier risk-only `m1_trend_down_breakout_assist` increases were rejected
  because the assist leg did not yet have enough exit quality. After the later
  exit retune, raising assist risk to `0.02` and extending hold to `25` improved
  the composite to `72.66798%` while keeping max drawdown at `2.28970%`.
- A fully max-risk M1 up/down configuration (`0.02` and `0.02`) returned
  `50.09672%`, but was rejected because max drawdown rose to `2.81584%` and
  total profit factor dropped to `3.62961`.
- A standalone M5 trend-down retest retune returned `53.71427%`, but its total
  profit factor was `3.99367`; it was only accepted after the simultaneous M1
  chop-rejection and assist changes lifted total profit factor to `4.43173`.
- The first 10th-leg search on top of the `56.78605%` candidate did not produce
  a robust addition. The only positive add-on was M1 `TREND_DOWN`
  volume-rejection at `riskFraction=0.0075`, which lifted the composite to
  `57.39298%` with `4.46439` profit factor, but it added exactly one accepted
  trade across the full year. It remains rejected as an isolated-trade
  improvement until a broader variant proves repeatable.
- The accepted time-exit retune was selected from one-at-a-time and combined
  `maxHoldM1Candles` checks. It beat the previous `56.78605%` candidate without
  adding trades or raising risk. A near variant with `range_failed_break_loose`
  left at `30` returned `67.65894%`; the accepted `20`-candle loose range hold
  improved the final composite to `68.72947%`.
- Replacing the M1 chop volume-rejection scalp with `targetR=1.0` and
  `minRejectionWickRatio=0.20` beat both the prior `0.25` wick candidate
  (`73.28826%`) and the looser `targetR=0.8` variant (`74.04605%`). A separate
  10th loose-chop add-on also returned `74.67127%`, but it was rejected because
  the incremental leg contributed exactly one accepted trade. The accepted path
  is the replacement, not a new isolated add-on.
- `m1_trend_up_breakout_scalp` at `targetR=0.9` and `maxHoldM1Candles=20`
  returned a slightly higher `74.76617%`, but was rejected because profit
  factor fell from `5.96458` to `5.47715` and max drawdown rose from
  `2.28970%` to `2.44460%` for only `0.09490` percentage points of annual
  return.
- M5 `RANGE` breakout continuation was rejected because the best checked
  variants were negative in both train and test periods. The strongest sampled
  variant had train `-5.23514%`, test `-5.07923%`, and weak profit factors.
- M5 `TREND_DOWN` close-confirmation runner variants looked good as standalone
  legs but added no accepted composite trades because their signals overlapped
  existing positions.

## Reproduction Body

POST `config/volume-flow-composite-current.json` to
`/backtests/volume-flow/composite/run` on a local server that uses the 1-year
runtime database. The current config includes `"maxConcurrentPositions":3`;
omitting that field falls back to the single-position default of `1`. Add
`"tradeLimit":10000` to the request body when the full accepted trade list is
needed for loss analysis; the default response still returns the most recent 50
trades.

## Tuning Gate

The composite backtest response returns `compoundDailyReturnPct`,
low-win-rate payoff metrics, `monthlyPerformance`, and
`walkForwardPerformance`; the request also accepts scoped `tradeLimit` and
`maxConcurrentPositions`, so the next tuning loop can reject candidates that
only improve the annual total through one or two isolated trades or through
unbounded overlapping risk.

Do not raise per-trade risk by itself just to force the daily target. The
accepted `0.023` sizing is tied to the additional M1 failed-break setup and to
positive monthly/walk-forward stability gates, but it does not complete the
compound daily target. Future risk increases need a new setup-quality or
exit-quality improvement and must preserve monthly and walk-forward drawdown
controls. The daily target gate must use `compoundDailyReturnPct`, not
`netReturnPct / observedDays`.
