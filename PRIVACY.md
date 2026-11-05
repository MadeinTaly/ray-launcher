# RAY privacy policy

**In short:** RAY does not collect, store or send personal data to the developer or to any third party. It has no
accounts, no analytics, no ads and no tracking, and contains no third-party SDKs. Everything stays on your device and
on your local network. The only internet requests RAY can make are the optional weather lookup (only after your explicit
consent), the update check below and, only if you turn on Full access, poster downloads (see below).

The developer does not operate any server and receives no data from the app. Because no personal data reaches the
developer, there is no data controller relationship to manage for the app itself; the weather provider you choose
(below) acts as an independent controller for the request it receives.

## What RAY does on your network

- **Phone remote (TV):** a small web server on port 8766, protected by a random **pairing key** shown on the TV
  (QR code). Traffic is plain HTTP/WebSocket on the LAN, so use it only on a network you trust. *New pairing code* on
  the TV disconnects every paired phone.
- **Pairing by code:** the TV can show a one-time 4-digit code (valid 2 minutes, kept only in memory) that the phone
  trades for the pairing key; nothing is sent outside your network.
- **Phone app:** talks to the TVs you paired, and shows its own screen from `127.0.0.1` (the phone itself).
- **Local announcement (mDNS):** while the remote is on, the TV announces itself on the LAN (`_raylauncher._tcp`) with
  its device name, type and a random id. The pairing key is never announced.
- **Kodi (loopback only):** while Kodi or a fork is in front, RAY reads its continue watching and suggestion lists from
  Kodi's own JSON-RPC web server on `127.0.0.1:8080` on the same device. Nothing leaves the device; it stays empty if Kodi's web control is off.
- **Links shared to the TV:** a link you share to RAY on the phone is sent to the TV (`http`/`https` only), which hands it to the
  app that opens it (a browser, for example). RAY itself does not fetch it; the app that opens it does.
- RAY makes no connection to servers of the developer or of any analytics service.

## Optional weather (consent required, off by default)

Nothing is requested until you accept the consent dialog on the TV or on the phone **and** type a city.
If you accept:

- The **city name** you typed is sent over HTTPS to the geocoding service of **Open-Meteo** (servers in the EU), and
  the resulting **coordinates** to the weather provider you choose: **Open-Meteo** (default, no key) or
  **OpenWeatherMap** (with your own API key, which is sent to OpenWeatherMap with the request).
- Like any internet request, the provider technically sees your **IP address**. No other data is sent: no device
  identifier, no installed-app list, no account.
- Legal basis: your consent (GDPR art. 6(1)(a)). You can **withdraw it at any time** in the settings: RAY then stops
  all requests and erases the stored city, coordinates and cached weather. Your API key, if any, is stored only on the
  TV, is never sent back to the phone and is shown nowhere.
- Provider policies: [Open-Meteo](https://open-meteo.com/en/terms), [OpenWeatherMap](https://openweathermap.org/privacy-policy).

## Update check

At start (GitHub installs only) RAY asks `api.github.com` for the latest
release of this project (at most every 6 hours) and, if you accept, downloads the APK from `github.com`. GitHub sees
your IP address; RAY sends no other data. Switch it off in the TV Settings (*Check for updates at start*). The beta
channel (*Beta updates*) also takes pre-releases; going back to stable waits for a newer stable release because
Android cannot downgrade an app. Installs from F-Droid never run the check.

## Data that stays on the device

- **Settings, schedule, pairing key** and, on a phone, the list of paired TVs (name, address, key).
- **Installed apps (launcher):** names, icons and banners are read on the device to show the home screen and the
  catalog. They are sent nowhere except, on request, to a phone you paired on your LAN.
- **Watch Next (`READ_TV_LISTINGS`, optional):** if you allow it, RAY reads the "Watch Next" entries that other TV
  apps publish (title, cover, deep link) to show them on the home screen. Covers are read only from local
  `content://` sources; RAY does not download them from the internet unless Full access is on (below).
- **Notification access (optional):** used only to see and control other apps' media sessions (title, play/pause,
  seek) so the phone can act as a remote. Notification text is not read, stored or sent.
- **Usage access (`PACKAGE_USAGE_STATS`, optional):** tells your paired phone which app is on screen. No history is kept.
- **Display over other apps (`SYSTEM_ALERT_WINDOW`) and accessibility service (optional):** used only to draw the
  pointer, the on-screen display and the screen filter above other apps. The service does not read screen content or input.
- **Audio (`MODIFY_AUDIO_SETTINGS`, optional `DUMP`):** sound effects are applied to the device audio. If you grant
  `DUMP` with adb, RAY reads which audio sessions are playing, on the device only.
- **`WRITE_SECURE_SETTINGS` (optional, only if you grant it with adb):** lets the Enable button switch on the RAY
  accessibility service by itself. Nothing else is changed.
- **Run at startup and foreground service:** restore your settings and keep the remote and the filter running.
- **VPN status:** RAY only shows whether a VPN is active; it does not inspect traffic.
- Links in the settings open web pages in your browser only when you tap them.

## Full access (optional, off by default)

Settings > Home > Full access works only on devices whose own ADB is open. When you press it, RAY connects to that ADB
on the device itself (`app/src/dev/raylauncher/RayAdb.java`) and uses it to give itself usage access, notification
access, overlay and accessibility, and to read the continue watching and suggestion lists that apps publish on the TV home.
With it on, RAY also downloads the posters of those lists from the addresses the apps published (`http` or `https`,
size and time limited), so those hosts see the TV's IP address. Nothing else is sent. Press it again to stop reading
and downloading; permissions already given stay until you remove them in Android's settings.

## Your rights

Since RAY keeps your data only on your own devices, you control it directly: clear the app's data or uninstall it to
erase everything. For weather, withdraw consent as described above. For questions, open an issue on
https://github.com/MadeinTaly/ray-launcher/issues

Last updated: 2026-11-05

---

## Informativa (italiano)

**In breve:** RAY non raccoglie ne invia dati personali allo sviluppatore o a terzi. Nessun account, analytics,
pubblicita o tracciamento, nessun SDK di terze parti. Tutto resta sul dispositivo e sulla rete locale. Le uniche
richieste a internet sono il meteo facoltativo (solo dopo il tuo consenso esplicito), il controllo aggiornamenti e, solo con l'accesso completo attivo, le copertine.

- **Rete locale:** telecomando sulla porta 8766 protetto da una chiave di abbinamento casuale (QR), annuncio mDNS
  della TV (mai la chiave). Il traffico e HTTP in chiaro sulla LAN: usalo solo su reti fidate.
- **Controllo aggiornamenti:** all'avvio della TV RAY chiede all'API pubblica di GitHub (api.github.com) l'ultima
  release; se c'e una versione piu nuova propone di scaricare l'APK da github.com e Android chiede conferma. Non viene
  inviato nessun dato oltre a una normale richiesta HTTPS (GitHub vede l'indirizzo IP). Si disattiva dalle
  Impostazioni; mai sulle copie installate da F-Droid.
- **Meteo (facoltativo, disattivato di default):** dopo il consenso e l'inserimento di una citta, il nome della citta
  e le sue coordinate vengono inviati via HTTPS a Open-Meteo (server in UE) oppure a OpenWeatherMap (con la tua chiave
  API). Il fornitore vede il tuo indirizzo IP; non viene inviato altro. Base giuridica: consenso (art. 6(1)(a) GDPR),
  revocabile in ogni momento nelle impostazioni: RAY smette le richieste e cancella citta, coordinate e dati in cache.
- **Kodi:** se e in primo piano, RAY legge le sue liste dal server web di Kodi su `127.0.0.1:8080`, sullo stesso dispositivo. Un link condiviso alla TV viene aperto dall'app che lo gestisce, non da RAY.
- **Accesso completo (facoltativo, spento di default):** usa l'ADB aperto del dispositivo stesso per darsi i permessi e leggere "continua a guardare" e i proposti delle app; in piu scarica le copertine dagli indirizzi pubblicati dalle app, che quindi vedono l'IP della TV. Si disattiva dalle Impostazioni.
- **Sul dispositivo:** impostazioni, programmazione, chiave di abbinamento, elenco delle TV abbinate, app installate,
  voci "Continua a guardare" (copertine locali `content://`), sessioni multimediali. Nulla lascia la tua rete.
- **Permessi facoltativi** (sovrapposizione, accessibilita, accesso all'utilizzo, notifiche, Watch Next, audio):
  servono solo alla funzione indicata e non leggono contenuti dello schermo ne testo delle notifiche.
- **Diritti:** i dati sono solo sui tuoi dispositivi: cancella i dati dell'app o disinstalla per eliminarli tutti.

Domande: https://github.com/MadeinTaly/ray-launcher/issues
