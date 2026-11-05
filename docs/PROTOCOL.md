# RAY WebSocket protocol

RAY adds a WebSocket endpoint alongside the HTTP API. The HTTP API and its authentication remain unchanged for scripts, Home Assistant and older clients.

## Connect and authenticate

The TV pairing URL is `http://TV_IP:8766/ray#k=PAIRING_KEY`. The fragment is read by the page and is not sent in the HTTP request. The socket endpoint is `ws://TV_IP:8766/ws` (or `wss` if hosted behind TLS).

After the HTTP WebSocket upgrade, the client must send a masked text frame containing this JSON within **5 seconds**:

```json
{"type":"auth","key":"PAIRING_KEY","client":{"name":"Pixel","version":1}}
```

Success replies with `{"type":"auth.ok","protocol":1}`, then a full `state.snapshot`. A bad key replies with `auth.error` and closes the connection. Authentication uses the RAY pairing key; mDNS never advertises it.

## Pairing with a 4-digit code

Alternative to the QR link, no key needed for these two calls (HTTP, same origin rules as the rest: no CORS headers, so only native clients and `curl` can use them).

- `POST /api/pair/start` makes the TV show a 4-digit code (Osd overlay, or the home screen's popup) and answers `{"ok":true,"shown":true}`. `shown:false`: the TV could not draw it (no overlay permission). The code is never in the answer. `429 {"error":"wait"}` if called less than 3 s after the previous start. A start while a code is open keeps the same code and restarts its 120 s.
- `POST /api/pair/finish {"pin":"1234"}` answers `{"k":"<pairing key>"}` on a match and hides the code. Otherwise `403 {"error":"pin"|"closed"|"locked"}`: wrong code; none open or expired; 5 wrong codes from this address.

The code comes from `SecureRandom`, is valid 120 s, is single use and is compared in constant time. 20 wrong codes from all addresses together close it and delay the next start by 30 s, so guesses spread over many addresses stay bounded. The code is only in memory. The phone app does these calls itself (`POST /api/devices` ops `pairstart` and `pair` on its loopback server) and saves the TV with its mDNS `id` (`did`) so it can follow the TV to a new address.

## Messages

Client commands use JSON text messages. `type` names the command, `id` is an optional request identifier, and `payload` carries command fields. For compatibility, command fields at the top level and `command` as an alias for `type` are accepted.

```json
{"type":"filter.set","id":"b7","payload":{"bright":45}}
```

Server snapshots use a sequence and timestamp:

```json
{"type":"state.snapshot","seq":12,"ts":1760000000000,"payload":{"device":"…","apps":[],"filter":{},"audio":{},"theme":{"skin":"cinema","skins":[{"id":"cinema","name":{"it":"Orizzonte","en":"Horizon"},"accent":"#FF9650","hasRemote":false,"user":false}]}}}
```

Non-pointer commands receive an acknowledgement and then a fresh snapshot:

```json
{"type":"ack","id":"b7","ok":true,"error":""}
```

`remote.pointer` and `remote.motion` do not receive per-frame acknowledgements. WebSocket ping frames receive pong frames.

## Supported commands

| Command | Payload | Notes |
|---|---|---|
| `launcher.home` | none | Opens RAY home. |
| `app.launch` | `{"package":"…"}` | Launches an installed app. |
| `remote.key` | `{"key":"UP\|DOWN\|LEFT\|RIGHT\|OK\|BACK\|HOME\|MENU\|MEDIA_PLAY_PAUSE\|MEDIA_REWIND\|MEDIA_FAST_FORWARD"}` | D-pad/OK control RAY while its home is active. `MENU` opens settings. Media keys use global key injection and require the reported `globalInput` capability. |
| `remote.pointer` | `{"x":0..1,"y":0..1}` or `{"visible":false}` | Coordinates are normalized to the display and focus the nearest visible focusable view, including buttons. Explicit pointer updates force it visible; `visible:false` hides it. Over other apps, display requires Android overlay permission or an already-enabled RAY accessibility service. No per-frame ACK. |
| `remote.motion` | `{"x":0..1,"y":0..1}` | Coordinates are normalized to the display and move the same pointer. It hides after 2 seconds idle; unchanged or sub-2-pixel jitter does not reset that timer, while cumulative movement over 2 pixels wakes it. Over other apps, display requires Android overlay permission or an already-enabled RAY accessibility service. No per-frame ACK. |
| `filter.set` | subset of `{"on":bool,"red":0..100,"bright":5..100,"temp":1000..6500}` | Controls the screen filter. `brightness` is accepted as an alias of `bright`. |
| `audio.set` | `/api/audio` fields | Delegates to current device audio capabilities. |
| `theme.set` | At least one of `{"skin":"<id from theme.skins>","remoteSkin":"match\|<skin id>","daynight":"auto\|day\|night"}` | Saves the launcher theme (bundled ids `cinema` = Horizon, `rivista` = Magazine, `lacca` = Lacquer, plus user skins). `color` and `style` are legacy fields still accepted for old clients. Invalid fields reject the whole command. |
| `scene.apply` | `{"id":"cinema\|night\|reset"}` | Applies the built-in filter presets. |
| `media.control` | `{"action":"play\|pause\|toggle\|next\|previous\|stop\|seek\|seekTo","ms":n}` | Transport of the media session of the app on screen (needs notification access). `seek` is relative, `seekTo` absolute. |
| `media.search` | `{"query":"…"}` | Opens the search of the app on screen (apps with a searchable activity). |
| `media.open` | `{"id":"…"}` | Opens an item of `media.continue` or `media.suggestions` of the app on screen by its stable id. The TV looks the id up in its own lists and starts the intent it read from the TV provider (checked against the app package); an unknown id answers `ok:false`. Kodi items go to Kodi through `Player.Open`. |
| `watch.open` | `{"index":n}` | Opens entry `n` of the Watch Next list behind `home` (below). |
| `url.open` | `{"url":"https://…"}` | Opens a link on the TV (`ACTION_VIEW`, `BROWSABLE`, new task) in whatever app handles it. Only `http` and `https`, at most 2048 characters, a host is required; anything else answers `ok:false`. The Android app sends it when a link is shared to RAY from any app (share sheet, `ACTION_SEND` `text/plain`: the first `http(s)` link of the text goes to the TV in use; with no TV saved the phone shows a toast and sends nothing). |

The accepted legacy short aliases include `up`, `down`, `left`, `right`, `ok`, `back`, `home`, `apps`, `launch`, `touch`, `motion` and `recenter`. A top-level `command` field is also accepted. App launch, filter and audio commands use their dedicated handlers. Pointer display over other apps uses Android overlay permission or an already-enabled RAY accessibility service and does not depend on root. Injected global key events and clicks require the reported `globalInput` capability; ADB root alone does not grant this app privilege.

## VPN and Watch Next

Snapshots include `vpn: {known, active, apps: [{pkg, name}], preferred}`, `weatherConsent` and `theme: {skin, remoteSkin, daynight (auto|day|night), isDay}`. VPN status is based on whether RAY's active network has the Android `TRANSPORT_VPN` transport. This does not describe routing for every installed app. `apps` lists installed apps that declare an Android `VpnService`. `vpn.open {pkg}` opens one of them on the TV (and remembers it as `preferred`); an empty `pkg` opens the system VPN settings. Users connect inside the VPN app. `theme.set` also accepts `{remoteSkin: match|<skin id>, daynight: auto|day|night}`; `weather.consent {accepted}` switches the weather on or off (nothing is sent over the network before it is accepted).

The TV launcher may show eligible Android TV Watch Next records after the user grants the runtime `READ_TV_LISTINGS` permission. It queries the platform `watch_next_program` content URI, keeps entries with positive engagement, sorts by last engagement and removes duplicate items. Cover loading is bounded and performed off the UI thread. This is platform data; the protocol does not query provider history directly.

## Phone "On TV" data

Snapshots also carry `media` (`access`, `package`, `app`, `session`, `title`, `subtitle`, `art`, `duration`, `position`, `playing`, `canSeek`, `canNext`, `canPrevious`, `searchable`, and for the app on screen two lists, `continue` (its Watch Next entries) and `suggestions` (its preview programs), each item `{id, title, sub, art, progress (0..1 or -1), minutes (or -1)}`; an app that publishes nothing gives empty lists. Kodi does not use the provider: while Kodi (or a fork) is in front the lists come from its JSON-RPC on `127.0.0.1:8080`, silently empty when its web control is off or has a password), `foregroundPackage`, `version` and `home`: the TV home row in the TV's order (pinned, then last use, Watch Next mixed in), each item `{k:"app", package, name}` or `{k:"watch", index, title, package, name, progress, minutes}`. Images are plain `GET`s with the pairing key as `k=` (an `<img>` cannot send `X-Key`): `/media-art`, `/watch-art?i=<index>` and `/item-art?id=<item id>` (JPEG) and `/app-icon?p=<package>` (PNG). `GET /fonts/ChakraPetch-SemiBold.ttf` (no key) serves the browser remote's button font.

## Limits

- At most **4 WebSocket companions** and **16 total accepted HTTP/WebSocket connections**.
- Authentication deadline: **5 seconds** after upgrade.
- At most **60 client text commands per second** per connection.
- Incoming frames must be masked, final, and unfragmented; only text, close, ping and pong opcodes are accepted. Text frame payload limit is **32,768 bytes**.
- Oversized, unmasked, fragmented or unsupported frames are closed with a protocol error. Invalid JSON gets a negative `ack` and the connection remains open.

## UDP pointer channel (native phone app)

UDP port 8767 on the TV carries only relative pointer motion (air mouse; layout v2, v1 absolute x/y with types 1-3 is removed). Datagram, 18 bytes, big endian: `type` (4 delta, 6 hello, 7 echo), `flags` (0), `seq` u32 (sender clock in ms), `dx` i16, `dy` i16, `mac` 8 bytes = first 8 bytes of HMAC-SHA256(pairing key, bytes 0..9). `dx`/`dy` are reference pixels: 1920 reference pixels are the screen width on both axes (square pixels), the TV scales them to its real resolution. The TV drops bad MACs and any `seq` not newer than the last accepted (it never waits for a missing one: a lost datagram is a lost micro-move), answers a hello with the same datagram as type 7, adds every delta to an accumulator and, once per vsync, moves the pointer by the accumulated sum with no smoothing, clamped to the screen without remembering the overshoot. The phone integrates the raw gyroscope (with a pointer-acceleration curve and a dead zone), keeps the sub-pixel fraction, and every 8 ms (125 Hz) sends the whole pixels it accumulated; it stays silent while the sum is zero except for a 250 ms heartbeat, and uses the WebSocket `remote.delta` `{dx, dy}` (at most every 20 ms) while no echo was heard in the last 1.5 s. `remote.delta` over WebSocket is summed on the TV, never latest-wins. Recentering is the absolute `remote.pointer` `{x: .5, y: .5}`. `remote.motion` / `remote.pointer` / `remote.delta` over WebSocket are exempt from the 60 commands/s limit (own cap 500/s); `remote.motion` and `remote.pointer` are absolute and coalesced latest-wins.

## HTTP compatibility

The HTTP routes are: `/api/info`, `/api/state`, `/api/set`, `/api/schedule`, `/api/days`, `/api/audio`. `/api/info` may include additive RAY capability data. HTTP continues to use `X-Key`; WebSocket authenticates with the same pairing key in its first JSON message.

## Following a TV to a new address

`POST /api/proof {"n":"<16-64 hex>"}`, no key, answers `{"proof":"<hex>"}`: HMAC-SHA256 under the pairing key of `n|<address the TV was reached at>`. The phone sends its key to an announced address only after checking this, so a device on the network cannot collect it by announcing a TV's id.
