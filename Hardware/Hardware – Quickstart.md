# PhyLog Hardware – Quickstart

Dieser Quickstart beschreibt den Aufbau der PhyLog-Hardware sowie das erste Bespielen und Verbinden des ESP32.

PhyLog besteht aus einem ESP32-Hub und verschiedenen steckbaren Sensormodulen. Die Sensormodule können je nach benötigter Messgröße ausgetauscht und erweitert werden. Die verfügbaren Sensoren und die zugehörigen Hardware-Dateien befinden sich im Ordner [`Hardware/prints`](https://github.com/timkohls/PhyLog/tree/main/Hardware/prints).

## 1. 3D-Druck

Alle benötigten Gehäuseteile müssen zunächst ausgedruckt werden.

Für den ESP32 wird einmal der

- `ESP_Bottom`

- `ESP_Top`

benötigt.

Für die Sensoren gibt es ein gemeinsames `Sensor_Top`, das für alle Sensoren verwendet werden kann. Für jeden Sensortyp wird zusätzlich das jeweils passende `*_Sensor_Bottom` benötigt.

Beispiel:

```text
ESP32
├── ESP_Bottom
└── ESP_Top

Sensor
├── Sensor_Top
└── <passendes Sensor_Bottom>
```

Die Anzahl der zu druckenden Sensor-Bottoms richtet sich daher danach, wie viele Sensoren des jeweiligen Typs gebaut werden sollen.

Aktuell liegen unter anderem Bottoms für Strom, Spannung, Kraft, Licht, Magnetfeld, Schall und Temperatur sowie ein gemeinsames Sensor-Top und ESP-Gehäuse im Repository.

## 2. Sensoren aufbauen

Die jeweilige Sensorplatine wird in das passende Bottom eingesetzt und mit **M3 × 4 mm Schrauben** befestigt.

Die Verdrahtung zwischen Sensor und ESP32 ist dem [Pinout](https://github.com/timkohls/PhyLog/blob/main/Hardware/pinout.md) zu entnehmen. Dort ist für die verschiedenen Sensortypen angegeben, welche RJ45-Pins mit welchen ESP32-Pins verbunden werden müssen.

Dabei gilt grundsätzlich:

- **Pin 1:** GND

- **Pin 8:** VCC

- Die übrigen verwendeten Pins hängen vom Sensortyp und vom verwendeten Kommunikationsprotokoll ab.

Nach dem Einsetzen und Verkabeln wird das gemeinsame Sensor-Top aufgesetzt und ebenfalls mit **M3 × 4 mm Schrauben** befestigt.

Damit ist das Sensormodul fertig.

Der ESP32-Hub wird auf die gleiche Weise aufgebaut: ESP32 in das `ESP_Bottom` einsetzen, befestigen und anschließend das `ESP_Top` mit M3 × 4 mm Schrauben verschließen.

## 4. Firmware auf den ESP32 spielen

Bevor ein ESP32 zum ersten Mal bespielt werden kann, muss auf dem verwendeten Rechner der passende **CH340/CH341-Treiber** installiert werden.

Der benötigte Treiber befindet sich bereits im Repository unter:

```
Firmware/CH341SER/
```

Der dort enthaltene Treiber wurde von der **Nanjing Qinheng Microelectronics (WCH)**-Website heruntergeladen und unverändert in das Repository übernommen. Er ist **kein Bestandteil der PhyLog-Software**, sondern eine benötigte Drittanbieter-Komponente, die für die USB-Kommunikation mit dem ESP32 bereitgestellt wird.

Die Originalquelle des Treibers ist:

**WCH – CH341SER USB-to-Serial Driver**  
https://www.wch-ic.com/downloads/CH341SER_ZIP.html





Die Firmware befindet sich unter:

```text
Firmware/PhyLog_firmware/PhyLog_firmware.ino
```

Die `.ino`-Datei kann direkt mit der **Arduino IDE** geöffnet werden.

Arduino erkennt die benötigten Bibliotheken normalerweise automatisch. Falls die ESP32-Unterstützung noch nicht installiert ist, muss das **ESP32 Dev Module** bzw. die ESP32-Board-Unterstützung über den Board Manager installiert werden.

Die Installation kann etwas dauern, da Arduino dabei zusätzliche Komponenten herunterlädt. Die offizielle ESP32-Unterstützung wird über das Arduino-ESP32-Paket bereitgestellt.

Anschließend:

1. ESP32 per USB mit dem Rechner verbinden.

2. In Arduino das entsprechende **ESP32 Dev Module** auswählen.

3. Den vom ESP32 verwendeten **COM-Port** auswählen.

4. Die Firmware mit **Upload** auf den ESP32 übertragen.

5. Warten, bis Arduino **`Upload Done`** meldet.

Falls der ESP32 nicht als COM-Port auftaucht, zunächst überprüfen, ob der CH340/CH341-Treiber korrekt installiert wurde.

## 5. Erste Verbindung mit PhyLog

Nach erfolgreichem Flashen kann der ESP32 zum ersten Mal mit der PhyLog-Software verbunden werden.

Am einfachsten ist die erste Verbindung zunächst über **USB**.

ESP32 anschließen und in PhyLog oben rechts die Verbindung herstellen. Der bevorzugte Weg ist die **automatische Portsuche**. PhyLog sucht dabei nach dem passenden Gerät und überprüft die Verbindung über einen Handshake.

Alternativ kann der COM-Port auch manuell über das Dropdown-Menü ausgewählt werden.

### Verbindung testen

Um zu überprüfen, ob die Firmware korrekt funktioniert, kann das integrierte **Terminal** geöffnet werden.

Den ESP32 einmal anpingen.

Wenn alles korrekt funktioniert, sollte eine **Hello-Antwort mit der entsprechenden Firmware-/Payload-Version** zurückkommen.

Ist dies der Fall, funktionieren:

- ESP32

- USB-Verbindung

- Treiber

- Firmware

- Kommunikation mit PhyLog

korrekt.

## 6. Fehlerbehebung bei USB

Wenn kein COM-Port angezeigt wird oder keine Verbindung hergestellt werden kann, sollten die folgenden Punkte überprüft werden:

1. Ist der CH340/CH341-Treiber installiert?

2. Wird der ESP32 im Geräte-Manager als COM-Port erkannt?

3. Ist in Arduino das richtige ESP32-Board ausgewählt?

4. Wurde die Firmware erfolgreich mit **`Upload Done`** übertragen?

5. Ist der richtige COM-Port ausgewählt?

6. Funktioniert die Kommunikation über das PhyLog-Terminal?

Wenn der ESP32 bereits erfolgreich geflasht wurde, aber keine Verbindung zustande kommt, sollte zunächst der USB-Anschluss und anschließend die automatische bzw. manuelle COM-Port-Auswahl überprüft werden.

## 7. Verbindung über Bluetooth

PhyLog unterstützt neben USB auch **Bluetooth SPP**.

Wenn Bluetooth verwendet werden soll, muss der ESP32 auf jedem Rechner, mit dem er verwendet werden soll, zunächst einmal ganz normal über die Bluetooth-Einstellungen des Betriebssystems gekoppelt werden.

Dazu:

1. ESP32 mit Strom versorgen.

2. Bluetooth-Einstellungen des Rechners öffnen.

3. Den ESP32 auswählen und koppeln.

4. PhyLog starten.

5. Oben rechts die automatische Portsuche verwenden oder den entsprechenden Port manuell auswählen.

> **Hinweis:** Die Bluetooth-Suche dauert etwas länger als die USB-Suche. Währenddessen zeigt PhyLog oben im Statusbereich an, was gerade passiert.

Nach erfolgreicher Verbindung kann der ESP32 genauso wie über USB verwendet werden.

## 8. Kurzfassung

```text
3D-Druck
   ↓
ESP-Bottom + ESP-Top
Sensor-Bottom + gemeinsames Sensor-Top
   ↓
Sensorplatinen mit M3 × 4 mm befestigen
   ↓
Nach pinout.md verkabeln
   ↓
Gehäuse schließen
   ↓
CH340/CH341-Treiber installieren
   ↓
PhyLog_firmware.ino in Arduino öffnen
   ↓
ESP32 Dev Module auswählen
   ↓
COM-Port auswählen
   ↓
Firmware hochladen
   ↓
"Upload Done"
   ↓
ESP32 mit PhyLog verbinden
   ↓
Terminal öffnen und ESP32 anpingen
   ↓
Hello + Payload-Version erhalten
   ↓
Fertig
```

Weitere Informationen zu Pinbelegung, Hardware und verfügbaren Komponenten befinden sich direkt im `Hardware`-Ordner des Projekts.
