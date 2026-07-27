import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.*;
import java.awt.*;

/**
 * Zentrale Farbpalette und Look-and-Feel-Konfiguration für PhyLog.
 *
 * <p>Alle Farbwerte, die anderswo im Programm verwendet werden (z. B. in {@link ChartPanel}
 * oder {@link ChiSquareInfoDialog}), sollten von hier bezogen werden, statt neue Farbwerte an
 * Ort und Stelle zu erfinden - so bleibt das Erscheinungsbild an einer einzigen Stelle
 * änderbar.</p>
 */
public class Theme {

    /** Fensterhintergrund. */
    public static final Color BG = new Color(37, 37, 37);
    /** Hintergrund von Panels/Karten (etwas heller als {@link #BG}). */
    public static final Color PANEL = new Color(48, 48, 48);
    /** Rahmen- und Trennlinienfarbe. */
    public static final Color BORDER = new Color(68, 68, 68);
    /** Standard-Textfarbe. */
    public static final Color TEXT = new Color(235, 235, 235);
    /** Akzentfarbe für interaktive Elemente und Fit-Kurven. */
    public static final Color ACCENT = new Color(82, 140, 255);
    /** Farbe für einzelne Messpunkte im Diagramm. */
    public static final Color POINT = new Color(255, 170, 90);

    /**
     * Initialisiert FlatLaf im Dark-Modus und überschreibt die für diese Anwendung relevanten
     * UIManager-Standardwerte (Eckenradien, Panel-/Tabellen-/Menüfarben) mit den Werten aus
     * dieser Klasse. Muss einmalig beim Programmstart aufgerufen werden, bevor irgendein
     * Swing-Fenster erzeugt wird (siehe {@link Main#main(String[])}).
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