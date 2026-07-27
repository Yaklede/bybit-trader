# Macro Pullback Recovery Development Result

Date: 2026-07-27

## Decision

The predeclared `macro-pullback-recovery-development-v1` experiment is
`REJECTED`. None of its 48 candidates passed the `0.2%` compound-daily return
gate, minimum trade count, or active-day coverage gate in all four development
folds.

The family is not implemented in the Kotlin runtime, is not connected to paper
or automatic execution, and does not consume post-2024 validation data.

## Reproduction

```bash
node scripts/volume-flow-feature-discovery.mjs \
  --db build/runtime-test/bybit-trader-full-history.sqlite \
  --windows config/volume-flow-development-folds-v3.json \
  --out build/macro-pullback-recovery-development-v1 \
  --profile macro-pullback-recovery \
  --maxCandidates 48 \
  --targetCdrPct 0.2 \
  --quiet true
```

The run loaded 659,461 BTCUSDT M5 candles and evaluated only D01-D04, which end
before `2024-01-01T00:00:00Z`.

## Candidate Summary

| Metric | Result |
|---|---:|
| Candidates | `48` |
| Candidates passing all development gates | `0` |
| Candidates positive in every fold | `0` |
| Candidates meeting three trades in every fold | `0` |
| Candidates meeting 2% active-day coverage in every fold | `0` |

The highest-ranked candidate was:

```text
macro_recovery_regime10_counter3_stop16_trail16_both
```

It uses a 10% 30-day regime threshold, a 3% opposing three-day move, a 1%
12-hour recovery, a 16-ATR initial stop, a 16-ATR trailing stop, and both
directions.

| Fold | CDR | Net return | MDD | Trades | Active days | Profit factor |
|---|---:|---:|---:|---:|---:|---:|
| D01 | `0.00126%` | `0.46172%` | `0%` | `1` | `0.27397%` | `999` |
| D02 | `0.00215%` | `0.78692%` | `0%` | `1` | `0.27397%` | `999` |
| D03 | `0.00105%` | `0.38515%` | `0.33541%` | `2` | `0.54795%` | `2.14831` |
| D04 | `0%` | `0%` | `0%` | `0` | `0%` | `n/a` |

Its worst CDR was `0%`, median CDR was `0.00115%`, average CDR was
`0.00111%`, and maximum MDD was `0.33541%`. Those low drawdowns are a
consequence of making only four trades across all folds, not evidence of a
deployable low-risk strategy.

## Causal Trace

The fixed top candidate produced two long and two short trades:

| Side | Trades | Wins | Net R | Average net R |
|---|---:|---:|---:|---:|
| Long | `2` | `2` | `1.18471R` | `0.59235R` |
| Short | `2` | `1` | `0.45151R` | `0.22575R` |

All four entries filled exactly 300,000 milliseconds after the completed M5
signal candle. All four exits were ATR trailing stops. The individual outcomes
are too sparse to support an expectancy estimate.

## Cost Stress

Cost stress was not run. The predeclared decision policy permits stress replay
only after a candidate passes the base development gates. Running it here
would not change the rejection because the base experiment already failed
return, coverage, and trade-count requirements.

## Evidence Hashes

```text
f6c0bf1ab27cd6398411b7ae579332a8099e5c9ec34e45a8850a4e87b73fd960  ranked.json
3f70e1cd211eb5f8465fabb6ba87ad4d521f102ea17988c6542517435089ed75  trace.json
```

Generated build reports remain ignored because they are reproducible artifacts.

## Interpretation

The conjunction is too restrictive for the requested target. A 30-day regime,
an opposing three-day move, a 12-hour recovery, and a still-aligned four-day
EMA slope rarely coexist at the transition boundary. D04 produced no top-row
trade, and no candidate met even the minimum activity gates in every fold.

Loosening the thresholds after observing this result would violate the frozen
grid and turn the same development folds into a tuning target. This family
must remain rejected. A subsequent experiment needs a separately predeclared
hypothesis rather than an expanded version of this grid.

The result also confirms that six-year OHLCV is sufficient to reject this
candle-only hypothesis. Real-time order-book collection was not required for
this decision.
