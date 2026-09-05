package oldways.launcher.ui;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Вращающийся игрок в своём скине.
 *
 * Рисуется своими силами, без OpenGL. Проекция ортографическая, поэтому
 * каждая грань коробки превращается на экране в параллелограмм, а Java2D
 * умеет рисовать картинку по такому преобразованию — получается настоящая
 * натянутая текстура, а не набор плоских прямоугольников. Порядок вывода —
 * художника: грани сортируются по глубине, невидимые сзади отбрасываются.
 *
 * Модель классическая: голова, тело, две руки, две ноги и шапка вторым слоем.
 * Скин 64x32 — старая развёртка, левые конечности зеркалят правые; 64x64 —
 * у них свои области. Игра 1.6.4 понимает только первый формат, но в
 * предпросмотре поддержаны оба: так видно, что именно уедет на сервер.
 */
public class SkinView extends JComponent {

    private static final long serialVersionUID = 1L;

    /** Часть тела: размеры и положение в точках скина, ноги стоят на нуле. */
    private static final class Part {
        final float w, h, d, x, y, z;
        final int u, v;
        final boolean mirror;
        final float grow;

        Part(float w, float h, float d, float x, float y, float z,
             int u, int v, boolean mirror, float grow) {
            this.w = w; this.h = h; this.d = d;
            this.x = x; this.y = y; this.z = z;
            this.u = u; this.v = v;
            this.mirror = mirror;
            this.grow = grow;
        }
    }

    private static final Part[] CLASSIC = {
        new Part(8, 8, 8,   0, 28, 0,   0, 0,  false, 0),     // голова
        new Part(8, 12, 4,  0, 18, 0,  16, 16, false, 0),     // тело
        new Part(4, 12, 4, -6, 18, 0,  40, 16, false, 0),     // правая рука
        new Part(4, 12, 4,  6, 18, 0,  40, 16, true,  0),     // левая — зеркало
        new Part(4, 12, 4, -2,  6, 0,   0, 16, false, 0),     // правая нога
        new Part(4, 12, 4,  2,  6, 0,   0, 16, true,  0),     // левая — зеркало
        new Part(8, 8, 8,   0, 28, 0,  32, 0,  false, 0.5f),  // шапка
    };

    /** У скина 64x64 левые конечности и накидки нарисованы отдельно. */
    private static final Part[] MODERN = {
        new Part(8, 8, 8,   0, 28, 0,   0, 0,  false, 0),
        new Part(8, 12, 4,  0, 18, 0,  16, 16, false, 0),
        new Part(4, 12, 4, -6, 18, 0,  40, 16, false, 0),
        new Part(4, 12, 4,  6, 18, 0,  32, 48, false, 0),
        new Part(4, 12, 4, -2,  6, 0,   0, 16, false, 0),
        new Part(4, 12, 4,  2,  6, 0,  16, 48, false, 0),
        new Part(8, 8, 8,   0, 28, 0,  32, 0,  false, 0.5f),  // шапка
        new Part(8, 12, 4,  0, 18, 0,  16, 32, false, 0.25f), // куртка
        new Part(4, 12, 4, -6, 18, 0,  40, 32, false, 0.25f),
        new Part(4, 12, 4,  6, 18, 0,  48, 48, false, 0.25f),
        new Part(4, 12, 4, -2,  6, 0,   0, 32, false, 0.25f),
        new Part(4, 12, 4,  2,  6, 0,   0, 48, false, 0.25f),
    };

    private static final double PITCH = Math.toRadians(9);

    private final double scale;
    private final Timer timer;
    private BufferedImage skin;
    private double angle = Math.toRadians(200);   // начинаем вполоборота

    public SkinView(double scale) {
        this.scale = scale;
        this.timer = new Timer(40, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                angle += Math.toRadians(1.2);
                repaint();
            }
        });
    }

    public void setSkin(BufferedImage skin) {
        this.skin = skin;
        repaint();
    }

    public void spin(boolean on) {
        if (on) timer.start();
        else timer.stop();
    }

    // ------------------------------------------------------------ рисование

    /** Грань в экранных координатах: три угла, кусок текстуры и глубина. */
    private static final class Face {
        double[] origin, along, down;
        int tu, tv, tw, th;
        double depth;
    }

    private double[] rotate(double x, double y, double z) {
        double cos = Math.cos(angle), sin = Math.sin(angle);
        double rx = x * cos + z * sin;
        double rz = -x * sin + z * cos;
        double cp = Math.cos(PITCH), sp = Math.sin(PITCH);
        return new double[] { rx, y * cp - rz * sp, y * sp + rz * cp };
    }

    private void face(List<Face> out, double[] o, double[] u, double[] v,
                      double[] normal, int tu, int tv, int tw, int th, boolean mirror) {
        double[] rn = rotate(normal[0], normal[1], normal[2]);
        if (rn[2] <= 0) return;                    // грань смотрит от нас

        double[] origin = o;
        double[] along = u;
        if (mirror) {                              // зеркалим текстуру по ширине
            origin = new double[] { o[0] + u[0], o[1] + u[1], o[2] + u[2] };
            along = new double[] { -u[0], -u[1], -u[2] };
        }
        Face f = new Face();
        f.origin = rotate(origin[0], origin[1], origin[2]);
        f.along = rotate(origin[0] + along[0], origin[1] + along[1], origin[2] + along[2]);
        f.down = rotate(origin[0] + v[0], origin[1] + v[1], origin[2] + v[2]);
        f.tu = tu; f.tv = tv; f.tw = tw; f.th = th;
        f.depth = (f.origin[2] + f.along[2] + f.down[2]) / 3;
        out.add(f);
    }

    private void collect(List<Face> out, Part p, boolean mirrorTexture) {
        float w = p.w + p.grow * 2, h = p.h + p.grow * 2, d = p.d + p.grow * 2;
        double x0 = p.x - w / 2, x1 = p.x + w / 2;
        double y0 = p.y - h / 2, y1 = p.y + h / 2;
        double z0 = p.z - d / 2, z1 = p.z + d / 2;
        int u = p.u, v = p.v;
        int iw = (int) p.w, ih = (int) p.h, id = (int) p.d;
        boolean m = mirrorTexture;

        // Развёртка идёт вокруг коробки: правый бок, перёд, левый бок, зад.
        face(out, new double[] { x0, y1, z0 }, new double[] { 0, 0, d },
                new double[] { 0, -h, 0 }, new double[] { -1, 0, 0 },
                u, v + id, id, ih, m);
        face(out, new double[] { x0, y1, z1 }, new double[] { w, 0, 0 },
                new double[] { 0, -h, 0 }, new double[] { 0, 0, 1 },
                u + id, v + id, iw, ih, m);
        face(out, new double[] { x1, y1, z1 }, new double[] { 0, 0, -d },
                new double[] { 0, -h, 0 }, new double[] { 1, 0, 0 },
                u + id + iw, v + id, id, ih, m);
        face(out, new double[] { x1, y1, z0 }, new double[] { -w, 0, 0 },
                new double[] { 0, -h, 0 }, new double[] { 0, 0, -1 },
                u + id + iw + id, v + id, iw, ih, m);
        face(out, new double[] { x0, y1, z0 }, new double[] { w, 0, 0 },
                new double[] { 0, 0, d }, new double[] { 0, 1, 0 },
                u + id, v, iw, id, m);
        face(out, new double[] { x0, y0, z1 }, new double[] { w, 0, 0 },
                new double[] { 0, 0, -d }, new double[] { 0, -1, 0 },
                u + id + iw, v, iw, id, m);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        int radius = (int) Math.round(5 * scale) * 2;
        RoundRectangle2D frame = new RoundRectangle2D.Float(
                0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, radius, radius);
        g.setColor(Theme.FIELD_FILL);
        g.fill(frame);
        g.setColor(Theme.FIELD_BORDER);
        g.draw(frame);

        if (skin == null) {
            g.dispose();
            return;
        }
        g.clip(frame);
        // Скин — пиксельная картинка: тянуть его сглаживанием нельзя, иначе
        // получится мыло вместо клеточек.
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        List<Face> faces = new ArrayList<Face>();
        boolean modern = skin.getHeight() >= 64;
        for (Part part : (modern ? MODERN : CLASSIC)) {
            collect(faces, part, part.mirror);
        }
        Collections.sort(faces, new Comparator<Face>() {
            public int compare(Face a, Face b) {
                return Double.compare(a.depth, b.depth);   // дальние раньше
            }
        });

        double unit = Math.min(getHeight() * 0.86 / 34.0, getWidth() * 0.8 / 18.0);
        double centerX = getWidth() / 2.0;
        double centerY = getHeight() / 2.0 + 16 * unit;    // ноги внизу

        for (Face f : faces) {
            double ox = centerX + f.origin[0] * unit, oy = centerY - f.origin[1] * unit;
            double ax = centerX + f.along[0] * unit, ay = centerY - f.along[1] * unit;
            double dx = centerX + f.down[0] * unit, dy = centerY - f.down[1] * unit;

            // Чуть раздвигаем углы: иначе между гранями видны волосяные щели.
            double bulge = 0.35;
            double ux = (ax - ox), uy = (ay - oy), vx = (dx - ox), vy = (dy - oy);
            double ul = Math.hypot(ux, uy), vl = Math.hypot(vx, vy);
            if (ul < 0.01 || vl < 0.01) continue;
            ox -= ux / ul * bulge + vx / vl * bulge;
            oy -= uy / ul * bulge + vy / vl * bulge;
            ux += ux / ul * bulge * 2; uy += uy / ul * bulge * 2;
            vx += vx / vl * bulge * 2; vy += vy / vl * bulge * 2;

            AffineTransform at = new AffineTransform(
                    ux / f.tw, uy / f.tw, vx / f.th, vy / f.th, ox, oy);
            try {
                g.drawImage(skin.getSubimage(f.tu, f.tv, f.tw, f.th), at, null);
            } catch (RuntimeException ignored) {
                // область за пределами скина — пропускаем эту грань
            }
        }
        g.dispose();
    }

    /** Заглушка, пока скин не пришёл: серый силуэт вместо пустоты. */
    public static BufferedImage placeholder() {
        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(120, 124, 132));
        g.fillRect(0, 0, 64, 32);
        g.setColor(new Color(96, 100, 108));
        g.fillRect(8, 8, 8, 8);
        g.dispose();
        return image;
    }
}
