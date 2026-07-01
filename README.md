# Echo Jimmy Extension (Tidal Metadata + Jimmy Streaming)

Tidal-rich metadata (no login needed) + Jimmy backend for streaming/downloads.

## Architecture
- **Metadata**: Tidal API (x-tidal-token header, no login required) — home feed, tracks, albums, artists, explore
- **Search**: Jimmy backend (Lateralus Worker) — track/artist/album search
- **Streaming**: DASH (Tidal CDN via Jimmy manifest) — 30ms TTFB
- **Download**: Progressive (fly.dev direct) — user-selectable quality

## Settings
- **Jimmy Worker URL**: Override backend URL (leave empty for default)
- **Jimmy Worker Token**: Override backend token (leave empty for default)
- **Monochrome API URL**: Optional, for search when Jimmy backend is down
- **DASH Port**: Local DASH server port (default 6969)

## Why no login?
Tidal's API allows access to most metadata endpoints (home, track, album, artist, explore) using just the `x-tidal-token` header — no OAuth login needed. Only search and recommendations require auth, so we use Jimmy's backend for search instead.
