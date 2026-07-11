# Bybit Order-Book Archive Backfill Design

## Purpose

This document records the first reproducible path for validating an
order-book-derived research signal without fabricating book history. It is a
research import only. It does not change a live strategy, execution loop,
environment variable, position size, or deployment.

Bybit's official historical-data portal lists contract `OrderBook` data, and
the portal's public catalog returns daily BTCUSDT files with the original
snapshot/delta messages. The available BTCUSDT linear archive was probed on
2026-07-11:

- first listed file: `2023-01-18_BTCUSDT_ob500.data.zip`;
- the first file starts at `2023-01-18T06:42:57.177Z`, so it is not a full UTC
  day and is intentionally excluded;
- the first eligible requested day is therefore `2023-01-19` after the importer
  verifies all 1,440 UTC minute bars;
- `2023-01-01`, and sample dates in 2020 through 2022, returned no file;
- current files can use a different raw depth, for example `ob500` in 2023 and
  `ob200` in 2026.

The official source explicitly identifies historical market data as including
orderbook data, and the portal lists `OrderBook` as an archived data product.
The current public WebSocket specification documents the same snapshot/delta
semantics and fields used by the files. Sources: [Bybit developer page](https://www.bybit.com/future-activity/developer),
[Bybit historical data portal](https://www.bybit.com/en/derivative-activity/history-data),
[Bybit orderbook specification](https://bybit-exchange.github.io/docs/v5/websocket/public/orderbook).

## Constraints

- The currently verified official archive cannot supply the original 2020-2022
  segment. It cannot create a truthful 1-60 month sealed protocol yet; the
  maximum possible contiguous period is the archive's actual eligible coverage.
- A sample raw day was 84 MiB compressed, 449 MiB uncompressed, and 619,907
  messages. Holding all raw days locally would exceed the current workspace's
  available storage. The importer streams one ZIP at a time instead.
- The archive's original top-of-book depth varies by date. The importer
  standardizes the retained feature to the top 50 levels, matching the current
  live collector depth.
- The archive importer writes one last-known reconstructed book sample per
  minute (`sample_count=1`). The live collector currently records each incoming
  snapshot. These are not interchangeable feature distributions, so archive
  thresholds are research-only until a separately approved canonical sampling
  change aligns the live collector.
- The archive has no verified historical all-liquidation stream in this import.
  No liquidation-flow filter may be enabled for an archive backtest. Simulated
  liquidation remains part of the existing execution model. The importer creates
  the runtime-compatible `liquidationFlowBars` table but does not insert events
  into it, so its empty state cannot be misread as historical liquidation data.
- A no-trade minute is a valid zero-flow observation only when the official
  trade archive has no event and the matching M1 candle volume is exactly zero.
  A missing positive-volume minute remains an import failure. The live capture
  service writes the same explicit zero bar only for a minute in which it
  observed an order-book bar but no public trade, so source absence is never
  silently converted into zero flow.

## Import Contract

`scripts/bybit-orderbook-backfill.mjs` accepts a target SQLite database and an
explicit UTC date range. It requests the official catalog in at most six-day
blocks, then streams each archive through `funzip`; it does not write raw ZIPs
or extracted data to the research database directory.

For each source day it must:

1. require catalog metadata for every requested date before writing any day;
2. require HTTPS source URLs and verify downloaded byte count against catalog
   metadata;
3. calculate and persist the ZIP SHA-256, source URL, filename, event count,
   event time bounds, importer version, and import timestamp;
4. reject a delta before a snapshot, a non-monotonic timestamp, malformed
   price/size, an empty book side, fewer than 50 levels, or an incomplete UTC
   day;
5. reconstruct snapshot/delta state, retain the last state in each completed
   UTC minute, and write only that minute's `OrderBookImbalanceBar`;
6. commit the day bars and provenance manifest together, so a failed day is
   rerunnable and cannot masquerade as completed.

The SQLite manifest table is `historicalOrderBookImports`; its uniqueness key
is provider, dataset, symbol, and source date. Re-running skips only a day that
has a committed manifest row. `--force=true` can rebuild the day bars only when
the re-downloaded ZIP SHA-256 matches its recorded provenance; a changed source
file is rejected rather than overwriting the original evidence.

Example, intentionally limited to one day for the first import:

```bash
node scripts/bybit-orderbook-backfill.mjs \
  --db=/private/tmp/bybit-trader-diagnostics-20260710.sqlite \
  --symbol=BTCUSDT \
  --start=2023-01-19 \
  --end=2023-01-19
```

`funzip` from the Info-ZIP package must be available on the research machine.
The production container is deliberately not a target for this operation.

Large imports may be split across non-overlapping temporary SQLite shards. The
final merge must use `scripts/bybit-orderbook-archive-merge.mjs`, which rejects
an incomplete source day and any existing target day with a different archive
SHA-256 before writing it. It never deletes or replaces prior provenance.

## Validation Sequence

1. Run the one-day command against a disposable research database and inspect
   the 1,440 `orderBookImbalanceBars` and one manifest row.
2. Run a seven-day contiguous import, then verify every M1 bar is continuous
   and its archive hash is recorded.
3. Import only the verified contiguous archive span into a separate research
   database that already contains M1/M5/M15 candles and historical taker flow.
   The taker-flow repair must treat a day as complete only with all 1,440
   continuous UTC minute bars; a single stored row is not sufficient evidence.
4. Generate a new immutable protocol only within that source span. Existing
   sealed protocol V1 remains consumed and must not be reused for tuning.
5. Freeze a candidate before any archive-period holdout scoring. A result from
   this source cannot authorize live execution until the sampling contract is
   aligned and forward observed data confirms parity.
