import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.*;
import java.awt.*;

/**
 * Farbpalette und Look-and-Feel-Konfiguration der Anwendung. Einzige Quelle der Wahrheit für
 * Farben - andere Klassen sollten keine eigenen Color-Literale für dieselben Zwecke anlegen,
 * damit sich das Farbschema an einer Stelle ändern lässt.
 */
public class Theme {

    /** Fensterhintergrund. */
    public static final Color BG = new Color(37, 37, 37);
    /** Panel- und Karten-Hintergrund. */
    public static final Color PANEL = new Color(48, 48, 48);
    /** Etwas dunklerer Karten-Hintergrund für Info-Dialoge (siehe {@link ChiSquareInfoDialog}). */
    public static final Color CARD = new Color(45, 45, 45);
    /** Rahmen- und Trennlinien. */
    public static final Color BORDER = new Color(68, 68, 68);
    /** Standard-Textfarbe. */
    public static final Color TEXT = new Color(235, 235, 235);
    /** Gedämpfte Textfarbe für Beschriftungen und Hinweise von zweitrangiger Bedeutung. */
    public static final Color MUTED = new Color(160, 160, 160);
    /** Akzentfarbe für Interaktionen und Fit-Kurven. */
    public static final Color ACCENT = new Color(82, 140, 255);
    /** Farbe für Messpunkte im Diagramm. */
    public static final Color POINT_A = new Color(255, 170, 90);
    public static final Color POINT_B = new Color(46, 204, 113);

    /** Status "gut/erfolgreich" (z. B. laufende Aufzeichnung, guter Chi²-Fit). */
    public static final Color SUCCESS = new Color(46, 204, 113);
    /** Status "Warnung/mäßig" (z. B. Über- oder mäßige Anpassung, Trigger wartet). */
    public static final Color WARNING = new Color(241, 196, 15);
    /** Status "Fehler/schlecht" (z. B. Unteranpassung, Verbindung verloren). */
    public static final Color DANGER = new Color(231, 76, 60);

    // --- Typografie ---
    // Zentral definiert, statt an jeder Stelle einzeln "new Font(...)" zu schreiben - damit
    // sich Schriftgrößen/-schnitte künftig an einer Stelle ändern lassen und alle Dialoge
    // gleich aussehen (siehe FormDialog, ChiSquareInfoDialog).

    /** Überschriften in Dialogen (z. B. {@link ChiSquareInfoDialog}). */
    public static final Font FONT_TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 15);
    /** Standard-UI-Text (Labels, Buttons, Formularfelder). */
    public static final Font FONT_UI = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    /** Wie {@link #FONT_UI}, aber fett - für Zwischenüberschriften/Kartentitel. */
    public static final Font FONT_UI_BOLD = new Font(Font.SANS_SERIF, Font.BOLD, 13);
    /** Kleine, gedämpfte Hinweistexte unterhalb von Formularfeldern. */
    public static final Font FONT_HINT = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
    /** Dicktengleiche Schrift für Terminal-Log und Fit-Gleichungen. */
    public static final Font FONT_MONO = new Font(Font.MONOSPACED, Font.PLAIN, 12);

    /** Eckenradius für Karten-/Info-Panels (siehe {@link RoundedPanel}), etwas dezenter als der
     *  Radius von Buttons/Textfeldern ({@code Component.arc} unten), damit größere Flächen nicht
     *  überrundet wirken. */
    public static final int CARD_ARC = 12;
    /** Einheitlicher Außenabstand zwischen Formularzeilen bzw. Karten-Inhalt und -Rand. */
    public static final int SPACING = 10;

    /**
     * Baut den einheitlichen "Panel-mit-Titel"-Rahmen für die Haupt-Arbeitsflächen (Diagramm,
     * Kanal-Tabellen) - bisher an jeder dieser Stellen einzeln als
     * {@code createTitledBorder(createLineBorder(...), ...)} dupliziert. Ergänzt gegenüber dem
     * bloßen Titled-Border zusätzlich etwas Innenabstand, damit z. B. Tabelleninhalt nicht direkt
     * an der Rahmenlinie klebt.
     *
     * @param title Anzeigetitel, z. B. "Diagramm" oder ein Kanalname.
     */
    public static javax.swing.border.Border titledPanelBorder(String title) {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(
                        BorderFactory.createLineBorder(BORDER), title, 0, 0, FONT_UI_BOLD, TEXT),
                BorderFactory.createEmptyBorder(4, 6, 6, 6));
    }

    /**
     * Initialisiert das Dark-Theme und setzt die UI-Defaults.
     */
    public static void setup() {
        FlatDarkLaf.setup();

        UIManager.put("Component.arc", 15);
        UIManager.put("Button.arc", 15);
        UIManager.put("TextComponent.arc", 15);
        UIManager.put("ScrollBar.thumbArc", 999);

        // Einheitliche Basis-Schriftart für alle FlatLaf-Komponenten (Buttons, Labels, Felder,
        // Menüs, Tabellen, ...), statt sie einzeln je Komponente zu setzen.
        UIManager.put("defaultFont", FONT_UI);

        UIManager.put("Panel.background", BG);
        UIManager.put("Viewport.background", BG);

        UIManager.put("Table.background", PANEL);
        UIManager.put("Table.foreground", TEXT);
        UIManager.put("Table.selectionBackground", ACCENT);
        UIManager.put("Table.gridColor", BORDER);

        UIManager.put("ScrollPane.background", BG);

        UIManager.put("MenuBar.background", BG);
        UIManager.put("Menu.background", PANEL);
        UIManager.put("Menu.foreground", TEXT);
        UIManager.put("MenuItem.background", PANEL);
        UIManager.put("MenuItem.foreground", TEXT);

        UIManager.put("ToolBar.background", BG);

        UIManager.put("Button.background", PANEL);
        UIManager.put("Button.foreground", TEXT);

        UIManager.put("Label.foreground", TEXT);

        UIManager.put("TitledBorder.titleColor", TEXT);
    }
}