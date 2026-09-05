package oldways.launcher;

import oldways.launcher.ui.Background;
import oldways.launcher.ui.Buttons;
import oldways.launcher.ui.Check;
import oldways.launcher.ui.Fields;
import oldways.launcher.ui.GearButton;
import oldways.launcher.ui.Glass;
import oldways.launcher.ui.Greeting;
import oldways.launcher.ui.Link;
import oldways.launcher.ui.MemorySlider;
import oldways.launcher.ui.ProgressBar;
import oldways.launcher.ui.Segmented;
import oldways.launcher.ui.SkinView;
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
import javax.swing.SwingConstants;
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
    private final Glass accountBox = new Glass(false);
    private final LogoPanel logo = new LogoPanel();
    private final GearButton gear;

    private final JLabel userLabel;
    private final JLabel passwordLabel;
    private final Fields.TextField userField;
    private final Fields.PasswordField passwordField;
    private final Buttons loginButton;
    private final Buttons playButton;
    private final Link accountLink;
    private final Link logoutLink;
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
    private final JLabel sizeLabel;
    private final Fields.TextField widthField;
    private final Fields.TextField heightField;
    private final JLabel sizeCross;
    private final Check fullscreen;

    private final SkinView skinView;
    private final Buttons changeSkinButton;
    private final Buttons changePasswordButton;
    private final JLabel skinCaption;
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
        playButton.setFont(Theme.font((float) (20 * k), true));
        accountLink = new Link("Аккаунт", java.awt.Color.WHITE, k);
        logoutLink = new Link("Выход", Theme.DANGER, k);
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
        sizeLabel = label("Размер окна игры", 14);
        widthField = Fields.text(k, "1280");
        heightField = Fields.text(k, "720");
        sizeCross = label("×", 14);
        sizeCross.setHorizontalAlignment(SwingConstants.CENTER);
        fullscreen = new Check("Полный экран",
                "true".equals(cfg.get("game.fullscreen", "false")), k);

        skinView = new SkinView(k);
        changeSkinButton = Buttons.small("Изменить скин…", k);
        changePasswordButton = Buttons.small("Изменить пароль…", k);
        skinCaption = label(" ", 11);
        skinCaption.setForeground(Theme.TEXT_DIM);
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
        loginBox.add(accountLink);
        loginBox.add(logoutLink);
        loginBox.add(greeting);
        loginBox.add(progress);
        loginBox.add(status);
        progress.setVisible(false);
        status.setHorizontalAlignment(SwingConstants.CENTER);

        settingsBox.add(memoryLabel);
        settingsBox.add(memory);
        settingsBox.add(addressLabel);
        settingsBox.add(addressField);
        settingsBox.add(autoConnect);
        settingsBox.add(themeLabel);
        settingsBox.add(themeChoice);
        settingsBox.add(sizeLabel);
        settingsBox.add(widthField);
        settingsBox.add(sizeCross);
        settingsBox.add(heightField);
        settingsBox.add(fullscreen);
        settingsBox.setVisible(false);

        accountBox.add(skinView);
        accountBox.add(changeSkinButton);
        accountBox.add(skinCaption);
        accountBox.add(changePasswordButton);
        accountBox.setVisible(false);

        // Swing рисует детей от последнего к первому, поэтому добавляем сверху
        // вниз — как складываются слои в макете: кнопки, меню, логотип, окно
        // входа. Логотип нависает над окном входа, но уходит под меню настроек.
        root.add(gear);
        root.add(logButton);
        root.add(folderButton);
        root.add(settingsBox);
        root.add(accountBox);
        root.add(logo);
        root.add(loginBox);

        addressField.setText(cfg.get("address", "localhost"));
        userField.setText(cfg.get("username", ""));
        themeChoice.select(cfg.get("theme", "night"));
        widthField.setText(cfg.get("game.width", ""));
        heightField.setText(cfg.get("game.height", ""));
        tintPlay();

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
        logoutLink.onClick(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onLogout();
            }
        });
        accountLink.onClick(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                showPanel(accountBox.isVisible() ? "login" : "account");
            }
        });
        changeSkinButton.addActionListener(new ActionListener() {
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
                showPanel(settingsBox.isVisible() ? "login" : "settings");
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
                tintPlay();
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
        int menuHeight = root.s(430);
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

        int cardWidth = root.s(563);
        int cardHeight = root.s(339);
        accountBox.setBounds((width - cardWidth) / 2, middle - cardHeight / 2,
                cardWidth, cardHeight);

        placeLogin(boxWidth, boxHeight);
        placeSettings(menuWidth, menuHeight);
        placeAccount(cardWidth, cardHeight);
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

        // Вошедшему на том же месте — кто он и одна широкая кнопка, а под ней
        // две ссылки помельче: главное действие в окне должно быть ровно одно.
        int playWidth = (int) (formWidth * 0.72);
        int playHeight = root.s(50);
        int playTop = y - root.s(8);
        playButton.setBounds((boxWidth - playWidth) / 2, playTop, playWidth, playHeight);

        int linkHeight = root.s(22);
        int linkTop = playTop + playHeight + root.s(10);
        int linkGap = root.s(28);
        int accountWidth = accountLink.textWidth() + root.s(14);
        int logoutWidth = logoutLink.textWidth() + root.s(14);
        int linksLeft = (boxWidth - accountWidth - logoutWidth - linkGap) / 2;
        accountLink.setBounds(linksLeft, linkTop, accountWidth, linkHeight);
        logoutLink.setBounds(linksLeft + accountWidth + linkGap, linkTop,
                logoutWidth, linkHeight);

        // Строку ставим не строго посередине, а чуть ниже: сверху над стеклом
        // нависает логотип, и по центру она смотрелась бы прижатой к нему.
        int greetingHeight = root.s(44);
        greeting.setBounds(inputX,
                paddingTop + (int) ((playTop - paddingTop - greetingHeight) * 0.55),
                inputWidth, greetingHeight);

        // Сообщение снизу: по центру и на два процента высоты выше края.
        int lift = (int) (boxHeight * 0.02);
        progress.setBounds(inputX, boxHeight - root.s(40) - lift, inputWidth, root.s(10));
        status.setBounds(inputX, boxHeight - root.s(28) - lift, inputWidth, root.s(20));
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

        int total = labelHeight + inner + sliderHeight
                + gap + labelHeight + inner + inputHeight
                + gap + rowHeight
                + gap + labelHeight + inner + inputHeight
                + gap + rowHeight
                + gap + labelHeight + inner + choiceHeight;
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

        sizeLabel.setBounds(x, y, fieldWidth, labelHeight);
        y += labelHeight + inner;
        int cross = root.s(30);
        int half = (fieldWidth - cross) / 2;
        widthField.setBounds(x, y, half, inputHeight);
        sizeCross.setBounds(x + half, y, cross, inputHeight);
        heightField.setBounds(x + half + cross, y, half, inputHeight);
        y += inputHeight + gap;

        fullscreen.setBounds(x, y, fieldWidth, rowHeight);
        y += rowHeight + gap;

        themeLabel.setBounds(x, y, fieldWidth, labelHeight);
        y += labelHeight + inner;
        themeChoice.setBounds(x, y, fieldWidth, choiceHeight);
    }

    /**
     * Карточка аккаунта: слева вращается игрок в своём скине, справа — что
     * с этим скином и паролем можно сделать.
     */
    private void placeAccount(int boxWidth, int boxHeight) {
        int pad = root.s(28);
        int previewWidth = root.s(150);
        int previewHeight = boxHeight - pad * 2;
        skinView.setBounds(pad, pad, previewWidth, previewHeight);

        int right = pad + previewWidth + root.s(30);
        int rightWidth = boxWidth - right - pad;
        int buttonHeight = root.s(40);
        int captionHeight = root.s(18);
        int stack = buttonHeight + root.s(10) + captionHeight + root.s(26) + buttonHeight;
        int y = pad + (previewHeight - stack) / 2;

        changeSkinButton.setBounds(right, y, rightWidth, buttonHeight);
        y += buttonHeight + root.s(10);
        skinCaption.setBounds(right, y, rightWidth, captionHeight);
        y += captionHeight + root.s(26);
        changePasswordButton.setBounds(right, y, rightWidth, buttonHeight);
    }

    private void saveSettings() {
        String previous = cfg.get("address", "localhost");
        String now = addressField.getText().trim();
        if (session != null && !previous.equals(now)) {
            // сеанс выдан прежним сервером и на новом не действует
            showSession(null);
            oops("Адрес сервера изменён — войдите заново");
        }
        cfg.set("address", now);
        cfg.set("memory", String.valueOf(memory.value()));
        cfg.set("autoconnect", String.valueOf(autoConnect.isChecked()));
        cfg.set("theme", themeChoice.selected());
        cfg.set("game.fullscreen", String.valueOf(fullscreen.isChecked()));
        cfg.set("game.width", digits(widthField.getText()));
        cfg.set("game.height", digits(heightField.getText()));
        cfg.save();
    }

    /** Оставляет от введённого только цифры: пустое значение вернёт размер по DPI. */
    private static String digits(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (Character.isDigit(text.charAt(i))) out.append(text.charAt(i));
        }
        return out.toString();
    }

    // -------------------------------------------------------------- действия

    /** Вход: только проверка учётной записи, ничего не качаем. */
    private void onLogin() {
        if (busy) return;
        final String raw = addressField.getText().trim();
        final String user = userField.getText().trim();
        final String password = new String(passwordField.getPassword());
        if (user.isEmpty() || password.isEmpty()) {
            oops("Введите логин и пароль");
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
                        status.setForeground(Theme.TEXT);
                        status.setText(" ");
                    }
                });
            }
        });
    }

    /** Какая из трёх карточек на экране: вход, настройки или аккаунт. */
    private void showPanel(String which) {
        boolean settings = "settings".equals(which);
        boolean account = "account".equals(which);
        if (settingsBox.isVisible() && !settings) saveSettings();
        settingsBox.setVisible(settings);
        accountBox.setVisible(account);
        loginBox.setVisible(!settings && !account);
        skinView.spin(account);
        if (account) loadSkin();
        root.repaint();
    }

    /** «Играть» подкрашивается под время суток: салатовый днём, голубой ночью. */
    private void tintPlay() {
        playButton.tint(root.isDay() ? Theme.PLAY_DAY : Theme.PLAY_NIGHT);
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
        accountLink.setVisible(in);
        logoutLink.setVisible(in);
        if (!in && accountBox.isVisible()) {   // вышел — карточке аккаунта конец
            accountBox.setVisible(false);
            skinView.spin(false);
            loginBox.setVisible(true);
        }
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
            oops("Сеанс истёк — войдите заново");
            return;
        }

        busy = true;
        cancelled = false;
        playButton.setText("Отмена");
        logoutLink.setVisible(false);
        accountLink.setVisible(false);
        changeSkinButton.setEnabled(false);
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
            oops("Игра завершилась с ошибкой (код " + code + ") — подробности в журнале");
        } catch (Exception e) {
            Log.error("запуск не удался", e);
            oops(Log.describe(e));
        } finally {
            SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    busy = false;
                    playButton.setText("Играть");
                    playButton.setEnabled(true);
                    logoutLink.setVisible(true);
                    accountLink.setVisible(true);
                    changeSkinButton.setEnabled(true);
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
                Auth.uploadSkin(base, current.token, skinBytes(file));
                cfg.set("skin.file", file.getName());
                cfg.save();
                say("Скин принят: " + file.getName());
                loadSkin();
            }
        });
    }

    /**
     * Готовит скин к отправке.
     *
     * Игра 1.6.4 понимает только старую развёртку 64x32. Современные скины
     * приходят 64x64, и верхняя их половина — ровно та же классическая
     * раскладка, поэтому лишнее просто отрезаем: иначе игрок увидел бы
     * в игре мешанину вместо своего персонажа.
     */
    private static byte[] skinBytes(File file) throws IOException {
        BufferedImage image = javax.imageio.ImageIO.read(file);
        if (image == null) throw new IOException("это не картинка PNG");
        if (image.getWidth() != 64 || (image.getHeight() != 32 && image.getHeight() != 64)) {
            throw new IOException("скин должен быть 64×32 или 64×64, а тут "
                    + image.getWidth() + "×" + image.getHeight());
        }
        if (image.getHeight() == 32) return read(file);

        BufferedImage classic = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = classic.createGraphics();
        g.drawImage(image, 0, 0, 64, 32, 0, 0, 64, 32, null);
        g.dispose();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(classic, "png", out);
        return out.toByteArray();
    }

    /** Тянет скин для предпросмотра: свой или общий — видно по заголовку. */
    private void loadSkin() {
        final Auth.Session current = session;
        if (current == null) return;
        skinView.setSkin(SkinView.placeholder());
        skinCaption.setText("загружаю…");
        new Thread(new Runnable() {
            public void run() {
                String note;
                BufferedImage image = null;
                try {
                    String base = Address.parse(addressField.getText().trim()).base();
                    String[] kind = new String[1];
                    byte[] png = Http.fetch(
                            base + "/MinecraftSkins/" + current.username + ".png",
                            "X-Skin", kind);
                    image = javax.imageio.ImageIO.read(
                            new java.io.ByteArrayInputStream(png));
                    note = "default".equals(kind[0])
                            ? "стандартный скин сервера"
                            : cfg.get("skin.file", "свой скин");
                } catch (Exception e) {
                    Log.error("не удалось получить скин", e);
                    note = "скин не получен";
                }
                final BufferedImage ready = image;
                final String caption = note;
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        if (ready != null) skinView.setSkin(ready);
                        skinCaption.setText(caption);
                    }
                });
            }
        }, "skin").start();
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
            oops("Пароли не совпали");
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
                    oops(Log.describe(e));
                } finally {
                    busy = false;
                }
            }
        }, "task").start();
    }

    private void say(String text) {
        message(text, false);
    }

    /** То же, но красным: ошибку видно сразу, читать строку не нужно. */
    private void oops(String text) {
        message(text, true);
    }

    private void message(final String text, final boolean bad) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                status.setForeground(bad ? Theme.DANGER : Theme.TEXT);
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
