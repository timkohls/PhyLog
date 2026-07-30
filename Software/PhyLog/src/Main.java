import javax.swing.*;

/** Einstiegspunkt der PhyLog-Anwendung. */
public class Main {

    /** Richtet das Dark-Theme ein und öffnet das Hauptfenster auf dem EDT. */
    public static void main(String[] args) {
        Theme.setup();
        SwingUtilities.invokeLater(() -> new GUI().setVisible(true));
    }
}
