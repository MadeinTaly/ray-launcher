# RAY privacy policy

**In short:** RAY does not collect, store or send personal data to the developer or to any third party. It has no
accounts, no analytics, no ads and no tracking, and contains no third-party SDKs. Everything stays on your device and
on your local network. The only internet request RAY can make is the optional weather lookup, and only after you
give your explicit consent.

The developer does not operate any server and receives no data from the app. Because no personal data reaches the
developer, there is no data controller relationship to manage for the app itself; the weather provider you choose
(below) acts as an independent controller for the request it receives.

## What RAY does on your network

- **Phone remote (TV):** a small web server on port 8766, protected by a random **pairing key** shown on the TV
  (QR code). Traffic is plain HTTP/WebSocket on the LAN, so use it only on a network you trust. *New pairing code* on
  the TV disconnects every paired phone.
- **Phone app:** talks to the TVs you paired, and shows its own screen from `127.0.0.1` (the phone itself).
- **Local announcement (mDNS):** while the remote is on, the TV announces itself on the LAN (`_raylauncher._tcp`) with
  its device name, type and a random id. The pairing key is never announced.
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

## Data that stays on the device

- **Settings, schedule, pairing key** and, on a phone, the list of paired TVs (name, address, key).
- **Installed apps (launcher):** names, icons and banners are read on the device to show the home screen and the
  catalog. They are sent nowhere except, on request, to a phone you paired on your LAN.
- **Watch Next (`READ_TV_LISTINGS`, optional):** if you allow it, RAY reads the "Watch Next" entries that other TV
  apps publish (title, cover, deep link) to show them on the home screen. Covers are read only from local
  `content://` sources; RAY does not download them from the internet.
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

## Your rights

Since RAY keeps your data only on your own devices, you control it directly: clear the app's data or uninstall it to
erase everything. For weather, withdraw consent as described above. For questions, open an issue on
https://github.com/MadeinTaly/ray-launcher/issues

Last updated: 2026-10-23

---

## Informativa (italiano)

**In breve:** RAY non raccoglie ne invia dati personali allo sviluppatore o a terzi. Nessun account, analytics,
pubblicita o tracciamento, nessun SDK di terze parti. Tutto resta sul dispositivo e sulla rete locale. L'unica
richiesta a internet e il meteo facoltativo, e solo dopo il tuo consenso esplicito.

- **Rete locale:** telecomando sulla porta 8766 protetto da una chiave di abbinamento casuale (QR), annuncio mDNS
  della TV (mai la chiave). Il traffico e HTTP in chiaro sulla LAN: usalo solo su reti fidate.
- **Meteo (facoltativo, disattivato di default):** dopo il consenso e l'inserimento di una citta, il nome della citta
  e le sue coordinate vengono inviati via HTTPS a Open-Meteo (server in UE) oppure a OpenWeatherMap (con la tua chiave
  API). Il fornitore vede il tuo indirizzo IP; non viene inviato altro. Base giuridica: consenso (art. 6(1)(a) GDPR),
  revocabile in ogni momento nelle impostazioni: RAY smette le richieste e cancella citta, coordinate e dati in cache.
- **Sul dispositivo:** impostazioni, programmazione, chiave di abbinamento, elenco delle TV abbinate, app installate,
  voci "Continua a guardare" (solo copertine locali `content://`), sessioni multimediali. Nulla lascia la tua rete.
- **Permessi facoltativi** (sovrapposizione, accessibilita, accesso all'utilizzo, notifiche, Watch Next, audio):
  servono solo alla funzione indicata e non leggono contenuti dello schermo ne testo delle notifiche.
- **Diritti:** i dati sono solo sui tuoi dispositivi: cancella i dati dell'app o disinstalla per eliminarli tutti.

Domande: https://github.com/MadeinTaly/ray-launcher/issues
