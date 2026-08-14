import javax.swing.*;
import java.awt.*;

/**
 * Gemeinsame Basis für einfache Formular-Dialoge (Label-Feld-Zeilen plus Abbrechen/Übernehmen-
 * Buttonleiste): {@link TriggerDialog}, {@link CalibrationDialog} und
 * {@link StandardDeviationDialog}. Bündelt an einer Stelle, was zuvor in jedem dieser Dialoge
 * separat nachgebaut wurde - Karten-Hintergrund ({@link Theme#CARD} statt einfachem
 * {@link Theme#BG}), Innenabstände, Beschriftungsschrift und Button-Reihenfolge. Eine neue
 * Konfigurationszeile braucht dadurch nur noch einen Aufruf von {@link #addRow}, statt eigenes
 * GridBagLayout-Boilerplate.
 *
 * <p>Feste Pixelgrößen ({@code setSize(...)}) sind bei unterschiedlichen Schriftgrößen/Systemen
 * leicht zu klein oder unnötig groß - Subklassen rufen daher am Ende ihres Konstruktors
 * {@link #finishLayout} auf, das per {@link #pack()} die tatsächlich benötigte Größe ermittelt.</p>
 */
abstract class FormDialog extends JDialog {

    /** Karten-Panel mit dem eigentlichen Formularinhalt, siehe {@link #addRow}. */
    protected final RoundedPanel formPanel;

    private final GridBagConstraints gbc = new GridBagConstraints();
    private int row = 0;

    protected FormDialog(Window owner, String title) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        getContentPane().setBackground(Theme.BG);
        setLayout(new BorderLayout());

        formPanel = new RoundedPanel(Theme.CARD, Theme.CARD_ARC);
        formPanel.setLayout(new GridBagLayout());
        formPanel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.weightx = 1.0;

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(Theme.BG);
        wrapper.setBorder(BorderFactory.createEmptyBorder(Theme.SPACING + 4, Theme.SPACING + 4, 0, Theme.SPACING + 4));
        wrapper.add(formPanel, BorderLayout.CENTER);
        add(wrapper, BorderLayout.CENTER);
    }

    /** Fügt eine Zeile mit Text-Label (linke Spalte) und Eingabekomponente (rechte Spalte) hinzu. */
    protected void addRow(String label, JComponent field) {
        addRow(labelComponent(label), field);
    }

    /** Fügt eine Zeile mit beliebiger Label-Komponente (linke Spalte) und Eingabekomponente
     *  (rechte Spalte) hinzu, z. B. für ein bereits speziell formatiertes {@link JLabel}. */
    protected void addRow(JComponent label, JComponent field) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        formPanel.add(label, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        formPanel.add(field, gbc);
        row++;
    }

    /** Dreispaltige Variante von {@link #addRow} für eine zusätzliche Einheiten-/Suffix-Spalte,
     *  siehe {@link CalibrationDialog}. */
    protected void addRow(JComponent label, JComponent field, JComponent suffix) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.5;
        formPanel.add(label, gbc);

        gbc.gridx = 1;
        gbc.weightx = 0.3;
        formPanel.add(field, gbc);

        gbc.gridx = 2;
        gbc.weightx = 0.2;
        formPanel.add(suffix, gbc);
        row++;
    }

    /** Fügt eine Zeile hinzu, die beide Spalten als eine durchgehende Komponente einnimmt, z. B.
     *  eine Checkbox, einen mehrzeiligen Hinweistext oder eine Radio-Button-Gruppe. */
    protected void addFullWidthRow(JComponent component) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        formPanel.add(component, gbc);
        gbc.gridwidth = 1;
        row++;
    }

    /** Erzeugt ein Standard-Formular-Label in der einheitlichen UI-Schrift. */
    protected JLabel labelComponent(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Theme.FONT_UI);
        return label;
    }

    /** Erzeugt ein kleines, gedämpftes Hinweis-Label mit Zeilenumbruch bei {@code wrapWidth}px,
     *  wie es z. B. {@link StandardDeviationDialog} für die Erklärtexte der Sigma-Modi nutzt. */
    protected JLabel hintLabel(String text, int wrapWidth) {
        JLabel label = new JLabel("<html><div style='width:" + wrapWidth + "px;'>" + text + "</div></html>");
        label.setFont(Theme.FONT_HINT);
        label.setForeground(Theme.MUTED);
        return label;
    }

    /**
     * Baut die Buttonleiste (in Aufrufreihenfolge, üblicherweise Abbrechen zuerst) auf, ermittelt
     * per {@link #pack()} die endgültige Fenstergröße und zentriert den Dialog über dem Owner.
     * Muss als letzter Schritt im Konstruktor jeder Subklasse aufgerufen werden.
     */
    protected void finishLayout(JButton... buttons) {
        JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttonBar.setBackground(Theme.BG);
        buttonBar.setBorder(BorderFactory.createEmptyBorder(Theme.SPACING, Theme.SPACING + 4,
                Theme.SPACING + 4, Theme.SPACING + 4));
        for (JButton button : buttons) {
            buttonBar.add(button);
        }
        add(buttonBar, BorderLayout.SOUTH);

        pack();
        setMinimumSize(getPreferredSize());
        setLocationRelativeTo(getOwner());
    }
}
