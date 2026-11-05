# The line map

The Plan tab draws the whole Naolib network itself, from open data only: the GTFS patterns and their
street paths from OpenStreetMap (`RideShapes`). Lines that share a street are drawn side by side,
in lanes, like on the official map. This package builds that drawing; the app turns it into MapLibre
layers (`app/.../presentation/map/LineMapLayers.kt`).

This document explains **why** the code does what it does. Nearly every rule below was added against
a specific failure seen when comparing our drawing with the official map, and names the place that
showed it, so that whoever changes a rule can check that place again.

## Why we draw it ourselves

Until 2026-11 the app showed the official map's line drawing: pre-rendered raster tiles from
`plan-app.naolib.fr` (a copy of z6-z14 shipped in the APK, z15+ fetched), plus its vector tiles for
the line numbers, its font server, and its list of stop positions. That made the map depend on a
private server we don't control, kept 3.4 MB of non-free artwork in the APK, and froze the drawing at
whatever the tiles showed (they still drew lines 26 and 5 on their usual routes months into works
detours that the GTFS already had).

The official **vector** tiles can't replace the raster: shared corridors are byte-identical,
un-offset 2-vertex segments, so MapLibre's `line-offset` stacks the lines on top of each other. The
lane separation exists only in the raster artwork. Hence our own layout.

## The model: LOOM

After LOOM (Bast, Brosi & Storandt, "Efficient generation of geographically accurate transit maps",
2018/2019):

1. a **line graph**: corridors as segments between junctions, each carrying a set of lines;
2. a **lane order** per segment, minimising crossings at junctions;
3. **rendering**: each line offset in its lane, curves at junctions.

Where we depart from LOOM: our input is per-pattern street paths, not a shared graph, so step 1 is a
snapping process with many clean-up rules (below); stations get special treatment; and the layout is
computed per zoom because the merge distance is the width of the bundles *as drawn*.

## Pipeline

| Step | Code | Notes |
|---|---|---|
| Which hops are drawn | `LineMapBuilder.HopRules` | minor variants, detours, failed paths |
| Snapping | `LineMapBuilder.snap` / `insert` | trams first, then Busway/Chronobus, then buses |
| Stations | `LineMapBuilder.placeHubs` | one junction per station |
| Clean-up rounds | `LineMapBuilder.cleanUp` | until nothing changes (`MAX_ROUNDS` = 16 at most: a merge on the map's last stray pair can keep a round busy forever) |
| Segments, markers | `LineMapBuilder.finish` / `markers` | smoothing, simplification |
| Lane order | `LaneOrderer` | barycentre sweeps, then local search |
| Lanes for a draw zoom | `LaneGeometry` | offsets, junction curves, station dashes |
| Storage | `LineMapSet`, `LineMapFormat` (`VERSION` 1) | `assets/seed/line_map.bin`, rebuilt on the phone; a file of another version is rebuilt |

Building the four layouts takes about 11 s on a desktop JVM (2-3 s per zoom). `./gradlew
:transit:buildSeed` writes them into `assets/seed/line_map.bin`; after a GTFS refresh the app rebuilds
them on the phone (`LineMapRepository`) and keeps showing the previous ones meanwhile.

## The rules, and why

### Layouts

- **One layout per zoom, z12 to z15; z16+ reuse z15.** Corridors merge where their bundles would
  overlap as drawn, so one layout can't serve every zoom: z12's merge distance makes the centre one
  bundle; at z14 (about 60 m) the corner of Rue d'Orléans folds into Place du Cirque and C3/C8 seem
  to go round the square (z15 has its own layout for that reason). Above z15 streets are far enough
  apart that only widths change; a z16 layout tangled Pirmil (its quays became separate junctions).
- **The drawing changes at each integer zoom**, like the raster tiles did. In between, the app
  regenerates the lanes every half zoom and scales them in between (see "Drawing").

### Which hops are drawn (`HopRules`)

- **Minor variants (`MIN_HOP_SHARE` = 15 %)**: a hop carrying under 15 % of the trips of its line's
  busiest hop isn't drawn. Hides tram 2's runs to Espace Diderot (Fridays and weekends, 196 trips)
  and to Neustrie via Les Couëts (23 depot runs): real, but on neither map.
- **Detours (`DETOUR_SHARE` = 50 %)**: where two variants of a line split and rejoin, the way with
  under half the other's trips isn't drawn. TE1 via Tabarly (Rue de l'Île Macé, 27-28 trips) beside
  TE1 via Sarrail (Bd Général de Gaulle, 68-71): the official map has only 36 on Rue de l'Île Macé.
  Also 69 via Lycée Rieffel, 75 Gachet, 80 Haluchère. A branch that never rejoins stays.
- **Exception: a run that is the only line at one of its stops is drawn anyway.** Line 30's runs to
  Îles de Loire (27 trips vs 250) serve 9 stops nothing else does; without this, line 30 vanished
  from Vertou, Clos Toreau and Saint-Sébastien. Tram 2's depot runs serve no stop of their own and
  stay hidden. 88's branch via Gare de Bouaye would stay hidden too (98 serves its stops): it is drawn
  by hand (below).
- **Straight hops over 400 m (`MAX_STRAIGHT_HOP_M`) aren't drawn, boats excepted.** A hop whose OSM
  routing failed is a straight line; long, it cuts through blocks and reads as a street. Left today:
  1B Babinière-Batignolles, TE1 Marguyonnes-Île Macé, the C8 Marcel Paul loop. Others were fixed at
  the source (`RideShapeBuilder`: roads under `construction=minor`, relation members with role
  `route`, tram streets in the bus graph). Boats are fine straight on water, and follow OSM's
  `route=ferry` ways anyway.
- **Hidden lines (`LineStyle.Kind.HIDDEN`)**: school runs (< `MIN_TRIPS` = 20 trips in the feed: the 1xx lines,
  LCE, CNO) and the night lines NC/NN/NO/NS, as on the official map. NA (airport shuttle) is drawn.
  Their stops keep a marker in the app (at the GTFS position) so they can still be tapped.

### Snapping (`insert`)

- **Order: trams, then Busway/Chronobus, then the longest bus patterns.** The main lines lay down
  the corridors the others snap onto.
- **A sample joins the nearest node within the merge distance, same axis (within 30°).** The axis is
  a doubled-angle vector, so both directions of a street agree, and a crossing street doesn't
  capture the line. **Between a tram and another line, 15°** (`TRAM_MIN_COS`), here and in the
  overlap merges: a tram and a bus sharing a street run parallel, while at Babinière the
  périphérique bundle (1B, 75, 80), crossing tram 1 at a shallow angle under the bridge, shared a
  100 m segment with it and their lanes wove through each other. The change also removed about 40
  crossings at z15.
- **Merge distance**: (this bundle's width + the other's) / 2 + a gap, in dp at the layout zoom,
  between `MIN_MERGE_DP` (8: both directions of a street, double tram tracks) and `MAX_MERGE_DP` (60:
  beyond, two corridors are two streets). For the previous node or a node of the same line, only the
  floor: else nodes end up a merge radius apart and the line gets coarse.
- **The same lines merge farther (`SAME_LINES_MERGE_DP` = 16 dp)**: two corridors where one's lines
  are all in the other's (the two directions of a line, or two variants) merge up to 16 dp apart
  whatever their widths (about 210 m at z12, 50 m at z14): one line far out, two on a close-up,
  instead of two thin parallel strokes of the same colour.
- **Node ranks (tram 2 > Busway/Chronobus 1 > bus 0; station `HUB_RANK` 9)**: only samples of a node's own rank
  move it, and a merge keeps the higher rank's position. A bus snapping onto the tram corridor bends
  to the trams instead of dragging them: Aimé Delrue at z13 was a sharp corner in trams 2 and 3.
- **A node seen recently in the same run can't be rejoined** (except the previous one): a line
  looping past itself would short-circuit its loop.
- **Jumps follow the existing corridor** (`shortPath`): when a sample lands on a node that isn't a
  neighbour of the previous one, the line takes the graph's path between them rather than a chord.

### Stations (`placeHubs`)

- **A stop with ≥ 5 lines drawn (`HUB_MIN_LINES`) and quays ≥ 40 m from their centre
  (`HUB_MIN_SPREAD_M`) becomes one junction** covering its quays + 30 m (`HUB_MARGIN_M`, ≤ 120 m,
  `HUB_MAX_RADIUS_M`). That is what the official map draws at Pirmil, Commerce, Haluchère...: the
  lines meet there. Without it: a mesh of short segments round the quays and terminal loops. 26
  stations on the current network.
- **Position = plain mean of its best-ranked members** (its trams, else Busway/Chronobus, else its
  quays' centre), read before any merge moves a node. Pirmil sits on the tram line: at its quays'
  centre the trams bent towards the bus station. The mean of the merged nodes instead drifted with
  every merge.
- **Two stations never merge** (`union`). Neighbouring hubs chained into one star covering the whole
  centre at z12.
- **A node already in a station stays in it**: stations placed on their trams would otherwise chain.

### Clean-up rounds (`cleanUp`)

Each round rebuilds the `Topology` and runs, in order:

- **Short segments contract** to one node (shorter than half their bundle's width: the junction
  radius). One contraction per node per round, and bounded: a cluster may not get wider than half the
  maximum merge distance (`union(bounded)`). Unbounded, chains of small merges dragged nodes hundreds
  of metres across a hub.
- **Twins**: two segments between the same nodes merge onto the one with most lines. A bare chord
  beside a path is rerouted along the path when the path stays near it; else they are two streets
  (Rue de l'Île Macé and Bd Général de Gaulle).
- **Bubbles** (`mergeBubbles`): two ways of up to three segments between the same junctions, merged
  when their bundles would overlap. The bus bundle on the Pont de Pont-Rousseau beside the tram
  bridge, merged in some places and not others, made a chain of small loops with lanes swapping at
  each.
  - The merge distance is that of the lines along the **whole** way: Busway 4 joining for one
    segment made Rue Gambetta and its parallel one-way street, 100 m apart, one corridor (C1/11/12
    lost their loop by Rue Stanislas Baudry).
  - The two directions of the same lines round a block or a roundabout merge up to 50 m
    (`ROUNDABOUT_M`) whatever their widths: the knot at the Pavillon roundabout.
  - At a station the distance grows to its size (a way through the bus station and the trams passing
    by are one way on the official map), but only for ways within 2 × 120 m of it **that serve no
    other stop**: not Rue Gambetta leaving Foch, not Place du Cirque 200 m from St-Nicolas (folded
    in at z13, it pulled the tram corridor round). There, trams are never folded onto the buses' way
    (at Hôtel Dieu they would leave the Pirmil bridge for Clos Toreau); ranking the ways globally
    instead made tram 2 absorb buses.
- **Overlaps** (`mergeOverlaps`): corridors side by side closer than their bundles are wide (the
  trams laid first, the buses on the parallel carriageway just out of reach) merge, narrow onto wide,
  one node to one node, where at least two consecutive nodes run alongside (`MIN_OVERLAP_RUN`: one
  node near another corridor is a crossing or a touch, not a parallel corridor). **Sideways only** and **never into a station**: at z13 Place du Cirque was
  merged onto a tram node 120 m down Cours des 50 Otages, then onto St-Nicolas 250 m off, and the
  trams at Commerce tilted from about 30° to 45°.
- **Tiny loops are cut**: a closed segment shorter than π × its bundle width can't be drawn as a
  loop; its lines go straight through its junction. Haluchère - Batignolles: a 170 m leftover of the
  bus station loops carrying tram 1 and four buses was a knot. (Contracting by junction radius was
  tried first and did nothing there.)
- **Roundabouts become one junction** (`contractRoundabouts`): a cycle of 2 to 6 segments, every
  node within 70 m of its centre (`ROUNDABOUT_RADIUS_M`) and none closer than 40 % of the farthest.
  At Place Mellinet each direction took one half and lanes swapped and curled at every junction of
  the ring. The thresholds are Mellinet's, measured at 23 to 52 m. Every cycle through a node is
  checked (stopping at the first one, a triangle at a junction, missed Mellinet). Terminus loops
  round a block aren't round and are kept; Armoricaine stays split (one direction cuts the corner to
  its stop): accepted, buses do go round, not through.
  Two more shapes count as a roundabout: a line going once round one to turn back (a single closed
  segment), and a line whose two directions take opposite ways round it, one cutting across and the
  other going three quarters round (a chord and a loop whose junctions lie outside the ring: round if
  the loop's own points are, ends within twice the radius). That was the hook of 96 at the
  Géraudière roundabout (Les Roches), and 86's next to it.

### Segments

- **Light smoothing** (one pass over three points, ends fixed): the nodes are means already, more
  cut corners (a line's zigzag to a stop). Then Douglas-Peucker at a quarter dp.
- **Only junctions are stored as nodes**; the rest is each segment's polyline.

### Lane order (`LaneOrderer`)

- **Crossings as chords**: around a junction, each segment end's lanes are points on a circle; a line
  through the junction is a chord; two lines cross where their chords do. Two lines crossing between
  the same two segments weigh 2 (they braid, and that is always avoidable), any other crossing 1.
- **Barycentre sweeps, then local search** (every permutation up to 6 lines, single moves for wider
  bundles). Result on the current network: 254 crossings at z12, 274 at z13, 330 at z14, 341 at z15
  (z14 was 895 before the station, bubble and detour rules; z15 414 before the tram angle rule). `LineMapRegressionTest` keeps a budget
  about 5 % above these.

### Manual adjustment: 88 via Gare de Bouaye (`DRAWN_DETOURS`)

88's runs via Gare de Bouaye (37 trips of 113 one way, 39 of 95 the other) are on the official map,
while TE1 via Tabarly (27 of 71) and 69 via Lycée Rieffel (47 of 147) are not, and no trip share or
"own stop" rule tells them apart (98 serves Gare de Bouaye). So that detour is drawn by name.

### Manual adjustment: Pirmil (`TRAMS_WEST_HUBS`)

On every bundle round Pirmil the trams keep the west side and the orderer places the other lines
around them. Left to the crossing count, the trams took the middle of the bundle to Pont-Rousseau
and the lines from the east split round them (C4 over the river, curls at z13). Freezing the whole
order there was tried first: it froze the buses too and TE1/E8 crossed the whole bundle, so the pin
only moves the trams. Pirmil is the network's one exceptional hub (tram terminus, bus station and
bridge); Commerce is the only other candidate if it ever needs the same.

### Stop markers (`markers`)

- **On the drawn line, not at the quay**: with lanes side by side a dot at the quay sits beside the
  line.
- **A station sits on its junction**, as a **dash** across its widest bundle from z14
  (`LineMap.HUB_DASH_MIN_ZOOM`), as long as the bundle is wide, so it reads as one stop for all its
  lines. At z12-13 stations are a few dp apart and their dashes piled up across each other's bundles
  (St-Nicolas over the Bretagne crossing), so a station is a dot there.
- **A marker per direction** when a stop's two directions run on different streets more than 16 dp
  apart at the layout zoom (`SPLIT_STOP_DP`: about 100 m at z14, 400 m at z12) and the lines of
  neither side pass the other: Toutes Joies, where 26 and C8 stop 220 m apart. 5 stops at z12, 21 at
  z13, 53 at z14, 73 at z15. The official map has one marker for those. Staggered quays on one
  corridor and the two sides of a street keep one dot.
- **Staggered quays get the dot between them**: the marker is the node nearest the quays' mean among
  all nodes of the segments the quays are on. Mont Blanc's dot was at one end, 110 m from the other
  quay.
- **Positions are ours, not the official list's**: markers are on our lines, near the GTFS quays; a
  few dots end up 60-150 m along the line from the official position.

### Drawing (`LaneGeometry`, the app's `LineMapLayers`)

- **Offsets computed per draw zoom**, not with MapLibre's `line-offset`, which is constant per
  feature and can't join a lane to the next segment's lane at another offset.
- **Lanes stop short of junctions** (half the widest bundle there + 2 dp) and a cubic curve joins each
  line to its next lane, so lines swap sides smoothly (sampled at `CURVE_POINTS` = 10 points: smooth
  at every zoom the layouts are drawn at, and the curves stay a small share of the features). A line ending at its terminus runs to the node.
- **Offset polylines**: miter joins capped at 2×, and the small self-loops an offset makes inside a
  tight turn are cut.
- **Regenerated every half zoom, scaled in between**: the app recomputes the lanes when the zoom
  moves to another half step and, in between, scales widths with the map (exponential base 2), so
  the picture behaves like a raster tile zoomed a little. The layout itself changes at integer
  zooms.
- **Widths by kind and zoom** (`LineStyle`): tram > Busway/Chronobus > bus, matched to the official
  tiles; the gap between lanes grows with the zoom.
- **White casing** under every line (its width + the gap), drawn first for all lines.
- **Chronobus look**: a `#6B2359` edge (`CHRONOBUS_EDGE`, sampled from the official tiles, the same
  for every line), the line colour, and a white stripe down the middle, 12 % of the width each
  (`CHRONOBUS_EDGE_SHARE`, `CHRONOBUS_STRIPE_SHARE`, measured on the same tiles).
- **Drawn line by line within a rank**, edge, body and stripe of one line before the next, and ranks
  bus < Busway/Chronobus < tram: a line crossing over a Chronobus hides its stripe too (Bretagne,
  z16), and trams stay on top.
- **Boats dashed**.
- **Line numbers** along the lanes, one per lane piece at its middle, collisions thinned by MapLibre
  (`line-center` placement: `line` placement puts a label only where a feature is longer than the
  label spacing). Drawn in the line colour with a white halo. The font (Noto Sans, OFL) is bundled
  in the APK.

## Where the official map differs, and why (data, not bugs)

- **26 on southern Île de Nantes**: diverted since 31 August 2026 (works for the new lines, alert
  "Travaux d'aménagement des nouvelles lignes secteur Ile de Nantes"), via the line 5 stops Nizan,
  Vincent Gâche, Fonderies, Île de Nantes. The GTFS has the detour, we draw it; the official raster
  showed the usual route.
- **Busway 5**: diverted since 7 April 2026 towards Hangar à Bananes ("Travaux boulevard de
  l'Estuaire"); we draw both directions on Bd de la Prairie au Duc.
- **NA (airport shuttle)**: no stop on Île de Nantes, so its path is OSM's route relations (via Bd de
  la Prairie au Duc / Bd de l'Estuaire); the official raster had Bd Gisèle Halimi. Unsettled.
- **Lines absent from the GTFS** (La Montagne, Brindais) or re-routed since the official tiles
  (Tourmaline).
- **Split stops** (Toutes Joies...): the official map has one marker.

## Known weaknesses

- Input problems in the ride paths: 3B squiggle at Pont Général Audibert, C3 hairpin at Atlantis,
  line 66 without a path to Babinière.
- Terminus loops are not drawn (Sartre, Cartier, Château de Rezé...).
- A few lanes still swap where a line joins a bundle (tram 2 at 50 Otages, TE1 north of Pirmil);
  small wiggles where a line leaves a bundle for a few metres (stop bays, one-way loops).
- One loop tighter than its bundle survives at z12 (the tiny-loop cut runs on the round's topology
  and a later merge can recreate one).
- Lines follow the streets more closely than the official map, which is more schematic.
- A corridor can end up as two straight two-node segments side by side, with no interior nodes to
  merge (south of Commerce at z15: trams beside the Chronobus bundle).

## Checking a change

- `./gradlew :transit:test --tests '*LineMapRegressionTest*'`: crossing budgets, Pirmil, Mellinet,
  line 30, Mont Blanc, Toutes Joies, Place du Cirque, stations, Haluchère.
- Visual comparison with the official tiles (dev only, images not committed):

  ```bash
  LINEMAP_OUT=/abs/dir [LINEMAP_DEBUG=1] [LINEMAP_ONLY=08,11] ./gradlew :transit:test --tests '*LineMapComparisonTest*' --rerun
  LINEMAP_RANDOM=100 LINEMAP_OUT=/abs/dir ./gradlew :transit:test --tests '*LineMapComparisonTest*' --rerun
  ```

  The fixed views cover the centre, Commerce, Gare Nord, Hôtel Dieu, Pirmil, Haluchère, Île de
  Nantes, Rezé, Orvault, Bretagne and Toutes Joies; the random sweep reads the 100 views reviewed on
  2026-11-05 (`transit/src/test/resources/lines/random-views.tsv`). `debug/` shows segment
  centrelines, junctions and each segment's lines, and the raw OSM input.
