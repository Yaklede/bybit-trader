# Tardis Bybit History Acquisition Decision

## Decision

The official Bybit order-book archive is a verified research source from
`2023-01-19` onward, but it does not provide the original 2020-2022 period and
contains a confirmed incomplete day at `2025-08-21`. It cannot support the
project's required 1-60 month, 20-40 random-window validation by itself.

Tardis is the only currently identified candidate source to evaluate before
changing the strategy. Its Bybit derivatives documentation states that linear
contract data begins at `2020-05-28`, its legacy `orderBook_200` channel spans
the pre-v5 period through `2023-04-05`, and its current v5 order-book channels
cover the later period. Source: [Bybit Derivatives coverage](https://docs.tardis.dev/historical-data-details/bybit).

This is a data-acquisition decision, not a live-trading change, a performance
claim, or approval to tune the strategy.

## Required Access

The minimum acceptable subscription is a Tardis **Business** plan with yearly
billing and raw replay access for Bybit derivatives. The vendor states that
Pro and lower tiers expose only four years of history, while Business yearly
billing exposes all available history; raw replay APIs require Pro or Business.
Sources: [subscription access rules](https://docs.tardis.dev/faq/billing-and-subscriptions), [market-data data types](https://docs.tardis.dev/faq/data).

The research-only credential is `TARDIS_API_KEY`. It must remain in a local
ignored environment file or a dedicated research secret store. It must not be
added to Git, deployment secrets, the bot runtime container, Discord, or logs.

Before requesting raw replay data, the public metadata probe must be run with
the intended 60-month range:

```bash
node scripts/tardis-bybit-coverage-probe.mjs \
  --symbol=BTCUSDT \
  --required-start=2020-05-28T00:00:00.000Z \
  --required-end=2026-07-02T00:00:00.000Z
```

The probe is deliberately not a qualification result. It reports the provider's
advertised channels, instrument range, dataset data types, and overlapping incident notices, then
returns `RAW_DAY_AUDIT_REQUIRED`. Only a credentialed raw-day import can prove
minute continuity for a sealed protocol.

The legacy feed preflight validates the actual `orderBook_200` snapshot/delta
and `trade` payload contract before a long import. The first day of a month can
be exercised without a credential; other dates use `TARDIS_API_KEY` only from
the local environment:

```bash
node scripts/tardis-bybit-legacy-feed-preflight.mjs \
  --symbol=BTCUSDT \
  --date=2020-06-01
```

The preflight records only counts, capture timestamps, and retained depth. It
does not persist raw provider messages or report the credential value.

Tardis raw HTTP feeds require `Authorization: Bearer <key>` and
`Accept-Encoding: gzip` for authenticated historical requests. The provider's
current Node dataset client requires Node 24 or later, while this repository's
research scripts run on Node 22 for SQLite support. This repository therefore
uses a separate, localhost-only [Tardis Machine](https://docs.tardis.dev/api/tardis-machine)
research runner for the long replay. It must not change the bot runtime image
merely to download research data. The Machine credentials stay in that runner;
the importer accepts no API-key command-line argument and only connects to
`localhost`. Sources: [HTTP API reference](https://docs.tardis.dev/api/http-api-reference), [CSV quickstart](https://docs.tardis.dev/downloadable-csv-files/overview), [Tardis Machine quickstart](https://docs.tardis.dev/tardis-machine/quickstart).

## Local Replay Procedure

The following is a research-only operation. Use a new SQLite database because
the importer refuses to overwrite an order-book day from another provider.
The Tardis key is not a bot secret and must not be added to GitHub Actions,
the bot `.env`, or a production host.

```bash
export TM_API_KEY='provided-by-tardis'
docker run --rm --name tardis-machine-research \
  -p 127.0.0.1:18000:8000 \
  -v "$PWD/build/tardis-machine-cache:/.cache" \
  --env TM_API_KEY \
  tardisdev/tardis-machine
```

In another terminal, import one disposable day first:

```bash
node scripts/tardis-machine-orderbook-backfill.mjs \
  --db=/private/tmp/bybit-trader-tardis-research.sqlite \
  --symbol=BTCUSDT \
  --start=2020-06-01 \
  --end=2020-06-01 \
  --machine-url=http://127.0.0.1:18000
```

The importer requests `book_snapshot_25_1m` with disconnect messages enabled.
The depth is deliberately fixed at 25 because the legacy `BTCUSDT` source
contains only 25 valid price levels even when a deeper normalized snapshot is
requested; using 25 keeps the legacy and v5 periods on one feature contract.
It uses each snapshot's `localTimestamp`, requires a snapshot in the first and
last minute of every day, rejects upstream errors and a disconnect that occurs
before any causal snapshot for that minute, preserves the
source-response SHA-256, and records exactly 1,440 causal minute bars.
Recovered disconnect counts and carried-forward minutes are stored per day for
audit. Minutes with no new snapshot can only carry the last already-observed
book state forward; this is conservative and avoids using a later book snapshot
for an earlier candle.

After the day contract passes, run the full range with the same separate
database. The sealing tool recognizes this source only as
`tardis-machine-orderbook-plus-bybit-taker-history` and requires a valid
Tardis replay manifest and the corresponding disconnect/carry-forward
diagnostic record for every order-book day.

Before generating a new random-window protocol, audit the complete imported
period independently. A missing manifest, invalid SHA-256, missing diagnostics,
invalid diagnostics, or non-contiguous minute bars leaves a source gap and
prevents sealing:

```bash
node scripts/tardis-machine-orderbook-coverage-audit.mjs \
  --db=/private/tmp/bybit-trader-tardis-research.sqlite \
  --symbol=BTCUSDT \
  --start=2020-05-28 \
  --end=2026-07-01
```

## Data Contract

The integration may proceed only after the vendor metadata confirms all of the
following for `BTCUSDT` linear perpetual:

1. Order-book update coverage begins no later than `2020-05-28` and includes a
   contiguous 60-month candidate observation period.
2. The selected order-book channel has an initial daily snapshot and ordered
   incremental updates. Replay begins at `00:00 UTC` for every day so state is
   reconstructable.
3. Historical trades and liquidation streams have independently declared
   coverage. An absent liquidation channel must stay absent; it cannot be
   replaced with zero events or inferred from candles.
4. Every imported day records provider, exchange, symbol, channel, source URL
   or request identity, raw byte count, SHA-256, event-time bounds, local-time
   bounds, importer version, and exact minute count.
5. A day with fewer than 1,440 contiguous UTC minute feature bars is rejected.
   It remains a source gap in the coverage report.

## Compatibility Boundary

The existing official importer normalizes archived Bybit books to the top 50
levels. Tardis legacy `orderBook_200` data and current v5 data are different
channel contracts, and they must not be treated as one interchangeable feature
distribution merely because both are order books.

The importer must therefore label every retained minute with the provider,
channel, and retained depth. A strategy candidate can use a provider/channel
combination only after its thresholds are fixed in a development period. It may
not tune on the existing consumed sealed protocol or mix a legacy depth with a
v5 depth inside one validation window without an explicit canonical sampling
contract.

## Implementation Sequence

1. Record the public metadata coverage report outside the live runtime, then
   verify the supplied key's historical entitlement before requesting the long
   period.
2. Replay one legacy day and one v5 day into disposable SQLite databases. Check
   the normalized snapshot contract, local timestamps, disconnect markers, and
   1,440 minute reconstruction before importing any long period.
3. Use the provider-specific research importer to stream one day at a time and
   retain minute features plus response provenance, not raw multi-gigabyte
   archives.
4. Run a complete coverage audit from the chosen 60-month start through the
   current end. Any gap rejects protocol generation.
5. Freeze one candidate on chronological development folds. Generate a new
   immutable 20-40 window protocol only after coverage passes; score it once.
6. Promote nothing unless every sealed window meets CDR `>= 0.8%`, MTM MDD
   `<= 40%`, zero liquidations, and the minimum trade and active-day coverage.

## Current State

The public metadata probe has confirmed the advertised `BTCUSDT` range and
channel names, and the unauthenticated `2020-06-01` legacy feed preflight
confirmed the original snapshot/delta payload shape. Neither result proves the
paid subscription entitlement, every-day continuity, or full-period coverage.

The local Tardis Machine contract importer was also run without a credential
for two vendor-provided free first-of-month samples, using the canonical
25-level snapshot:

| UTC day | Channel era | Snapshots | Disconnects | Carried-forward minutes | Imported bars |
| --- | --- | ---: | ---: | ---: | ---: |
| 2020-06-01 | legacy | 1,444 | 4 | 0 | 1,440 |
| 2023-05-01 | v5 | 1,448 | 8 | 0 | 1,440 |

Those samples prove only the importer contract on one legacy and one v5 day.
No Tardis credential or long-range replay data has been received. Until those
are available, the original 1-60 month target remains unproven and must not be
represented as passed.
