# Data-App

Dateien, Fotos und Texte zwischen zwei Android-Handys austauschen – einfach die Handys aneinanderhalten.

## So funktioniert's

1. Auf dem **empfangenden** Handy die Data-App öffnen – sie ist dann automatisch empfangsbereit
   (läuft auch im Hintergrund weiter, erkennbar an der Benachrichtigung).
2. Auf dem **sendenden** Handy ein Foto/eine Datei öffnen (Galerie, Dateien, …) → **Teilen** → **Data-App**.
3. Handys aneinanderhalten – die Übertragung startet automatisch. Danach dürfen die Handys wieder auseinander.

**Kontakte tauschen:** Unter „Meine Visitenkarte“ Name, Telefon, E-Mail usw. eintragen. Hält man dann zwei Handys
mit Data-App *ohne* Datei aneinander (App läuft im Hintergrund, Display an), tauschen sie automatisch ihre Visitenkarten.
Der empfangene Kontakt erscheint als Benachrichtigung und in der App und lässt sich mit einem Tipp in den Kontakten speichern.

**Text und Links:** Geteilter Text landet beim Empfänger in der Zwischenablage; ein Link lässt sich per Tipp auf die
Benachrichtigung direkt öffnen.

**Sicherheit:** Jede Übertragung ist verschlüsselt. Von unbekannten Handys wird erst nach Bestätigung empfangen; dabei
zeigen beide Handys denselben 4-stelligen Code. Vertraute Geräte dürfen ohne Nachfrage senden.

Empfangene Dateien landen in `Bilder/Data-App`, `Filme/Data-App`, `Musik/Data-App` bzw. `Download/Data-App`.

## Technik

- Bluetooth Low Energy: Der Empfänger macht sich per BLE bekannt, der Sender erkennt über die Signalstärke,
  wann die Handys aneinanderliegen, und überträgt dann über einen L2CAP-Kanal – ohne Kopplung, ohne Internet.
- Ab 5 MB wird – wenn beide Handys es erlauben – automatisch auf WLAN-Direct umgeschaltet (deutlich schneller);
  klappt das nicht, läuft die Übertragung über Bluetooth weiter.
- Verschlüsselung: ECDH (P-256) + AES-256-GCM; Geräte weisen sich mit einem dauerhaften Schlüssel aus.
- Voraussetzungen: Android 10 oder neuer, Bluetooth LE, Data-App auf beiden Handys.
- Unter **Einstellungen** lässt sich festlegen, wie nah die Handys kommen müssen (oder nur manuell antippen).

## Bauen

```bash
./gradlew assembleDebug testDebugUnitTest
```

Release-APK: `./gradlew assembleRelease` (optional mit `keystore.properties`: `storeFile`, `storePassword`,
`keyAlias`, `keyPassword`; ohne Datei wird mit dem Debug-Schlüssel signiert).
Die GitHub-Action baut bei jedem Push eine APK als Artefakt.
