# Cluster protocol notes

How the Hero Xpulse 210 cluster takes navigation data over Bluetooth. This was worked out so that
another app can talk to the cluster (interoperability) and has been checked on a real bike. It describes
behaviour only; it contains no code from the official app.

## Link

BLE GATT. The cluster is a Telit-style serial module ("TIO") with credit-based flow control. Two
service layouts are used:

| Role                                   | Cluster service `E837D9A2-9C49-4493-9547-8E6918A59CA8` | Plain Telit service `0000FEFB-0000-1000-8000-00805F9B34FB` |
|----------------------------------------|---------------------------------------------------------|-------------------------------------------------------------|
| Phone writes data here                 | `64AECB40-849A-44F1-934F-ADDC4B316423`                  | `00000001-0000-1000-8000-008025000000`                      |
| Phone writes credits here (uint8)      | `F535DD6E-7975-4ABD-9719-491E38A81179`                  | `00000003-0000-1000-8000-008025000000`                      |
| Cluster -> phone data (notify)         | `B792A4BB-DB87-436A-9066-DB63C5FB3F00`                  | `00000002-0000-1000-8000-008025000000`                      |
| Cluster -> phone credits (indicate)    | `36E8614B-2DED-45C1-9AD3-C9F59A01F21E`                  | `00000004-0000-1000-8000-008025000000`                      |

An Xpulse 210 cluster was seen using the first layout.

### Handshake

1. `connectGatt` (LE transport), wait about 1 s, `discoverServices`
2. `requestMtu(65)`
3. Enable **indications** on the cluster-credits characteristic
4. Enable **notifications** on the cluster-data characteristic
5. Write **10** (uint8, with response) to the credits characteristic
6. Start writing navigation packets to the data characteristic

The cluster must be bonded (paired). The cluster sends a 6-byte heartbeat (`0A 41 00 32 65 0A`) about every
0.6 s; giving one credit back for each keeps the link healthy.

## Navigation packet

Written to the data characteristic as `"\n" + body + "\n"`, windows-1252, and **re-sent every 800 ms**.

`body` is plain concatenation with no separators:

| # | Field            | Width | Notes |
|---|------------------|-------|-------|
| 1 | id               | 1     | always `1` |
| 2 | turn letter      | 1     | icon selector (table below) |
| 3 | distance         | 5     | text such as `500 m` / `1.2 km` with the unit letters removed, so a trailing space remains, left-padded with zeros: `0500 `, `01.2 `, `0012 `. Steps: up to 100 m by 5, up to 300 m by 10, below 975 m by 50; km from 975 m, one decimal up to 9.5 km, none above |
| 4 | unit             | 1     | `1` = metres, `k` = kilometres |
| 5 | ETA hour         | 2     | 12-hour clock, `01`-`12`; `00` when blank |
| 6 | AM/PM            | 1     | `A` / `P` (lowercase in the extended packet) |
| 7 | ETA minute       | 2     | `00`-`59` |
| 8 | `M`              | 1     | literally `M` (lowercase `m` in the extended packet) |
| 9 | roundabout exit  | 2     | `00` = none, `01`-`04` |
| 10| GPS-lost flag    | 1     | `0` / `1` |
|   | *basic packet ends here (17 characters)* | | |
| 11| remaining dist.  | 5     | metres up to 999, then km (`009.4`, `00012`) |
| 12| remaining unit   | 1     | `1` / `k` |
| 13| remaining time   | 4     | placeholder `~~~~` |
| 14| next turn letter | 1     | placeholder `~` |
| 15| 2nd roundabout   | 2     | constant `04` |
| 16| road name        | 18    | padded with `~`; **must not contain `/`** (the cluster then falls back to "start navigation from phone"). The cluster does not display it |

The Xpulse 210 cluster accepted the extended packet. A packet with letter `G` and distance `00000` shows
the straight arrow; `H` with `00000` shows the finish flag.

## Turn letters (seen on an Xpulse 210)

| Letter | Shows | Letter | Shows |
|---|---|---|---|
| `G` | straight (also "cancelled") | `K` | exit left |
| `I` | left | `L` | exit right |
| `J` | right | `Q` | left, short Y |
| `C` | slight left | `R` | right, short Y |
| `D` | slight right | `Z` | keep left |
| `E` | sharp left | `X` | keep right |
| `F` | sharp right | `U` | roundabout left |
| `O` | U-turn left | `N` | roundabout right |
| `P` | U-turn right | `V` | roundabout |
| `H` | finish flag | `Y` | ferry |
| `T` | car | `B` | rerouting |

Lowercase letters (turn closer than 200 m) may exist; the app sends uppercase because lowercase gave
inconsistent results. The roundabout exit number (1-4) did not change the drawing on the bike tested.

## Design notes

The app uses a single GATT operation queue with a timeout on every step, back-off between retries, a
forced LE transport, autoConnect plus a filtered scan to find the bike after a drop, and a foreground
service so Android keeps it alive.
