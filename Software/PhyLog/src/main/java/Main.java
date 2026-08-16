import javax.swing.*;

public class Main {
    /** Einstiegspunkt der PhyLog-Anwendung. */
    public void main(String args[]) {
        Theme.setup();
        SwingUtilities.invokeLater(() -> new GUI().setVisible(true));
    }
}
