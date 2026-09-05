package oldways.launcher;

import oldways.launcher.ui.Background;
import oldways.launcher.ui.Buttons;
import oldways.launcher.ui.Check;
import oldways.launcher.ui.Fields;
import oldways.launcher.ui.GearButton;
import oldways.launcher.ui.Glass;
import oldways.launcher.ui.Greeting;
import oldways.launcher.ui.MemorySlider;
import oldways.launcher.ui.ProgressBar;
import oldways.launcher.ui.Segmented;
import oldways.launcher.ui.Theme;

import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;

/**
 * Окно лаунчера по макету ui_template.html.
 *
 * Раскладка повторяет макет в его же единицах (1280x800) и умножается на
 * масштаб окна: на маленьком экране всё сжимается целиком, а не разъезжается.
 * Работа идёт в отдельном потоке, интерфейс трогается только через
 * invokeLater — Swing однопоточен, а загрузка сборки занимает минуты.
 */
final class MainWindow {

    private static final String[] THEME_LABELS = { "День", "Ночь", "Авто" };
    private static final String[] THEME_VALUES = { "day", "night", "auto" };
    private static final double LOGO_ASPECT = 389.0 / 1424.0;

    private final Config cfg;
    private final double k;
    private final JFrame frame = new JFrame("Old Ways");
    private final Background root;
    private final Glass loginBox = new Glass(true);
    private final Glass settingsBox = new Glass(false);
    private final LogoPanel logo = new LogoPanel();
    private final GearButton gear;

    private final JLabel userLabel;
    private final JLabel passwordLabel;
    private final Fields.TextField userField;
    private final Fields.PasswordField passwordField;
    private final Buttons loginButton;
    private final Buttons playButton;
    private final Buttons logoutButton;
    private final Greeting greeting;
    private final ProgressBar progress;
    private final JLabel status;

    private final JLabel memoryLabel;
    private final MemorySlider memory;
    private final JLabel addressLabel;
    private final Fields.TextField addressField;
    private final Check autoConnect;
    private final JLabel themeLabel;
    private final Segmented themeChoice;
    private final Buttons skinButton;
    private final Buttons changePasswordButton;
    private final Buttons logButton;
    private final Buttons folderButton;

    private final JTextArea logArea = new JTextArea();
    private JDialog logDialog;

    private volatile boolean busy;
    private volatile boolean cancelled;
    /** Кто вошёл. null — окно показывает поля логина и пароля. */
    private volatile Auth.Session session;

    private MainWindow(Config cfg) {
        this.cfg = cfg;
        this.k = windowScale();

        root = new Background(Theme.isDay(cfg.get("theme", "night"))) {
            private static final long serialVersionUID = 1L;

            @Override
            protected void layoutContent() {
                place();
            }
        };

        userLabel = label("Логин:", 14);
        passwordLabel = label("Пароль:", 14);
        userField = Fields.text(k, null);
        passwordField = Fields.password(k, null);
        loginButton = Buttons.primary("Войти", k);
        playButton = Buttons.primary("Играть", k);
        logoutButton = Buttons.primary("Выйти", k)
                .accent(Theme.DANGER_BORDER, Theme.DANGER);
        greeting = new Greeting(k);
        progress = new ProgressBar(k);
        status = label(" ", 12);

        memory = new MemorySlider(512, 4096, 512, cfg.getInt("memory", 2048), k);
        memoryLabel = label("Выделенная память: " + memory.text(), 14);
        addressLabel = label("Адрес сервера авторизации", 14);
        addressField = Fields.text(k, "localhost");
        autoConnect = new Check("Подключаться к серверу сразу",
                !"false".equals(cfg.get("autoconnect", "true")), k);
        themeLabel = label("Тема оформления", 14);
        themeChoice = new Segmented(THEME_LABELS, THEME_VALUES, k);
        skinButton = Buttons.small("Загрузить скин…", k);
        changePasswordButton = Buttons.small("Сменить пароль…", k);
        logButton = Buttons.small("Журнал", k);
        folderButton = Buttons.small("Папка игры", k);
        gear = new GearButton("Настройки", k);

        build();
    }

    static void open(final Config cfg) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                new MainWindow(cfg).frame.setVisible(true);
            }
        });
    }

    /**
     * Во сколько раз рисовать макет 1280x800.
     *
     * За основу берётся масштаб интерфейса системы: 96 точек на дюйм — это
     * сто процентов, 144 — полтора раза. Java 8 объявляет себя знающей про DPI,
     * но сама ничего не увеличивает, поэтому на экране 4К при 150% окно без
     * этой поправки выходило втрое мельче соседних программ.
     *
     * Дальше масштаб ужимается так, чтобы окно влезло в рабочую область
     * (без панели задач): на ноутбуке 1920x1080 при тех же 150% полный
     * полуторный размер уже не помещается.
     *
     * Значение можно закрепить руками — ключ scale в launcher.properties.
     */
    private double windowScale() {
        double manual = 0;
        try {
            manual = Double.parseDouble(cfg.get("scale", "0").replace(',', '.'));
        } catch (NumberFormatException ignored) {
            // в настройках чепуха — считаем сами
        }

        double system = Toolkit.getDefaultToolkit().getScreenResolution() / 96.0;
        double wanted = manual > 0 ? manual : system;

        Rectangle work = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        double byWidth = (work.width - 80.0) / Theme.DESIGN_W;
        double byHeight = (work.height - 60.0) / Theme.DESIGN_H;
        double fits = Math.min(byWidth, byHeight);

        Log.info("масштаб окна: система %.2f, помещается %.2f%s",
                system, fits, manual > 0 ? String.format(", задан %.2f", manual) : "");
        return Math.max(0.55, Math.min(wanted, fits));
    }

    private JLabel label(String text, float size) {
        JLabel label = new JLabel(text);
        label.setFont(Theme.font((float) (size * k), false));
        label.setForeground(Theme.TEXT);
        return label;
    }

    // ------------------------------------------------------------ сборка окна

    private void build() {
        loginBox.add(userLabel);
        loginBox.add(userField);
        loginBox.add(passwordLabel);
        loginBox.add(passwordField);
        loginBox.add(loginButton);
        loginBox.add(playButton);
        loginBox.add(logoutButton);
        loginBox.add(greeting);
        loginBox.add(progress);
        loginBox.add(status);
        progress.setVisible(false);

        settingsBox.add(memoryLabel);
        settingsBox.add(memory);
        settingsBox.add(addressLabel);
        settingsBox.add(addressField);
        settingsBox.add(autoConnect);
        settingsBox.add(themeLabel);
        settingsBox.add(themeChoice);
        settingsBox.add(skinButton);
        settingsBox.add(changePasswordButton);
        settingsBox.setVisible(false);

        // Swing рисует детей от последнего к первому, поэтому добавляем сверху
        // вниз — как складываются слои в макете: кнопки, меню, логотип, окно
        // входа. Логотип нависает над окном входа, но уходит под меню настроек.
        root.add(gear);
        root.add(logButton);
        root.add(folderButton);
        root.add(settingsBox);
        root.add(logo);
        root.add(loginBox);

        addressField.setText(cfg.get("address", "localhost"));
        userField.setText(cfg.get("username", ""));
        themeChoice.select(cfg.get("theme", "night"));

        loginButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onLogin();
            }
        });
        playButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onPlay();
            }
        });
        logoutButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onLogout();
            }
        });
        skinButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onSkin();
            }
        });
        changePasswordButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onPassword();
            }
        });
        logButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                toggleLog();
            }
        });
        folderButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                openFolder();
            }
        });
        gear.onClick(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                boolean showing = !settingsBox.isVisible();
                settingsBox.setVisible(showing);
                loginBox.setVisible(!showing);
                if (!showing) saveSettings();
                root.repaint();
            }
        });
        memory.onChange(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                memoryLabel.setText("Выделенная память: " + memory.text());
            }
        });
        themeChoice.onChange(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                cfg.set("theme", themeChoice.selected());
                cfg.save();
                root.setDay(Theme.isDay(themeChoice.selected()));
                logo.repaint();
            }
        });

        Log.listen(new Log.Sink() {
            public void line(final String text) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        logArea.append(text + "\n");
                        logArea.setCaretPosition(logArea.getDocument().getLength());
                    }
                });
            }
        });

        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setContentPane(root);
        root.setPreferredSize(new Dimension(
                (int) (Theme.DESIGN_W * k), (int) (Theme.DESIGN_H * k)));
        showSession(null);
        frame.setResizable(false);
        frame.pack();
        frame.setLocationRelativeTo(null);
        // Значок берём квадратный: широкий логотип в панели задач ужимается
        // в нечитаемую полоску
        BufferedImage icon = Theme.image("icon.png");
        if (icon == null) icon = Theme.logo(root.isDay());
        if (icon != null) frame.setIconImage(icon);
    }

    /** Расстановка по пропорциям макета; вызывается при каждой перекладке. */
    private void place() {
        int width = root.getWidth();
        int height = root.getHeight();
        int middle = (int) Math.round(height * 0.486);   // top: 48.6% из макета

        int boxWidth = root.s(512);                      // width: 40%
        int boxHeight = root.s(308);
        loginBox.setBounds((width - boxWidth) / 2, middle - boxHeight / 2, boxWidth, boxHeight);

        int menuWidth = root.s(563);                     // width: 44%
        // без входа в меню на строку меньше: скин и пароль там ни к чему
        int menuHeight = root.s(skinButton.isVisible() ? 339 : 289);
        settingsBox.setBounds((width - menuWidth) / 2, middle - menuHeight / 2,
                menuWidth, menuHeight);

        int logoWidth = root.s(411);                     // 0.67 * 48%
        int logoHeight = (int) Math.round(logoWidth * LOGO_ASPECT);
        logo.setBounds((width - logoWidth) / 2,
                middle - root.s(140) - (int) (logoHeight * 0.65), logoWidth, logoHeight);

        gear.setBounds(width - root.s(16) - gear.fullWidth(), root.s(16),
                gear.fullWidth(), gear.fullHeight());

        Dimension logSize = logButton.getPreferredSize();
        int bottom = height - root.s(16) - logSize.height;
        logButton.setBounds(width - root.s(16) - logSize.width, bottom,
                logSize.width, logSize.height);
        Dimension folderSize = folderButton.getPreferredSize();
        folderButton.setBounds(width - root.s(24) - logSize.width - folderSize.width,
                bottom, folderSize.width, folderSize.height);

        placeLogin(boxWidth, boxHeight);
        placeSettings(menuWidth, menuHeight);
    }

    private void placeLogin(int boxWidth, int boxHeight) {
        int formWidth = (int) (boxWidth * 0.78);
        int inputWidth = (int) (formWidth * 0.92);
        int inputX = (boxWidth - inputWidth) / 2;

        int labelHeight = root.s(17);
        int gap = root.s(5);
        int inputHeight = root.s(45);
        int betweenFields = root.s(10.5);
        int buttonTop = root.s(24);
        int buttonHeight = root.s(38);
        int buttonWidth = (int) (formWidth * 0.45);

        int formHeight = labelHeight + gap + inputHeight + betweenFields
                + labelHeight + gap + inputHeight + buttonTop + buttonHeight;
        int paddingTop = root.s(40);
        // justify-content: center внутри padding-box плюс transform: translateY(-15px)
        int y = paddingTop + (boxHeight - paddingTop - formHeight) / 2 - root.s(15);

        userLabel.setBounds(inputX, y, inputWidth, labelHeight);
        y += labelHeight + gap;
        userField.setBounds(inputX, y, inputWidth, inputHeight);
        y += inputHeight + betweenFields;
        passwordLabel.setBounds(inputX, y, inputWidth, labelHeight);
        y += labelHeight + gap;
        passwordField.setBounds(inputX, y, inputWidth, inputHeight);
        y += inputHeight + buttonTop;
        loginButton.setBounds((boxWidth - buttonWidth) / 2, y, buttonWidth, buttonHeight);

        // Вошедшему на том же месте — кто он и что дальше: «Играть» и «Выйти»
        // встают в тот же ряд, где была кнопка входа, чтобы окно не прыгало.
        int pairGap = root.s(16);
        int pairWidth = buttonWidth * 2 + pairGap;
        int pairLeft = (boxWidth - pairWidth) / 2;
        playButton.setBounds(pairLeft, y, buttonWidth, buttonHeight);
        logoutButton.setBounds(pairLeft + buttonWidth + pairGap, y, buttonWidth, buttonHeight);

        // Строку ставим не строго посередине, а чуть ниже: сверху над стеклом
        // нависает логотип, и по центру она смотрелась бы прижатой к нему.
        int greetingHeight = root.s(26);
        greeting.setBounds(inputX,
                paddingTop + (int) ((y - paddingTop - greetingHeight) * 0.62),
                inputWidth, greetingHeight);

        progress.setBounds(inputX, boxHeight - root.s(40), inputWidth, root.s(10));
        status.setBounds(inputX, boxHeight - root.s(28), inputWidth, root.s(20));
    }

    private void placeSettings(int menuWidth, int menuHeight) {
        int fieldWidth = (int) (menuWidth * 0.8);
        int x = (menuWidth - fieldWidth) / 2;
        int labelHeight = root.s(17);
        int inner = root.s(8);
        int gap = root.s(20);
        int sliderHeight = root.s(16);
        int inputHeight = root.s(39);
        int rowHeight = root.s(20);
        int choiceHeight = root.s(30);
        int buttonHeight = root.s(30);

        boolean actions = skinButton.isVisible();
        int total = labelHeight + inner + sliderHeight
                + gap + labelHeight + inner + inputHeight
                + gap + rowHeight
                + gap + labelHeight + inner + choiceHeight
                + (actions ? gap + buttonHeight : 0);
        int y = (menuHeight - total) / 2;

        memoryLabel.setBounds(x, y, fieldWidth, labelHeight);
        y += labelHeight + inner;
        memory.setBounds(x, y, fieldWidth, sliderHeight);
        y += sliderHeight + gap;

        addressLabel.setBounds(x, y, fieldWidth, labelHeight);
        y += labelHeight + inner;
        addressField.setBounds(x, y, fieldWidth, inputHeight);
        y += inputHeight + gap;

        autoConnect.setBounds(x, y, fieldWidth, rowHeight);
        y += rowHeight + gap;

        themeLabel.setBounds(x, y, fieldWidth, labelHeight);
        y += labelHeight + inner;
        themeChoice.setBounds(x, y, fieldWidth, choiceHeight);
        y += choiceHeight + gap;

        if (actions) {
            int half = (fieldWidth - root.s(6)) / 2;
            skinButton.setBounds(x, y, half, buttonHeight);
            changePasswordButton.setBounds(x + half + root.s(6), y, half, buttonHeight);
        }
    }

    private void saveSettings() {
        String previous = cfg.get("address", "localhost");
        String now = addressField.getText().trim();
        if (session != null && !previous.equals(now)) {
            // сеанс выдан прежним сервером и на новом не действует
            showSession(null);
            status.setText("Адрес сервера изменён — войдите заново");
        }
        cfg.set("address", now);
        cfg.set("memory", String.valueOf(memory.value()));
        cfg.set("autoconnect", String.valueOf(autoConnect.isChecked()));
        cfg.set("theme", themeChoice.selected());
        cfg.save();
    }

    // -------------------------------------------------------------- действия

    /** Вход: только проверка учётной записи, ничего не качаем. */
    private void onLogin() {
        if (busy) return;
        final String raw = addressField.getText().trim();
        final String user = userField.getText().trim();
        final String password = new String(passwordField.getPassword());
        if (user.isEmpty() || password.isEmpty()) {
            status.setText("Введите логин и пароль");
            return;
        }
        saveSettings();
        cfg.set("username", user);
        cfg.save();

        run("Вхожу…", new Task() {
            public void go() throws Exception {
                final Auth.Session opened =
                        Auth.login(Address.parse(raw).base(), user, password);
                Log.info("вход выполнен: %s", opened.username);
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        passwordField.setText("");   // дальше он не нужен
                        showSession(opened);
                        status.setText(opened.mustChangePassword
                                ? "Пароль совпадает с логином — смените его в настройках"
                                : " ");
                    }
                });
            }
        });
    }

    private void onLogout() {
        if (busy) return;
        showSession(null);
        passwordField.setText("");
        status.setText(" ");
    }

    /**
     * Переключает окно между «войдите» и «вы вошли как …».
     *
     * Скин и смена пароля показываются только вошедшему: раньше эти кнопки
     * были доступны всегда и втихую делали свой собственный вход тем, что
     * набрано в полях, — то есть либо повторяли работу, либо падали в ошибку.
     */
    private void showSession(Auth.Session value) {
        session = value;
        boolean in = value != null;
        userLabel.setVisible(!in);
        userField.setVisible(!in);
        passwordLabel.setVisible(!in);
        passwordField.setVisible(!in);
        loginButton.setVisible(!in);
        greeting.setVisible(in);
        playButton.setVisible(in);
        logoutButton.setVisible(in);
        skinButton.setVisible(in);
        changePasswordButton.setVisible(in);
        if (in) greeting.set("Вы вошли как ", value.username);
        frame.getRootPane().setDefaultButton(in ? playButton : loginButton);
        root.revalidate();
        root.repaint();
    }

    private void onPlay() {
        if (busy) {                       // кнопка во время работы означает «отменить»
            cancelled = true;
            status.setText("Отменяю…");
            return;
        }
        // Сначала настройки: смена адреса там закрывает сеанс, и играть
        // по старому пропуску на новом сервере уже нельзя.
        saveSettings();
        final Auth.Session current = session;
        if (current == null) return;
        if (current.expiresAt * 1000L <= System.currentTimeMillis()) {
            showSession(null);
            status.setText("Сеанс истёк — войдите заново");
            return;
        }

        busy = true;
        cancelled = false;
        playButton.setText("Отмена");
        logoutButton.setEnabled(false);
        skinButton.setEnabled(false);
        changePasswordButton.setEnabled(false);
        progress.setVisible(true);
        progress.setRunning(true);
        status.setText("Проверяю сборку…");

        final String raw = addressField.getText().trim();
        new Thread(new Runnable() {
            public void run() {
                play(raw, current);
            }
        }, "launch").start();
    }

    private void play(String raw, Auth.Session current) {
        try {
            Address address = Address.parse(raw);
            String base = address.base();
            Manifest manifest = Manifest.fetch(base);
            File client = new Installer(cfg, manifest, base, watcher()).run();

            List<String> command = GameRunner.command(cfg, manifest, client, current,
                    autoConnect.isChecked() ? address : null);
            final Process game = GameRunner.start(cfg, command);
            SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    progress.setRunning(false);
                    progress.setVisible(false);
                    status.setText("Игра запущена");
                    // отменять уже нечего: сборка на месте, игра живёт сама
                    playButton.setEnabled(false);
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
                    showLog();
                }
            });
            say("Игра завершилась с ошибкой (код " + code + ") — подробности в журнале");
        } catch (Exception e) {
            Log.error("запуск не удался", e);
            say(Log.describe(e));
        } finally {
            SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    busy = false;
                    playButton.setText("Играть");
                    playButton.setEnabled(true);
                    logoutButton.setEnabled(true);
                    skinButton.setEnabled(true);
                    changePasswordButton.setEnabled(true);
                    progress.setRunning(false);
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
                        progress.setVisible(true);
                        progress.setRunning(true);
                    }
                });
            }

            public void bytes(final long done, final long total) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        progress.setVisible(true);
                        progress.setFraction(total > 0 ? (double) done / total : 0);
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
        final Auth.Session current = session;
        if (current == null) return;     // кнопка видна только вошедшему
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Скин: PNG 64×32 или 64×64");
        chooser.setFileFilter(new FileNameExtensionFilter("Картинка PNG", "png"));
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        final File file = chooser.getSelectedFile();
        run("Отправляю скин…", new Task() {
            public void go() throws Exception {
                String base = Address.parse(addressField.getText().trim()).base();
                Auth.uploadSkin(base, current.token, read(file));
                say("Скин принят: " + file.getName());
            }
        });
    }

    private void onPassword() {
        final Auth.Session current = session;
        if (current == null) return;     // кнопка видна только вошедшему
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
                Auth.changePassword(base, current.token, oldPassword, newPassword);
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        // сервис закрывает все сеансы разом, наш в том числе
                        showSession(null);
                        status.setText("Пароль изменён — войдите с новым");
                    }
                });
            }
        });
    }

    /** Игра лежит в профиле пользователя, поэтому путь туда нужен под рукой. */
    private void openFolder() {
        File dir = cfg.root();
        try {
            Util.mkdirs(dir);
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir);
            } else {
                new ProcessBuilder("explorer.exe", dir.getPath()).start();
            }
            say(dir.getPath());
        } catch (Exception e) {
            Log.error("не открылась папка " + dir, e);
            say("Папка игры: " + dir);
        }
    }

    // ---------------------------------------------------------------- журнал

    private void toggleLog() {
        if (logDialog != null && logDialog.isVisible()) {
            logDialog.setVisible(false);
            return;
        }
        showLog();
    }

    private void showLog() {
        if (logDialog == null) {
            logArea.setEditable(false);
            logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, (int) (12 * k)));
            logArea.setBackground(new Color(22, 22, 22));
            logArea.setForeground(new Color(220, 220, 220));
            logArea.setCaretColor(Color.WHITE);
            JScrollPane pane = new JScrollPane(logArea);
            pane.setBorder(null);
            logDialog = new JDialog(frame, "Журнал", false);
            logDialog.setContentPane(pane);
            logDialog.setSize(root.s(760), root.s(420));
            logDialog.setLocationRelativeTo(frame);
        }
        logDialog.setVisible(true);
    }

    // ------------------------------------------------------------- мелочёвка

    private interface Task {
        void go() throws Exception;
    }

    private void run(final String label, final Task task) {
        if (busy) return;
        busy = true;
        say(label);
        new Thread(new Runnable() {
            public void run() {
                try {
                    task.go();
                } catch (Exception e) {
                    Log.error(label, e);
                    say(Log.describe(e));
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

    private static byte[] read(File file) throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            return Util.readAll(in);
        } finally {
            in.close();
        }
    }

    /** Логотип поверх фона; картинка меняется вместе с темой. */
    private final class LogoPanel extends JComponent {
        private static final long serialVersionUID = 1L;

        @Override
        protected void paintComponent(Graphics graphics) {
            BufferedImage image = Theme.logo(root.isDay());
            if (image == null) return;
            Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
            g.drawImage(image, 0, 0, getWidth(), getHeight(), null);
            g.dispose();
        }
    }
}
