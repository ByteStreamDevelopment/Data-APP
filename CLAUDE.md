# Projektnotizen für Claude

- **Sprache**: Mit dem Nutzer immer auf Deutsch kommunizieren. UI-Texte der App sind deutsch.
- **Zweck**: „Data-App“ – Dateien/Fotos/Text zwischen zwei Android-Handys austauschen, indem man sie aneinanderhält.
  Senden über das System-„Teilen“-Menü → „Data-App“. Beide Handys brauchen die App.
- **GitHub-Repo**: `bytestreamdevelopment/data-app` (Schwesterprojekt: `bytestreamdevelopment/app-launcher`,
  ein App-Store, der APKs aus Releases auf `https://git.nebendahl.eu` (Forgejo) lädt – für eine Verteilung über den
  Launcher muss dort ein Release mit `.apk`-Anhang liegen).
- **Technikentscheidung (Nutzerwunsch: Bluetooth)**:
  - NFC/Android Beam gibt es seit Android 10 nicht mehr → „Aneinanderhalten“ wird über die **BLE-Signalstärke (RSSI)** erkannt.
  - Empfänger (`ReceiverService`, Vordergrunddienst `connectedDevice`) bewirbt sich per BLE mit Service-UUID
    `Proto.SERVICE_UUID`; Scan-Response enthält Service-Daten = PSM (2 Byte) + gekürzter Gerätename (≤10 Byte UTF-8).
  - Sender (`ShareActivity` → `SendViewModel` → `Sender`) scannt, glättet RSSI (`ProximityTracker`, EMA α=0,5,
    mind. 2 Messwerte) und verbindet automatisch, sobald der Schwellwert erreicht ist (`Proximity`: TOUCH −45 dBm,
    NEAR −60, ROOM −90, MANUAL = nur antippen). Tx-Power des Advertisings: MEDIUM.
  - Übertragung über **unverschlüsselten L2CAP-CoC-Kanal** (`listenUsingInsecureL2capChannel` /
    `createInsecureL2capChannel`) → keine Kopplung nötig, deshalb **minSdk 29** (Android 10).
    Klassisches RFCOMM geht nicht, weil man die Classic-MAC des Gegenübers nicht kennt.
  - Protokoll **Version 2** in `transfer/Proto.kt` (reine JVM, Unit-Tests in `ProtoTest`/`CryptoTest`):
    Handshake → Header + Geräteausweis → ACCEPT/REJECT (+ Transportwahl) → Dateien → DONE. Nicht kompatibel zu Version 1.
  - Gespeichert wird über MediaStore: Bilder/Data-App, Filme/Data-App, Musik/Data-App, Download/Data-App.
    Verlauf (max. 100) als JSON in SharedPreferences `data_app`.
  - **Geteilter Text/Link** (ohne Datei) wird als Element mit MIME `Proto.MIME_TEXT` gesendet: Der Empfänger speichert
    keine Datei, sondern legt den Text in die Zwischenablage; Links (`Proto.asLink`) öffnet ein Tipp auf die
    Benachrichtigung bzw. den Verlaufseintrag (`ReceivedFile.text`).
- **Verschlüsselung** (`transfer/Crypto.kt`): je Verbindung ECDH P-256 mit kurzlebigen Schlüsseln → HKDF-SHA256 →
  AES-256-GCM je Richtung (`SealedOutputStream`/`SealedInputStream`, 32-KB-Blöcke, Zähler als Nonce).
  Dauerhafter Geräteschlüssel (`Identity`, in SharedPreferences `identity`) signiert die Handshake-Schlüssel
  (`Proto.writeAuth/readAuth`); Geräte-ID = SHA-256-Fingerabdruck. 4-stelliger Code (`SessionKeys.code`) wird auf
  beiden Handys angezeigt (Schutz vor Man-in-the-Middle bei unbekannten Geräten). Kontaktaustausch ist ebenfalls
  verschlüsselt, aber ohne Geräteausweis.
- **Annahme-Dialog**: Bei unbekanntem Absender fragt `ReceiverService.askUser` nach (Dialog in `MainScreen` +
  Benachrichtigung mit Annehmen/Ablehnen, 45 s Zeitlimit → Ablehnung). „Diesem Gerät künftig vertrauen“ speichert die
  Geräte-ID (`AppPrefs.trusted`); vertraute Geräte senden ohne Nachfrage. Abschaltbar (`askBeforeReceive`).
- **Abbrechen**: Sender `SendViewModel.cancel()` (schließt alle gemeldeten Sockets; `token` verhindert veraltete
  Anzeigen), Empfänger `ReceiverService.cancelTransfer()` (Knopf in App und Benachrichtigung). Halbe Dateien werden gelöscht.
- **Rückmeldung** (`Feedback`): kurze Vibration beim Erkennen, Doppel-Vibration + Ton bei Erfolg, lange bei Fehler.
- **Geschwindigkeit**:
  - `LinkBooster`: zusätzliche GATT-Verbindung zum selben Gerät, darüber 2M-PHY und hohe Verbindungspriorität
    (der L2CAP-Socket hat dafür keine API). Nur Senderseite, nur bei Bluetooth-Transport, abschaltbar.
  - `WifiDirect`: ab 5 MB (`MIN_BYTES`), wenn beide Seiten können. Empfänger eröffnet Gruppe mit zufälligem
    `DIRECT-DA-…`-Namen + Passwort (`WifiP2pConfig.Builder`, kein Systemdialog), schickt sie über den BLE-Kanal;
    Sender tritt bei und verbindet per TCP zur Gruppeneigentümer-Adresse. Schlüssel für TCP stammen aus derselben
    Sitzung (`WIFI_I2R`/`WIFI_R2I`). Scheitert der Aufbau, geht es über Bluetooth weiter.
    Berechtigung: ab API 33 NEARBY_WIFI_DEVICES (wird mit den Bluetooth-Rechten angefragt), API 31/32 Standort
    (nur auf Wunsch über den Schalter in den Einstellungen), API 29/30 Standort (ohnehin vorhanden).
- **Autostart**: `BootReceiver` startet den Empfangsdienst nach dem Hochfahren (Einstellung, Standard aus).
- **Kontaktaustausch (Issue #1)**: Halten zwei Handys mit laufendem Empfangsdienst *ohne* Datei aneinander,
  tauschen sie ihre Visitenkarten („Meine Visitenkarte“: Name, Telefon, E-Mail, Firma, Website; `ContactCard`).
  - Advertising-Hauptpaket enthält zusätzlich Herstellerdaten 0xFFFF = `Proto.Meta` (Flag Kontaktaustausch + zufällige
    Instanz-ID; Paket 30/31 Byte). Das Gerät mit der **kleineren Instanz-ID** startet (`ContactTrigger`), die andere Seite
    nimmt im `ReceiverService.handleContact` an (Unterscheidung über `CONTACT_MAGIC` vs. `MAGIC`).
  - `ContactExchanger` scannt im Dienst (LOW_LATENCY, nur bei eingeschaltetem Display), Schwelle höchstens „Sehr nah“.
  - Sperren: 2 min je Gegenstelle, 60 s nach jeder Dateiübertragung, und solange `ShareActivity` sichtbar ist
    (`ReceiverState.shareActive` → Werbung + Kontaktsuche pausieren), damit Dateiversand keinen Kontakttausch auslöst.
  - Empfangene Kontakte: Liste in der App + Benachrichtigung; Speichern über `ContactsContract.Intents.Insert`
    (keine Kontakte-Berechtigung nötig). Gespeichert in SharedPreferences (`contacts`, `my_card`, `contact_exchange`).
  - Kontaktfoto: 256-px-JPEG (`CardPhotos`, Dateien in `filesDir`), wird mit der Karte übertragen (max. 300 KB).
  - „Aus meinen Kontakten übernehmen“ im Bearbeiten-Dialog: Kontaktauswahl + READ_CONTACTS (erst beim Antippen angefragt).
  - Einschränkung: Auf Android 10/11 liefert BLE-Scan im Hintergrund ohne ACCESS_BACKGROUND_LOCATION keine Ergebnisse →
    dort klappt der Kontakttausch nur bei geöffneter App (bewusst keine Hintergrund-Standortberechtigung angefragt).
- **Berechtigungen**: ab API 31 BLUETOOTH_SCAN (neverForLocation)/ADVERTISE/CONNECT; API 29/30 Standort (FINE+COARSE,
  Lint verlangt beide; im Manifest bis API 32 wegen WLAN-Direct); optional POST_NOTIFICATIONS, NEARBY_WIFI_DEVICES,
  READ_CONTACTS. Ohne Laufzeitabfrage: INTERNET (TCP-Socket), WLAN-Status, VIBRATE, WAKE_LOCK, RECEIVE_BOOT_COMPLETED.
- **Technik**: Kotlin 2.0.21, Jetpack Compose (Material 3, BOM 2024.12.01), AGP 8.7.3, Gradle 8.11.1, JDK 17,
  compileSdk/targetSdk 35, Paket `com.bytestreamdevelopment.dataapp`. Keine Fremdbibliotheken außer AndroidX/Coroutines.
- **Bauen/Testen**: `./gradlew assembleDebug testDebugUnitTest lintDebug`. In der Cloud-Sandbox: Android SDK nach
  `/opt/android-sdk` (cmdline-tools von dl.google.com; `platforms;android-35`, `build-tools;35.0.0`),
  `local.properties` mit `sdk.dir=/opt/android-sdk`. Maven Central liefert oft 429 → mit `--max-workers=1` erneut bauen.
  Kein KVM/Emulator, und BLE lässt sich ohnehin nur auf zwei echten Handys testen.
- **Bekannte Grenzen / Ideen**: Durchsatz von BLE-L2CAP ca. 50–300 KB/s je nach Handy (große Videos dauern);
  RSSI-Werte schwanken je nach Modell → Einstellung „Sehr nah“ als Ausweichoption.
  **Ungetestet auf echten Geräten** (Stand 2026-11): gesamte Funkstrecke, besonders `LinkBooster` und `WifiDirect` –
  beide sind so gebaut, dass ein Fehlschlag auf normales Bluetooth zurückfällt, und lassen sich in den Einstellungen abschalten.
  Mögliche Erweiterungen: Felder der Visitenkarte pro Austausch wählbar, Ordner senden, main-Branch/Release für App-Launcher.
