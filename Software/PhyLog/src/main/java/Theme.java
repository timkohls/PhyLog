import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.util.Map;

/** Farbpalette, Typografie und Look-and-Feel-Konfiguration der Anwendung. */
public class Theme {

    public static final Color BG = new Color(37, 37, 37);
    public static final Color PANEL = new Color(48, 48, 48);
    public static final Color CARD = new Color(45, 45, 45);
    public static final Color BORDER = new Color(68, 68, 68);
    public static final Color TEXT = new Color(235, 235, 235);
    public static final Color MUTED = new Color(160, 160, 160);
    public static final Color ACCENT = new Color(82, 140, 255);
    public static final Color POINT_A = new Color(255, 170, 90);
    public static final Color POINT_B = new Color(46, 204, 113);

    public static final Color SUCCESS = new Color(46, 204, 113);
    public static final Color WARNING = new Color(241, 196, 15);
    public static final Color DANGER = new Color(231, 76, 60);

    public static final Font FONT_TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 15);
    public static final Font FONT_UI = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    public static final Font FONT_UI_BOLD = new Font(Font.SANS_SERIF, Font.BOLD, 13);
    public static final Font FONT_HINT = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
    public static final Font FONT_MONO = new Font(Font.MONOSPACED, Font.PLAIN, 12);

    public static final int CARD_ARC = 12;
    public static final int SPACING = 10;

    /** Einheitlicher "Panel-mit-Titel"-Rahmen für die Haupt-Arbeitsflächen. */
    public static javax.swing.border.Border titledPanelBorder(String title) {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(
                        BorderFactory.createLineBorder(BORDER), title, 0, 0, FONT_UI_BOLD, TEXT),
                BorderFactory.createEmptyBorder(4, 6, 6, 6));
    }

    /** Initialisiert das Dark-Theme und setzt die UI-Defaults. */
    public static void setup() {
        FlatDarkLaf.setup();

        UIManager.put("Component.arc", 15);
        UIManager.put("Button.arc", 15);
        UIManager.put("TextComponent.arc", 15);
        UIManager.put("ScrollBar.thumbArc", 999);

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

    /** Kompakter Symbol-/Icon-Button (Zoom, Port-Refresh, ...) mit einheitlichem Margin und
     *  ohne Fokus-Rahmen.
     *
     * @param boldFont {@code true} für größere, fette Schrift, {@code false} für Standard-UI-Schrift
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
     * Baut eine Gruppe sich gegenseitig ausschließender {@link JRadioButtonMenuItem}s, fügt sie
     * in Reihenfolge zu {@code targetMenu} hinzu und verdrahtet jeden Eintrag mit seinem Listener.
     *
     * @param selectedIndex Index des initial ausgewählten Eintrags, -1 für keinen
     * @param entries       Beschriftung + Listener je Eintrag, in Anzeigereihenfolge
     * @return die erzeugten Items, z. B. um die Auswahl später programmatisch zu synchronisieren
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
