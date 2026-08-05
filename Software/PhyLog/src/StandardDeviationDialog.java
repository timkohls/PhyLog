import javax.swing.*;
import java.awt.*;

/**
 * Dialog zur Konfiguration der Messunsicherheit (Standardabweichung/Sigma).
 */
public class StandardDeviationDialog extends JDialog {

    private final JRadioButton rbConstant;
    private final JRadioButton rbAutoGaussian;
    private final JRadioButton rbAutoLocal;
    private final JTextField tfValue;
    private final JSpinner spNeighbors;

    private boolean confirmed = false;
    private double standardDeviation;
    private GoodnessOfFit.SigmaMode sigmaMode;
    private int localSigmaNeighbors;

    /**
     * Erstellt den Dialog zur Einstellung der Standardabweichung.
     *
     * @param parent           Das übergeordnete Fenster.
     * @param currentVal       Aktuell geltender konstanter Wert.
     * @param currentMode      Aktuell geltender {@link GoodnessOfFit.SigmaMode}.
     * @param currentNeighbors Aktuelle Anzahl an Nachbarn für den lokalen Modus.
     */
    public StandardDeviationDialog(JFrame parent, double currentVal, GoodnessOfFit.SigmaMode currentMode, int currentNeighbors) {
        super(parent, "Standardabweichung einstellen", true);
        setSize(560, 400);
        setLocationRelativeTo(parent);
        setLayout(new BorderLayout(10, 10));

        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 5, 15));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.weightx = 1.0;

        ButtonGroup group = new ButtonGroup();

        // --- Konstant ---
        rbConstant = new JRadioButton("Konstant (manueller Wert)");
        group.add(rbConstant);
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        formPanel.add(rbConstant, gbc);

        gbc.gridwidth = 1;
        gbc.gridx = 0; gbc.gridy = 1;
        formPanel.add(new JLabel("     Wert (s):"), gbc);
        tfValue = new JTextField(String.valueOf(currentVal));
        gbc.gridx = 1;
        formPanel.add(tfValue, gbc);

        // --- Automatisch, Gauß-gewichtet ---
        rbAutoGaussian = new JRadioButton("Automatisch \u2013 gewichtet (Gau\u00df-Kernel)");
        group.add(rbAutoGaussian);
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2;
        formPanel.add(rbAutoGaussian, gbc);

        JLabel lblAutoGaussianHint = hintLabel("Wie 'lokal', aber mit weichem statt hartem Übergang: nahe Punkte gehen stärker, entfernte schwächer ein (Gewicht nimmt mit dem Abstand gaußförmig ab) - dadurch verläuft die Fehlerbreite glatt statt stufig, auch bei ungleichmäßig verteilten Messpunkten.");
        gbc.gridy = 3;
        formPanel.add(lblAutoGaussianHint, gbc);

        // --- Automatisch, lokal (hartes Fenster) ---
        rbAutoLocal = new JRadioButton("Automatisch \u2013 lokal, hartes Fenster (nächste Nachbarn)");
        group.add(rbAutoLocal);
        gbc.gridy = 4;
        formPanel.add(rbAutoLocal, gbc);

        JLabel lblAutoLocalHint = hintLabel("Ein eigener Wert je Punkt, aus dessen nächsten Nachbarn geschätzt - passt sich ungleichmäßig verteiltem Rauschen entlang der Messreihe an.");
        gbc.gridy = 5;
        formPanel.add(lblAutoLocalHint, gbc);

        gbc.gridwidth = 1;
        gbc.gridx = 0; gbc.gridy = 6;
        formPanel.add(new JLabel("     Nachbarschaftsgröße (k):"), gbc);
        spNeighbors = new JSpinner(new SpinnerNumberModel(Math.max(2, currentNeighbors), 2, 100, 1));
        gbc.gridx = 1;
        formPanel.add(spNeighbors, gbc);

        gbc.gridwidth = 2;
        gbc.gridx = 0; gbc.gridy = 7;
        JLabel lblFallback = hintLabel("Alle automatischen Modi benötigen einen Funktions-Fit; ohne Fit gilt der konstante Wert.");
        lblFallback.setForeground(Theme.ACCENT);
        formPanel.add(lblFallback, gbc);

        add(formPanel, BorderLayout.CENTER);

        // --- Vorbelegung ---
        sigmaMode = (currentMode != null) ? currentMode : GoodnessOfFit.SigmaMode.CONSTANT;
        switch (sigmaMode) {
            case RESIDUAL_LOCAL_GAUSSIAN -> rbAutoGaussian.setSelected(true);
            case RESIDUAL_LOCAL -> rbAutoLocal.setSelected(true);
            default -> rbConstant.setSelected(true);
        }
        updateFieldStates();

        rbConstant.addActionListener(e -> updateFieldStates());
        rbAutoGaussian.addActionListener(e -> updateFieldStates());
        rbAutoLocal.addActionListener(e -> updateFieldStates());

        // --- Buttons ---
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton btnOk = new JButton("Übernehmen");
        JButton btnCancel = new JButton("Abbrechen");

        btnOk.addActionListener(e -> tryApplyAndClose());
        btnCancel.addActionListener(e -> dispose());

        btnPanel.add(btnCancel);
        btnPanel.add(btnOk);
        add(btnPanel, BorderLayout.SOUTH);
    }

    /**
     * Erstellt ein formatiertes Hinweis-Label.
     *
     * @param text Der Hinweistext.
     * @return Das Hinweis-Label.
     */
    private JLabel hintLabel(String text) {
        JLabel label = new JLabel("<html><div style='width:380px;'>" + text + "</div></html>");
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
        return label;
    }

    /**
     * Aktiviert oder deaktiviert Eingabefelder passend zum gewählten Modus.
     */
    private void updateFieldStates() {
        tfValue.setEnabled(rbConstant.isSelected());
        spNeighbors.setEnabled(rbAutoLocal.isSelected() || rbAutoGaussian.isSelected());
    }

    /**
     * Validiert die Eingaben und schließt den Dialog bei Erfolg.
     */
    private void tryApplyAndClose() {
        double val;
        try {
            val = Double.parseDouble(tfValue.getText().replace(",", "."));
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Bitte geben Sie eine gültige Zahl ein.", "Ungültige Eingabe", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (val < 0) {
            JOptionPane.showMessageDialog(this, "Der Wert darf nicht negativ sein.", "Fehler", JOptionPane.ERROR_MESSAGE);
            return;
        }

        standardDeviation = val;
        localSigmaNeighbors = (int) spNeighbors.getValue();
        sigmaMode = rbAutoGaussian.isSelected() ? GoodnessOfFit.SigmaMode.RESIDUAL_LOCAL_GAUSSIAN
                : rbAutoLocal.isSelected() ? GoodnessOfFit.SigmaMode.RESIDUAL_LOCAL
                : GoodnessOfFit.SigmaMode.CONSTANT;

        confirmed = true;
        dispose();
    }

    /**
     * Gibt zurück, ob die Eingaben bestätigt wurden.
     *
     * @return {@code true}, wenn mit "Übernehmen" bestätigt wurde.
     */
    public boolean isConfirmed() {
        return confirmed;
    }

    /**
     * Gibt den eingestellten konstanten Sigma-Wert bzw. die Rückfallebene zurück.
     *
     * @return Der Sigma-Wert.
     */
    public double getStandardDeviation() {
        return standardDeviation;
    }

    /**
     * Gibt den gewählten Sigma-Berechnungsmodus zurück.
     *
     * @return Der {@link GoodnessOfFit.SigmaMode}.
     */
    public GoodnessOfFit.SigmaMode getSigmaMode() {
        return sigmaMode;
    }

    /**
     * Gibt die Anzahl der Nachbarn für den lokalen Modus zurück.
     *
     * @return Die Anzahl der Nachbarn.
     */
    public int getLocalSigmaNeighbors() {
        return localSigmaNeighbors;
    }
}
