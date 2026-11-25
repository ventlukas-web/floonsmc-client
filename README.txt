Floons Client 1.0.0 – Fabric-Mod für WindSMP (Minecraft 1.21.11)

KEIN AUTO-UPDATE: Die Mod prüft nie auf neue Versionen und lädt nichts herunter.
Einzige Verbindung: der Twitch-Chat, und nur wenn du ihn einschaltest. Neue Version = .jar selbst austauschen.

Bauen (wie beim Toasty Client):
  Repo ventlukas-web/floonsmc-client: jeder Push baut die .jar per GitHub Actions
  (Artifact "floons-client-jar" und Release "latest"). Das Repo aktualisiert sich, die Mod nicht.
  Benötigt Fabric API.

AUKTION (Gebote = Zahlungen an dich, Mehrfachzahlungen addieren sich)
  /action start <startgebot> <dauer> <item>   z. B. /action start 500k 2m Elytra
  /action hand <startgebot> <dauer>           Item aus der Hand (Menü: Knöpfe Hand / Inv)
  /action end | cancel | extend <s> | status | refund | history
  /action test <spieler> <betrag>             Gebot simulieren, ohne Geld
  /action pause | resume                      Auktion anhalten / fortsetzen
  /action buynow <betrag|aus>                 Sofortkauf: Gesamtgebot erreicht -> sofort verkauft
  /action reserve <betrag|aus>                Mindestpreis: darunter wird nicht verkauft
  /action queue add <start> <dauer> <item>    Warteschlange, startet automatisch nacheinander
  /action queue list | next | remove <nr> | clear
  /action reset | reset bids | reset <spieler>   alles / nur Gebote / einen Zahler zurücksetzen
  /action stats                               Erlös, Schnitt, teuerstes Item, bester Käufer
  - Neues Auktions-HUD: Logo, große Restzeit, Zeitbalken, nächstes Mindestgebot,
    Sofortkauf/Mindestpreis, Top 3, Live-Gebotsverlauf, Warteschlange; frei verschiebbar
  - Großer Countdown in der Bildschirmmitte (letzte 5 s), VERKAUFT-Banner mit Konfetti
  - HUD mit Zeitbalken, Höchstgebot, Meistbietendem, Top 3, Gesamt
  - Anti-Snipe (an/aus): mehr Geld in den letzten 10 s -> Zeit wieder auf 15 s
    /floons antisnipe (an/aus) · /floons antisnipe <fenster> <auf>, z. B. 10 15
  - Countdown-Ansagen bei 60/30/10/5 s
  - Am Ende: Rückzahlungsliste mit [Zurückzahlen]-Knopf (trägt /pay nur ein, Enter drückst du selbst)
  - Verlauf der letzten 50 Auktionen (config/floonsclient-history.json)
  /floons  announce | countdown | sound | hud | watermark | debug | minraise <b> | antisnipe [fenster auf]
         scale <0.5-3> | corner <top_left|top_right|bottom_right|mid_left|mid_right> | pattern <regex>

MENÜ: Rechte Shift oder /floons menu
  General   Design (Farben, Vorlagen, Deckkraft, Wasserzeichen, HUD verschieben) · FPS
  Gamble    Win/Lose/Payback, Multiplikator, Auto-Pay, Min/Max, Limits
  Auktion   Auktion starten/steuern, Ansagen, Anti-Snipe, HUD-Position
  Visuals   HUD · Mehr HUDs · PvP-Anzeigen · Extras (Fullbright, NoFog, Anti-Leak, Glas, Zoom C) · Crosshair · Scoreboard · Eigenes HUD
  Tools     Wegpunkte · Notizen · Timer/Stoppuhr/Rechner/Roulette
  Commands  alle Befehle zum Anklicken
  Tasten: Rechte Shift (Menü), C (Zoom), HUD verschieben und Fullbright frei belegbar

GAMBLE (wie Toasty Client; läuft keine Auktion, ist jede Zahlung ein Einsatz)
  /win [spieler|all] [multiplikator]   Gewinn auszahlen (Einsatz x Multiplikator, Auto-Pay)
  /lose [spieler|all]                  Einsatz behalten
  /payback [spieler|all]               Einsatz zurückzahlen
  Ohne Namen gilt der älteste offene Einsatz. Mehrere Zahlungen desselben Spielers stellen sich an.
  Manuelles /pay an einen Spieler mit offenem Einsatz zählt als Gewinn-Auszahlung.
  /gamble  stats | list | reset | min <b> | max <b|aus> | multiplier <x> | autopay | delay <ms>
           warn <s> | limit <b|aus> | hud | effects | confetti
  /roll [max]  /coinflip  /roulette  /calc
  Info-Pillen: jede neue Info fliegt unten rechts als Pille rein (Design: Toasts an/aus).
  Gamble-HUD "Floons Client" oben links: aktuelles Gamble, Warteschlange, Profit, Winrate.
  GEWONNEN/VERLOREN-Banner, Konfetti, Töne, Warte- und Verlust-Limit-Warnung.

Zahlungserkennung: gleiches Muster wie im Toasty Client
  You received $<betrag> from <spieler>

Wasserzeichen: "Floons Client" oben rechts in allen Menüs (/floons watermark schaltet um).

STREAMER (Reiter Streamer: Twitch-Chat · Giveaway · Auktion · Streamer & Werbung)
  /twitch <kanal> | off                  Twitch-Chat im HUD (anonym, ohne Login)
  /giveaway start <dauer> <preis>        Teilnahme per Stichwort im Spiel- und Twitch-Chat
  /giveaway end | cancel | reroll | list | keyword <wort> | winners <n> | twitch
  /werbung                               Werbe-Text in den Chat
  Giveaway-HUD: Preis, Zeitbalken, große Teilnehmerzahl, neue Teilnehmer live ([T] = Twitch),
  Glücksrad in der Bildschirmmitte: Rad mit allen Namen dreht sich und bleibt beim Gewinner
  unter dem Zeiger stehen (Tick-Ton, max. 24 Felder), danach Gewinner-Banner mit Konfetti.
  /auction und /floons auction (auch /floons auctions) funktionieren genauso wie /action.

EFFEKTE (Visuals → Effekte): Luftpartikel (Dichte wenig/mittel/viel), Jumpringe, Block-Kontur

ANTI-LEAK (Visuals → Anti-Leak, /antileak, eigene Taste unter Steuerung)
  Blöcke als Netherite: jeder Block sieht gleich aus (Erze, Basen verraten nichts im Stream/ss)
  Chat-Filter: blockiert deine Koordinaten, Koordinaten deiner Wegpunkte, IP-Adressen,
    deinen PC-Namen und eigene gesperrte Wörter – im Chat und in /msg, /w, /r usw.
    [Trotzdem senden] bzw. /antileak send schickt die blockierte Nachricht doch.
  Koordinaten in HUDs verstecken (Koordinaten-HUD, Eigenes HUD)
  /antileak | blocks | chat | hud | words <a,b,c> | test <text> | send
