# Volume Flow Segmented Evaluation Report

Source note: this report was produced on 2026-07-02 from the local
`build/runtime-test/bybit-trader-full-history.sqlite` BTCUSDT Bybit linear
public kline database. Results are backtest outputs, not live-trading return
guarantees.

Command:

```bash
node scripts/volume-flow-segmented-evaluate.mjs \
  --api http://127.0.0.1:18080 \
  --token local-test-token \
  --out build/volume-flow-segmented-evaluation
```

Config: `config/volume-flow-composite-current.json`.

Promotion gates:

- mark-to-market max drawdown must be `<= 40%`
- segment return must be `>= 0%`
- expectancy must be `> 0R`
- profit factor must be `>= 1`
- max consecutive losses must be `<= 10`
- major negative side/regime contributors are rejection reasons

## Segment Results

| Segment | Role | Window | Pass | Final equity | Return | CDR | MTM MDD | Trades | Win | PF | ExpR | Rejections |
| --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| S1 | stress-train | 2020-03-25..2021-10-18 | FAIL | `436,477 KRW` | `-56.35231%` | `-0.14458%` | `62.98033%` | `205` | `40.97561%` | `0.70576` | `-0.06857R` | return, expectancy, PF, MDD, loss streak, side/regime contributors |
| S2 | stress-validation | 2021-10-19..2023-05-28 | FAIL | `396,594 KRW` | `-60.34058%` | `-0.15743%` | `61.46592%` | `174` | `37.93103%` | `0.47475` | `-0.10417R` | return, expectancy, PF, MDD, side/regime contributors |
| S3 | validation | 2023-05-29..2024-12-31 | PASS | `83,981,032 KRW` | `8,298.10324%` | `0.76286%` | `39.69854%` | `149` | `53.69128%` | `2.25554` | `0.31883R` | none |
| S4 | validation | 2025-01-01..2026-07-02 | FAIL | `201,389,238 KRW` | `20,038.92383%` | `0.97281%` | `38.59458%` | `128` | `63.28125%` | `2.73150` | `0.38431R` | negative RANGE contributor |
| FULL | final-gate | 2020-03-25..2026-07-02 | FAIL | `12,012,078 KRW` | `1,101.20785%` | `0.10857%` | `76.80356%` | `628` | `47.13376%` | `2.11465` | `0.08634R` | MDD, loss streak, negative RANGE contributor |

## Diagnosis

The current strategy remains rejected as a robust new-market candidate.

- S1 and S2 lose more than half the starting balance before the favorable
  post-2023 regime begins.
- Full-history return is positive, but the equity path is not deployable under
  the current risk tolerance because mark-to-market drawdown reaches
  `76.80356%`.
- S3 passes all gates, confirming that the current config is heavily tuned to
  the post-2023 regime.
- S4 has strong return, but RANGE remains a negative contributor even in the
  favorable recent window.
- The next tuning loop must improve survival in S1/S2 before optimizing recent
  CDR.

## Next Tuning Targets

1. Gate or disable RANGE exposure unless segment-level expectancy turns
   positive.
2. Reduce fixed per-leg risk in failure regimes and re-expand only when regime
   quality is confirmed.
3. Rebuild BUY and trend-up entries against S1/S2, not against recent windows
   only.
4. Keep S3/S4 as validation windows so S1/S2 fixes do not destroy the current
   post-2023 edge.
