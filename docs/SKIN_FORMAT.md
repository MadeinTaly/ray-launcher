# RAY skin format v1

A skin is a **folder**: `skin.json` plus optional wallpaper images and icons. The home is drawn by code
(`RayStockHero` and the home row), so a skin only sets colours and which of the three looks it uses.

- bundled skins: `app/assets/skins/<id>/`: `cinema` (shown as Horizon), `rivista` (Magazine), `lacca` (Lacquer)
- user skins: `getExternalFilesDir("skins")/<id>/` (`/sdcard/Android/data/dev.raylauncher/files/skins/<id>/`), or a zip uploaded from the phone (`tools/skin_pack.py <folder>` makes it)

```
<id>/
  skin.json                 required
  wallpaper(-day|-night).webp   optional: picture used by wallpaperDefault.image
  remote(-day|-night).webp  optional: phone remote background
  preview.webp              optional
  icons(-day|-night)/<name>.png   optional: search, pair, sources, settings
```

The folder name must equal `id` (`[a-z0-9_-]`, max 32). A user skin with the id of an existing skin is skipped. Other files are ignored.

## skin.json

Example: [`app/assets/skins/rivista/skin.json`](../app/assets/skins/rivista/skin.json).

| Field | Notes |
|---|---|
| `format` | `1`, required |
| `id` | required, equal to the folder name |
| `name` | `{it, en, de, fr, es}`, at least `it` or `en` (max 48 characters); the system language is used when present |
| `author` | free text |
| `accent` | `#rrggbb`, required; also the phone page colour |
| `style.layout` | `cinema` (Horizon), `rivista` or `lacca` (default `cinema`): the composition of the home. Only `cinema` has the animated background, and only on capable devices |
| `style.surface`, `surface2`, `moon` | colours of the rivista page / lacca gradient and the empty moon (`#rrggbb` or `#aarrggbb`) |
| `style` (other) | `cardRadius` (dp), `text`, `muted`, `dim`, `focusScale`, `ringDp`, `ring`, `shadow`, `panelFill`, `panelStrong`, `ok`, `btnFg` |
| `wallpaperDefault` | what is behind the home without a user wallpaper: `gradient`, `stops`, `angle`, `glows`, or `image` (a file of the folder) |
| `wallpaperFx` | blur below `blurBelowY`, scrim `scrimH` / `scrimV` (see the `cinema` skin) |
| `remote`, `icons` | file / folder names of the remote background and icons (day/night forms allowed) |

The wallpaper chosen from the phone always replaces `wallpaperDefault`.

## Day and night (`variants`)

```json
"variants": { "night": {}, "day": { "accent": "#C62F18", "style": { "surface": "#F3EEE4", "text": "#161412" } } }
```

Each variant is the base `skin.json` with the variant's fields on top: `style` is merged key by key, everything else
is replaced. A missing or `{}` variant is the base. A broken variant is dropped, not the whole skin.
The `ray_daynight` preference (`auto`, `day`, `night`) picks the look; `auto` follows sunrise and sunset when weather is on and a city is set, otherwise 07:00-19:30.
A saved skin id that no longer exists falls back to `cinema`.
