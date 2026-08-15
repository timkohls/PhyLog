<div align="center">
  <img src="Assets/icon.png" alt="PhyLog Logo" width="140">

# PhyLog

  Digitale Messwerterfassung für den Physik- und Chemieunterricht –
  ein ESP32, ein paar Sensoren, unter 100 € pro Messplatz.

</div>

---

## Worum geht's

> „Nicht das bessere Messgerät entwickeln, sondern mehr Experimentieren ermöglichen."

Ein Klassensatz Vernier- oder Leybold-Equipment kostet schnell so viel wie ein Kleinwagen –entsprechend selten stehen genug Geräte für echtes Zweiergruppen-Experimentieren zur Verfügung,
und aus vielen Versuchen werden Frontalvorführungen. PhyLog dreht den Spieß um: ein ESP32, ein 3D-gedrucktes Gehäuse und austauschbare Sensormodule ergeben einen Messplatz für einen Bruchteil
der üblichen Kosten. Steckbar, offen dokumentiert und mit einer Desktop-Software, die live mitschreibt, statt nur Rohdaten abzukippen.

**Woraus das Ganze besteht:**

```
        ┌─────────────────────────────────────────────────┐
        │                     SOFTWARE                    │
        │   Live-Diagramm · Trigger · Fits · CSV/PNG      │
        └───────────────────────┬─────────────────────────┘
                                │  USB oder Bluetooth
        ┌───────────────────────▼──────────────────────────┐
        │                  ESP32 (Firmware)                │
        │      USB-C · zwei Sensor-Slots · Streaming       │
        └───────────────────────┬──────────────────────────┘
                                │  Steckverbinder
┌───────────────────────────────▼──────────────────────────────────┐
│                     Sensormodule (steckbar)                      │
│ Spannung · Strom · Temperatur · Licht · Kraft · Mikrofon · Hall  │
└──────────────────────────────────────────────────────────────────┘
```

---

## Quickstart

1. **ESP32 anschließen.** Per USB-Kabel oder – wenn das Board vorher schon einmal gekoppelt wurde – per Bluetooth. Oben rechts im Hauptfenster "Verbinden" klicken; PhyLog sucht den richtigen Port automatisch über einen kurzen Handshake, ein manuelles Rätselraten ist nicht nötig.
2. **Sensoren zuweisen.** Über *Sensor → Sensor konfigurieren…* für Kanal A und/oder B den passenden Sensortyp wählen und bei Bedarf kalibrieren (siehe unten). Live-Werte zeigt der Dialog dabei direkt an, man sieht also sofort, ob der Sensor überhaupt etwas liefert.
3. **Abtastrate einstellen** und mit **Start** loslegen. Ohne Schwellenwert-Trigger beginnt die Aufzeichnung sofort; mit Trigger wartet PhyLog, bis die konfigurierte Flanke auftritt, und nimmt dank Vorlaufpuffer auch die Millisekunden *vor* dem eigentlichen Auslöser mit auf.
4. **Auswerten.** Im Diagramm zoomen (Rubberband oder Freihand), einen Fit durchlegen, Chi² ablesen – und am Ende als CSV oder PNG rausschreiben.

Wer nur schnell einen einzelnen Messwert braucht (z. B. für eine Wertetabelle mit mehreren Positionen statt einer Zeitreihe), nutzt statt "Start" den **Momentaufnahme**-Knopf: der trägt den aktuellen Live-Wert als neue Zeile mit fortlaufendem Index statt Zeitstempel ein.

---

## Was die Software kann

**Live-Erfassung & Trigger**
Zwei unabhängige Kanäle (A/B), durchgehendes Streaming vom Gerät, sodass Live-Anzeigen und Kalibrierung auch ohne laufende Aufzeichnung aktuelle Werte zeigen. Start entweder manuell per Knopfdruck oder über einen Schwellenwert-Trigger (steigende/fallende Flanke, einstellbare Vorlaufzeit, optionales Zeitlimit).

**Diagramm**
Zoomen per Rubberband oder Freihand-Auswahl, Zoom-Buttons, gemeinsame oder getrennte Y-Achse für Kanal A/B, gerade/glatte (Spline) Linienverbindung. Regressionen für linear, Polynom (Grad wählbar), Sinus und Exponentialfunktion, dazu reduziertes Chi² zur Bewertung der Fit-Güte – inklusive automatischer Sigma-Schätzung aus den Residuen, falls keine Messunsicherheit von Hand eingetragen wurde.

**Sensoren (steckbar)**
INA219 für Spannung und Strom, VEML7700 für Beleuchtungsstärke, HX711 für Kraft/Gewicht, INMP441-Mikrofon (als Schalldruckpegel oder live als Frequenzspektrum), ein KY-003-Hall-Sensor für Drehzahl-/Periodendauermessungen sowie ein generischer 0–25V-Spannungsteiler und ein DS18B20-Digitalthermometer. Jeder Sensor mit Kalibrierbedarf bringt seinen eigenen Dialog mit.

**Import/Export**
CSV-Import gezielt pro Kanal (die Software fragt beim Öffnen, ob die Datei nach A oder B soll – so lassen sich zwei frühere Messungen nacheinander laden, ohne dass sich die Kanäle gegenseitig überschreiben), CSV-Export getrennt je Kanal, Diagramm-Export als PNG.

**Verbindung**
USB und Bluetooth SPP, automatische Geräteerkennung per Handshake statt Rate-Raten über die COM-Port-Liste, ein eingebautes Terminal für rohe Kommandos an die Firmware.
