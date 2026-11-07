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
  - Protokoll in `transfer/Proto.kt` (reine JVM, Unit-Tests in `ProtoTest`): Header → ACCEPT → Dateien → DONE.
    Empfänger nimmt derzeit alles an, solange „Empfangsbereit“ aktiv ist (kein Bestätigungsdialog).
  - Gespeichert wird über MediaStore: Bilder/Data-App, Filme/Data-App, Musik/Data-App, Download/Data-App.
    Verlauf (max. 100) als JSON in SharedPreferences `data_app`.
  - Geteilter Text (ohne Datei) wird als `Text_<Zeitstempel>.txt` gesendet.
- **Berechtigungen**: ab API 31 BLUETOOTH_SCAN (neverForLocation)/ADVERTISE/CONNECT; API 29/30 Standort (FINE+COARSE,
  Lint verlangt beide); POST_NOTIFICATIONS optional.
- **Technik**: Kotlin 2.0.21, Jetpack Compose (Material 3, BOM 2024.12.01), AGP 8.7.3, Gradle 8.11.1, JDK 17,
  compileSdk/targetSdk 35, Paket `com.bytestreamdevelopment.dataapp`. Keine Fremdbibliotheken außer AndroidX/Coroutines.
- **Bauen/Testen**: `./gradlew assembleDebug testDebugUnitTest lintDebug`. In der Cloud-Sandbox: Android SDK nach
  `/opt/android-sdk` (cmdline-tools von dl.google.com; `platforms;android-35`, `build-tools;35.0.0`),
  `local.properties` mit `sdk.dir=/opt/android-sdk`. Maven Central liefert oft 429 → mit `--max-workers=1` erneut bauen.
  Kein KVM/Emulator, und BLE lässt sich ohnehin nur auf zwei echten Handys testen.
- **Bekannte Grenzen / Ideen**: Durchsatz von BLE-L2CAP ca. 50–300 KB/s je nach Handy (große Videos dauern);
  RSSI-Werte schwanken je nach Modell → Einstellung „Sehr nah“ als Ausweichoption.
  Mögliche Erweiterungen: Annahme-Dialog, 2M-PHY, Wi-Fi-Direct für große Dateien.
