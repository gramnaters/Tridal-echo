# Echo tridal Extension (Tidal Metadata + tridal Streaming)

Tidal-rich metadata (no login needed) + tridal backend for streaming/downloads.

## Architecture
- **Metadata**: Tidal API (x-tidal-token header, no login required) — home feed, tracks, albums, artists, explore
- **Search**: tridal backend (Morg Worker) — track/artist/album search
- **Streaming**: DASH (Tidal CDN via tridal manifest) — 30ms TTFB
- **Download**: Progressive (fly.dev direct) — user-selectable quality

## Settings
- **tridal Worker URL**: Override backend URL (leave empty for default)
- **tridal Worker Token**: Override backend token (leave empty for default)
- **Monochrome API URL**: Optional, for search when tridal backend is down
- **DASH Port**: Local DASH server port (default 6969)

## Why no login?
Tidal's API allows access to most metadata endpoints (home, track, album, artist, explore) using just the `x-tidal-token` header — no OAuth login needed. Only search and recommendations require auth, so we use tridal's backend for search instead.
