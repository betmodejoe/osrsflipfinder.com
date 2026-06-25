# Flip Finder Sync

A [RuneLite](https://runelite.net) plugin that syncs your **Grand Exchange**
buys and sells to your [OSRS Flip Finder](https://osrsflipfinder.com) trade
journal. Trade in-game and completed offers show up in your journal
automatically — FIFO-matched into flips with GE tax and realised profit/loss
computed for you.

Only completed (filled) buy/sell quantities are sent — never your inventory,
location, or anything else.

## Setup

1. Install **Flip Finder Sync** from the RuneLite Plugin Hub (or build it, below).
2. Sign in at [osrsflipfinder.com](https://osrsflipfinder.com) → **Settings** →
   **Generate key**. The key is shown once — copy it.
3. Open the plugin's config in RuneLite and paste the key into **API key**.
4. The plugin **auto-connects** as soon as the key is set — open the **Flip Finder
   Sync** side panel and it should already say **Connected** (the **Test
   connection** button re-checks on demand).

Now place offers on the Grand Exchange — they appear in your journal as you trade.

## How it works

The plugin watches `GrandExchangeOfferChanged`. Each time an offer fills a little
more it computes the delta (newly-filled quantity + the average price paid),
gives it a deterministic id (`accountHash:slot:type:itemId:cumulativeQty`), and
POSTs it to `…/api/sync/trades` with `Authorization: Bearer <your key>`. The
deterministic id means re-observing the same fill (e.g. after a relog) is
de-duplicated server-side, so trades are never double-counted.

## Configuration

| Setting | Default | Notes |
| --- | --- | --- |
| API key | _(empty)_ | From OSRS Flip Finder → Settings → API keys |
| Enable sync | on | Master switch |

The endpoint (`https://osrsflipfinder.com`) is fixed in the plugin and is not
user-configurable.

## Building from source

Requires **JDK 11** (RuneLite's target). The Gradle wrapper is included.

```bash
./gradlew run         # boot a developer-mode RuneLite client with the plugin loaded
./gradlew shadowJar   # build build/libs/flip-finder-sync-1.0.0-all.jar
```

## License

[BSD 2-Clause](LICENSE).
