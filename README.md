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
3. Open the plugin's config in RuneLite and set:
   - **Base URL** — your OSRS Flip Finder URL (e.g. `https://osrsflipfinder.com`)
   - **API key** — the key you just generated
4. Open the **Flip Finder Sync** side panel → **Test connection** → it should say
   **Connected**.

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
| Base URL | `http://localhost:3000` | Your OSRS Flip Finder URL, no trailing slash |
| API key | _(empty)_ | From OSRS Flip Finder → Settings → API keys |
| Enable sync | on | Master switch |
| Sync existing offers on login | off | If on, re-reports offers already in progress at login |

## Building from source

Requires **JDK 11** (RuneLite's target). The Gradle wrapper is included.

```bash
./gradlew run         # boot a developer-mode RuneLite client with the plugin loaded
./gradlew shadowJar   # build build/libs/flip-finder-sync-1.0.0-all.jar
```

## License

[BSD 2-Clause](LICENSE).
