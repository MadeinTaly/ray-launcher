# RAY skin format v1

A skin is a **folder**. RAY reads skins from two places:

- bundled skins: `app/assets/skins/<id>/`
- user skins: `/sdcard/Android/data/dev.raylauncher/files/skins/<id>/` (that is `getExternalFilesDir("skins")`)

```
<id>/
  skin.json      required
  frame.webp     frame artwork (or frame.png); null in skin.json = frameless skin
  remote.webp    optional: phone remote background
  preview.webp   optional: preview
  frame-day.webp, frame-night.webp, remote-day.webp, remote-night.webp   optional: day/night variant artwork
  icons/<name>.png   optional: search, sources, audio, settings, pair, apps, play, night
```

The folder name must equal `id` (`[a-z0-9_-]`, max 32 characters). A user skin whose `id` matches an existing skin is skipped.
Other files in the folder (or in the zip) are ignored.

## skin.json

Real example (`lanterna`):

```json
{
  "format": 1,
  "id": "lanterna",
  "name": {"it": "Lanterna magica", "en": "Magic lantern"},
  "author": "RAY",
  "accent": "#ffb347",
  "frame": "frame.webp",
  "animated": false,
  "wallpaper": "user",
  "slots": {
    "hero": [0.259, 0.133, 0.481, 0.424],
    "logo": [0.066, 0.115, 0.092, 0.06],
    "clock": [0.06, 0.19, 0.104, 0.11],
    "connect": [0.817, 0.14, 0.122, 0.211],
    "resume": [0.285, 0.47, 0.1, 0.06],
    "discover": [0.395, 0.47, 0.09, 0.06],
    "nav": [],
    "rowTitles": [],
    "rows": [
      [[0.062, 0.61, 0.198, 0.151], [0.289, 0.61, 0.199, 0.151], [0.511, 0.611, 0.2, 0.151], [0.74, 0.611, 0.2, 0.15]],
      [[0.066, 0.806, 0.108, 0.1], [0.1816, 0.806, 0.108, 0.1], [0.2972, 0.806, 0.108, 0.1], [0.4128, 0.806, 0.108, 0.1], [0.5284, 0.806, 0.108, 0.1]]
    ],
    "actions": [
      {"id": "search", "rect": [0.678, 0.832, 0.083, 0.07]},
      {"id": "sources", "rect": [0.768, 0.832, 0.083, 0.07]},
      {"id": "settings", "rect": [0.858, 0.832, 0.083, 0.07]}
    ]
  }
}
```

| Field | Type | Notes |
|---|---|---|
| `format` | `1` | required |
| `id` | string | required, equal to the folder name |
| `name` | `{it, en}` | required, at least one language (max 48 characters) |
| `author` | string | free text |
| `weatherColor` | `"#rrggbb"` | optional: text colour inside the `weather` window (for light backgrounds) |
| `accent` | `"#rrggbb"` | required. Text/focus colour when `animated` is false; page colour on the phone |
| `frame` | `"frame.webp"`, `"frame.png"` or `null` | `null` = frameless skin, drawn by code with a fixed layout (`slots` are not needed; see `essenziale`) |
| `animated` | bool | `true`: the frame is tinted by the user's theme colour; `false`: drawn as is |
| `wallpaper` | `"user"`, `"none"` or a file in the folder | `user`: background chosen by the user from the phone (`filesDir/wallpaper.jpg`, `POST /api/wallpaper`), otherwise RAY's landscape (framed skins) or the `wallpaperDefault` gradient (frameless) |
| `style` | object | frameless skins: `cardRadius` (dp), `text`, `muted`, `dim`, `focusScale`, `ringDp`, `ring`, `shadow`, `panelFill`, `panelStrong`, `ok`, `btnBg/btnFg/btn2Bg/btn2Fg` (`#rrggbb` or `#aarrggbb`); see essenziale/skin.json |
| `wallpaperDefault`, `wallpaperFx` | object | frameless: gradient/glows/mountains used without a user wallpaper; blur below `blurBelowY`, scrim `scrimH`/`scrimV` |
| `slots` | object | see below |

## Day and night (`variants`)

Every skin can have two looks, day and night. In `skin.json`:

```json
"variants": {
  "day":   {"frame": "frame-day.webp", "remote": "remote-day.webp"},
  "night": {}
}
```

- Each variant is the base `skin.json` with the variant's fields on top: `frame`, `remote`, `icons`, `accent`, `weatherColor`, `slots` are replaced; `style` is merged key by key; `wallpaperDefault`/`wallpaperFx` also apply. Anything missing stays as in the base.
- A missing (or `{}`) variant uses the base artwork in that mode. If a variant frame has the same alpha channel as the base (identical holes) it is enough to give `frame` and `remote`: the `slots` are the base ones.
- Allowed files (also in zips): `frame-day|night.webp|png`, `remote-day|night.webp`, `icons-day|night/`. A variant with missing files is dropped, not the whole skin.
- A skin without `variants` looks the same by day and by night.
- `essenziale` (frameless): base = night, `day` = the light look. A saved `ray_skin` that no longer exists falls back to `essenziale`.

The `ray_daynight` preference decides which look is active: `auto` (default), `day`, `night`.
In `auto`, sunrise and sunset from Open-Meteo (`daily`, cached by RayWeather) are used only if weather is enabled (consent given) and a city is set; otherwise 07:00-19:30. The launcher re-evaluates every minute and rebuilds the home only when the look changes.
Set it from the skin's mode button (cycles auto, day, night), from the TV settings or from the phone (`theme.set {daynight}`). The state exposes `theme.daynight` and `theme.isDay`; the phone background follows the active variant (`/skins/<id>/remote.webp?m=day|night`).

### slots

All rectangles are `[x, y, w, h]` as **fractions** of a 16:9 screen (0..1, origin top left). Out-of-range values are clipped.
At 1280x720: `x = pixel_x / 1280`, `y = pixel_y / 720`, `w = width / 1280`, `h = height / 720`.

| Slot | Req. | Hosts |
|---|---|---|
| `hero` | no | large window: highlighted content (Watch Next, app, greeting) |
| `heroInset` | no | `[dx, dy]` fraction of the hero's width/height to keep free for text (round or organic holes), max 0.4 |
| `logo` | no | RAY wordmark |
| `clock` | no | clock |
| `weather` | no | weather window (e.g. a moon): large temperature, glyph + condition, city. Without a city it asks to set one. Frameless skins show the weather under the date |
| `device`, `mode`, `pair` | no | device name, day/night button, pair button (only for skins that draw them as their own buttons) |
| `connect` | yes | "connect your phone" QR panel (visible only while no phone is connected) |
| `resume`, `discover` | yes | "Resume" and "Discover" buttons on the hero |
| `nav` | no | `[{id, rect}]` categories, id: `home`, `video`, `games`, `music`, `apps`. Empty = no categories |
| `rowTitles` | no | `[rect]` title of each row |
| `rows` | yes | `[[rect]]` rows of cards. Row 0 = Watch Next, the others = apps. At least one row with one card |
| `actions` | no | `[{id, rect, pad}]` buttons, id: `search`, `apps`, `sources`, `audio`, `pair`, `settings`. `pad` = space on the left (fraction of the screen) for a drawing in the frame |

Unknown ids in `nav` and `actions` are ignored, like unknown fields. If a required field is missing or a rectangle is not 4 numbers, the skin is **discarded** with a log line (`adb logcat -s RaySkin`), never a crash.

## Hole rules

**Every hole must host a real function**: hero, Watch Next card, app, clock, QR, button. No decorative holes: users would see them empty.
Cards are painted under the frame (1.2x the hole, feathered edges): the hole must be 100% transparent, the surrounding frame covers the edges.
Small round holes show only the button icon (from `icons/`); the name goes to accessibility.

## Making the frame

- PNG with **transparency (alpha)**, 16:9, **1280x720**. No text baked into the image (RAY draws the labels, localised).
- Holes: alpha = 0. Everything else: opaque or semi-transparent as you like.
- Save as `frame.webp` (quality ~88) or `frame.png`. Max 8 MB per file.
- Icons: square PNGs with alpha, in a style consistent with the frame, named `search sources audio settings pair apps play night`.
- Phone background: `remote.webp`, portrait or square, dark (it is dimmed behind the controls).

## Measuring the holes

```
python tools/skin_measure.py frame.webp
```

Flood fill over the transparent regions that do not touch the border (needs `pip install pillow numpy`). It prints one `[x, y, w, h]` rectangle per hole, top to bottom and left to right: copy the values into the slots. `--min-area N` (default 1500 px) discards small holes.

## Installing

1. **adb**: `adb push my-skin /sdcard/Android/data/dev.raylauncher/files/skins/my-skin` (the folder containing `skin.json`). The skin shows up in the TV's Settings > Skin menu the next time it is opened or reloaded.
2. **From the phone**: `python tools/skin_pack.py my-skin` creates `my-skin.zip` (only the allowed files, `skin.json` is checked). Then in the web remote: Settings > Install skin (.zip). The file goes to `POST /api/skin` (same pairing key as the other APIs, max 20 MB). The zip may have the files at its root or inside a single folder; an `id` already installed by the user is replaced, one that matches a bundled skin is rejected.

A skin is chosen from the TV menu (Settings > Skin) or from the phone (Settings > Theme). A saved skin that no longer exists falls back to `essenziale`.
