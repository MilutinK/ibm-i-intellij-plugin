# IBM i Helper – IntelliJ Plugin

![Build](https://github.com/MilutinK/ibm-i-intellij-plugin/workflows/Build/badge.svg)

Ein IntelliJ IDEA Plugin für IBM i (AS/400) Entwickler.

IBM i Datenbanktabellen und -spalten haben kryptische Kurznamen mit maximal 10 Zeichen (z.B. `CUSTNBR`, `ORDDAT`, `EMPLNM`). Im IBM i System-Katalog (`QSYS2.SYSTABLES`, `QSYS2.SYSCOLUMNS`) sind lesbare Beschreibungen hinterlegt. Dieses Plugin holt diese Beschreibungen per JDBC und zeigt sie direkt in IntelliJ an – ohne Kontextwechsel zur IBM i Konsole oder Dokumentation.

<!-- Plugin description -->
**IBM i Helper** zeigt Beschreibungen zu IBM i Tabellen und Spalten direkt in IntelliJ IDEA an.

**Features:**
- **Tool Window** mit Verbindungsformular (Host, Port, Benutzer, Passwort, Bibliotheken-Filter)
- **Tabellen- und Spaltenanzeige** mit Beschreibungen aus dem QSYS2-Katalog
- **Live-Suche** über Tabellennamen und Beschreibungen
- **Hover-Tooltips** im Editor: über einen IBM i Spalten- oder Tabellennamen hovern zeigt die Beschreibung als Balloon
- **Persistente Verbindungseinstellungen** (Passwort via PasswordSafe verschlüsselt)
- **Bibliotheken-Filter** für gezielte Abfragen (kommagetrennt, z.B. `MYLIB,HRLIB`)
- **Test-Modus** mit H2 In-Memory Datenbank – kein IBM i System nötig
<!-- Plugin description end -->

## Screenshots

### Tool Window
Das Tool Window zeigt alle Tabellen und Spalten der verbundenen IBM i Bibliotheken mit ihren Beschreibungen.

### Hover-Tooltip
Nach dem Verbinden zeigt ein Hover über einem IBM i Feldnamen im Editor automatisch die Beschreibung aus dem Systemkatalog.

## Voraussetzungen

- IntelliJ IDEA 2026.1 oder neuer
- Zugang zu einem IBM i System (oder Test-Modus verwenden)
- Java 21

## Installation

**Manuell (aus Release):**

1. Unter [Releases](https://github.com/MilutinK/ibm-i-intellij-plugin/releases/latest) die aktuelle `.zip`-Datei herunterladen
2. In IntelliJ: <kbd>Settings</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install Plugin from Disk...</kbd>

## Verwendung

1. Tool Window **IBM i Helper** öffnen (standardmäßig rechts angedockt)
2. Verbindungsdaten eingeben:
   - **Host**: IBM i Hostname oder IP (z.B. `pub400.com`)
   - **Port**: Standard `446` (SSL)
   - **Benutzer / Passwort**: IBM i Credentials
   - **Bibliotheken**: Kommagetrennte Liste der Bibliotheken (z.B. `MYLIB,HRLIB`) – **Pflichtfeld für IBM i Verbindungen**
3. **Verbinden** klicken
4. Tabellen und Spalten werden geladen und der Hover-Tooltip ist aktiv

**Test-Modus:** Die Checkbox „Test-Modus (H2)" aktivieren – verbindet ohne IBM i mit einer lokalen In-Memory Datenbank mit Beispieldaten.

## Entwicklung

```bash
# Plugin in Sandbox starten
./gradlew runIde

# Plugin bauen
./gradlew buildPlugin

# Tests ausführen
./gradlew test

# Bei Sandbox-Problemen (stale state)
./gradlew clean runIde
```

## Tech Stack

- Kotlin + Gradle (Kotlin DSL)
- IntelliJ Platform Gradle Plugin 2.x
- [JTOpen](https://github.com/IBM/JTOpen) (`net.sf.jt400:jt400`) – IBM i JDBC Treiber
- H2 In-Memory DB für den Test-Modus

## Lizenz

[Apache License 2.0](LICENSE)
