# PrimeSlots

Client-side Fabric mod für **Minecraft 26.2**, das das aktuell ausgerüstete Relikt-Set
(`/slots`) als HUD-Overlay anzeigt — mit Stats, den beiden Paar-Synergien und den effektiven
Gesamtwerten.

Autor: BIG_Gamba. Das Mod-Icon ist aus den `relic_altar`-Blocktexturen des PrimeBlocks-
Serverresourcepacks isometrisch zusammengesetzt (`scratchpad/make_icon.py`).

## Woher die Daten kommen

Seit dem 28.07.2026 trägt `RelicSetResponse` ein **`active`**-Flag, das das ausgerüstete Set
markiert. Damit beantwortet die API die zentrale Frage selbst — das Overlay steht direkt nach dem
Joinen, ohne dass `/slots` je geöffnet werden muss.

| | Quelle | liefert |
|---|---|---|
| **A** | Public API | alle Sets samt Relikten, Stats — und `active` |
| **B** | `/slots`-Menü im Spiel | die **Set-Namen** (nur dort), und eine Reaktion binnen 250 ms |

B bleibt drin, weil die Namen (`Money`) nirgends sonst stehen und weil das Menü sofort reagiert,
während der Poll bis zu einer Sekunde braucht. Bei Widerspruch gewinnt das offene Menü; sobald es
zu ist, korrigiert der nächste Abruf über `active`.

**Historisch:** vorher bestand `RelicSetResponse` nur aus `{ id, slots }`. Das Menü-Auslesen war
damals die einzige Möglichkeit, das aktive Set zu bestimmen, und ist der Grund für den
Container-Parser in `tracking/`.

## Was über die API herausgefunden wurde

Verifiziert am 2026-07-28 gegen `https://public-api.primeblocks.net`:

* **Antwort-Envelope.** Jede Antwort ist in `{ success, data, error, message }` gewrappt. Das
  OpenAPI-Dokument beschreibt nur den inneren Teil und erwähnt den Envelope nicht.
* **`/api/relic/{db}/debug/players/{uuid}` braucht keinen Token.** Da der Client seine eigene UUID
  kennt, funktioniert der Mod ohne jeden Login. Das Dokument nennt den Endpoint „intended for
  debugging only“ — er kann also verschwinden. Deshalb ist der Login-Flow
  (`/primerelics login`, PIN im Spiel bestätigen) als Fallback eingebaut; das Token liegt nur im
  Arbeitsspeicher, nie in der Config-Datei.
* **UUIDs müssen mit Bindestrichen** übergeben werden, sonst `400 owner must be a valid UUID`.
* **Datenbanken:** `cb1`, `cb2`, `cb3`. Welche für den Spieler gilt, sagt die API nicht — der Mod
  probiert sie einmal durch und merkt sich die, in der Daten liegen (`/primerelics database <name>`
  überschreibt das).
* **`d`** auf einem Relikt ist die Würfelform aus dem Wiki: 0/4/6/8/10/12/20.

Stat-Werte und alle 21 Form-Paar-Synergien stammen aus dem
[Relikt-System-Wiki](https://wiki.primeblocks.net/de/citybuild/relikt-system) und liegen in
`data/StatCatalog.java` bzw. `data/SynergyTable.java`.

## Was aus dem echten Menü kam

Alles hier wurde aus automatisch geschriebenen Dumps eines echten `/slots`-Menüs abgeleitet und
gegen dessen eigene Anzeige verifiziert — nicht geraten.

* **Das Menü hat keinen lesbaren Titel.** Er besteht aus Private-Use-Glyphen einer eigenen
  GUI-Font. Kein Titel-Regex kann darauf matchen, deshalb wird das Menü an seinem **Inhalt**
  erkannt (den „Relikt-Set …"-Tabs).
* **Set-Tabs nutzen römische Zahlen:** `Relikt-Set II: Money`. Das aktive trägt in der Lore
  „Dies ist dein aktuell ausgerüstetes Relikt-Set." und einen Enchantment-Glint (letzterer dient
  als Fallback). Nicht gekaufte Sets tragen „Nicht freigeschaltet." und werden übersprungen.
* **Maluswerte sind 1,5× der Bonuswerte.** Das Wiki tabelliert nur die Bonus-Seite. Rüstung Stufe 0
  gibt +2,0%, rollt aber als −3,0%; Erfahrung Stufe I gibt +8,0%, rollt als −12,0%. Über elf
  beobachtete Negativ-Rolls ist der Faktor konstant.
* **Anzeigeformat:** deutsches Dezimalkomma, eine Nachkommastelle, Stufe 0 ohne römische Ziffer,
  Reihenfolge `+12,0% Job XP V`. Beide Größen-Stats heißen im Spiel schlicht „Größe", die Richtung
  steckt im Vorzeichen.

Die Stat-IDs 1–20 der API mappen exakt 1:1 auf die zwanzig Wiki-Zeilen, in derselben Reihenfolge.
`StatCatalog` schlüsselt deshalb nach ID (stabil) und fällt nur bei unbekannter ID auf den Namen
zurück.

### Verifiziert

* Alle 27 Relikt-Vergleiche: die vom Mod berechneten Stat-Zeilen sind **zeichengleich** mit der
  Menü-Lore.
* Das „Relikt-Werte"-Panel wird **exakt reproduziert**, inklusive Sortierung (Boni vor Mali,
  Prozent- vor Flat-Stats, dann absteigende Magnitude, Gleichstand nach Stat-ID).
* Die berechneten Synergien decken sich mit den „Synergie"-Items, die der Server selbst anzeigt —
  für alle drei Sets. Damit ist auch die Slot-Paarung (1+2, 3+4) bestätigt.
* Alle 456 Stat-Rollen in 114 Lager-Relikten werden aufgelöst.

Falls der Server die Formulierungen ändert: alle Muster sind Config-Optionen, ein Rebuild ist nicht
nötig. `discoveryMode: true` schaltet das Mitschreiben *jedes* Containers wieder ein.

## Wer wird abgefragt

Die UUID des lokalen Spielers, `minecraft.player.getUUID()` — nichts ist konfiguriert oder fest
verdrahtet. Wer den Mod installiert, sieht seine eigenen Relikte, ohne Einrichtung.

Der Debug-Endpoint braucht keinen Token, deshalb ist auch kein Login nötig. Die API-Doku nennt ihn
allerdings „intended for debugging only": fällt er weg, braucht **jeder** Nutzer den PIN-Login
(`/primerelics login`). Der lässt sich nicht automatisieren — die PIN muss im Spiel bestätigt
werden, das ist gerade sein Zweck.

## Aktualisierung

| Situation | Intervall | Config |
|---|---|---|
| `/slots` offen | 1 s | `menuRefreshMillis` |
| nach dem Schließen | einmalig nach 750 ms | — |
| sonst | 60 s | `refreshIntervalSeconds` |

`menuRefreshMillis` wird auf mindestens 250 ms geklemmt und nie langsamer als das Leerlauf-
Intervall — ein Tippfehler kann die API also weder überrennen noch die Anzeige einfrieren.

Der offene Container wird zusätzlich alle 5 Ticks (250 ms) neu gelesen — daher wird ein
Set-Wechsel sofort sichtbar. Die *Werte* kommen aber aus der API, deshalb der schnelle Poll,
solange das Menü offen ist: nur so schlagen Relikt-Wechsel und Änderungen über die Webseite
zeitnah durch.

Es bleibt Polling, kein Push. Wann die Änderung ankommt, hängt auch davon ab, wann der Server sie
in die Datenbank schreibt — darauf hat der Mod keinen Einfluss.

## Befehle

| Befehl | Wirkung |
|---|---|
| `/primerelics` / `status` | Datenbank, Anzahl Sets, aktives Set, letzter Fehler |
| `/primerelics refresh` | Reliktdaten sofort neu laden |
| `/primerelics reload` | Config neu einlesen |
| `/primerelics dump` | offenen Container manuell nach JSON dumpen |
| `/primerelics dumps` | Dump-Ordner und Zähler anzeigen |
| `/primerelics hud on\|off` | Overlay an/aus (auch **F7**) |
| `/primerelics hud move` | Einstellungs-Editor öffnen (auch **F6**) |
| `/primerelics where` | zeigt, woran der Citybuild erkannt wurde |
| `/primerelics database <name\|auto>` | Datenbank festlegen |
| `/primerelics login` | authentifizierten Endpoint per PIN freischalten |

Beide Tasten liegen unter **Optionen → Steuerung → Sonstiges** und sind frei belegbar; F6/F7 sind
nur die Vorgaben.

## Editor (F6)

Ziehen zum Verschieben, Mausrad oder der Größe-Button skaliert (50–300 %), Pfeiltasten schieben
pixelweise. Buttons für Hintergrund (aus/leicht/normal/kräftig), Relikte, Einzelwerte, Synergien,
Werte, sowie „Stats: alle / nur Boni" — Letzteres blendet alle Mali aus, in der Relikt-Auflistung
wie im Werte-Block. Esc speichert.

Config: `config/primerelics.json`. Negative `hudX`/`hudY` ankern an den rechten/unteren Rand; der
Editor setzt das beim Loslassen selbst, je nachdem in welcher Bildschirmhälfte die Box landet.
Skaliert wird über die Matrix, nicht über die Schriftgröße, damit das Layout in unskalierten
Einheiten bleibt und die Kantenbindung auch bei 200 % stimmt.

## Citybuild-Wechsel

Welche Datenbank gilt, kommt vom Server selbst. Die einzige Stelle, an der PrimeBlocks das
mitteilt, ist die **Tab-Fußzeile**: „Du befindest dich derzeit auf: CityBuild-1 (Farmwelt-5)".
Weder Scoreboard noch Dimensionsname noch Serveradresse unterscheiden die Citybuilds.

`PlayerTabOverlay.footer` ist privat und hat nur einen Setter, deshalb liest ein Mixin-Accessor
(`mixin/PlayerTabOverlayAccessor`) ihn aus. Erkannt werden `CityBuild-1` ebenso wie die Kurzformen
`CB1`, `CB 1`, `CB-1`. Der Header („DEIN CITYBUILD NETZWERK!") löst bewusst nicht aus, weil auf
`citybuild` dort keine Ziffer folgt.

Weil die Fußzeile den Citybuild auch in der Farmwelt nennt, bleibt die Anzeige dort korrekt.

Erkannt wird jeden Tick, nicht erst beim Menüaufruf: ein Wechsel von CB1 auf CB2 leert die Anzeige
sofort und lädt neu, ohne Relog und ohne `/slots`.

### Farmwelt

Der Gang in eine Farmwelt ist ein **vollständiger Reconnect**, und die Tab-Fußzeile trifft erst
Sekunden später ein. Zwei Dinge fallen daraus:

* Beim Join wird nichts zurückgesetzt. Täte man das, wäre das Overlay nach jedem Farmwelt-Besuch
  leer, obwohl sich am Citybuild und an den Relikten nichts geändert hat. Ist der neue Server
  wirklich ein anderer Citybuild, merkt der Tick das, sobald die Fußzeile da ist.
* Das zuletzt gesehene Set wird **pro Citybuild** gemerkt. Zurück auf CB1 heißt: sofort wieder da,
  ohne `/slots` erneut zu öffnen (als Erinnerung mit `*` markiert).

Bewusst *nicht* implementiert: „nimm die Datenbank, in der Daten liegen". Wer auf CB2 steht und nur
auf CB1 Relikte hat, bekäme sonst CB1s Sets angezeigt — und zwar völlig unauffällig. Das
Durchprobieren greift nur noch, wenn die Erkennung gar nichts findet; das Ergebnis gilt dann als
unsicher und die Überschrift bekommt ein `· cb1?` angehängt, damit die Vermutung sichtbar bleibt.
`/primerelics where` zeigt alle untersuchten Texte.

Hat man auf einem Citybuild keine Relikte, bleibt das Overlay leer bis auf den Set-Namen aus dem
Menü (`Set I`, `Set II`, …): die API hat dann nichts zum Zuordnen, aber die Menü-Auswahl ist echt.

Weil eine offene GUI die Tastatur belegt und kein Befehl getippt werden kann, schreibt der Tracker
Dumps selbstständig: beim Öffnen, bei jeder Änderung und beim Schließen (`autoDump`).

## Bauen

Minecraft 26.2 braucht **Java 25**. Auf diesem Rechner liegt ein passendes JDK in der
Launcher-Runtime:

```bash
JAVA_HOME="$LOCALAPPDATA/Packages/Microsoft.4297127D64EC6_8wekyb3d8bbwe/LocalCache/Local/runtime/java-runtime-epsilon/windows-x64/java-runtime-epsilon" ./gradlew build
```

Ergebnis: `build/libs/primeslots-26.2.jar`.

## CI

`.github/workflows/build.yml` baut bei jedem Push auf `main`, bei Pull Requests und auf Knopfdruck
(`workflow_dispatch`). Die Jar landet als Build-Artefakt, benannt nach `mod_version`.

Ein Tag `v*` erzeugt zusätzlich ein GitHub-Release mit der Jar im Anhang:

```bash
git tag v26.2 && git push origin v26.2
```

Hinweis für Windows: `gradlew` braucht im Git das Ausführbar-Bit, sonst bricht der Runner mit
„Permission denied“ ab. Gesetzt mit `git update-index --chmod=+x gradlew`.

### Versionsschema

Die Mod-Version ist die Minecraft-Version, für die gebaut wurde — am Dateinamen allein ist also
ablesbar, worauf die Jar läuft. Für die nächste Minecraft-Version reicht es, in `gradle.properties`
`minecraft_version` **und** `mod_version` zu setzen (plus `fabric_api_version`, siehe
[fabricmc.net/develop](https://fabricmc.net/develop)):

```properties
minecraft_version=26.3
fabric_api_version=…+26.3
mod_version=26.3
```

Braucht es einen zweiten Build gegen dieselbe Minecraft-Version, wird ein Zähler angehängt:
`26.2.1`, `26.2.2`. Das sortiert korrekt und bleibt eindeutig.

Zur Toolchain: ab 26.x liefert Mojang die Jars **unobfuskiert** aus (kein `client_mappings`-Download
mehr, kein Yarn-Build nach 1.21.11). Deshalb hat `build.gradle` bewusst *keinen* `mappings`-Eintrag,
nutzt die Plugin-ID `net.fabricmc.fabric-loom` und `implementation` statt `modImplementation`.
Aus demselben Grund gibt es keinen `remapJar`-Schritt.

Ebenfalls neu in 26.x: HUD-Rendering läuft über `HudElement.extractRenderState(GuiGraphicsExtractor,
DeltaTracker)` statt über einen Render-Callback, und der aktuelle Screen hängt an
`Minecraft.gui.screen()` statt an einem Feld auf `Minecraft`.
