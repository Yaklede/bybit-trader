# Forward Market Raw Capture

Date: 2026-07-22

## Problem

The forward collector previously retained only one-minute aggregates. That was
enough to inspect broad order-book imbalance and taker flow, but it could not
replay message ordering, distinguish a valid book epoch from a broken one, or
re-parse historical payloads after a parser correction.

## Goals

- Preserve each subscribed public market message without writing high-rate raw
  payloads into the trading SQLite database.
- Record exchange, matching-engine, and local receive times separately.
- Record local connection generation, order-book epoch, update ID, and sequence
  range for deterministic quality analysis.
- Invalidate the local order book and reconnect when its state cannot be trusted.
- Expose archive progress and gap counts through the authenticated dashboard
  summary API.

## Non-goals

- This change does not approve a strategy or enable automatic execution.
- It does not backfill historical L2 data.
- It does not infer individual order cancellation, maker queue position, or
  hidden/RPI liquidity.
- It does not automatically delete or upload archived segments.

## Source Contract

The collector follows Bybit's current public WebSocket contracts:

- [Orderbook](https://bybit-exchange.github.io/docs/v5/websocket/public/orderbook):
  snapshot replaces the local book, delta updates mutate it, `u=1` resets it,
  `seq` compares generation order across depths, and `cts` is the matching-engine
  timestamp. Level 50 can publish every 20 ms.
- [Public trade](https://bybit-exchange.github.io/docs/v5/websocket/public/trade):
  one message can contain many trades and multiple messages can share a `seq`.
- [All liquidation](https://bybit-exchange.github.io/docs/v5/websocket/public/all-liquidation):
  liquidation batches publish at up to 500 ms intervals.

Because the public trade contract permits repeated `seq`, the collector stores
its minimum and maximum values but does not treat repetition as a gap. The
order-book update ID is required and must increase monotonically. The official
contract does not explicitly guarantee that every delivered Level 50 update ID
increments by exactly one, so upward jumps are recorded rather than rejected.

## Capture Contract

Each accepted market message becomes one raw envelope plus zero or more
normalized events:

```text
WebSocket text frame
  -> raw envelope
     - schemaVersion
     - localConnectionId
     - topic / symbol / eventKind / messageType
     - exchangeTimestamp / matchingEngineTimestamp / receivedAt
     - sequenceStart / sequenceEnd / updateId / bookEpoch
     - quality / gapDetected
     - exact rawPayload string
  -> normalized order-book, trade, or liquidation events
  -> existing one-minute aggregation
```

The feed writes both market batches and minute-flush commands into one bounded
FIFO channel before archive and aggregation work. A flush therefore cannot pass
older queued market messages and replace a completed minute with a partial late
aggregate. The queue absorbs short disk or parser stalls without silently
dropping messages. Sustained backpressure eventually pauses the WebSocket reader
instead of discarding data. A graceful stop cancels intake first, drains the
channel, performs a final flush, and only then seals the archive.

## Order-book Quality States

| State | Meaning | Action |
|---|---|---|
| `VALID` | Valid delta on an initialized book | Apply and aggregate |
| `SNAPSHOT_RESET` | Snapshot or `u=1` reset | Replace book and increment epoch |
| `DELTA_BEFORE_SNAPSHOT` | Delta arrived without a book baseline | Archive, invalidate, reconnect |
| `MISSING_UPDATE_ID` | Required `u` is absent | Archive, invalidate, reconnect |
| `NON_MONOTONIC_UPDATE_ID` | `u` did not increase | Archive, invalidate, reconnect |
| `EMPTY_ORDER_BOOK` | One side of the reconstructed book is empty | Archive, invalidate, reconnect |
| `CROSSED_ORDER_BOOK` | Best ask is below best bid | Archive, invalidate, reconnect |

The invalid message is archived before the connection is restarted. No
normalized order-book sample is emitted from an invalid state.

## Storage Contract

Raw events are written under `BOT_FORWARD_RAW_ARCHIVE_PATH` as UTC date
partitions and minute segments:

```text
YYYY/MM/DD/
  BTCUSDT-YYYYMMDDTHHmmZ-<archive-session>-<sequence>.ndjson.gz
```

An active segment has the `.part` suffix. Closing the minute completes the gzip
stream and atomically renames it to `.ndjson.gz` when the filesystem supports an
atomic move. A process crash can leave only the current minute as `.part`; replay
must exclude it unless a separate salvage tool validates it. Final segment files
are never reopened or overwritten.

The writer flushes every 250 messages and at the minute maintenance interval.
There is no automatic retention because silent deletion would destroy research
evidence. Operations must monitor the Docker volume and transfer sealed segments
to durable storage.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `BOT_FORWARD_MARKET_CAPTURE_ENABLED` | `false` | Enables public stream capture |
| `BOT_FORWARD_RAW_ARCHIVE_ENABLED` | Same as capture | Enables raw gzip segments |
| `BOT_FORWARD_RAW_ARCHIVE_PATH` | `data/market-events` | Archive root; deployment uses `/data/market-events` |
| `BOT_FORWARD_ORDER_BOOK_DEPTH` | `50` | Reconstructed order-book depth |

The raw archive cannot be enabled while forward capture is disabled. Preflight
also verifies that the archive directory is writable.

## API Observability

`GET /dashboard/summary` remains control-token protected and now includes:

```text
forwardMarketCapture.rawArchive.enabled
forwardMarketCapture.rawArchive.archiveSessionId
forwardMarketCapture.rawArchive.archivedEventCount
forwardMarketCapture.rawArchive.gapEventCount
forwardMarketCapture.rawArchive.latestReceivedAt
forwardMarketCapture.rawArchive.activeSegment
forwardMarketCapture.rawArchive.lastCompletedSegment
```

Counts are scoped to the current process session. Persistent truth remains the
sealed archive files.

## Acceptance Criteria

- A snapshot followed by valid deltas reconstructs the same local book.
- Reconnect requires a new snapshot before any delta can be aggregated.
- Invalid order-book state is archived, counted, and forces reconnection.
- Public trade batches preserve sequence ranges without false duplicate gaps.
- A completed minute produces a readable gzip NDJSON segment.
- The active file remains `.part` until sealing or clean shutdown.
- Forward capture remains independent of private order execution.

## Risks

- Level 50 raw traffic can consume substantial disk even when compressed.
- A full disk stops the capture pipeline and requires operator remediation.
- Counts in the API reset on restart and must not be treated as the persistent
  event total.
- Public L2 still cannot identify individual order IDs, exact cancellations, or
  RPI orders omitted by the public feed.
