package oldways.launcher;

import oldways.launcher.ui.Background;
import oldways.launcher.ui.Buttons;
import oldways.launcher.ui.Check;
import oldways.launcher.ui.CloseButton;
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
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
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
    private final Glass passwordBox = new Glass(false);
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
    private final JLabel linkDivider;
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
    private final CloseButton settingsClose;

    private final SkinView skinView;
    private final Buttons changeSkinButton;
    private final Buttons changeCapeButton;
    private final Buttons changePasswordButton;
    private final JLabel skinCaption;
    private final JLabel capeCaption;
    private final Link removeCapeLink;
    private final CloseButton accountClose;

    private final JLabel passwordTitle;
    private final JLabel oldPasswordLabel;
    private final JLabel newPasswordLabel;
    private final JLabel repeatPasswordLabel;
    private final Fields.PasswordField oldPasswordField;
    private final Fields.PasswordField newPasswordField;
    private final Fields.PasswordField repeatPasswordField;
    private final Buttons savePasswordButton;
    private final JLabel passwordStatus;
    private final CloseButton passwordClose;
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
        logoutLink = new Link("Выйти", Theme.DANGER, k);
        // В полтора раза крупнее собственного размера ссылки: под широкой
        // кнопкой мелкий текст терялся.
        accountLink.setFont(Theme.font((float) (19.5 * k), false));
        logoutLink.setFont(Theme.font((float) (19.5 * k), false));
        linkDivider = label("|", 19.5f);
        linkDivider.setForeground(new Color(255, 255, 255, 90));
        linkDivider.setHorizontalAlignment(SwingConstants.CENTER);
        greeting = new Greeting(k);
        progress = new ProgressBar(k);
        status = label(" ", 12);

        memory = new MemorySlider(512, 4096, 512, cfg.getInt("memory", 2048), k);
        memoryLabel = label("Выделенная память: " + memory.text(), 14);
        addressLabel = label("Адрес сервера", 14);
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

        settingsClose = new CloseButton(k);

        skinView = new SkinView(k);
        changeSkinButton = Buttons.small("Изменить скин…", k);
        changePasswordButton = Buttons.small("Изменить пароль…", k);
        skinCaption = label(" ", 11);
        skinCaption.setForeground(Theme.TEXT_DIM);
        changeCapeButton = Buttons.small("Изменить плащ…", k);
        capeCaption = label(" ", 11);
        capeCaption.setForeground(Theme.TEXT_DIM);
        removeCapeLink = new Link("убрать", Theme.DANGER, k);
        removeCapeLink.setVisible(false);
        accountClose = new CloseButton(k);

        passwordTitle = label("Смена пароля", 18);
        passwordTitle.setForeground(Color.WHITE);
        passwordTitle.setHorizontalAlignment(SwingConstants.CENTER);
        oldPasswordLabel = label("Текущий пароль:", 14);
        newPasswordLabel = label("Новый пароль:", 14);
        repeatPasswordLabel = label("Ещё раз:", 14);
        oldPasswordField = Fields.password(k, null);
        newPasswordField = Fields.password(k, null);
        repeatPasswordField = Fields.password(k, null);
        savePasswordButton = Buttons.primary("Сменить пароль", k);
        passwordStatus = label(" ", 12);
        passwordStatus.setHorizontalAlignment(SwingConstants.CENTER);
        passwordClose = new CloseButton(k);
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
        loginBox.add(linkDivider);
        loginBox.add(logoutLink);
        loginBox.add(greeting);
        loginBox.add(progress);
        loginBox.add(status);
        progress.setVisible(false);
        status.setHorizontalAlignment(SwingConstants.CENTER);

        settingsBox.add(settingsClose);
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

        accountBox.add(accountClose);
        accountBox.add(skinView);
        accountBox.add(changeSkinButton);
        accountBox.add(skinCaption);
        accountBox.add(changeCapeButton);
        accountBox.add(capeCaption);
        accountBox.add(removeCapeLink);
        accountBox.add(changePasswordButton);
        accountBox.setVisible(false);

        passwordBox.add(passwordClose);
        passwordBox.add(passwordTitle);
        passwordBox.add(oldPasswordLabel);
        passwordBox.add(oldPasswordField);
        passwordBox.add(newPasswordLabel);
        passwordBox.add(newPasswordField);
        passwordBox.add(repeatPasswordLabel);
        passwordBox.add(repeatPasswordField);
        passwordBox.add(savePasswordButton);
        passwordBox.add(passwordStatus);
        passwordBox.setVisible(false);

        // Swing рисует детей от последнего к первому, поэтому добавляем сверху
        // вниз — как складываются слои в макете: кнопки, меню, логотип, окно
        // входа. Логотип нависает над окном входа, но уходит под меню настроек.
        root.add(gear);
        root.add(logButton);
        root.add(folderButton);
        root.add(settingsBox);
        root.add(accountBox);
        root.add(passwordBox);
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
        changeCapeButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onCape();
            }
        });
        removeCapeLink.onClick(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onCapeRemove();
            }
        });
        changePasswordButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onPassword();
            }
        });
        savePasswordButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                submitPassword();
            }
        });
        settingsClose.onClick(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                showPanel("login");
            }
        });
        accountClose.onClick(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                showPanel("login");
            }
        });
        passwordClose.onClick(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                showPanel("account");
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
        restoreSession();
        watchSession();
        checkUpdate();
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

        int passWidth = root.s(440);
        int passHeight = root.s(390);
        passwordBox.setBounds((width - passWidth) / 2, middle - passHeight / 2,
                passWidth, passHeight);

        placeLogin(boxWidth, boxHeight);
        placeSettings(menuWidth, menuHeight);
        placeAccount(cardWidth, cardHeight);
        placePassword(passWidth, passHeight);
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
        // Пара «кто ты — Играть» стоит выше формы входа: так она читается
        // вместе, а ссылки под кнопкой не упираются в нижний край стекла.
        int playWidth = (int) (formWidth * 0.72);
        int playHeight = root.s(50);
        int playTop = y - root.s(50);
        playButton.setBounds((boxWidth - playWidth) / 2, playTop, playWidth, playHeight);

        // Ссылки стоят ровно посередине между кнопкой и нижним краем стекла,
        // разделённые тонкой чертой.
        int linkHeight = root.s(33);
        int linkTop = playTop + playHeight
                + (boxHeight - playTop - playHeight - linkHeight) / 2;
        int linkGap = root.s(14);
        int dividerWidth = root.s(10);
        int accountWidth = accountLink.textWidth() + root.s(18);
        int logoutWidth = logoutLink.textWidth() + root.s(18);
        int linksLeft = (boxWidth - accountWidth - logoutWidth
                - dividerWidth - linkGap * 2) / 2;
        accountLink.setBounds(linksLeft, linkTop, accountWidth, linkHeight);
        linkDivider.setBounds(linksLeft + accountWidth + linkGap, linkTop,
                dividerWidth, linkHeight);
        logoutLink.setBounds(linksLeft + accountWidth + linkGap * 2 + dividerWidth,
                linkTop, logoutWidth, linkHeight);

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
        int close = root.s(26);
        settingsClose.setBounds(menuWidth - root.s(14) - close, root.s(14), close, close);

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
        int close = root.s(26);
        accountClose.setBounds(boxWidth - root.s(14) - close, root.s(14), close, close);
        int previewWidth = root.s(150);
        int previewHeight = boxHeight - pad * 2;
        skinView.setBounds(pad, pad, previewWidth, previewHeight);

        int right = pad + previewWidth + root.s(30);
        int rightWidth = boxWidth - right - pad;
        int buttonHeight = root.s(40);
        int captionHeight = root.s(18);
        int afterCaption = root.s(18);
        int stack = (buttonHeight + root.s(8) + captionHeight + afterCaption) * 2 + buttonHeight;
        int y = pad + (previewHeight - stack) / 2;

        changeSkinButton.setBounds(right, y, rightWidth, buttonHeight);
        y += buttonHeight + root.s(8);
        skinCaption.setBounds(right, y, rightWidth, captionHeight);
        y += captionHeight + afterCaption;

        changeCapeButton.setBounds(right, y, rightWidth, buttonHeight);
        y += buttonHeight + root.s(8);
        // Подпись и «убрать» стоят в одной строке: ссылка появляется, только
        // когда плащ есть, и подпись под неё не переезжает.
        int removeWidth = removeCapeLink.textWidth() + root.s(14);
        capeCaption.setBounds(right, y, rightWidth - removeWidth, captionHeight);
        removeCapeLink.setBounds(right + rightWidth - removeWidth, y,
                removeWidth, captionHeight);
        y += captionHeight + afterCaption;

        changePasswordButton.setBounds(right, y, rightWidth, buttonHeight);
    }

    /** Карточка смены пароля: три поля в столбик, как в форме входа. */
    private void placePassword(int boxWidth, int boxHeight) {
        int close = root.s(26);
        passwordClose.setBounds(boxWidth - root.s(14) - close, root.s(14), close, close);

        int fieldWidth = (int) (boxWidth * 0.8);
        int x = (boxWidth - fieldWidth) / 2;
        int titleHeight = root.s(26);
        int labelHeight = root.s(17);
        int inner = root.s(6);
        int gap = root.s(12);
        int inputHeight = root.s(39);
        int buttonHeight = root.s(42);
        int statusHeight = root.s(20);

        int row = labelHeight + inner + inputHeight;
        int total = titleHeight + root.s(20) + row * 3 + gap * 2
                + root.s(24) + buttonHeight + root.s(10) + statusHeight;
        int y = (boxHeight - total) / 2;

        passwordTitle.setBounds(x, y, fieldWidth, titleHeight);
        y += titleHeight + root.s(20);

        JLabel[] labels = { oldPasswordLabel, newPasswordLabel, repeatPasswordLabel };
        Fields.PasswordField[] fields =
                { oldPasswordField, newPasswordField, repeatPasswordField };
        for (int i = 0; i < labels.length; i++) {
            labels[i].setBounds(x, y, fieldWidth, labelHeight);
            y += labelHeight + inner;
            fields[i].setBounds(x, y, fieldWidth, inputHeight);
            y += inputHeight + (i < labels.length - 1 ? gap : 0);
        }

        y += root.s(24);
        int buttonWidth = (int) (fieldWidth * 0.66);
        savePasswordButton.setBounds((boxWidth - buttonWidth) / 2, y,
                buttonWidth, buttonHeight);
        y += buttonHeight + root.s(10);
        passwordStatus.setBounds(x, y, fieldWidth, statusHeight);
    }

    private void saveSettings() {
        String previous = cfg.get("address", "localhost");
        String now = addressField.getText().trim();
        if (session != null && !previous.equals(now)) {
            // сеанс выдан прежним сервером и на новом не действует
            showSession(null);
            forgetSession();
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
                rememberSession(opened);
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

    /**
     * Кладёт пропуск в настройки, чтобы следующий запуск начался с «вы вошли».
     *
     * Пропуск живёт неделю и хранится в launcher.properties — том же файле, что
     * и остальные настройки, в папке профиля игрока. Это не пароль: его можно
     * погасить кнопкой «Выйти» или сменой пароля, и он привязан к адресу
     * сервера, для которого выдан.
     */
    private void rememberSession(Auth.Session value) {
        cfg.set("session.token", value.token);
        cfg.set("session.address", Address.parse(addressField.getText().trim()).base());
        cfg.save();
    }

    private void forgetSession() {
        cfg.set("session.token", null);
        cfg.set("session.address", null);
        cfg.save();
    }

    /** Проверяет сохранённый пропуск и, если он жив, сразу показывает «вы вошли». */
    private void restoreSession() {
        final String token = cfg.get("session.token", "");
        if (token.isEmpty()) return;
        final String base;
        try {
            base = Address.parse(addressField.getText().trim()).base();
        } catch (RuntimeException e) {
            return;
        }
        if (!base.equals(cfg.get("session.address", ""))) {
            forgetSession();     // пропуск выдан другим сервером
            return;
        }

        say("Проверяю вход…");
        new Thread(new Runnable() {
            public void run() {
                try {
                    final Auth.Session opened = Auth.check(base, token);
                    Log.info("пропуск на месте: %s", opened.username);
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            if (session != null) return;   // успели войти руками
                            userField.setText(opened.username);
                            showSession(opened);
                            say(" ");
                        }
                    });
                } catch (Auth.SessionGone e) {
                    Log.info("пропуск не подошёл: %s", e.getMessage());
                    forgetSession();
                    say(" ");
                } catch (Exception e) {
                    // сервис недоступен — форма входа и так на экране
                    Log.error("не удалось проверить пропуск", e);
                    say(" ");
                }
            }
        }, "session").start();
    }

    /**
     * Присматривает за пропуском, пока окно открыто.
     *
     * На аккаунт живёт один пропуск: стоит войти с другого устройства, и наш
     * перестаёт действовать. Узнать об этом молча, нажав «Играть» посреди
     * вечера, — худшее из решений, поэтому лаунчер спрашивает сервис сам и
     * возвращает окно к форме входа с объяснением.
     *
     * Раз в полминуты: чаще незачем, а реже — игрок успеет забыть, что делал
     * на второй машине.
     */
    /**
     * Обновление лаунчера: спросить сервис и, если там новее, скачать.
     *
     * Молча: игроку показывается только успех, и то одной строкой. Сети может
     * не быть, сервис может быть старым — ни то ни другое не повод мешать
     * человеку играть, поэтому любая ошибка уходит в журнал.
     */
    private void checkUpdate() {
        Updater.forgetBackup();
        Thread thread = new Thread(new Runnable() {
            public void run() {
                try {
                    String base = Address.parse(addressField.getText().trim()).base();
                    final String version = Updater.prepare(base);
                    if (version != null) {
                        say("Обновление " + version + " применится при следующем запуске");
                    }
                } catch (Exception error) {
                    Log.info("обновление не проверено: %s", error);
                }
            }
        }, "update-check");
        thread.setDaemon(true);
        thread.start();
    }

    private void watchSession() {
        Timer watch = new Timer(30000, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                final Auth.Session current = session;
                if (current == null || busy) return;   // нечего или некогда проверять
                new Thread(new Runnable() {
                    public void run() {
                        try {
                            Auth.check(Address.parse(addressField.getText().trim()).base(),
                                    current.token);
                        } catch (final Auth.SessionGone gone) {
                            SwingUtilities.invokeLater(new Runnable() {
                                public void run() {
                                    if (session != current) return;   // уже сменился
                                    Log.info("пропуск отозван: %s", gone.getMessage());
                                    forgetSession();
                                    showPanel("login");
                                    showSession(null);
                                    oops(capitalize(gone.getMessage()) + " — войдите заново");
                                }
                            });
                        } catch (Exception ignored) {
                            // сеть отвалилась — это не повод выкидывать игрока
                        }
                    }
                }, "session-watch").start();
            }
        });
        watch.start();
    }

    /** Сервис отвечает строчными, а у нас это отдельная строка сообщения. */
    private static String capitalize(String text) {
        if (text == null || text.isEmpty()) return "Пропуск больше не действует";
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** Какая карточка на экране: вход, настройки, аккаунт или смена пароля. */
    private void showPanel(String which) {
        boolean settings = "settings".equals(which);
        boolean account = "account".equals(which);
        boolean password = "password".equals(which);
        if (settingsBox.isVisible() && !settings) saveSettings();
        settingsBox.setVisible(settings);
        accountBox.setVisible(account);
        passwordBox.setVisible(password);
        loginBox.setVisible(!settings && !account && !password);
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
        final Auth.Session leaving = session;
        showSession(null);
        passwordField.setText("");
        status.setText(" ");
        forgetSession();
        if (leaving == null) return;
        final String raw = addressField.getText().trim();
        new Thread(new Runnable() {
            public void run() {
                try {
                    Auth.logout(Address.parse(raw).base(), leaving.token);
                } catch (Exception e) {
                    // не достучались — пропуск всё равно стёрт у нас
                    Log.error("не вышло погасить пропуск на сервисе", e);
                }
            }
        }, "logout").start();
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
        linkDivider.setVisible(in);
        if (!in && (accountBox.isVisible() || passwordBox.isVisible())) {
            // вышел — карточкам аккаунта и пароля конец
            accountBox.setVisible(false);
            passwordBox.setVisible(false);
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
        changeCapeButton.setEnabled(false);
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
                    autoConnect.isChecked() ? address : null, base);
            GameRunner.start(cfg, command);
            // Игра живёт сама по себе, а лаунчеру больше нечего делать: он
            // закрывается, чтобы не занимать панель задач и память. Вывод игры
            // с этого мгновения пишется в game.log — по нему разбирают падения.
            Log.info("игра запущена, лаунчер закрывается");
            cfg.save();
            System.exit(0);
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
                    changeCapeButton.setEnabled(true);
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
        final File file = choose("Скин: PNG 64×32, 64×64 или крупнее, до 1024×512");
        if (file == null) return;
        run("Отправляю скин…", new Task() {
            public void go() throws Exception {
                String base = Address.parse(addressField.getText().trim()).base();
                Auth.uploadSkin(base, current.token, textureBytes(file, "скин"));
                cfg.set("skin.file", file.getName());
                cfg.save();
                say("Скин принят: " + file.getName());
                loadSkin();
            }
        });
    }

    private void onCape() {
        final Auth.Session current = session;
        if (current == null) return;
        final File file = choose("Плащ: PNG 64×32 или крупнее, до 1024×512");
        if (file == null) return;
        run("Отправляю плащ…", new Task() {
            public void go() throws Exception {
                String base = Address.parse(addressField.getText().trim()).base();
                Auth.uploadCape(base, current.token, textureBytes(file, "плащ"));
                cfg.set("cape.file", file.getName());
                cfg.save();
                say("Плащ принят: " + file.getName());
                loadSkin();
            }
        });
    }

    private void onCapeRemove() {
        final Auth.Session current = session;
        if (current == null) return;
        run("Убираю плащ…", new Task() {
            public void go() throws Exception {
                String base = Address.parse(addressField.getText().trim()).base();
                Auth.removeCape(base, current.token);
                cfg.set("cape.file", "");
                cfg.save();
                say("Плащ убран");
                loadSkin();
            }
        });
    }

    private File choose(String title) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setFileFilter(new FileNameExtensionFilter("Картинка PNG", "png"));
        return chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION
                ? chooser.getSelectedFile() : null;
    }

    /**
     * Готовит текстуру к отправке.
     *
     * Развёртка 1.6.4 вдвое шире, чем выше: 64x32 и её кратные увеличения
     * до 1024x512 — крупные тянет OptiFine из нашей сборки. Квадратная
     * картинка — это современная развёртка 64x64, где верхняя половина
     * совпадает с классической, поэтому лишнее отрезаем: иначе в игре вышла
     * бы мешанина вместо персонажа.
     */
    private static byte[] textureBytes(File file, String what) throws IOException {
        BufferedImage image = javax.imageio.ImageIO.read(file);
        if (image == null) throw new IOException("это не картинка PNG");
        int width = image.getWidth();
        int height = image.getHeight();
        if (width < 64 || width > 1024 || Integer.bitCount(width) != 1) {
            throw new IOException(what + " должен быть шириной 64, 128, 256, 512 или 1024, а тут "
                    + width + "×" + height);
        }
        if (height == width / 2) return read(file);
        if (height != width) {
            throw new IOException(what + " должен быть вдвое шире, чем выше, а тут "
                    + width + "×" + height);
        }

        BufferedImage classic = new BufferedImage(width, height / 2,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = classic.createGraphics();
        g.drawImage(image, 0, 0, width, height / 2, 0, 0, width, height / 2, null);
        g.dispose();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(classic, "png", out);
        return out.toByteArray();
    }

    /** Тянет скин для предпросмотра: свой или общий — видно по заголовку. */
    /** Тянет с сервера скин и плащ игрока и показывает их на модели. */
    private void loadSkin() {
        final Auth.Session current = session;
        if (current == null) return;
        skinView.setSkin(SkinView.placeholder());
        skinView.setCape(null);
        skinCaption.setText("загружаю…");
        capeCaption.setText(" ");
        new Thread(new Runnable() {
            public void run() {
                String base = Address.parse(addressField.getText().trim()).base();
                String note;
                BufferedImage image = null;
                try {
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

                // Плаща может не быть — сервис отвечает 404, и это обычное
                // дело, а не поломка.
                BufferedImage capeImage = null;
                String capeNote = "плаща нет";
                try {
                    byte[] png = Http.fetch(
                            base + "/MinecraftCloaks/" + current.username + ".png", null, null);
                    capeImage = javax.imageio.ImageIO.read(
                            new java.io.ByteArrayInputStream(png));
                    capeNote = cfg.get("cape.file", "");
                    if (capeNote.isEmpty()) capeNote = "свой плащ";
                } catch (Http.HttpError e) {
                    if (e.code != 404) Log.error("не удалось получить плащ", e);
                } catch (Exception e) {
                    Log.error("не удалось получить плащ", e);
                }

                final BufferedImage ready = image;
                final BufferedImage readyCape = capeImage;
                final String caption = note;
                final String capeText = capeNote;
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        if (ready != null) skinView.setSkin(ready);
                        skinView.setCape(readyCape);
                        skinCaption.setText(caption);
                        capeCaption.setText(capeText);
                        removeCapeLink.setVisible(readyCape != null);
                    }
                });
            }
        }, "skin").start();
    }

    /**
     * Смена пароля — своя карточка, а не системный диалог.
     *
     * Диалог JOptionPane рисуется оформлением Windows: он не знает ни нашего
     * масштаба, ни шрифтов, и на экране с высоким DPI выходил крошечной
     * серой формой посреди окна.
     */
    private void onPassword() {
        if (session == null) return;     // кнопка видна только вошедшему
        oldPasswordField.setText("");
        newPasswordField.setText("");
        repeatPasswordField.setText("");
        passwordStatus.setText(" ");
        showPanel("password");
        oldPasswordField.requestFocusInWindow();
    }

    private void submitPassword() {
        final Auth.Session current = session;
        if (current == null || busy) return;

        final String oldPassword = new String(oldPasswordField.getPassword());
        final String newPassword = new String(newPasswordField.getPassword());
        String repeat = new String(repeatPasswordField.getPassword());
        if (oldPassword.isEmpty() || newPassword.isEmpty()) {
            passwordNote("Заполните все поля", true);
            return;
        }
        if (!newPassword.equals(repeat)) {
            passwordNote("Пароли не совпали", true);
            return;
        }

        passwordNote("Меняю пароль…", false);
        run("Меняю пароль…", new Task() {
            public void go() throws Exception {
                String base = Address.parse(addressField.getText().trim()).base();
                try {
                    Auth.changePassword(base, current.token, oldPassword, newPassword);
                } catch (final Exception e) {
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            passwordNote(Log.describe(e), true);
                        }
                    });
                    throw e;
                }
                forgetSession();
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        // сервис закрывает все сеансы разом, наш в том числе
                        showPanel("login");
                        showSession(null);
                        status.setForeground(Theme.TEXT);
                        status.setText("Пароль изменён — войдите с новым");
                    }
                });
            }
        });
    }

    /** Сообщение внутри карточки пароля: ошибку красным, ход дела обычным. */
    private void passwordNote(String text, boolean bad) {
        passwordStatus.setForeground(bad ? Theme.DANGER : Theme.TEXT);
        // сервис отвечает строчными («старый пароль не подходит»), а строка
        // в карточке — самостоятельное предложение
        if (text != null && !text.isEmpty()) {
            text = Character.toUpperCase(text.charAt(0)) + text.substring(1);
        }
        passwordStatus.setText(text);
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
