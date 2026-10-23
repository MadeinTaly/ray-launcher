# RAY WebSocket protocol

RAY adds a WebSocket endpoint alongside the HTTP API. The HTTP API and its authentication remain unchanged for scripts, Home Assistant and older clients.

## Connect and authenticate

The TV pairing URL is `http://TV_IP:8766/ray#k=PAIRING_KEY`. The fragment is read by the page and is not sent in the HTTP request. The socket endpoint is `ws://TV_IP:8766/ws` (or `wss` if hosted behind TLS).

After the HTTP WebSocket upgrade, the client must send a masked text frame containing this JSON within **5 seconds**:

```json
{"type":"auth","key":"PAIRING_KEY","client":{"name":"Pixel","version":1}}
```

Success replies with `{"type":"auth.ok","protocol":1}`, then a full `state.snapshot`. A bad key replies with `auth.error` and closes the connection. Authentication uses the RAY pairing key; mDNS never advertises it.

## Messages

Client commands use JSON text messages. `type` names the command, `id` is an optional request identifier, and `payload` carries command fields. For compatibility, command fields at the top level and `command` as an alias for `type` are accepted.

```json
{"type":"filter.set","id":"b7","payload":{"bright":45}}
```

Server snapshots use a sequence and timestamp:

```json
{"type":"state.snapshot","seq":12,"ts":1760000000000,"payload":{"device":"…","apps":[],"filter":{},"audio":{},"theme":{"color":"#24dcff","style":"glass","skin":"liquid","skins":[{"id":"liquid","name":{"it":"Liquida","en":"Liquid"},"accent":"#24dcff","hasRemote":true,"user":false}]}}}
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
| `theme.set` | At least one of `{"color":"#RRGGBB","style":"glass\|metal\|glow\|plain\|flood\|horror","skin":"<id from theme.skins>"}` | Saves the selected launcher color, style and skin (frame artwork plus layout; `metal` ignores color and style). `plain` uses a static background. Invalid fields reject the whole command. |
| `scene.apply` | `{"id":"cinema\|night\|reset"}` | Applies the built-in filter presets. |

The accepted legacy short aliases include `up`, `down`, `left`, `right`, `ok`, `back`, `home`, `apps`, `launch`, `touch`, `motion` and `recenter`. A top-level `command` field is also accepted. App launch, filter and audio commands use their dedicated handlers. Pointer display over other apps uses Android overlay permission or an already-enabled RAY accessibility service and does not depend on root. Injected global key events and clicks require the reported `globalInput` capability; ADB root alone does not grant this app privilege.

## VPN and Watch Next

Snapshots include `vpn: {known, active, apps: [{pkg, name}], preferred}`, `weatherConsent` and `theme: {color, style, skin, remoteSkin, daynight (auto|day|night), isDay}`. VPN status is based on whether RAY's active network has the Android `TRANSPORT_VPN` transport. This does not describe routing for every installed app. `apps` lists installed apps that declare an Android `VpnService`. `vpn.open {pkg}` opens one of them on the TV (and remembers it as `preferred`); an empty `pkg` opens the system VPN settings. Users connect inside the VPN app. `theme.set` also accepts `{daynight: auto|day|night}`; `weather.consent {accepted}` switches the weather on or off (nothing is sent over the network before it is accepted).

The TV launcher may show eligible Android TV Watch Next records after the user grants the runtime `READ_TV_LISTINGS` permission. It queries the platform `watch_next_program` content URI, keeps entries with positive engagement, sorts by last engagement and removes duplicate items. Cover loading is bounded and performed off the UI thread. This is platform data; the protocol does not query provider history directly.

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
