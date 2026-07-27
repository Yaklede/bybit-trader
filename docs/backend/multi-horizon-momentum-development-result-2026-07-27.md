# Multi-Horizon Momentum Development Result

Date: 2026-07-27

## Decision

The predeclared `multi-horizon-momentum-development-v1` experiment is
`REJECTED` for the `0.2%` compound-daily target. None of its 108 candidates
passed the target in all four development folds. The family is not implemented
in the Kotlin runtime, is not connected to paper or automatic execution, and
does not consume post-2024 validation data.

The experiment did find a positive but sub-target long-only research baseline.
That result is retained for comparison, not promoted.

## Reproduction

```bash
node scripts/volume-flow-feature-discovery.mjs \
  --db build/runtime-test/bybit-trader-full-history.sqlite \
  --windows config/volume-flow-development-folds-v3.json \
  --out build/multi-horizon-momentum-development-v1 \
  --profile multi-horizon-momentum \
  --maxCandidates 108 \
  --targetCdrPct 0.2 \
  --quiet true
```

The run loaded 659,461 BTCUSDT M5 candles and evaluated only D01-D04, which end
before 2024.

## Candidate Summary

| Metric | Result |
|---|---:|
| Candidates | `108` |
| Candidates passing `0.2%` in every fold | `0` |
| Candidates positive in every fold | `3` |
| Both-side candidates positive in every fold | `0` |
| Long-only candidates positive in every fold | `3` |
| Short-only candidates positive in every fold | `0` |

The strongest candidate was:

```text
multi_momentum_scale0.75_votes3_stop8_trail16_long_only
```

It requires all three momentum horizons, an eight-ATR initial stop, a
sixteen-ATR trailing stop, and long-only execution.

| Fold | CDR | Net return | MDD | Trades | Win rate | Profit factor |
|---|---:|---:|---:|---:|---:|---:|
| D01 | `0.08627%` | `36.99%` | `13.22%` | `118` | `31.36%` | `1.49278` |
| D02 | `0.01852%` | `6.99%` | `7.98%` | `57` | `33.33%` | `1.24561` |
| D03 | `0.01392%` | `5.21%` | `9.11%` | `63` | `36.51%` | `1.16623` |
| D04 | `0.06462%` | `14.83%` | `9.91%` | `51` | `37.25%` | `1.61979` |

The worst CDR was `0.01392%`, median CDR was `0.04157%`, and maximum MDD was
`13.22386%`. All non-return development gates passed, but the return gate missed
by a wide margin.

Across all four folds, the candidate closed 289 long trades. Its win rate was
`33.91%`, total net result was `64.44247R`, and average net expectancy was
`+0.22298R` per trade. Every exit was an ATR trailing stop. All 289 traced fills
occurred exactly 300,000 milliseconds after their completed M5 signal candle.

## Cost Stress

The same fixed candidate was replayed without changing its signal or risk
parameters.

### 1.5x fees and slippage

| Fold | CDR | MDD | Profit factor |
|---|---:|---:|---:|
| D01 | `0.06709%` | `14.87%` | `1.35799` |
| D02 | `0.01060%` | `10.22%` | `1.13134` |
| D03 | `0.00467%` | `10.12%` | `1.05237` |
| D04 | `0.04400%` | `10.99%` | `1.37279` |

All folds remained positive, but the worst CDR fell to `0.00467%`.

### 2x fees and slippage

| Fold | CDR | MDD | Profit factor |
|---|---:|---:|---:|
| D01 | `0.04793%` | `16.48%` | `1.23978` |
| D02 | `0.00266%` | `12.42%` | `1.03099` |
| D03 | `-0.02585%` | `12.70%` | `0.73819` |
| D04 | `0.02681%` | `12.23%` | `1.20749` |

The D03 edge failed at twice the configured costs. This candidate is therefore
not robust enough to justify risk scaling or runtime promotion.

## Evidence Hashes

```text
1e23a9c7a0ed6fc245608f6339630304a525bfda4769c163e276bcc8b3d5ab10  ranked.json
557ac911095288702fb086399e2084c7646bc4a82dca1577467633576bdb2d60  cost-1p5x/ranked.json
cf759f8e4449477c2583a7f590bd80200096e194920d813ba856c8e8c9d85d66  cost-2x/ranked.json
ce7dabead54573981b6946bf22eabd8b636640ca4bfe2d31bf1fac51f2688dc1  trace.json
```

Generated build reports remain ignored because they are reproducible artifacts.
The hashes make the local evidence auditable without committing large output
files.

## Interpretation

The result confirms that the existing six-year candle history can be used
without waiting for forward raw collection. Multi-horizon momentum produced a
repeatable long-side edge in development, but it did not approach the requested
return and did not produce a corresponding short-side edge.

Increasing risk would magnify the same return and drawdown distribution rather
than improve expectancy. It is not accepted as a way to satisfy the target.
The next experiment must use a genuinely different hypothesis; expanding this
candidate's thresholds or inspecting post-2024 data would violate the
predeclared decision policy.
