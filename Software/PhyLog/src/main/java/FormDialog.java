import javax.swing.*;
import java.awt.*;

/**
 * Gemeinsame Basis für Formular-Dialoge (Label-Feld-Zeilen plus Abbrechen/Übernehmen-Buttonleiste).
 */
abstract class FormDialog extends JDialog {

    /** Karten-Panel mit dem Formularinhalt, siehe {@link #addRow}. */
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

    /** Fügt eine Zeile mit Text-Label und Eingabekomponente hinzu. */
    protected void addRow(String label, JComponent field) {
        addRow(labelComponent(label), field);
    }

    /** Fügt eine Zeile mit beliebiger Label-Komponente und Eingabekomponente hinzu. */
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

    /** Dreispaltige Variante von {@link #addRow} mit zusätzlicher Einheiten-/Suffix-Spalte. */
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

    /** Fügt eine Zeile hinzu, die beide Spalten als eine durchgehende Komponente einnimmt. */
    protected void addFullWidthRow(JComponent component) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        formPanel.add(component, gbc);
        gbc.gridwidth = 1;
        row++;
    }

    protected JLabel labelComponent(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Theme.FONT_UI);
        return label;
    }

    /** Erzeugt ein kleines, gedämpftes Hinweis-Label mit Zeilenumbruch bei {@code wrapWidth}px. */
    protected JLabel hintLabel(String text, int wrapWidth) {
        JLabel label = new JLabel("<html><div style='width:" + wrapWidth + "px;'>" + text + "</div></html>");
        label.setFont(Theme.FONT_HINT);
        label.setForeground(Theme.MUTED);
        return label;
    }

    /**
     * Baut die Buttonleiste auf, ermittelt per {@link #pack()} die Fenstergröße und zentriert den
     * Dialog über dem Owner. Muss als letzter Schritt im Konstruktor jeder Subklasse aufgerufen werden.
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
