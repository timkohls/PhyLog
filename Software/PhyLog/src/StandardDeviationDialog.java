import javax.swing.*;
import java.awt.*;

/**
 * Dialog zum Einstellen der Messunsicherheit sigma, die {@link ChartPanel} für Chi²-Berechnung
 * und Toleranzband verwendet. Bietet neben dem bisherigen konstanten, manuell eingegebenen Wert
 * zwei automatische Alternativen an (siehe {@link ChartPanel.SigmaMode}):
 * <ul>
 *   <li><b>Konstant</b>: ein einziger, vom Nutzer eingegebener Wert für alle Punkte
 *       (bisheriges Verhalten).</li>
 *   <li><b>Automatisch, global</b>: sigma wird aus der Streuung der Messwerte um den aktuellen
 *       Fit geschätzt (empirische Standardabweichung der Residuen) - sinnvoll, wenn die
 *       tatsächliche Messunsicherheit nicht bekannt ist, aber gaußverteiltes Rauschen
 *       angenommen werden kann.</li>
 *   <li><b>Automatisch, lokal</b>: wie global, aber je Punkt aus dessen nächsten Nachbarn
 *       geschätzt (Nearest-Neighbour) - passt Toleranzband und Chi² an ungleichmäßig verteiltes
 *       Rauschen entlang der Messreihe an, statt eine einzige Streuung für den gesamten
 *       Datensatz anzunehmen.</li>
 * </ul>
 * Beide automatischen Modi setzen einen aktiven Funktions-Fit voraus; ohne Fit verwendet
 * {@link ChartPanel} intern den konstanten Wert als Rückfallebene (siehe dortige Dokumentation).
 */
public class StandardDeviationDialog extends JDialog {

    private final JRadioButton rbConstant;
    private final JRadioButton rbAutoGlobal;
    private final JRadioButton rbAutoLocal;
    private final JTextField tfValue;
    private final JSpinner spNeighbors;

    private boolean confirmed = false;
    private double standardDeviation;
    private ChartPanel.SigmaMode sigmaMode;
    private int localSigmaNeighbors;

    /**
     * @param parent            Eigentümerfenster
     * @param currentVal        aktuell geltender konstanter Wert (auch Rückfallebene für die
     *                          automatischen Modi ohne aktiven Fit)
     * @param currentMode       aktuell geltender {@link ChartPanel.SigmaMode}
     * @param currentNeighbors  aktuell geltende Nachbarn-Anzahl für den lokalen Modus
     */
    public StandardDeviationDialog(JFrame parent, double currentVal, ChartPanel.SigmaMode currentMode, int currentNeighbors) {
        super(parent, "Standardabweichung einstellen", true);
        setSize(560, 420);
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

        // --- Automatisch, global ---
        rbAutoGlobal = new JRadioButton("Automatisch \u2013 global aus Fit-Residuen");
        group.add(rbAutoGlobal);
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2;
        formPanel.add(rbAutoGlobal, gbc);

        JLabel lblAutoGlobalHint = hintLabel("Ein Wert für alle Punkte, geschätzt aus der Streuung der Messwerte um den aktuellen Fit (nimmt gaußverteiltes Rauschen an).");
        gbc.gridy = 3;
        formPanel.add(lblAutoGlobalHint, gbc);

        // --- Automatisch, lokal ---
        rbAutoLocal = new JRadioButton("Automatisch \u2013 lokal (nächste Nachbarn)");
        group.add(rbAutoLocal);
        gbc.gridy = 4;
        formPanel.add(rbAutoLocal, gbc);

        JLabel lblAutoLocalHint = hintLabel("Ein eigener Wert je Punkt, aus dessen nächsten Nachbarn geschätzt - passt sich ungleichmäßig verteiltem Rauschen entlang der Messreihe an.");
        gbc.gridy = 5;
        formPanel.add(lblAutoLocalHint, gbc);

        gbc.gridwidth = 1;
        gbc.gridx = 0; gbc.gridy = 6;
        formPanel.add(new JLabel("     Nachbarn (k):"), gbc);
        spNeighbors = new JSpinner(new SpinnerNumberModel(Math.max(2, currentNeighbors), 2, 100, 1));
        gbc.gridx = 1;
        formPanel.add(spNeighbors, gbc);

        gbc.gridwidth = 2;
        gbc.gridx = 0; gbc.gridy = 7;
        JLabel lblFallback = hintLabel("Beide automatischen Modi benötigen einen Funktions-Fit; ohne Fit gilt der konstante Wert.");
        lblFallback.setForeground(Theme.ACCENT);
        formPanel.add(lblFallback, gbc);

        add(formPanel, BorderLayout.CENTER);

        // --- Vorbelegung ---
        sigmaMode = (currentMode != null) ? currentMode : ChartPanel.SigmaMode.CONSTANT;
        switch (sigmaMode) {
            case RESIDUAL_GLOBAL -> rbAutoGlobal.setSelected(true);
            case RESIDUAL_LOCAL -> rbAutoLocal.setSelected(true);
            default -> rbConstant.setSelected(true);
        }
        updateFieldStates();

        rbConstant.addActionListener(e -> updateFieldStates());
        rbAutoGlobal.addActionListener(e -> updateFieldStates());
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

    private JLabel hintLabel(String text) {
        JLabel label = new JLabel("<html><div style='width:380px;'>" + text + "</div></html>");
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
        return label;
    }

    /** Aktiviert Wertfeld bzw. Nachbarn-Spinner nur passend zum gewählten Modus. */
    private void updateFieldStates() {
        tfValue.setEnabled(rbConstant.isSelected());
        spNeighbors.setEnabled(rbAutoLocal.isSelected());
    }

    /** Validiert den konstanten Wert (immer, da er auch als Rückfallebene der automatischen
     *  Modi ohne aktiven Fit dient) und übernimmt bei Erfolg Modus, Wert und Nachbarn-Anzahl. */
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
        sigmaMode = rbAutoGlobal.isSelected() ? ChartPanel.SigmaMode.RESIDUAL_GLOBAL
                : rbAutoLocal.isSelected() ? ChartPanel.SigmaMode.RESIDUAL_LOCAL
                : ChartPanel.SigmaMode.CONSTANT;

        confirmed = true;
        dispose();
    }

    /** @return {@code true}, wenn über "Übernehmen" mit gültigem Wert bestätigt wurde */
    public boolean isConfirmed() {
        return confirmed;
    }

    /** @return der bestätigte konstante Wert bzw. dessen Rückfallebene (nur gültig, wenn {@link #isConfirmed()}) */
    public double getStandardDeviation() {
        return standardDeviation;
    }

    /** @return der bestätigte {@link ChartPanel.SigmaMode} (nur gültig, wenn {@link #isConfirmed()}) */
    public ChartPanel.SigmaMode getSigmaMode() {
        return sigmaMode;
    }

    /** @return die bestätigte Nachbarn-Anzahl für den lokalen Modus (nur gültig, wenn {@link #isConfirmed()}) */
    public int getLocalSigmaNeighbors() {
        return localSigmaNeighbors;
    }
}