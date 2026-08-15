import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.util.Map;

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

    /** Kompakter Symbol-/Icon-Button (Zoom, Port-Refresh, ...): einheitliches Margin, kein
     *  Fokus-Rahmen, optional eigene Schrift für größere Symbole (z. B. "+"/"−"). Zentralisiert das
     *  bisher an mehreren Stellen in GUI wiederholte Font/Margin/FocusPainted-Trio.
     *
     * @param symbol   Beschriftung des Buttons (kurzes Symbol/Zeichen)
     * @param tooltip  Tooltip-Text, {@code null} für keinen
     * @param boldFont {@code true} für größere, fette Schrift (z. B. Zoom-Buttons),
     *                 {@code false} für die Standard-UI-Schrift (z. B. Emoji-Buttons)
     */
    public static JButton compactButton(String symbol, String tooltip, boolean boldFont) {
        JButton button = new JButton(symbol);
        button.setFocusPainted(false);
        if (boldFont) {
            button.setFont(new Font("SansSerif", Font.BOLD, 14));
            button.setMargin(new Insets(2, 6, 2, 6));
        } else {
            button.setMargin(new Insets(2, 4, 2, 4));
        }
        if (tooltip != null) button.setToolTipText(tooltip);
        return button;
    }

    /**
     * Baut eine Gruppe sich gegenseitig ausschließender {@link JRadioButtonMenuItem}s, fügt sie in
     * Reihenfolge zu {@code targetMenu} hinzu und verdrahtet jeden Eintrag mit seinem Listener.
     * Zentralisiert das in GUI mehrfach wiederholte Muster (ButtonGroup anlegen, je Item erzeugen,
     * zu Gruppe UND Menü hinzufügen) - der Aufrufer bekommt die Items als Array zurück, falls er
     * (wie bei Y-Achsen/Fit-Ziel) später programmatisch die Auswahl synchronisieren muss.
     *
     * @param targetMenu     Menü, in das die Items eingehängt werden
     * @param selectedIndex  Index des initial ausgewählten Eintrags, -1 für keinen
     * @param entries        Beschriftung + Listener je Eintrag, in Anzeigereihenfolge
     */
    @SafeVarargs
    public static JRadioButtonMenuItem[] radioMenuGroup(JMenu targetMenu, int selectedIndex,
                                                        Map.Entry<String, ActionListener>... entries) {
        ButtonGroup group = new ButtonGroup();
        JRadioButtonMenuItem[] items = new JRadioButtonMenuItem[entries.length];
        for (int i = 0; i < entries.length; i++) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(entries[i].getKey(), i == selectedIndex);
            item.addActionListener(entries[i].getValue());
            group.add(item);
            targetMenu.add(item);
            items[i] = item;
        }
        return items;
    }
}