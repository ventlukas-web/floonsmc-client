# WindSMP-Preis-Server

Gemeinsamer Preis-Server für Floons Client und Toasty Client (wie bei der FWM Mod).
Clients schicken gesehene `/ah`- und `/orders`-Preise, alle bekommen den Durchschnittspreis (Median, letzte 7 Tage).

## Einrichten (geht am Handy im Browser, gratis)

1. Auf https://dash.cloudflare.com ein Konto anlegen.
2. **Storage & Databases → D1 → Create database**, Name `windsmp-prices`.
3. **Workers & Pages → Create → Worker** (Hello World), Name z. B. `windsmp-prices`, **Deploy**.
4. **Edit code**: alles löschen, Inhalt von `worker.js` einfügen, **Deploy**.
5. Worker → **Settings → Bindings → Add → D1 database**: Variable name `DB`, Datenbank `windsmp-prices`, speichern.
6. URL kopieren (z. B. `https://windsmp-prices.<name>.workers.dev`).
   Test im Browser: `<url>/prices` muss `{"updated":…,"items":{}}` zeigen.
7. Ingame: `/prices server <url>` oder Mod-Menü → Tools → Preise → URL eintragen.

Die Tabelle legt der Worker beim ersten Aufruf selbst an. Einträge älter als 31 Tage werden gelöscht.

## API

- `POST /report` – `{"obs":[{"item":"minecraft:diamond","name":"Diamond","src":"ah"|"order","price":123.4,"fp":"…"}]}` (max. 300)
- `GET /prices` – `{"updated":…,"days":7,"items":{"minecraft:diamond":{"name":"Diamond","ah":120,"ahN":14,"o":95,"oN":6,"t":…}}}`

Hinweis: Jeder mit der URL kann Preise schicken. Der Median sorgt dafür, dass einzelne falsche Werte kaum etwas ändern.
