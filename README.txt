Floons Client 1.0.0 – Fabric-Mod für WindSMP (Minecraft 1.21.11)

KEIN AUTO-UPDATE: Die Mod baut keine einzige Netzwerkverbindung auf, prüft nie auf
neue Versionen und lädt nichts herunter. Neue Version = .jar selbst austauschen.

Bauen (wie beim Toasty Client):
  Repo ventlukas-web/floonsmc-client: jeder Push baut die .jar per GitHub Actions
  (Artifact "floons-client-jar" und Release "latest"). Das Repo aktualisiert sich, die Mod nicht.
  Benötigt Fabric API.

AUKTION (Gebote = Zahlungen an dich, Mehrfachzahlungen addieren sich)
  /wa start <startgebot> <dauer> <item>   z. B. /wa start 500k 2m Elytra
  /wa end | cancel | extend <s> | status | refund | history
  /wa test <spieler> <betrag>             Gebot simulieren, ohne Geld
  - HUD mit Zeitbalken, Höchstgebot, Meistbietendem, Top 3, Gesamt
  - Anti-Snipe: Gebot kurz vor Schluss verlängert die Zeit
  - Countdown-Ansagen bei 60/30/10/5 s
  - Am Ende: Rückzahlungsliste mit [Zurückzahlen]-Knopf (trägt /pay nur ein, Enter drückst du selbst)
  - Verlauf der letzten 50 Auktionen (config/floonsclient-history.json)
  /floons  announce | countdown | sound | hud | watermark | debug | minraise <b> | antisnipe <s>
         scale <0.5-3> | corner <top_left|top_right|bottom_right|mid_left|mid_right> | pattern <regex>

MENÜ: Rechte Shift oder /floons menu (Reiter Auktion, Gamble, Allgemein)

GAMBLE (wie Toasty Client; läuft keine Auktion, ist jede Zahlung ein Einsatz)
  /win [spieler|all] [multiplikator]   Gewinn auszahlen (Einsatz x Multiplikator, Auto-Pay)
  /lose [spieler|all]                  Einsatz behalten
  /payback [spieler|all]               Einsatz zurückzahlen
  Ohne Namen gilt der älteste offene Einsatz. Mehrere Zahlungen desselben Spielers stellen sich an.
  Manuelles /pay an einen Spieler mit offenem Einsatz zählt als Gewinn-Auszahlung.
  /gamble  stats | list | reset | min <b> | max <b|aus> | multiplier <x> | autopay | delay <ms>
           warn <s> | limit <b|aus> | hud | effects | confetti
  /roll [max]  /coinflip  /roulette  /calc
  Gamble-HUD "Floons Client" oben links: aktuelles Gamble, Warteschlange, Profit, Winrate.
  GEWONNEN/VERLOREN-Banner, Konfetti, Töne, Warte- und Verlust-Limit-Warnung.

Zahlungserkennung: gleiches Muster wie im Toasty Client
  You received $<betrag> from <spieler>

Wasserzeichen: "Floons Client" oben rechts in allen Menüs (/floons watermark schaltet um).
