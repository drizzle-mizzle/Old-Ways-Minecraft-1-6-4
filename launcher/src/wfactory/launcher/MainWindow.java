package wfactory.launcher;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;

/**
 * Окно лаунчера.
 *
 * Работа идёт в отдельном потоке, интерфейс трогается только через
 * invokeLater: Swing однопоточен, а загрузка сборки занимает минуты.
 */
final class MainWindow {

    private final Config cfg;
    private final JFrame frame = new JFrame("W-Factory");
    private final JTextField addressField = new JTextField(18);
    private final JTextField userField = new JTextField(18);
    private final JPasswordField passwordField = new JPasswordField(18);
    private final JSpinner memoryField;
    private final JCheckBox connectBox = new JCheckBox("Сразу подключиться к серверу", true);
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private final JLabel status = new JLabel(" ");
    private final JButton playButton = new JButton("Играть");
    private final JButton skinButton = new JButton("Скин…");
    private final JButton passwordButton = new JButton("Пароль…");
    private final JButton logButton = new JButton("Журнал");
    private final JTextArea logArea = new JTextArea(12, 60);
    private final JScrollPane logPane = new JScrollPane(logArea);

    private volatile boolean busy;
    private volatile boolean cancelled;

    private MainWindow(Config cfg) {
        this.cfg = cfg;
        memoryField = new JSpinner(new SpinnerNumberModel(
                cfg.getInt("memory", 1024), 512, 8192, 256));
        build();
    }

    static void open(final Config cfg) {
        theme();
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                new MainWindow(cfg).frame.setVisible(true);
            }
        });
    }

    /** FlatLaf, если он оказался рядом; иначе оформление системы. */
    private static void theme() {
        try {
            UIManager.setLookAndFeel(Class.forName("com.formdev.flatlaf.FlatLightLaf")
                    .asSubclass(javax.swing.LookAndFeel.class).newInstance());
            return;
        } catch (Throwable ignored) {
            // FlatLaf не вложен в раздачу — не беда
        }
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // останется оформление по умолчанию
        }
    }

    // ------------------------------------------------------------ сборка окна

    private void build() {
        JLabel title = new JLabel("W-Factory");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        JLabel subtitle = new JLabel("Minecraft 1.6.4 — города и прокачка");
        subtitle.setFont(subtitle.getFont().deriveFont(Font.PLAIN, 12f));

        JPanel head = new JPanel();
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        head.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 16));
        title.setAlignmentX(0f);
        subtitle.setAlignmentX(0f);
        head.add(title);
        head.add(subtitle);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(0, 16, 4, 16));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 0, 4, 8);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0; c.gridy = 0;
        form.add(new JLabel("Адрес сервера"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;
        form.add(addressField, c);
        c.gridx = 0; c.gridy = 1; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        form.add(new JLabel("Ник"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;
        form.add(userField, c);
        c.gridx = 0; c.gridy = 2; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        form.add(new JLabel("Пароль"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;
        form.add(passwordField, c);

        JPanel memory = new JPanel();
        memory.setLayout(new BoxLayout(memory, BoxLayout.X_AXIS));
        memory.add(new JLabel("Память "));
        memoryField.setMaximumSize(new Dimension(90, 26));
        memory.add(memoryField);
        memory.add(new JLabel(" МБ"));
        memory.add(Box.createHorizontalStrut(16));
        memory.add(connectBox);
        c.gridx = 1; c.gridy = 3;
        form.add(memory, c);

        progress.setVisible(false);
        progress.setStringPainted(false);
        status.setFont(status.getFont().deriveFont(Font.PLAIN, 12f));

        JPanel middle = new JPanel();
        middle.setLayout(new BoxLayout(middle, BoxLayout.Y_AXIS));
        middle.setBorder(BorderFactory.createEmptyBorder(6, 16, 6, 16));
        progress.setAlignmentX(0f);
        status.setAlignmentX(0f);
        middle.add(progress);
        middle.add(Box.createVerticalStrut(4));
        middle.add(status);

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setBorder(BorderFactory.createEmptyBorder(4, 16, 14, 16));
        playButton.setFont(playButton.getFont().deriveFont(Font.BOLD, 14f));
        buttons.add(playButton);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(skinButton);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(passwordButton);
        buttons.add(Box.createHorizontalGlue());
        buttons.add(logButton);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        logPane.setVisible(false);
        logPane.setBorder(BorderFactory.createEmptyBorder(0, 16, 12, 16));

        JPanel content = new JPanel();
        content.setLayout(new BorderLayout());
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(head);
        top.add(form);
        top.add(middle);
        top.add(buttons);
        content.add(top, BorderLayout.NORTH);
        content.add(logPane, BorderLayout.CENTER);

        addressField.setText(cfg.get("address", "localhost"));
        userField.setText(cfg.get("username", ""));
        connectBox.setSelected(!"false".equals(cfg.get("autoconnect", "true")));

        playButton.addActionListener(e -> onPlay());
        skinButton.addActionListener(e -> onSkin());
        passwordButton.addActionListener(e -> onPassword());
        logButton.addActionListener(e -> toggleLog());
        frame.getRootPane().setDefaultButton(playButton);

        Log.listen(text -> SwingUtilities.invokeLater(() -> {
            logArea.append(text + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        }));

        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setContentPane(content);
        frame.pack();
        frame.setMinimumSize(frame.getSize());
        frame.setLocationRelativeTo(null);
    }

    private void toggleLog() {
        logPane.setVisible(!logPane.isVisible());
        frame.pack();
    }

    // -------------------------------------------------------------- действия

    private void onPlay() {
        if (busy) {                       // кнопка во время работы означает «отменить»
            cancelled = true;
            status.setText("Отменяю…");
            return;
        }
        final String raw = addressField.getText().trim();
        final String user = userField.getText().trim();
        final String password = new String(passwordField.getPassword());
        if (user.isEmpty() || password.isEmpty()) {
            status.setText("Введите ник и пароль");
            return;
        }
        cfg.set("address", raw);
        cfg.set("username", user);
        cfg.set("memory", String.valueOf(memoryField.getValue()));
        cfg.set("autoconnect", String.valueOf(connectBox.isSelected()));
        cfg.save();

        busy = true;
        cancelled = false;
        playButton.setText("Отмена");
        skinButton.setEnabled(false);
        passwordButton.setEnabled(false);
        progress.setVisible(true);
        progress.setIndeterminate(true);
        status.setText("Соединяюсь с сервером…");

        new Thread(new Runnable() {
            public void run() {
                play(raw, user, password);
            }
        }, "launch").start();
    }

    private void play(String raw, String user, String password) {
        try {
            Address address = Address.parse(raw);
            String base = address.base();
            Auth.Session session = Auth.login(base, user, password);
            Log.info("вход выполнен: %s", session.username);
            if (session.mustChangePassword) {
                say("Пароль совпадает с ником — смените его кнопкой «Пароль…»");
            }

            Manifest manifest = Manifest.fetch(base);
            File client = new Installer(cfg, manifest, base, watcher()).run();

            List<String> command = GameRunner.command(cfg, manifest, client, session,
                    connectBox.isSelected() ? address : null);
            final Process game = GameRunner.start(cfg, command);
            SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    progress.setIndeterminate(false);
                    progress.setVisible(false);
                    status.setText("Игра запущена");
                    frame.setExtendedState(JFrame.ICONIFIED);
                }
            });
            int code = game.waitFor();
            Log.info("игра завершилась с кодом %d", code);
            if (code == 0) {
                System.exit(0);
            }
            SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    frame.setExtendedState(JFrame.NORMAL);
                    logPane.setVisible(true);
                    frame.pack();
                }
            });
            fail("Игра завершилась с ошибкой (код " + code + ") — подробности в журнале");
        } catch (Exception e) {
            Log.error("запуск не удался", e);
            fail(Log.describe(e));
        } finally {
            SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    busy = false;
                    playButton.setText("Играть");
                    skinButton.setEnabled(true);
                    passwordButton.setEnabled(true);
                    progress.setIndeterminate(false);
                    progress.setVisible(false);
                }
            });
        }
    }

    private Installer.Watcher watcher() {
        return new Installer.Watcher() {
            public void stage(final String text) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        status.setText(text);
                        progress.setIndeterminate(true);
                    }
                });
            }

            public void bytes(final long done, final long total) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        progress.setIndeterminate(false);
                        progress.setValue(total > 0 ? (int) (done * 1000 / total) : 0);
                        status.setText("Качаю: " + Util.size(done) + " из " + Util.size(total));
                    }
                });
            }

            public boolean cancelled() {
                return cancelled;
            }
        };
    }

    private void onSkin() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Скин: PNG 64×32 или 64×64");
        chooser.setFileFilter(new FileNameExtensionFilter("Картинка PNG", "png"));
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        final File file = chooser.getSelectedFile();
        final String password = new String(passwordField.getPassword());
        if (password.isEmpty()) {
            status.setText("Для смены скина введите пароль");
            return;
        }
        run("Отправляю скин…", new Task() {
            public void go() throws Exception {
                String base = Address.parse(addressField.getText().trim()).base();
                Auth.Session session = Auth.login(base, userField.getText().trim(), password);
                byte[] png = read(file);
                Auth.uploadSkin(base, session.token, png);
                say("Скин принят: " + file.getName());
            }
        });
    }

    private void onPassword() {
        JPasswordField oldField = new JPasswordField(16);
        JPasswordField newField = new JPasswordField(16);
        JPasswordField repeatField = new JPasswordField(16);
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 0, 3, 6);
        c.anchor = GridBagConstraints.WEST;
        String[] labels = { "Текущий пароль", "Новый пароль", "Ещё раз" };
        JComponent[] fields = { oldField, newField, repeatField };
        for (int i = 0; i < labels.length; i++) {
            c.gridx = 0; c.gridy = i;
            panel.add(new JLabel(labels[i]), c);
            c.gridx = 1;
            panel.add(fields[i], c);
        }
        int answer = JOptionPane.showConfirmDialog(frame, panel, "Смена пароля",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;

        final String oldPassword = new String(oldField.getPassword());
        final String newPassword = new String(newField.getPassword());
        if (!newPassword.equals(new String(repeatField.getPassword()))) {
            say("Пароли не совпали");
            return;
        }
        run("Меняю пароль…", new Task() {
            public void go() throws Exception {
                String base = Address.parse(addressField.getText().trim()).base();
                Auth.Session session = Auth.login(base, userField.getText().trim(), oldPassword);
                Auth.changePassword(base, session.token, oldPassword, newPassword);
                say("Пароль изменён, войдите с новым");
            }
        });
    }

    // ------------------------------------------------------------- мелочёвка

    private interface Task {
        void go() throws Exception;
    }

    private void run(final String label, final Task task) {
        if (busy) return;
        busy = true;
        status.setText(label);
        new Thread(new Runnable() {
            public void run() {
                try {
                    task.go();
                } catch (Exception e) {
                    Log.error(label, e);
                    fail(Log.describe(e));
                } finally {
                    busy = false;
                }
            }
        }, "task").start();
    }

    private void say(final String text) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                status.setText(text);
            }
        });
    }

    private void fail(final String text) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                status.setText(text);
            }
        });
    }

    private static byte[] read(File file) throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            return Util.readAll(in);
        } finally {
            in.close();
        }
    }
}
