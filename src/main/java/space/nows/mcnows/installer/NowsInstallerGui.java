package space.nows.mcnows.installer;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.UIManager;

public final class NowsInstallerGui {
    private final JFrame frame = new JFrame("Nows Installer");
    private final JTextField nowsVersion = new JTextField(NowsInstaller.defaultNowsVersion());
    private final JTextField minecraftVersion = new JTextField(NowsInstaller.defaultMinecraftVersion());
    private final JTextField minecraftDir = new JTextField(defaultMinecraftDir());
    private final JTextField manifest = new JTextField();
    private final JTextField artifactDir = new JTextField();
    private final JCheckBox offline = new JCheckBox("Offline install");
    private final JCheckBox profileGameDir = new JCheckBox("Use profile-local game folder");
    private final JButton install = new JButton("Install");
    private final JTextArea log = new JTextArea();

    private NowsInstallerGui() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(720, 520));
        frame.setLayout(new BorderLayout(12, 12));
        frame.add(form(), BorderLayout.NORTH);
        frame.add(logPane(), BorderLayout.CENTER);
        frame.add(actions(), BorderLayout.SOUTH);
        frame.setLocationRelativeTo(null);
    }

    public static void main(String[] args) {
        EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception ignored) {
                    // Swing falls back to the default look and feel.
                }
                new NowsInstallerGui().show();
            }
        });
    }

    private void show() {
        frame.setVisible(true);
    }

    private JPanel form() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(javax.swing.BorderFactory.createEmptyBorder(14, 14, 0, 14));

        addRow(panel, 0, "Nows version", nowsVersion, null);
        addRow(panel, 1, "Minecraft version", minecraftVersion, null);
        addRow(panel, 2, "Minecraft dir", minecraftDir, browseDirectoryButton(minecraftDir));
        addRow(panel, 3, "Manifest", manifest, browseFileButton(manifest));
        addRow(panel, 4, "Artifact dir", artifactDir, browseDirectoryButton(artifactDir));

        GridBagConstraints gbc = constraints(5, 1);
        gbc.gridwidth = 2;
        panel.add(offline, gbc);
        GridBagConstraints profileGbc = constraints(6, 1);
        profileGbc.gridwidth = 2;
        panel.add(profileGameDir, profileGbc);
        return panel;
    }

    private JScrollPane logPane() {
        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        JScrollPane pane = new JScrollPane(log);
        pane.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 14, 0, 14));
        return pane;
    }

    private JPanel actions() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 14, 14, 14));
        install.addActionListener(event -> runInstall());
        panel.add(install, BorderLayout.EAST);
        return panel;
    }

    private void runInstall() {
        install.setEnabled(false);
        log.setText("");
        final String[] args = installerArgs();

        new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() throws Exception {
                NowsInstaller.install(NowsInstaller.Options.parse(args), new NowsInstaller.InstallerListener() {
                    @Override
                    public void log(String message) {
                        publish(message);
                    }
                });
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String line : chunks) {
                    append(line);
                }
            }

            @Override
            protected void done() {
                install.setEnabled(true);
                try {
                    get();
                    append("[NowsInstaller] Done");
                    JOptionPane.showMessageDialog(frame, "Nows installation finished.");
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    append("[NowsInstaller] Failed: " + cause.getMessage());
                    JOptionPane.showMessageDialog(frame, cause.getMessage(), "Install failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private String[] installerArgs() {
        List<String> args = new ArrayList<>();
        addOption(args, "--nows", nowsVersion.getText());
        addOption(args, "--minecraft", minecraftVersion.getText());
        addOption(args, "--minecraftDir", minecraftDir.getText());
        addOption(args, "--manifest", manifest.getText());
        addOption(args, "--artifactDir", artifactDir.getText());
        if (offline.isSelected()) {
            args.add("--offline");
        }
        if (profileGameDir.isSelected()) {
            args.add("--profileGameDir");
        }
        return args.toArray(new String[0]);
    }

    private static void addOption(List<String> args, String name, String value) {
        if (value != null && !value.trim().isEmpty()) {
            args.add(name);
            args.add(value.trim());
        }
    }

    private void append(String line) {
        log.append(line);
        log.append(System.lineSeparator());
        log.setCaretPosition(log.getDocument().getLength());
    }

    private static void addRow(JPanel panel, int row, String label, JTextField field, JButton button) {
        GridBagConstraints labelConstraints = constraints(row, 0);
        labelConstraints.weightx = 0;
        panel.add(new JLabel(label), labelConstraints);

        GridBagConstraints fieldConstraints = constraints(row, 1);
        fieldConstraints.weightx = 1;
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, fieldConstraints);

        if (button != null) {
            GridBagConstraints buttonConstraints = constraints(row, 2);
            buttonConstraints.weightx = 0;
            panel.add(button, buttonConstraints);
        }
    }

    private static GridBagConstraints constraints(int row, int column) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = column;
        gbc.gridy = row;
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        return gbc;
    }

    private JButton browseDirectoryButton(final JTextField target) {
        JButton button = new JButton("Browse");
        button.addActionListener(event -> choose(target, JFileChooser.DIRECTORIES_ONLY));
        return button;
    }

    private JButton browseFileButton(final JTextField target) {
        JButton button = new JButton("Browse");
        button.addActionListener(event -> choose(target, JFileChooser.FILES_ONLY));
        return button;
    }

    private void choose(JTextField target, int mode) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(mode);
        if (target.getText() != null && !target.getText().trim().isEmpty()) {
            chooser.setSelectedFile(new File(target.getText().trim()));
        }
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            target.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private static String defaultMinecraftDir() {
        return NowsInstaller.defaultMinecraftDir().toString();
    }
}
