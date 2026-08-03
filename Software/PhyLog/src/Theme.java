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
    /** Status "Warnung/mäßig" (z. B. Über- oder mäßige Anpassung). */
    public static final Color WARNING = new Color(241, 196, 15);
    /** Status "Fehler/schlecht" (z. B. Unteranpassung). */
    public static final Color DANGER = new Color(231, 76, 60);

    /**
     * Initialisiert das Dark-Theme und setzt die UI-Defaults.
     */
    public static void setup() {
        FlatDarkLaf.setup();

        UIManager.put("Component.arc", 15);
        UIManager.put("Button.arc", 15);
        UIManager.put("TextComponent.arc", 15);
        UIManager.put("ScrollBar.thumbArc", 999);

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
