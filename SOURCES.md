# Data sources — reference

One place to see every source of transport information for the Naolib network: what each provides,
how it behaves, its ids, its limits, and what was measured. It's written for future refactors around
timetables, real-time and itinerary planning. Facts are dated; re-verify anything older than a few
months before relying on it.

Sources covered:

| Name used here | Host | Operator / nature |
|---|---|---|
| **Naolib official app backend** | `plan-app.naolib.fr` | Backend of the official Naolib web/Android app (reverse-engineered, unauthenticated, no published contract) |
| **MaaS** | `api-maas.hub.flowbird.eu` | Flowbird MaaS platform used by the official Android app (reverse-engineered, static API key + guest token) |
| **Open data — GTFS / NeTEx** | `data.nantesmetropole.fr` | Nantes Métropole open-data portal (published, Licence Ouverte v2) |
| **Open data — Okina real-time** | `api.okina.fr` | Okina gateway serving Nantes Métropole's open real-time feeds (SIRI, GTFS-RT) |
| **transport.data.gouv.fr** | `transport.data.gouv.fr` | National access point: catalogue, validators, a GTFS-RT proxy |
| **Géoplateforme** | `data.geopf.fr` | IGN/BAN geocoding (open) |
| **OpenStreetMap** | `tile.openstreetmap.org` | Basemap tiles |

`CLAUDE.md` § "Data sources" holds additional implementation detail for the sources the app
uses. When the two disagree, re-verify live and fix both. Measurement scripts were
throwaway; the methodology is described inline so it can be redone.

---

## 1. Summary matrix

| Information | Naolib official app backend | MaaS | Open data | Notes (2026-10-11) |
|---|---|---|---|---|
| Stop list + coords | `map/logical_stops.geojson` | `v1/transport/stops?skip&take` | GTFS `stops.txt` (**used**), NeTEx Arrêts | Backend geojson names are stale for 5 stops; GTFS names are current |
| Lines (names, colours, mode) | — (only inside board/itinerary payloads) | `v1/transport/lines` | GTFS `routes.txt` (**used**) | Equivalent |
| Directions of a line | board / timetable `directionId` 1-based | `directions[{code,name}]` | GTFS `direction_id` + `trip_headsign` (**used**) | Board id − 1 = MaaS code = GTFS id (tram 3 checked) |
| Lines/directions served at a stop | geojson `linked_lines` (lines only) | `stops?phrase&withLines=true` | GTFS patterns (**used**) | GTFS can also detect terminus-only directions |
| Scheduled timetable for a date | `api/stop/external/horaires/...` | — | GTFS `stop_times` + calendars (**used**) | GTFS is offline and valid longer |
| Ordered stops of a line | `api/line/sequence/{line}/{dir}` | — | GTFS patterns | |
| **Real-time departures at a stop** | `api/stop/logical/{id}` board | `v1/transport/stops/{Sid}/timetable` | **Okina SIRI StopMonitoring (used)** | All three come from **one engine**; SIRI = board to the second |
| Real-time, whole network in one call | — | — | Okina GTFS-RT TripUpdates (**used**, stop-gap) | Incomplete (~73% of board RT entries) |
| Vehicle GPS positions | — | — | GTFS-RT VP / SIRI VM | **Not produced** anywhere (empty feeds) |
| Disruptions | — | `v1/transport/disruptions` (unused, shape unverified) | **Okina GTFS-RT Alerts (used)**, SIRI SX | Alerts: titled HTML notices, line/quay targets; see §5.5 |
| Itinerary planning | `api/itinerary/search` | — | none (local RAPTOR on GTFS, **used**) | Runs on the device, §8 |
| Itinerary place autocomplete | `api/autocomplete/itinerary` | — | local stops + Géoplateforme (**used**) | |
| Address geocoding | — | — | Géoplateforme (**used**) | |
| Map line drawing | raster tiles v5 (**used**, seeded) | — | GTFS `shapes.txt` | Raster has lane offsetting baked in; vectors can't reproduce it |
| Map line labels / hit-testing | vector tiles `network` layer (**used**) | — | GTFS shapes | |

**One prediction engine.** Measured 2026-10-05: the official app board and the MaaS timetable carry
identical predictions when fetched together; MaaS is a delayed copy that lists only tracked
vehicles. Measured 2026-10-11: Okina SIRI StopMonitoring carries predictions identical to the
board to the second. Choosing between them is about **completeness, ids, limits and dependency**,
not accuracy.

---

## 2. Naolib official app backend (`plan-app.naolib.fr`)

Backend of the official Naolib app/web map. **Unauthenticated, no rate limit observed, no published
contract**: everything below was reverse-engineered (official APK v8.0.2, and
the web front-end's JS bundle) and verified live. It can change without notice.

### 2.1 Ids used by this backend

- **logical stop id**: `properties.id` in the geojson (e.g. `9756`). It keys the real-time board
  and the scheduled timetable.
- **external_id**: `properties.external_id` (e.g. `"57116"`). The official app's stop id; shared
  with MaaS (`"S" + external_id`) and with the itinerary API's `concernedStop.external_id` /
  `StopPlace.LogicalId` (despite the name).
- Line = **short name** (`"3"`, `"C1"`); direction = **1-based** (`1`/`2`).
- None of these ids exist in the open data. See §4.1 for the crosswalk.

### 2.2 Stops — `GET /map/logical_stops.geojson`

- Single request, ~1,236 features. `properties`:
  - `id` (logical), `external_id`, `stop_id`, `stop_param_id`
  - `name`, `lat`/`lng`, `city` (INSEE), `is_accessible`
  - `linked_lines` = **semicolon-joined string** (`"3;50;C8;"`, not an array)
  - `width`, `angle` (map rendering)
- **Stale names:** 5 stops still carried pre-rename names on 2026-09-28 (59582 Chanzy→Bretéché,
  61766 Gare de Bouaye→Bouaye, 65357 Lieu Unique→Duchesse Anne-Chateau, 60877 Route de la
  Chapelle→Picabia, 45493 St-Paul - Salengro→St Paul Salengro). MaaS carries the current names.
- A frozen copy (`transit/seed/logical_stops.geojson`) feeds the crosswalk for display positions:
  the raster line tiles are aligned to these coordinates.

### 2.3 Real-time board — `GET /api/stop/logical/{logicalId}`

- Keyed by the logical id (an external_id gives HTTP 500).
- `departures` = `lineNumber → directionId("1"/"2") → {line, direction (name), hours:[{time "HH:mm",
  ts, is_rt, destination_label}]}`. Use **`ts`** (ISO-8601 with seconds and offset): `time`
  truncates to the minute.
- An *empty* map is encoded as `[]` (PHP), so it must be decoded as a raw `JsonElement`.
- Shows real-time (`is_rt`) and timetable-only ("Théo") entries. Only lines/directions with an
  upcoming departure appear. Trips terminating at the stop are omitted.
- **Real-time horizon** (3 network sweeps, 2026-10-05 18:00):
  - never more than **60 min** ahead
  - per-line windows: Busway 4 ≈ 12 min, C1/C4 ≈ 25, tram 3 ≈ 30, most buses 50–59
  - no real-time at all on TE2, N3, 1B, 118, 131, 137, 147
- **Duplicates:** it sometimes emits two predictions for one vehicle (~300 pairs per network sweep).
- **Phantom buses:** timetable slots announced although no vehicle runs them. The app's phantom rule
  was calibrated on this board (`tagSuspectedPhantoms`: Théo entry on a line with real-time data,
  ≤ 8 min away or sandwiched between RT departures of the same line+direction).
- Direction **names** match MaaS exactly; `directionId − 1` = MaaS direction code.
- Observed 2026-10-11: the board for "Gare Sud" (geojson id) returned HTTP 500 for minutes on end.

### 2.4 Scheduled timetable — `GET /api/stop/external/horaires/{logicalId}/{lineNumber}/{directionId}?date=yyyy-MM-dd`

- Despite `external` in the path: the **logical** id, the line **short name**, and the 1-based
  direction. Wrong ids give 500.
- `times.time[] → {hour, minutes[{minute, destination_stop_name}]}`, plus `destination_stop_names`.
  Calendar-aware.
- No service on that date → 200 **without** the `times` key. Also embeds the real-time
  `departures` (redundant).

### 2.5 Itinerary — `GET /api/itinerary/search`

- Params (the web front-end's own):
  - `from`, `from_lat`, `from_lng`, `to`, `to_lat`, `to_lng`
  - `type` = `now|departure|arrival`, `date` (`yyyy-MM-dd`), `time` (`HH:mm`)
  - `avoid_dis`, `access`, `avoid_walk`, `walk_speed`, `electric_bike`
- **Arrive-by works.** Traps:
  - `departure`/`arrival` need both `date` and `time` (otherwise 400).
  - `HH:mm:ss` → an HTML error page.
  - `dd/MM/yyyy` → 200 with 0 solutions.
  - An unknown `type` falls into a lenient parser.
- **Schedule window** = [yesterday, end of the published timetable] (2026-12-24 when checked
  2026-09-28). Outside it: 200 with walk/bike/car only.
- Response:
  - wrapped in `solutions`; modes `walk`, `bus` (covers tram), `velo`, `car`
  - `PTRide`/`Leg` nested under `section.api`
  - timestamps `dd/MM/yyyy HH:mm:ss` local
  - walk legs carry `pathLinks[]` WKT **street geometry**
  - transit legs carry `concernedStops` (external_id) and `PTRide.Direction.Id` (= board direction id)
  - Navibus boats are reported as bus
- **No trip-exclusion parameter.** Avoiding a given trip means re-searching from just too late.

### 2.6 Itinerary autocomplete — `GET /api/autocomplete/itinerary?q=`

Suggestions (stops, places) for the itinerary fields.

### 2.7 Line sequence — `GET /api/line/sequence/{lineNumber}/{directionId}`

Ordered stops of a line+direction (`line_sequences`).

### 2.8 Map tiles

- **Raster line overlay** `https://plan-app.naolib.fr/data/raster/v5/{z}/{x}/{y}.png`:
  - day style; night is `v1nuit`
  - 512-px tiles, z6–17
  - Parallel lanes for co-located lines are baked in by the upstream cartography pipeline.
  - Seeded offline as `assets/seed/naolib_lines_v5_z6-14.mbtiles` (TMS rows).
- **Vector tiles** via TileJSON `/api/external/tilejson/naolib_network.json` → `tile.cartographie.pro`:
  - source layer **`network`**
  - `route_color`/`route_text_color` include `#`
  - `route_type` is a string
  - co-located routes are byte-identical 2-vertex segments (hence no vector lane offsetting)
  - glyphs at `tile.cartographie.pro/fonts/...`
  - Used for line-number labels and tap hit-testing only.

---

## 3. MaaS (`api-maas.hub.flowbird.eu`)

Flowbird's MaaS platform behind the official Android app. Reverse-engineered from the APK.

- **Headers on every call:**
  - `X-Gravitee-Api-Key: 66e075a7-fcd0-40c4-9933-3c5ca76a1636` (static, from the APK's
    `libnative-lib.so`)
  - `X-MPP-Channel: android`
  - `iv-transit-operator: 105`
  - `iv-transit-network: 105`
- **Guest token:** `POST v1/auth/guest`, no body → `{"token": "..."}` (field `token`). Sent as
  `Authorization: Bearer`. Enough for all read-only transport data.
- **Stop ids:** `"S" + external_id` (e.g. `S57116`). There is no get-stop-by-id endpoint (404).

### 3.1 Lines — `GET v1/transport/lines`

- Params: `phrase`, `skip`, `take`, `transportModes[]`.
- Response: `{"items":[...]}`. Each item: `number`, `name`, `lineColor` (#-prefixed), `textColor`,
  `transportType` (lowercase; `ferry` = N1/N2/N3 Navibus), `directions[{code, name}]`.

### 3.2 Stops — `GET v1/transport/stops`

- **Catalogue:** `?skip=&take=` with no phrase, `take` ≤ **1000** (else 400), so 2 pages for ~1,100
  stops. `lines` is `null` without `withLines` (decode with `coerceInputValues`).
  - **Authoritative for current names** (the geojson's are stale for 5 stops, §2.2).
  - Its coordinates can differ from the geojson's (~147 m for Bretéché).
- **With lines:** `?phrase={name}&withLines=true` → `{items:[{id:"S57116", lines:[<line with both
  directions>]}]}`.
  - Filter `items` by id.
  - Lists every line/direction served regardless of service hours. It's **not terminus-aware**:
    final-destination directions are listed too.

### 3.3 Timetable — `GET v1/transport/stops/{Sid}/timetable`

- `List` of items, each with **one** direction in `line.directions[0]`.
- Only the **next 3** per line+direction, and **only tracked vehicles**: 251/252 board Théo entries
  were absent, while all 1,830 MaaS `delay 0` items were board RT entries.
- Signed `delay` in seconds (`time − delay` = scheduled).
- Includes trips **terminating** at the stop.
- A **delayed copy** of the board's engine: over 8 min × 3 stops, the board showed a new prediction
  first in 76/91 cases (median +20 s).

### 3.4 Disruptions — `GET v1/transport/disruptions`

- Params: `favoritesOnly`, `lineId`, `locale`, `skip`, `take`.
- Response shape **never captured**.

---

## 4. Open data — static offer

### 4.1 GTFS

- **Dataset:** data.nantesmetropole.fr `244400404_transports_commun_naolib_nantes_metropole_gtfs`
  (mirrored on transport.data.gouv.fr dataset `685d9000ec5a7481ab634360`, resource 84101).
- **Download URL is not stable**: the file id inside the URL changes on each publication. Resolve
  it through the records API:
  `GET https://data.nantesmetropole.fr/api/explore/v2.1/catalog/datasets/244400404_transports_commun_naolib_nantes_metropole_gtfs/records?limit=20`
  → `results[0].fichier.url` (+ `debut_de_validite`, `fin_de_validite`). As of 2026-10-11:
  `.../files/0cc0469a72de54ee045cb66d1a21de9e/download/`, file `gtfs_lumidata_id.zip`.
- **Licence:** Licence Ouverte v2 (attribution).
- **Validity window:** 2026-10-09 → 2027-01-09 (≈3 months, republished regularly). Longer than the
  official app's itinerary schedule window (which ended 2026-12-24 when checked).
- **Size:**
  - 26 MB zip
  - `stop_times.txt` **583 MB** uncompressed (4,649,834 rows)
  - `trips.txt` 45 MB (187,896 trips)
  - `shapes.txt` 0.6 MB; `stops.txt` 3,619 rows; `routes.txt` 97 rows
  - 219 services in `calendar.txt` + 272 `calendar_dates.txt` exceptions
  - no `transfers.txt`, no `frequencies.txt`; fares present (2 fare attributes)
- **Heavily redundant:** a VehicleJourney is duplicated once per service-period chunk (`...-Dimanche-01`,
  `-03`, …). After de-duplication:
  - **42,596** unique (stop sequence, times) trips
  - **314** distinct stop patterns
  - **7,589** distinct running-time profiles
  - **392** distinct service-id sets

  A naive binary packing (pattern idx, profile idx, start minute, calendar idx + profiles) zlib-
  compresses the **whole 3-month timetable to ≈220 KB**, excluding the original trip-id strings
  needed to join real-time.
- **Daily volume:** weekday (Mon 2026-10-12) 9,019 trips / 230,249 stop events; Saturday 5,694 /
  149,084; Sunday 2,713 / 68,430; Christmas day 2,670.
- **All times are whole minutes** (0 rows with non-zero seconds). `arrival_time != departure_time`
  in only 1,616 rows. Max time 29:26 (after-midnight service as >24 h, standard GTFS).
- **Ids** (the producer states they're shared with NeTEx, SIRI and GTFS-RT):
  - stops: `FR_NAOLIB:StopPlace:<n>` (1,101, `location_type=1`) / `FR_NAOLIB:Quay:<n>` (2,518)
  - routes: `FR_NAOLIB:Line:<shortName>`
  - trips: `FR_NAOLIB:VehicleJourney:<n>-<service>`
  - services: `FR_NAOLIB:Timetable:...`
- **Routes:** `route_type` 0 = tram (3), 3 = bus (91; busways included), 4 = ferry (3: N1/N2/N3,
  Navibus). Colours **without** `#`. `route_sort_order` present.
- **Directions:** `direction_id` 0/1. For tram 3, `0` = Marcel Paul and `1` = Neustrie: equal to
  the board's `directionId − 1` and the MaaS code (only tram 3 checked). `trip_headsign` is per
  trip, so short runs carry their own terminus.
- **Stop names are current:** Bretéché, Picabia, … Some old names still exist as *separate*
  StopPlaces (e.g. a "Chanzy" StopPlace 1040 next to "Bretéché" 1078). Don't assume a rename means
  one record.
- **No official-app or MaaS ids anywhere.** Crosswalk against the seed geojson (1,236 features):
  - Exact accent-insensitive name + nearest StopPlace < 300 m matches **1,153**.
  - 5 are same-name-but-far.
  - **78** have no exact-name StopPlace (hyphen/abbreviation variants, e.g. "Viarme-Talensac",
    "Michelet - Sciences", "Ste-Philomène").

  Distance between matched points: median 19 m, p95 101 m (the raster tiles are aligned to the
  geojson coordinates).
- transport.data.gouv.fr validator: 6 "DuplicateStops", 17 "NullDuration" warnings — harmless.
- Producer note: Nantes Métropole recommends NeTEx over GTFS for "richer features". For this app,
  GTFS carries everything needed and is far simpler to parse.

### 4.2 NeTEx

- **Arrêts** (`244400404_arrets_transports_commun_naolib_nantes_metropole_netex`): one 9 MB XML.
  Quays carry:
  - `KeyValue imported-id` = legacy Naolib codes (e.g. `NAOLIBORG:Quay:PAS59`)
  - postal town
  - accessibility assessment
  - `SiteRef` → StopPlace

  Same `FR_NAOLIB:*` ids as GTFS.
- **Offre** (`244400404_offre_transports_commun_naolib_nantes_metropole_netex`): one XML per line
  plus common/calendar files. Not analysed. It might hold canonical direction names /
  DestinationDisplays if GTFS headsigns prove insufficient.
- Same unstable-file-id caveat as GTFS (resolve via the records API).

---

## 5. Open data — real-time (Okina gateway)

Nantes Métropole's real-time open data is served by Okina's Gravitee gateway `api.okina.fr`. The
endpoint list comes from the records APIs of `244400404_reseau-transports-collectifs-naolib`,
`..._gtfs_rt`, `..._siri`, `..._sirilite`.

### 5.1 Access plans and rate limits (verified 2026-10-11)

From the Okina portal (API `d62676bc-2691-4cbb-a676-bc26911cbbdb`, page "Différents types d'accès"):

- **Accès libre (keyless):** SIRI only (not SIRI Lite), request mode only (no subscriptions),
  **1 request per 30 s**.
- **Accès authentifié (API key, manual approval):** SIRI + SIRI Lite, request + subscription,
  **no rate limit**.

Observed behaviour:

- **Limit is per caller (IP) and per gateway API.**
  - All SIRI paths (`gateway/sem/...`) share one budget: a 403 on `/anshar/siri` consumed the slot
    for `/anshar/services`.
  - All GTFS-RT paths (`gateway/semgtfsrt/...`: trip-updates, alerts, vehicle-positions) share
    another.
  - **SIRI and GTFS-RT are independent**: SIRI 200 → GTFS-RT 200 immediately → SIRI 429.
- **429 response:** JSON `{"message":"Rate limit exceeded ! You reach the limit of 1 requests per 30
  seconds","http_status_code":429}`. **No `Retry-After` or `RateLimit-*` headers.**
- **Spacing works:** 7 consecutive batched SIRI calls spaced 34 s apart → zero 429s.
- **IPv4 only** (no AAAA record). Users behind one IPv4 address share a quota: carrier-grade NAT
  on mobile, shared Wi-Fi.
- **gzip:** GTFS-RT honours `Accept-Encoding: gzip` (trip-updates ≈380-590 KB → 86-130 KB on the
  wire). **SIRI too** (verified 2026-10-12: `content-encoding: gzip` on a 200 from
  `/anshar/services`); its XML compresses ~18×.

### 5.2 SIRI StopMonitoring (keyless) — the board equivalent

```
POST https://api.okina.fr/gateway/sem/realtime/anshar/services?datasetId=NAOLIBORG
Content-Type: application/xml

<Siri xmlns="http://www.siri.org.uk/siri" version="2.0"><ServiceRequest>
  <RequestTimestamp>2026-10-11T12:30:00Z</RequestTimestamp>
  <RequestorRef>opendata</RequestorRef>
  <StopMonitoringRequest version="2.0">
    <RequestTimestamp>2026-10-11T12:30:00Z</RequestTimestamp>
    <MonitoringRef>FR_NAOLIB:Quay:1234</MonitoringRef>
  </StopMonitoringRequest>
  <!-- repeat StopMonitoringRequest for more quays: 77 in one call worked -->
</ServiceRequest></Siri>
```

- `POST .../anshar/siri` (the documented raw-XML path) → **403** without a key. Use `/anshar/services`
  with `?datasetId=NAOLIBORG`.
- `MonitoringRef` must be a **Quay** id. A StopPlace id returns `Status=true` with zero visits.
- **Response:** `ServiceDelivery/StopMonitoringDelivery/MonitoredStopVisit[]`, all quays in one
  delivery. Fields per visit:
  - `MonitoringRef` (quay)
  - `LineRef` = `FR_NAOLIB:Line:<short>:LOC`
  - `DatedVehicleJourneyRef` = GTFS trip_id + `:LOC`
  - `DirectionName` `A`/`R`
  - `DestinationRef` (quay) + `DestinationName` — **per-trip destination**, handles short runs
  - `PublishedLineName`, `VehicleMode`
  - `MonitoredCall/{Order, Aimed|Expected}{Arrival|Departure}Time` (ISO with offset, seconds precision)
  - `ArrivalStatus` `onTime|delayed|early`
  - `Monitored`, `RecordedAtTime`
- Payload: ~600 KB XML for 77 quays (~48 visits for an 8-quay stop). Horizon up to ~2 h.
- **Whole network in one request works** (2026-10-12 14:27, weekday): all **2,518** quays in one
  batched POST -> HTTP 200, 11,933 visits on 2,225 quays, **1.24 MB gzipped / 15.8 MB XML, 7.6-10 s**
  server time. `PreviewInterval` (PT60M) and `MaximumStopVisits` (4) are **ignored** (same size).
  Used once per app foreground (≥ 10 min apart), never in a loop (~150 MB/h at one per slot).
- `EstimatedTimetableRequest` with `RequestorRef=opendata` returned an empty delivery (Anshar tracks
  per-requestor deltas); a retry with a unique requestor was rate-limited and not pursued - the bulk
  StopMonitoring above covers the need.
- **Comparison with the official app board**
  - Method: 6 paired snapshots, 2026-10-11 14:30–14:35 (Sunday), 19 busy stops, all quays of each
    stop. A board entry and a SIRI visit match on line short name with times within 30 s.
  - Board **real-time** entries found in SIRI: **1,505 / 1,538 (97.9%)**.
  - Matched times **identical to the second** in 1,549 / 1,581 pairs: the same engine.
  - Board **Théo** entries found in SIRI: only **76 / 507**. In one snapshot cross-checked by GTFS
    trip id, 46 were present and 42 absent. All absent ones were **≥ 25 min ahead**, mostly trams
    1/2/3/4: trips the operating system hasn't picked up yet, which the board pads from the
    timetable.
  - **Phantom-rule cross-check** (`tagSuspectedPhantoms` on the same snapshots):
    - Every tagged slot was absent from SIRI, but that was just 1 slot seen in 5 snapshots (tram 3
      → Neustrie at Rond-Point de Vannes, "sandwiched", ~44 min ahead).
    - The other 226 absent Théo slots were untagged.
    - No Théo slot ≤ 8 min existed in the sample (Sunday): the "imminent" half is **untested**.
  - SIRI also lists **trips terminating at the stop** (e.g. tram 2 → Gare de Pont Rousseau at Gare
    de Pont Rousseau), which the board omits. Filter on `DestinationRef` ∈ the stop's quays.
- **`Monitored` is NOT the board's `is_rt`.** It is `true` even 2 h ahead. Matched board-RT entries
  were 1,463 `true` / 42 `false`; board-Théo entries 75 `true` / 1 `false`.
- **The board's `is_rt` can't be derived from SIRI field values.**
  - Of board-RT entries matched in SIRI: 686 have Expected ≠ Aimed with seconds precision, 9 have
    Expected ≠ Aimed on whole minutes, and **809 have Expected == Aimed on whole minutes
    (`onTime`)**.
  - Those 809 have the same XML shape as board-Théo visits: same tags, same `Monitored`; only
    `RecordedAtTime` varies, with no pattern.
  - "Real-time" must therefore be redefined when using SIRI.
- **Scoring SIRI rules against the board's `is_rt`** (2026-10-11 14:30–15:05, Sunday; 18
  snapshots × 19 stops; 6,244 board entries ≤ 60 min ahead; 4,548 RT / 1,696 Théo). The board's
  RT flag is accurate, so it serves as ground truth. A board entry counts as "present" when SIRI
  has a visit of the same line within 2 min.

  | Rule | Agreement | Board RT caught | Théo wrongly RT |
  |---|---|---|---|
  | Expected ≠ Aimed | 59.6% | 46.4% | 84 / 1,696 |
  | `Monitored = true` | 90.8% | 95.5% | 368 |
  | Trip present in SIRI | 91.2% | 96.8% | 405 |
  | **Trip present in SIRI ∧ ≤ 50 min ahead** | **93.8%** | **93.8%** | **101** |
  | Present ∧ ≤ 45 min | 90.8% | 88.2% | 41 |
  | GTFS: trip already departed its terminus (+0…15 min) | 54–71% | 38–60% | 5–19 |
  | GTFS: vehicle block (`block_id`) already on the road | 90.5% | 95.0% | 369 |
  | Present ∧ ≤ per-line horizon learned on the other half | 89–91% | 90–93% | — (worse than a flat cap) |

  - "Present ∧ ≤ 50 min" by minutes ahead: 0–5 min **97.2%**, 5–10 **99.1%**, 10–20 **98.1%**,
    20–30 94.1%, 30–40 97.8%, 40–50 89.4%, 50–60 66%. Disagreements sit almost entirely where the
    board's per-line real-time window ends.
  - **Real-time ≠ "vehicle on the road".** The board marks trips RT up to ~58 min before they
    leave their terminus (727 on-time RT entries had not started yet). It isn't the vehicle block
    either (rule above, 90.5%). SIRI listing the trip at all is the best proxy found.
  - Times agree too: of 4,806 matched pairs, 4,465 were identical to the second and 4,606 within
    30 s.
  - Residual board-RT entries without a SIRI visit within 2 min: 147 of 4,548. None were board
    duplicates; 13 were ≤ 5 min away (mostly vehicles arriving at the stop).
  - **Limit:** Sunday only. Weekday per-line horizons are much shorter for some lines (Busway 4
    ≈ 12 min, C1/C4 ≈ 25, tram 3 ≈ 30 — 2026-10-05 sweeps), so the 50-min cap and per-line
    horizons must be re-checked on a weekday.

### 5.3 GTFS-RT TripUpdates (keyless)

- `GET https://api.okina.fr/gateway/semgtfsrt/realtime/trip-updates/NAOLIBORG` (protobuf, full
  dataset each time).
- Content (Sunday 14:00):
  - ~330–340 trips, all `SCHEDULED`.
  - `trip_id` matches static GTFS **100%**; `route_id` present.
  - `vehicle.id` = the trip id (no real vehicle id).
  - Per-stop `arrival/departure {delay, time}` in seconds.
  - Header timestamp ≤ 30 s old.
  - Window ≈ **next 60 min** (remaining stops cut at ~now+60 min; trips starting up to ~2 h ahead
    appear with scheduled times).
  - Timing-point stop updates carry exactly scheduled times (`delay 0`).
- **Coverage vs board** (6 snapshots, same 19 stops):
  - Board RT entries found: 936 / 1,430 within 30 s, 104 within 120 s, **390 missing (27%)**.
  - Missing ones are whole **in-progress vehicles**, concentrated on trams 1/2/3. Example: tram 3
    at Hôtel Dieu, where the board's 14:24:14 / 14:32:27 / 14:38:13 RT vehicles were absent while
    later trips were present.
  - Where present, times are identical to the board (p50 difference 0 s).
- Suitable only as a **stop-gap** (e.g. a stop opened while the SIRI budget is spent). Absence from
  this feed means nothing, so never use it to infer phantoms.

### 5.4 Vehicle positions — not available

- GTFS-RT `.../vehicle-positions/NAOLIBORG` → 200 with a **15-byte** feed (header only, 0 entities).
- SIRI VehicleMonitoring (keyless, `LineRef FR_NAOLIB:Line:3:LOC`) → empty `VehicleMonitoringDelivery`.
- SIRI StopMonitoring visits carry no `VehicleLocation`.
- Consistent with Nantes Métropole's statement (data.gouv.fr discussion, closed 2026-06-02) that
  vehicle monitoring isn't produced yet. The official app has no live-vehicle map; a full
  re-decompilation of its APK found no GTFS-RT/SIRI/Okina reference.

### 5.5 Alerts (measured 2026-10-12 11:04, used by the app)

- **GTFS-RT Alerts** `https://api.okina.fr/gateway/semgtfsrt/realtime/alerts/NAOLIBORG`: keyless,
  whole network in one message (11.6 KB, 59 alerts). Shares the GTFS-RT 1 req / 30 s budget with
  TripUpdates.
  - Text: French, `language = "unspecified"`; `header_text` plain, `description_text` **HTML** (`<h4>`
    dates, `<p>`, `<strong>`, links to diversion maps on naolib.fr). No `url`.
  - Periods: real `active_period`s, including **future** notices (a demonstration the next day, an
    event in two weeks, works starting later).
  - Targets: `route_id` = GTFS `FR_NAOLIB:Line:<short>`, `stop_id` = GTFS `FR_NAOLIB:Quay:<n>`
    (route-only 63 entities, route+stop 33). Join directly.
  - **27 of 59 alerts are blank** (no header, empty description): targeting twins of a titled alert
    with the same active periods (22 match exactly, the rest share a line with overlapping periods).
    9 titled alerts have no entity at all; their text names the lines ("les lignes C1, 11 et 12").
  - `effect` never set; `cause` UNKNOWN (52), CONSTRUCTION (4), DEMONSTRATION (3); `severity_level`
    INFO (54), UNKNOWN (4), SEVERE (1). Nothing machine-usable for routing.
  - App (`GtfsRtDisruptionRepository`, `:transit` `ServiceAlerts`): blank twins folded into their
    titled alert, lines inferred from the text for untargeted ones, quays mapped to StopPlaces;
    fetched at most every 5 min while a screen asks, raw message kept on disk.
- **SIRI SituationExchange** (same SIRI gateway, `SituationExchangeRequest`): 51 situations, same
  content; `Severity` "noImpact" on 49/51, no `Consequence`/`ReportType`. **Not used** - it would spend
  the SIRI budget the departure boards need.
- transport.data.gouv.fr's alerts proxy has the 429 problem of §6 - avoid.

### 5.6 SIRI Lite

Needs an authenticated key (`.../sem/realtime/siri?...` → 401 without). transport.data.gouv.fr's
listing uses its own private key; not for reuse.

---

## 6. transport.data.gouv.fr

- National catalogue entry "Réseau urbain Naolib" (`685d9000ec5a7481ab634360`): mirrors the GTFS,
  NeTEx, SIRI, SIRI Lite and GTFS-RT resources, with validation reports and hourly availability
  history. Catalogue API: `GET https://transport.data.gouv.fr/api/datasets`.
- **GTFS-RT proxy — avoid.** `https://proxy.transport.data.gouv.fr/resource/naolib-nantes-gtfs-rt-trip-update`
  (and `-alerts`) relays the same Okina GTFS-RT through a single upstream identity:
  - It serves a short-lived cached copy, then hits Okina's 1/30 s limit on refresh and passes the
    **429 to everyone** (plain-text body, same message).
  - Measured: 7 of 10 polls at 45 s spacing got 429.
  - Its own availability history shows 52–84% per day.

## 6b. Bacs de Loire (Département de Loire-Atlantique) - used

- Dataset `224400028_horaires-bacs-loire-en-loire-atlantique-gtfs` (data.loire-atlantique.fr, Licence
  Ouverte v2; on transport.data.gouv.fr as "Bacs de Loire en Loire-Atlantique (44)"). Current zip via
  `GET https://data.loire-atlantique.fr/api/v2/catalog/datasets/224400028_horaires-bacs-loire-en-loire-atlantique-gtfs/alternative_exports`
  (`alternative_exports[].metas.url`, one file per year: `gtfs_bac_loire2026_zip`, 3,979 bytes).
- Content (2026-10-12): agency "Compagnie des Bacs de Loire"; routes `B1` Basse-Indre <> Indret and
  `B2` Couëron <> Le Pellerin (`route_type` 4, no colours); 4 stops (no parent stations); trips via
  `frequencies.txt`, headway 1,200 s, `exact_times=0` (B1 weekdays 05:30-22:10, weekends 06:30-21:30;
  B2 06:00/06:10-21:00/20:50); crossing 10 min; services `SEMAINE` / `WEEKEND_JF` 2025-11-01 ->
  2027-12-31 with public holidays listed to 2027; fare `TARIF_GRATUIT` 0.00 EUR; shapes present.
- The official Naolib planner uses them (leg "B1"); the Naolib GTFS doesn't contain them.
- Also on transport.data.gouv.fr: the regional aggregate GTFS (`mobi-iti-pdl.okina.fr/.../gtfs_global.zip`,
  every Pays de la Loire network) - not needed.

## 7. Other open sources in use

- **Géoplateforme geocoding** `https://data.geopf.fr/geocodage/search/` (BAN addresses; no auth).
- **OpenStreetMap tiles** `https://tile.openstreetmap.org/{z}/{x}/{y}.png` (basemap; not seeded offline).

## 8. Itinerary planning without the official app backend

No open itinerary API exists for Naolib, so the app routes on the device (RAPTOR over the GTFS).
Data it needs:

- Timetable: GTFS (packed ≈220 KB for 3 months, §4.1).
- Transfers: no `transfers.txt`. 12,957 quay pairs lie within 500 m crow-fly.
- Walking: **OSM pedestrian graph (used)**, from the Geofabrik extract
  `https://download.geofabrik.de/europe/france/pays-de-la-loire-latest.osm.pbf` (373 MB, daily;
  the `-latest` URL redirects to a dated file, `.md5` alongside; ODbL - attribution "© contributeurs
  OpenStreetMap"). Clipped to the Nantes Métropole boundary relation 420326
  (`ref:FR:SIREN=244400404`): 86,852 walkable ways -> 126,260 vertices / 171,899 edges, 1.1 MB packed.
  Checked against the Géoplateforme pedestrian router
  (`data.geopf.fr/navigation/itineraire?resource=bdtopo-osrm&profile=pedestrian`, keyless, BD TOPO
  data): median ratio 1.02 on 40 random pairs. `download.openstreetmap.fr` department extracts are
  not reachable from the dev sandbox.
- Real-time adjustment: SIRI StopMonitoring for boarding stops (trip ids join directly). GTFS-RT is
  too incomplete to drive routing.
- Ride geometry (what a ride is drawn along): **not the GTFS shapes** - all 314 `shapes.txt` shapes
  have exactly one point per stop (C2: 29 points, median 250-300 m apart), so a ride drawn from them
  cuts through buildings. **OSM (used)**, same Geofabrik extract: every stop-to-stop hop routed on a
  bus graph (roads a bus may use, one-way respected unless `oneway:bus|psv=no`) or a tram graph
  (`railway=tram`), guided by the **OSM route relations** (`type=route`, `network=Naolib` or `TAN`,
  `ref` = short name; 169 relations, PTv2): their ways cost 1×, others 1.6×. Extract of 2026-10-11:
  63 of 94 non-ferry lines have relations (missing: almost every 1xx line, 1B, 3B, CNO, TE3); 115 of
  179 relations chain end to end, the others have gaps the routing bridges. Result: 2,875 hops,
  2,853 routed, 10 straight (stop off the graph / no path / absurd detour), ferries straight;
  routed length median 1.07 × crow-fly, 82 % on the lines' own relations; ~32 k points, +104 KB in
  `network.bin`. Visual check on 10 random itineraries against the OSM basemap: street-accurate.

## 9. Verified 2026-10-12 (open data vs the official app)

- **Trip ids** in GTFS, SIRI and GTFS-RT are always `FR_NAOLIB:VehicleJourney:<n>-<serviceSuffix>`
  where `<serviceSuffix>` is the trip's `service_id` minus `FR_NAOLIB:Timetable:` (187,896/187,896
  trips). 60,421 distinct vehicle-journey numbers; the same number recurs across service chunks.
- **Directions:** GTFS `direction_id` = MaaS direction code on 158/159 line directions (the other
  differences are spelling: "St-Blaise" / "St Blaise", "ICO" / "Hôpital Laënnec"…; line 122 looks
  swapped). SIRI `DirectionName` `A` = `direction_id` 0, `R` = 1 on 791/791 visits whose trip the GTFS
  knows (5 unknown trips in that capture).
- **stop_times.txt is grouped by trip** (no trip id reappears after another started); 57 rows have
  `pickup_type=1`, none a non-zero `drop_off_type`; 4,425 rows carry a `stop_headsign`.
- **De-duplicated network:** 42,598 unique (pattern, times) trips, 316 patterns (route, direction,
  headsign, quays), 7,439 running-time profiles, 202 service-day bitsets; packed with shapes and the
  vj → trip join table: **≈300 KB**. Streaming conversion on a desktop JVM: **~7 s, peak < 128 MB**.
- **Crosswalk (seed geojson ↔ GTFS, for display positions):** StopPlace-centred matching (same loose
  name ≤ 600 m, else an official stop ≤ 150 m whose lines ⊇ the StopPlace's) matches 1,100/1,101 StopPlaces (median 18 m,
  p95 85 m). The "renamed" geojson stops are really **splits**: geojson "Chanzy" 59582 (C1;C6;NN) =
  GTFS "Chanzy" 1040 (C1, NN) + "Bretéché" 1078 (C6); "Route de la Chapelle" 60877 (59;C2;109) = GTFS
  1050 (C2) + "Picabia" 185 (59, 109); "Lieu Unique" 65357 = GTFS "Lieu Unique" 766 + "Duchesse Anne -
  Château" 30 (line 4). GTFS still calls Bouaye "Gare de Bouaye". 78 geojson stops have no StopPlace
  at all (discontinued/school lines: 26, 128, 135…). Geojson duplicates share an external_id (La
  Chapelle Aulnay 8196, Port Boyer 66823 appear twice).
- **SIRI vs official board on a weekday** (Monday 09:33, 28 stops, simultaneous): 513/562 board RT
  entries identical to the second, the rest present in SIRI but 30-120 s apart (mostly trams);
  a 5-stop live check later that morning: 150-152/166-167 within 30 s (90-92 %, vs 97.9 % on Sunday).
- **Local RAPTOR vs official planner** (2 × 40 random address trips, 06:00-22:00, next 3 days): the
  local planner found a journey every time the official one did. Median arrival Δ −2 / −1 min, 76/80
  within +5 min. The official planner walks streets at `walk_speed=4` (an 88 m crow-fly access took it
  3 min 48 s), keeps ≥ 1 min after alighting and before boarding, and proposes 1 h+ walk-only
  options; its remaining wins start/end with an 18-21 min walk to a farther stop.

## 9b. Weekday study of SIRI vs the official board (2026-10-12 14:40-15:55 and 16:54-18:09)

Every 35 s for 75 min (129 snapshots, zero 429s): one SIRI request for all quays of 27 busy stops +
the official board of each. Raw data not kept in the repo. The first run was early afternoon; the
evening peak was re-run with the same method (below).

- **Phantom buses.** 799 theoretical (non-RT) board slots ≤ 30 min ahead; 467 followed past their time:
  **46 never became tracked** (≈10 %), 421 did. Never-tracked by line: tram 1 19/164, tram 2 11/168,
  tram 3 7/86, C8 5/8, 95 3/3, busway 4 0/33.
  | Rule | phantoms tagged | real buses wrongly tagged | phantoms missed |
  |---|---|---|---|
  | **current: line has RT, ≤ 8 min or sandwiched** | **7** | **0** | 39 |
  | absent from SIRI at first sight, line has RT, ≤ 8 min | 1 | 0 | 45 |
  | same, ≤ 15 min | 4 | 7 | 42 |
  | same, ≤ 20 min | 5 | 28 | 41 |
  | same, ≤ 30 min | 42 | 418 | 4 |
  Absence from SIRI is no signal: almost every theoretical slot is absent at first sight, phantom or
  not. The current rule is the only one with no false alarm; it catches 15 % of phantoms.
- **Real-time flag vs the board's `is_rt`** (121,926 entries ≤ 60 min, 61,898 RT):
  "listed by SIRI ∧ ≤ 50 min" (current) **93.1 %** agreement, 96.8 % of RT caught; ≤ 45 min 92.6 %,
  ≤ 55 min 92.7 %, ≤ 40 min 91.0 %; per-line horizons (learned on the first half, tested on the
  second) 91.1 % vs 91.5 % for 50 min on the same subset. Learned horizons: busway 4 22 min, trams
  24-34, Chronobus 38-55, most buses 50-60.
- **Evening peak re-run (16:54-18:09, same stops and method; 126 usable snapshots, one 429 at the end).**
  1,201 theoretical slots ≤ 30 min ahead, 783 followed: **63 never tracked** (≈8 %). By line: tram 1
  11/186, tram 2 17/172, tram 3 14/95, C3 7/39, 95 6/6, C8 3/33, busway 4 2/65.
  | Rule | phantoms tagged | real buses wrongly tagged | phantoms missed |
  |---|---|---|---|
  | **current: line has RT, ≤ 8 min or sandwiched** | **16** | **2** | 47 |
  | absent from SIRI at first sight, line has RT, ≤ 8 min | 8 | 0 | 55 |
  | same, ≤ 15 min | 12 | 22 | 51 |
  | same, ≤ 20 min | 20 | 53 | 43 |
  | same, ≤ 30 min | 57 | 716 | 6 |
  The 2 false alarms are **one C8 run** (`49909225`) sandwiched 20-24 min ahead at Gare Sud and Hôtel
  Dieu: it showed up in SIRI 35 s and 4.5 min later, so the tag lasted one or a few refreshes, never
  near departure time. The current rule catches 25 % of phantoms (afternoon 15 %).
  Real-time flag (127,500 entries, 68,047 RT): ≤ 50 min **93.9 %** (96.9 % of RT caught), ≤ 45 min
  93.3 %, ≤ 55 min 94.1 %; per-line horizons 94.2 % vs 93.8 % for 50 min on the same half - +0.4 pt,
  where the afternoon gave −0.4 pt: noise. Peak horizons are shorter for the frequent lines (busway 4
  14 min, trams 22-36, Chronobus 26-37) and unchanged for most buses (50-60).
- **Conclusion:** both rules kept as they are (the afternoon result, confirmed by the peak re-run).

## 10. Open questions

- (Answered 2026-10-12, §9b: absence from SIRI alone is **not** a phantom signal; the current rule is
  precise but catches few phantoms.)
- Does the Okina rate limit key on the client IP for all clients, or are some networks pooled? Only
  one IP was tested.
- (Answered 2026-10-12, §9: `direction_id` = MaaS code on 158/159 directions; `A`/`R` = 0/1.)
- MaaS disruptions response shape (never captured).
