package oldways.launcher.ui;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

/**
 * Оформление окна: картинки, шрифты, цвета и матовое стекло.
 *
 * Размеры везде задаются в единицах макета 1280x800 и умножаются на масштаб
 * окна — так раскладка повторяет макет на любом экране, включая ноутбучные,
 * куда полный размер не влезает.
 *
 * Шрифт макета — Chakra Petch, но в нём нет кириллицы: в браузере русские
 * подписи молча рисовались системным шрифтом. Вместо него вложен Play
 * (тоже Google Fonts, OFL): та же техно-квадратная манера, но с кириллицей.
 */
public final class Theme {

    public static final int DESIGN_W = 1280;
    public static final int DESIGN_H = 800;

    // Цвета макета. Прозрачность там задана в rgba(), здесь — четвёртым числом.
    public static final Color GLASS_FILL     = new Color(22, 22, 22, 204);   // 0.8
    public static final Color MENU_FILL      = new Color(22, 22, 22, 255);   // меню без прозрачности
    public static final Color GLASS_TOP      = new Color(255, 255, 255, 31); // 0.12
    public static final Color GLASS_BOTTOM   = new Color(70, 100, 95, 20);   // 0.08
    public static final Color BORDER         = new Color(255, 255, 255, 46); // 0.18
    public static final Color INNER_LIGHT    = new Color(255, 255, 255, 38); // 0.15
    public static final Color TEXT           = new Color(255, 255, 255, 217);// 0.85
    public static final Color TEXT_DIM       = new Color(255, 255, 255, 102);// 0.4
    public static final Color FIELD_FILL     = new Color(255, 255, 255, 31); // 0.12
    public static final Color FIELD_BORDER   = new Color(255, 255, 255, 64); // 0.25
    public static final Color FIELD_FOCUS    = new Color(255, 255, 255, 153);// 0.6
    public static final Color BUTTON_FILL    = new Color(255, 255, 255, 41); // 0.16
    public static final Color BUTTON_HOVER   = new Color(255, 255, 255, 71); // 0.28
    public static final Color BUTTON_BORDER  = new Color(255, 255, 255, 77); // 0.3
    public static final Color CHOICE_FILL    = new Color(255, 255, 255, 20); // 0.08
    public static final Color CHOICE_PICKED  = new Color(255, 255, 255, 71); // 0.28
    public static final Color CHOICE_BORDER  = new Color(255, 255, 255, 128);// 0.5
    public static final Color GEAR_FILL      = new Color(74, 74, 74);        // #4a4a4a
    public static final Color SHADOW         = new Color(0, 0, 0, 89);       // 0.35

    private static Font regular;
    private static Font bold;
    private static final Map<String, BufferedImage> IMAGES = new HashMap<String, BufferedImage>();

    private Theme() {}

    // ---------------------------------------------------------------- шрифт

    public static Font font(float size, boolean heavy) {
        load();
        Font base = heavy ? bold : regular;
        Map<TextAttribute, Object> attributes = new HashMap<TextAttribute, Object>();
        attributes.put(TextAttribute.TRACKING, 0.02);   // letter-spacing: 0.02em
        return base.deriveFont(size).deriveFont(attributes);
    }

    private static synchronized void load() {
        if (regular != null) return;
        regular = read("Play-Regular.ttf", Font.PLAIN);
        bold = read("Play-Bold.ttf", Font.BOLD);
    }

    private static Font read(String name, int fallbackStyle) {
        try {
            InputStream in = Theme.class.getResourceAsStream(name);
            if (in != null) {
                try {
                    return Font.createFont(Font.TRUETYPE_FONT, in);
                } finally {
                    in.close();
                }
            }
        } catch (Exception ignored) {
            // шрифт не вложен или система его не приняла — обойдёмся системным
        }
        return new Font(Font.SANS_SERIF, fallbackStyle, 12);
    }

    // -------------------------------------------------------------- картинки

    public static BufferedImage image(String name) {
        BufferedImage cached = IMAGES.get(name);
        if (cached != null) return cached;
        try {
            InputStream in = Theme.class.getResourceAsStream(name);
            if (in == null) return null;
            try {
                BufferedImage image = ImageIO.read(in);
                IMAGES.put(name, image);
                return image;
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** День сейчас или ночь: для темы «Авто». */
    public static boolean daytime() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hour >= 7 && hour < 19;
    }

    public static boolean isDay(String theme) {
        if ("day".equals(theme)) return true;
        if ("night".equals(theme)) return false;
        return daytime();
    }

    public static BufferedImage background(boolean day) {
        return image(day ? "bg_day.jpg" : "bg_night.jpg");
    }

    public static BufferedImage logo(boolean day) {
        return image(day ? "logo_day.png" : "logo_night.png");
    }

    // ------------------------------------------------------------ обработка

    /** Масштабирует картинку под окно как CSS background-size: cover. */
    public static BufferedImage cover(BufferedImage source, int width, int height) {
        if (source == null || width <= 0 || height <= 0) return null;
        double scale = Math.max((double) width / source.getWidth(),
                (double) height / source.getHeight());
        int w = (int) Math.ceil(source.getWidth() * scale);
        int h = (int) Math.ceil(source.getHeight() * scale);
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, (width - w) / 2, (height - h) / 2, w, h, null);
        g.dispose();
        return out;
    }

    /**
     * Матовое стекло: размытие плюс подъём насыщенности — то же, что делает
     * backdrop-filter: blur(5px) saturate(160%) в макете.
     *
     * Размытие — три прохода коробочного фильтра: три коробки на глаз
     * неотличимы от гауссова, а считаются линейно от радиуса.
     */
    public static BufferedImage frost(BufferedImage source, int radius, double saturation) {
        if (source == null) return null;
        int width = source.getWidth();
        int height = source.getHeight();
        int[] pixels = source.getRGB(0, 0, width, height, null, 0, width);
        int[] scratch = new int[pixels.length];
        for (int pass = 0; pass < 3; pass++) {
            blurRows(pixels, scratch, width, height, radius);
            blurRows(scratch, pixels, height, width, radius);   // тот же проход по столбцам
        }
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
            int grey = (r * 77 + g * 151 + b * 28) >> 8;
            r = clamp(grey + (int) ((r - grey) * saturation));
            g = clamp(grey + (int) ((g - grey) * saturation));
            b = clamp(grey + (int) ((b - grey) * saturation));
            pixels[i] = 0xff000000 | (r << 16) | (g << 8) | b;
        }
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, width, height, pixels, 0, width);
        return out;
    }

    /** Горизонтальное размытие с поворотом на выходе: второй вызов даёт вертикальное. */
    private static void blurRows(int[] in, int[] out, int width, int height, int radius) {
        int window = radius + radius + 1;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            int r = 0, g = 0, b = 0;
            for (int i = -radius; i <= radius; i++) {
                int p = in[row + clampIndex(i, width)];
                r += (p >> 16) & 0xff;
                g += (p >> 8) & 0xff;
                b += p & 0xff;
            }
            for (int x = 0; x < width; x++) {
                out[x * height + y] = 0xff000000
                        | ((r / window) << 16) | ((g / window) << 8) | (b / window);
                int add = in[row + clampIndex(x + radius + 1, width)];
                int drop = in[row + clampIndex(x - radius, width)];
                r += ((add >> 16) & 0xff) - ((drop >> 16) & 0xff);
                g += ((add >> 8) & 0xff) - ((drop >> 8) & 0xff);
                b += (add & 0xff) - (drop & 0xff);
            }
        }
    }

    private static int clampIndex(int value, int limit) {
        return value < 0 ? 0 : (value >= limit ? limit - 1 : value);
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : (value > 255 ? 255 : value);
    }

    // -------------------------------------------------------------- рисование

    public static Graphics2D smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                RenderingHints.VALUE_STROKE_PURE);
        // на увеличении фона и логотипа бикубическая заметно чище билинейной,
        // а рисуем мы их считанные разы за перерисовку
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        return g;
    }

    /** Мягкая тень под панелью: box-shadow 0 12px 20px из макета. */
    public static void shadow(Graphics2D g, int x, int y, int width, int height,
                              int radius, double scale) {
        int drop = (int) Math.round(12 * scale);
        int spread = (int) Math.round(20 * scale);
        for (int i = spread; i > 0; i -= 2) {
            int alpha = (int) (SHADOW.getAlpha() * (1.0 - (double) i / spread) / 6);
            if (alpha <= 0) continue;
            g.setColor(new Color(0, 0, 0, alpha));
            g.fillRoundRect(x - i / 2, y + drop - i / 2, width + i, height + i,
                    radius + i, radius + i);
        }
    }
}
