# CLAUDE.md — Bus et Tram Nantes

Native Android replacement for the official **Naolib** app (Nantes public transport network). Goal: fast,
offline-first, better UX than the official app (which over-fetches). Package
`com.bustramnantes.app`. Kotlin + Jetpack Compose + Hilt + Room + Retrofit/kotlinx.serialization,
MapLibre for the map. Clean-ish architecture: `data/` (network/realtime/remote/Room/repositories) →
`domain/` (models, repository interfaces, use cases) → `presentation/` (Compose screens + MVVM
ViewModels, one `UiState`/`onEvent` per screen). A pure-JVM Gradle module **`:transit`** holds the
transit core (GTFS converter, packed network, RAPTOR, SIRI/GTFS-RT parsing, board composition).

## Build / test / run

```bash
./gradlew :transit:test :app:testDebugUnitTest   # JVM unit tests (fast, no device)
./gradlew :app:assembleDebug                     # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:compileDebugKotlin                # quick type-check
./gradlew :transit:packSeed [-Pgtfs=/path.zip]   # regenerate assets/seed/network.bin from the current GTFS
./gradlew :transit:packWalkGraph -Posm=/path/pays-de-la-loire-YYMMDD.osm.pbf  # regenerate assets/seed/walk_graph.bin
./gradlew :transit:packRideShapes -Posm=/path/pays-de-la-loire-YYMMDD.osm.pbf # add OSM ride paths to assets/seed/network.bin
```
- Android SDK is at `~/android-sdk` (`local.properties` has `sdk.dir`). Java/gradle work out of the box.
- **No emulator/device is available in this sandbox** — you cannot run the app or screenshot it.
  Verify via unit tests + compile; the user installs the APK to eyeball UI.
- Live opt-in tests: `LIVE_API=1 ./gradlew --no-daemon :app:testDebugUnitTest --tests '*Live…*' --rerun`
  (`--rerun`: env vars aren't task inputs).
- `.gitignore` excludes build/`.gradle`/`.kotlin`, `local.properties`, APKs and the `oeffi/` reference
  checkout (separate repo).

## Data sources

`SOURCES.md` is the reference for every source (endpoints, ids, rate limits, measurements). Summary:

| Data | Source | Code |
|---|---|---|
| Stops, lines, directions, served lines, timetables | **GTFS** (data.nantesmetropole.fr, Licence Ouverte), packed (~300 KB) | `TransitNetworkRepository`, `NetworkIndex` |
| Bacs de Loire B1/B2 (not in the Naolib GTFS) | **GTFS of the Département 44** (data.loire-atlantique.fr, Licence Ouverte), appended at conversion | `:transit` `gtfs/BacsDeLoire`, `AppendedFeed` |
| Itineraries | **on the device**, RAPTOR over the GTFS | `LocalItineraryRepository`, `:transit` `raptor/` |
| Walking (itineraries) | **OSM** pedestrian graph of Nantes Métropole (Geofabrik extract, ODbL), packed ~1.1 MB | `:transit` `walk/`, `NetworkIndex.walkGraph` |
| Ride paths (drawing a ride) | **OSM** bus/tram graphs + Naolib route relations (same extract), per stop-to-stop hop, in `network.bin` | `:transit` `RideShapes`, `tools/RideShapeBuilder` |
| Real-time departures | **Okina SIRI StopMonitoring** (keyless) | `RealtimeGateway`, `OkinaClient` |
| Provisional real-time | Okina GTFS-RT TripUpdates (own budget) | `RealtimeGateway` |
| Disruption notices | **Okina GTFS-RT Alerts** (shares the GTFS-RT budget) | `GtfsRtDisruptionRepository` |
| Map lines / labels | official raster + vector tiles (plan-app, seeded) | `MapStyle.kt` |
| Addresses | Géoplateforme | `GeocodingRepositoryImpl` |

The official app's backends - plan-app APIs (board, timetables, itineraries, autocomplete) and the
Flowbird MaaS API - are **not used**: they were reverse-engineered only as references to measure the
open data against (SOURCES.md §2-3). The plan-app board has no trip ids, so it can't be joined to the
timetable. Real time = SIRI + GTFS-RT only; only the plan-app **map tiles** are used. Don't add them.

### Static network (GTFS)
- Publication resolved through `GET data.nantesmetropole.fr/api/explore/v2.1/catalog/datasets/
  244400404_transports_commun_naolib_nantes_metropole_gtfs/records` → `fichier.id` (the change key;
  the download URL embeds it — never hard-code it), `fichier.url`, validity. ~26 MB zip, ~3 months.
- `:transit` `GtfsConverter` streams it (stop_times 583 MB / 4.6 M rows, never materialised) and
  de-duplicates: 187,896 trips → ~42.6 k unique (pattern, times) trips, ~316 patterns, ~200 service
  bitsets. `NetworkFormat` packs it (varints + deflate) to ~300 KB with shapes and the trip-id join
  table. JVM: ~7 s, < 128 MB heap. Trip ids are `FR_NAOLIB:VehicleJourney:<vj>-<serviceSuffix>`
  (always: `<serviceSuffix>` = the service id minus `FR_NAOLIB:Timetable:`), so only `vj → unique
  trip` pairs are stored; a SIRI/GTFS-RT trip id resolves via vj + the service day.
- `TransitNetworkRepository`: bundled `assets/seed/network.bin` vs the last downloaded
  `filesDir/network/<fileId>.bin` — **whichever is valid longest wins** (no seed version to bump).
  Refresh = daily `CacheRefreshWorker` + once-a-day app-open check: new file id → download
  (unmetered unless the installed feed ends within 3 days) → convert on the device with the current
  crosswalk carried over → write + re-read → atomic pointer swap. Settings "Effacer" → back to seed.
  Debug builds: Settings › Débogage converts on the phone and reports time/peak heap.
- **Ids**: `Stop.id` = GTFS StopPlace id `FR_NAOLIB:StopPlace:<n>`; `Line.id` = `FR_NAOLIB:Line:<short>`;
  `Direction.code` = GTFS `direction_id` (`"0"/"1"`) = SIRI `DirectionName` `A`/`R` (791/791 visits)
  (= the official board's `directionId − 1`). Canonical direction name = most common headsign of that
  direction (same as the official app's on 158/159 directions).
- **Crosswalk** (`:transit` `Crosswalk`, built at seed time against the official map's stop list
  `transit/seed/logical_stops.geojson`) = **display positions only**: the raster line tiles are drawn
  against the official positions. GTFS is finer than the official list where it merges stops: official
  "Chanzy" (C1;C6;NN) = GTFS Chanzy (C1, NN) + Bretéché (C6); same for Route de la Chapelle/Picabia
  and Lieu Unique/Duchesse Anne - Château. Display position = the geojson one when exactly one
  StopPlace took that official stop by name, else GTFS. 1,098/1,101 StopPlaces matched. Five geojson
  names lag the current ones (`Crosswalk.STALE_NAMES`). `Crosswalk.SEARCH_ALIASES` (former names) is
  **intentionally empty** — the hook stays for a future rename not yet in GTFS.
- `NetworkFormat`: magic `NAOLNETW`, **v3** (v1 = without footpaths, v2 = without ride shapes, both
  still readable). Bump the version on any layout change: an unreadable download falls back to the seed.
- **Ride paths** (`:transit` `RideShapes`, format v3): the GTFS shapes are useless for drawing (all
  one point per stop), so every stop-to-stop **hop** has its street path from OSM, built by
  `:transit:packRideShapes` (`tools/RideShapeBuilder` + `VehicleGraph`): bus graph / tram graph from the
  Geofabrik extract, routed hop by hop with the lines' OSM route relations as a guide (relation ways
  1×, others 1.6×; a gap or a line without relation is bridged by routing; straight when a stop is
  > 80 m off the graph or the path is > 2.5 × crow-fly + 300 m; ferries straight). Keyed by **quay
  numbers** (stable across publications) and only the interior points are stored (the ends are the
  current quays). `packSeed` and the on-device conversion **carry them over** (`RideShapes.carriedOver`):
  a new GTFS keeps every hop it shares with the previous network (and the seed's, for a download made
  by an older version - `loadBest`); a truly new hop is straight until the next `packRideShapes`.
  `LocalItineraryRepository.rideGeometry` = `TransitNetwork.ridePath` (quays + hop paths). SOURCES.md §8.
- Served lines (`NetworkIndex.servedLines`) come from patterns and are **terminus-aware** (a direction
  the stop only ends is omitted).
- Theoretical timetables (`TheoreticalTimetableRepositoryImpl`) are computed locally per service day,
  each run with its own headsign; after-midnight runs (> 24:00) come last with `nextDay`.

### Real-time (`RealtimeGateway`, one per process)
- **Okina SIRI SM**: `POST https://api.okina.fr/gateway/sem/realtime/anshar/services?datasetId=NAOLIBORG`,
  one `StopMonitoringRequest` per **Quay**, gzip honoured. Keyless = **1 request / 30 s per IP** for all
  SIRI paths (GTFS-RT has its own budget); 429 has no Retry-After.
- Consumers ask `RealtimeBoardRepository.boards(stopIds, priority)` (best board now + registers
  interest, TTL 75 s) or `awaitRealtime(…)`. One batched SIRI request per slot covers every wanted quay
  (priority open stop > itinerary > favorites, cap 120). `RequestBudget` (31 s) persists the last
  request time in DataStore, consumes the slot before the call and re-checks after waking.
- Board per stop: fresh SIRI (≤ 65 s) → `BoardComposer.composeWithSiri` + phantom tagging (SIRI and
  timetable are joined **by trip id**, so an early bus never leaves its own slot behind); else a **cached SIRI snapshot ≤ 5 min old** (`StopBoard.stale`, shown at
  once, no phantom tagging, refreshed at the next slot); else **provisional** (GTFS-RT overlay or bare
  timetable, **never phantom-tagged** — GTFS-RT misses ~27 % of tracked vehicles).
  `StopBoard.realtimePending` = fresher data is on its way (false right after an Okina failure).
- **Network prefetch** (`RealtimeGateway.prefetchNetwork`, from `MainActivity.onStart`): every Naolib
  quay in **one** SIRI request (2,518 quays, 1.24 MB gzipped, 8–10 s, longer OkHttp timeout), at most
  every 10 min (2 min after a failure), so a stop opened next shows real time immediately. Never looped
  (1.2 MB per slot would be ~150 MB/h).
- Stop Detail states the board's real-time status (`RealtimeStatus`): "Temps réel · mis à jour à
  HH:mm", "Temps réel de HH:mm · actualisation…" (cached, spinner), "Récupération du temps réel…"
  (spinner), "Temps réel indisponible · horaires théoriques", "Horaires théoriques" (bac only).
- **Real-time flag** (`Departure.isRealtime`) = trip listed by SIRI ∧ ≤ 50 min ahead (Sunday study:
  93.8 % agreement with the official `is_rt`, 97–99 % within 20 min). SIRI field values can't reproduce
  `is_rt`; the weekday study (§9b) confirms 50 min (93.1 %, per-line horizons 91.1 %; evening peak 93.9 % vs 94.2 %). Visits whose `DestinationRef` is one of the stop's quays are dropped (terminating trips).
  Unknown trips (newer feed) are still shown from SIRI's own fields.
- SIRI vs official board (weekday): 90–92 % of board RT entries identical within 30 s
  (Sunday 97.9 %); the rest are the same trips 30–120 s apart (re-predictions out of step).
- `Departure` carries `isRealtime`, `scheduledTime`, `tripId` (`"<trip>@<service day>"` or SIRI trip
  id), `destinationName` (short runs: Stop Detail labels the row "→ dest"),
  `source` (`SIRI | GTFS_RT | TIMETABLE`).
- **Real-time horizon & coverage (official board, 3 network sweeps at 18:00):** nothing more
  than **60 min** ahead is real-time; per line: Busway 4 ≈ 12 min, C1/C4 ≈ 25, tram 3 ≈ 30, most buses
  50–59. No real-time at all: TE2, N3, 1B, 118, 131, 137, 147.
- **Phantom buses** (`domain/util/PhantomDepartures.kt`, `tagSuspectedPhantoms`, on complete boards
  only): a theoretical departure on a line with real-time data on that board, ≤ 8 min away or
  sandwiched between RT departures of the same line+direction. Basis (official board): of 11
  Théo slots followed, 9 never went live. Shown greyed/italic, struck through, "probablement
  supprimé". Weekday SIRI study (SOURCES.md §9b): the rule tagged 7 of 46 phantoms with
  **no false alarm** (evening peak: 16 of 63, 2 false alarms = one C8 run sandwiched 20+ min ahead, in
  SIRI minutes later); every looser rule ("absent from SIRI", longer windows) flags real buses. Kept as is
  (ask before changing it).
- No live vehicle positions anywhere (GTFS-RT VP / SIRI VM empty).

### Disruptions (GTFS-RT Alerts)
- `GtfsRtDisruptionRepository` (singleton): fetch ≤ every 5 min (retry after 1 min on failure) when a
  screen calls `refreshIfStale()`, through the **shared GTFS-RT `RequestBudget`** (`@GtfsRtBudget`,
  also used by the TripUpdates overlay); raw message kept in `filesDir/realtime/alerts.pb` (offline,
  restart). `:transit` `ServiceAlerts.clean`: blank twin alerts (half the feed) folded into the titled
  one with the same periods; untargeted alerts get the lines their text names; quays -> StopPlaces.
- Relevance (`domain/util/DisruptionRelevance.kt`): shown = in force or starting within 3 days. Stop:
  "À cet arrêt" (targets a member StopPlace) + "Sur les lignes de cet arrêt" (whole-line targets of a
  served line). Line favorite: that line there/as a whole + every-line-at-this-stop. Itinerary leg:
  its line, or its boarding/alighting stop, overlapping the ride time (intermediate stops ignored).
- UI: Stop Detail boxes + dialog (HTML via `AnnotatedString.fromHtml`), amber dot on badges
  (`LineBadgeWithAlert`), one-line notice on favorite cards, Home top-bar "Perturbations" (count) →
  `DisruptionsScreen` (En cours / À venir, line chips), warnings on journey cards and leg cards.
  SIRI SX is deliberately unused (same content, would spend the SIRI budget).

### Itineraries (`LocalItineraryRepository`, RAPTOR in `:transit`)
- Range RAPTOR (up to 6 options over 2 h), arrive-by = the same engine on a time-reversed timetable,
  ≤ 5 rides, service days D−1..D+1, trips can be excluded. ~5 ms/query on the real network.
- Walking (real walks inside Nantes Métropole, straight lines outside): the
  **OSM walking graph** `assets/seed/walk_graph.bin` (`:transit` `walk/WalkGraph`, built by
  `:transit:packWalkGraph` from the **Geofabrik Pays de la Loire extract** - never the live OSM API;
  clipped to the métropole boundary relation `ref:FR:SIREN=244400404`, ways kept whole; pedestrian
  profile in `WalkGraphBuilder.isWalkable`; islands < 2 km dropped; 126 k vertices, 172 k edges,
  7,600 km, ~22 s to build). Undirected, 4 km/h on walked metres, **1 min to board** every vehicle.
  - **A, quay-to-quay** (`Footpaths`, in `network.bin` v2): street distance for
    quays ≤ 500 m crow-fly apart, kept when ≤ 650 m walked (~24.7 k directed pairs); off-graph quays
    and ≤ 50 m pairs the graph can't join keep crow-fly × 1.3. Computed by `packSeed` and by the
    on-device conversion (the bundled graph); a network without them gets them at router build.
  - **C, points** (`LocalItineraryRepository`): an address is attached to its nearest ways
    (`snapAll`: every edge within 40 m of the closest, ≤ 6 - one nearest way alone can be a dead-end
    path behind a fence) and one Dijkstra costs every quay ≤ 1.5 km crow-fly (3 km if nothing found),
    walk limit radius × 1.3; a picked stop's own quays at 0 + their footpaths. Walk-only ≤ 30 min on
    the streets. Walk legs are drawn along the street path. Outside the graph (a point > 200 m from
    any walkable way, a quay > 100 m): crow-fly × 1.3, straight line.
  - Graph vs Géoplateforme pedestrian router (40 random pairs ≤ 1.5 km): median ratio 1.02, 32/40
    within 15 % (crow-fly × 1.3: 21/40); outliers are paths present in only one of OSM / BD TOPO.
- Phantom avoidance (`PlanItineraryUseCase`): legs ≤ 60 min ahead are matched to their boarding
  stop's SIRI board **by trip id** (fallback line+direction+±90 s), tracked legs show the predicted
  time, phantom trips are **excluded and RAPTOR re-run** (≤ 2 retries; all-phantom results are kept
  and flagged). Waits ≤ 12 s per round for the shared SIRI slot - **never blocking the screen**:
  `PlanItineraryUseCase.stream` emits the timetable journeys first (`realtimeCheckPending`, the results
  screen shows a spinner + "Vérification des données temps réel…") and then the checked plan, which
  replaces them in place (an open journey stays open when its trips are still in the list). A search
  with no leg ≤ 60 min ahead emits once. Two latency helpers: the Search form registers the Départ's
  stop (or the stops ≤ 700 m of an address) with the SIRI scheduler as soon as it's set, for up to
  3 min, when leaving now / within the hour (`SearchViewModel.prefetchDepartureBoards`); and
  `TransitNetworkRepository` warms the walking graph, quay attachments and router 2 s after startup
  and after each new network.
- Validation vs the official planner (`LiveLocalVsPlanAppItineraryTest`, 2 × 40 random address trips,
  street walks): a journey every time the official one had one; median arrival Δ −1 / −1 min; 76/80
  within +5 min (remaining: official walks of 18–21 min to a farther stop; the one Indret trip that
  needed the bac now matches the official planner since the bacs were added).
- **Journey detail map** (`presentation/itinerary/map/`): the detail view is the journey on a map
  (`JourneyMap` on `NaolibMapView`) under a non-modal `BottomSheetScaffold` holding
  `JourneyDetailContent` (opens at half height, map pannable underneath). `journeyMapModel` (pure,
  tested) turns a `Journey` into legs (rides along their OSM path in the line colour with a white
  casing and a line badge; walks dotted grey), stops (transfers larger, tappable -> Stop Detail), a
  green start dot and a checkered arrival dot (an icon drawn at runtime: a circle layer can't carry a
  pattern) at the journey's first/last point; the camera fits it above the sheet.
- **Results map**: the results list sits in a non-modal sheet (opens at 55 % of the screen, collapses
  to a strip showing the highlighted journey - tap it for the detail) over `ItineraryOptionsMap`:
  journeys are grouped by route (`optionRoutes`: same lines between the same stops = one line on the
  map), each route in an `OPTION_COLORS` colour shown as a bar on its cards. Routes sharing a path are
  drawn **side by side** (`OptionLanes`, after LOOM - Bast/Brosi/Storandt): bundles within one lane at
  the map's scale with the same heading, 6 dp lanes, one global lane order searched exhaustively
  (≤ 7 routes) minimising join/leave crossings then keeping routes that share most next to each
  other, offsets smoothed, tapered to 0 at the start/arrival dots, loops cut on sharp turns. Computed off the main
  thread in Web Mercator metres per half zoom level (order kept across zooms; ≤ 4,000 samples per
  route, < 30 ms on a desktop JVM). A tap picks the line **closest to the finger** among those in the
  14 dp tap box (`nearestRoute`, screen distance) - lanes are 6 dp apart, so the first feature MapLibre
  lists would always win a shared stretch. Tapping a line highlights its route (`HighlightJourney`, wider, on
  top, others faded, list scrolls to its card); opening a journey highlights it too; the highlight
  survives the real-time recheck like the open journey (`rideKey`).
- Date picker / "plus tôt·plus tard" capped at `min(today+90 d, installed feed's last day)`.
- Navibus = GTFS ferry mode (`TransportMode.FERRY`, N1/N2/N3); NA/NC/NN/NO/NS are night buses.

### Bacs de Loire (B1 Basse-Indre ↔ Indret, B2 Couëron ↔ Le Pellerin)
- Département de Loire-Atlantique, free, used by the official planner, **absent from the Naolib GTFS**.
  Call them "bac" in UI and code comments alike, with no wording note in the code:
  `TransportMode.BAC`, share text "Bac B1", long name "Bac de Loire · Basse-Indre ↔ Indret".
- Badges: boats (Navibus `FERRY` and `BAC`) get a boat outline (`BoatHullShape` in `LineBadge.kt`, bow
  on the right; `res/drawable/badge_boat_hull.xml` tinted for the Glance widget); bus = circle, tram =
  rounded rectangle.
- Source: dataset `224400028_horaires-bacs-loire-en-loire-atlantique-gtfs` on data.loire-atlantique.fr,
  one zip per year listed by `BacsDeLoire.EXPORTS_URL` (never hard-code `gtfs_bac_loire2026.zip`). 4
  stops without parent stations, `frequencies.txt` every 20 min (`exact_times=0`), calendars to 2027.
- `GtfsSource.appending(extra, spec)` folds it into the Naolib feed as the converter reads it: landings
  numbered from `TransitNetwork.EXTERNAL_NUMBER_BASE` (9,000,000; bacs 9,900,000 + n, e.g.
  `FR_NAOLIB:StopPlace:9900003` Basse-Indre), frequencies expanded (end exclusive), calendar **clipped
  to the Naolib range**, a short name Naolib already uses becomes "Bac B1". The crosswalk skips those
  landings. `packSeed` fetches the current zip and bundles it as `assets/seed/bacs_de_loire.zip`; the
  on-device conversion fetches it again and falls back to that copy.
- Landings are never sent to SIRI (`TransitNetwork.isExternalQuay`): a bac-only stop's board is the
  timetable (not provisional), it registers no real-time interest.

### Map tiles (plan-app)
- **Map line traces = pre-rendered RASTER overlay** (not vector): `https://plan-app.naolib.fr/data/
  raster/v5/{z}/{x}/{y}.png` (day; night `v1nuit`). Standard slippy XYZ, 512-px tiles, z6–17. The
  parallel-lane offsetting is baked into the PNGs; the vector tiles CANNOT reproduce it (co-located
  routes are byte-identical, un-offset 2-vertex segments — do NOT retry `line-offset`).
- **Vector tiles** (labels + tap hit-testing only): TileJSON `https://plan-app.naolib.fr/api/
  external/tilejson/naolib_network.json` → `tile.cartographie.pro`, source layer **`network`**;
  `route_color` includes `#`, `route_type` is a **String**.
- **Map line tiles are seeded too**: `assets/seed/naolib_lines_v5_z6-14.mbtiles` (~3.4 MB, MBTiles =
  SQLite of the v5 raster PNGs, z6–14 over the Nantes bbox). MapLibre's HTTP goes through a dedicated
  OkHttp client (`@MapTileClient` in `NetworkModule`, set via `HttpRequestUtil.setOkHttpClient` in
  the Application). `SeedTileInterceptor` serves bundled tiles instantly/offline (MBTiles rows are
  **TMS** y-flipped — `MbTilesTileStore` handles it); un-seeded zooms (z15+) and the OSM basemap fall
  through to network and are disk-cached (50 MB). Seed is a build-time snapshot; regenerate the
  `.mbtiles` + bump `MbTilesTileStore.SEED_VERSION` to refresh. To regen: fetch v5 tiles over the
  bbox, store as MBTiles (TMS row = `2^z-1-y`). NB the OSM basemap is NOT seeded, so a fully-offline
  first launch shows lines over the beige backdrop (see below) until OSM loads.
- **Beige map backdrop**: a `BackgroundLayer` (`MapStyleIds.backgroundLayer()`, `#EFE9DD`) is added
  first (bottom of the style) so areas without loaded basemap tiles — while loading, or offline —
  read as a loading map instead of a black void. Lines/stops/labels draw on top.
  - **`MapLibre.setConnected(true)` is REQUIRED for the offline seed to work** (called in the
    Application). By default MapLibre auto-detects connectivity and, when offline, short-circuits tile
    requests BEFORE the OkHttp stack — so `SeedTileInterceptor` never runs and seeded tiles never show
    in airplane mode. The override forces every request through OkHttp (seeded tiles served offline;
    real-remote tiles just fail fast). Don't remove it.

## Offline-first design
- Everything static works with no network on a fresh install: stops, lines, served lines, timetables
  and **itineraries** (with street walks, `assets/seed/walk_graph.bin`) come from the bundled
  `assets/seed/network.bin`; map lines from the seeded
  MBTiles. Only real-time, address search and the OSM basemap need the network.
- Stop Detail renders badges + every direction block instantly from the network (`GetCachedStopLinesUseCase`),
  then the board (provisional at first if the SIRI budget is busy) fills the times.
- Connectivity errors: `presentation/common/utils/ErrorMessages.kt` maps network exceptions to a
  friendly French message (`CONNECTIVITY_ERROR_MESSAGE`) — never surface raw `Throwable.message`.

## Room
- `AppDatabase` is at **version 6**: only `favorites` and `recent_search` (user data); stops/lines are
  never stored (they come from the network). Migrations 1→6 are all additive (v2 `directionName`, v3
  the `recent_search` table, v4 `lineTextColor`, v5 recent-search `lat`/`lon`, v6 `legacyStopId`), all
  registered in `di/DatabaseModule`. `exportSchema = false`. Never use destructive migration (favorites
  are user data).
- `FavoriteRekeyer` (startup, idempotent): a favorite whose StopPlace id a newer GTFS publication
  retired moves to a same-name StopPlace (the one serving its line/direction, else the first) and keeps
  the old id in `legacyStopId`; recent searches are rewritten the same way.

## Stops (search / merge)
- **Search is accent-insensitive**: `normalizeForMatch` (NFD + strip marks + lowercase) on names and
  `Stop.aliases`, filtered in memory over a per-network index. Itinerary fields share this path.
- **Same-name StopPlaces ≤ 150 m apart merge into one marker** (`mergeDuplicateStops`; representative =
  most lines). `Stop.memberIds` lists every member: departures/served lines/timetables use all their
  quays, favorites on any member belong to the stop, and `getStopById` resolves member ids too. Far-apart same-name stops (two "Jean Jaurès", ~9 km) stay separate.
  `normalizeForMatch` collapses whitespace but not punctuation ("duchesse anne - chateau" ≠
  "duchesse anne-chateau").
- Search rows resolve line badges by **short name** (`linkedLines` holds short names).

## Navigation
- 3 bottom-nav tabs (`BottomNavTab`): Favoris (`home`), Recherche (`search`), Plan (`map`).
- `NavController.navigateToTab` uses `popUpTo(start)` + `launchSingleTop` and **deliberately no
  saveState/restoreState** — with a single graph, cross-tab detours (map→stop detail→"partir d'ici"
  →search) stack on the active tab and `restoreState` would revive them, making a tab tap look dead.
- **Two hosts for the Stop Detail sheet, one shared body** (`StopDetailSheetInner` in
  `StopDetailBottomSheet.kt`, `showMapAction` flag):
  - **Off the map** (Home/Search/Itinerary): a `dialog(...)` destination hosting a `ModalBottomSheet`
    (opens expanded). Shows the **"Voir sur la carte"** action → `NavController.showStopOnMap(stopId)`
    (`NavigationExtensions.kt`): closes the dialog, lands on a single fresh `Map` entry, hands it the
    stop id via that entry's `SavedStateHandle` (`Screen.Map.PRESELECT_STOP_ID_KEY`).
  - **On the map**: `MapScreen` hosts the sheet itself in a **non-modal `BottomSheetScaffold`** (no
    scrim → the map stays pan/zoom-interactive beneath the sheet). Tapping a stop / a search result /
    a preselect calls `selectStopOnMap` → `StopDetailViewModel.selectStop(id)` + ring + camera +
    `partialExpand()`. Tapping empty map or dragging to `Hidden` → `clearSelection()` (stops polling;
    ring persists). The map is full-screen (the old 0.4f "squish" was removed).
- `StopDetailViewModel` is **re-targetable** (`selectStop`/`clearSelection`; `currentStopId` is a
  `var`) so the map reuses ONE instance across taps — no per-stop instance leak / no piling-up 30s
  pollers. Dialog entry points still seed it once from the `stopId` route arg. (The `lowered` route
  arg + transparent-scrim path is now dormant — kept but unused after the BottomSheetScaffold move.)

## Favorites (Stop Detail star)
- No favorite at the stop: the star ("Mettre en favori") opens the picker (`FavoriteGranularityDialog`,
  Add mode). With some: "Favoris (n)" opens `StopFavoritesDialog` - "Gérer les favoris à cet arrêt" (a
  row per favorite: Modifier / Retirer) + "Ajouter un favori à cet arrêt". State = `FavoriteDialog`
  (`Manage | Add | Edit`); Annuler in Add/Edit returns to the list when opened from it.
- **Modifier rewrites in place** (`UpdateFavoriteUseCase`): same id (home widgets are bound to it),
  position and `addedAt`. Retirer is immediate + snackbar "Annuler" (`restoreFavorite`, back at its
  Home position, Home's undo too). The picker's confirm never removes: an exact twin of another saved
  favorite is disabled ("Déjà dans vos favoris"); overlaps (whole stop + a line) are allowed.
- Home card menu "Modifier" opens Stop Detail with `editFavoriteId` (route arg) -> editor up at once.

## Conventions / gotchas
- Match surrounding code style (heavy explanatory KDoc is the norm here).
- Line colors: `Line.color` is stored WITHOUT `#`; `Departure.lineColor`/`ServedLine.lineColor` keep
  the `#`. `LineBadge` accepts either.
- Bash tool: control characters in commands are rejected — use a Python script file for those edits.
- Persistent env: `/etc/sandbox-persistent.sh` (never put shell completions there — breaks bash).
- **Shared map component**: `presentation/common/map/NaolibMapView` = lifecycle-forwarded `MapView` +
  base style (glyphs, beige backdrop, OSM basemap); a screen adds its own sources/layers in
  `onStyleLoaded(map, style)` (Plan tab: network raster + labels + stops; itinerary maps: rides/walks).
- **MapLibre init order at startup.** `HttpRequestUtil.setOkHttpClient()` triggers MapLibre's
  `HttpRequestImpl` static init → `MapLibre.getApplicationContext()`, which throws if
  `MapLibre.getInstance()` hasn't run. `BusEtTramApplication.onCreate` therefore calls
  `MapLibre.getInstance(this)` **before** `setOkHttpClient(...)` (else instant crash on launch,
  before any screen). Any future startup code touching MapLibre HTTP must stay after that init.
- **Line-number labels had TWO independent bugs (both fixed).** (1) MapLibre symbol text needs a
  `glyphs` source; a bare `Style.Builder()` has none, so ANY `SymbolLayer` text silently never
  renders while raster/circle layers still work — the style is now started from
  `MapStyleIds.baseStyleJson()` (sets `glyphs` → `tile.cartographie.pro/fonts/...`). (2) The network
  vector tiles are tiny 2-vertex segments, and `symbol-placement: line` places a label only where a
  feature is longer than the spacing → placed *zero* labels; use `SYMBOL_PLACEMENT_LINE_CENTER` (one
  label per segment, collision-thinned). Labels show at zoom ≥13, drawn in `route_color` + white halo.
- `:transit` is pure JVM: SIRI XML goes through the XmlPullParser API (Android provides it at
  runtime via `android.util.Xml`; kxml2 is only `compileOnly`/`testImplementation`). GTFS-RT uses a
  hand-written protobuf decoder (no Wire/protobuf runtime — F-Droid friendly).

## Feature state (implemented)
Map with Naolib lines (pre-rendered raster overlay, seeded offline) + line-number labels + stops;
unified search (stops/addresses/itinerary); stop detail with all lines/directions (terminus-aware),
"Provisoire" boards and short-run labels; theoretical timetables (local); itinerary results and journey detail on a map (street-accurate ride
paths from OSM, side-by-side lanes for shared paths); favorites (stop / stop+line /
stop+line+direction) with per-departure line badges; home widget (one awaited SIRI request per refresh
for all widgets); **on-device itinerary planning** (Maintenant / Partir à / Arriver à, "Arriver plus tôt
/ plus tard" ±15 min via `domain/util/shiftItineraryTime`, handed back to the Search form through the
Search entry's `SavedStateHandle`); journey share menu (calendar via `ACTION_INSERT`, text, full-height
PNG through the `${applicationId}.fileprovider`; texts from `JourneyShareText.kt`). Suspected phantom
departures are flagged everywhere and near-term itineraries avoid them. Disruption notices (GTFS-RT
Alerts) on stops, favorites, itineraries and a "Perturbations" screen.
Tests (`:transit`): `GtfsConverterTest`, `CrosswalkTest`, `RaptorTest`, `RealtimeTest`,
`RealNetworkBenchmarkTest` (seed network), `RideShapesTest`. Tests (`:app`): `NetworkIndexTest`, `FavoriteRekeyerTest`,
`StopMergeTest`, `RealtimeGatewayTest`, `RequestBudgetTest`, `LocalItineraryRepositoryTest`,
`JourneyPhantomCheckTest`, `JourneyWalkTrimmingTest`, `DepartureGroupingTest`, `PhantomDeparturesTest`,
`LiveApiShapeDecodingTest`, `ErrorMessagesTest`, `ItineraryTimeShiftTest`,
`JourneyShareTextTest`, `TimetableHourBlocksTest`, `StopDetailFavoritesTest`, `GtfsRtDisruptionRepositoryTest`,
`DisruptionRelevanceTest`, `StreetWalkPlanningTest`, `JourneyMapModelTest`, `OptionLanesTest`, … (`:transit` also `ServiceAlertsTest`,
`WalkGraphTest`, `BacsDeLoireTest`) Live, opt-in: `LiveLocalVsPlanAppItineraryTest` (official planner as a reference only).
