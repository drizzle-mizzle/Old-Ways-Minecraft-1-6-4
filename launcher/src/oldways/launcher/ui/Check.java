package oldways.launcher.ui;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** Галочка с подписью — квадратик 16 точек и текст справа, как в макете. */
public class Check extends JComponent {

    private static final long serialVersionUID = 1L;
    private static final Color MARK = new Color(0xd8, 0xd8, 0xd8);   // accent-color макета

    private final String caption;
    private final double scale;
    private boolean checked;

    public Check(String caption, boolean checked, double scale) {
        this.caption = caption;
        this.checked = checked;
        this.scale = scale;
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFont(Theme.font((float) (14 * scale), false));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                Check.this.checked = !Check.this.checked;
                repaint();
            }
        });
    }

    public boolean isChecked() {
        return checked;
    }

    public void setChecked(boolean checked) {
        this.checked = checked;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        int box = (int) Math.round(16 * scale);
        int top = (getHeight() - box) / 2;
        int radius = (int) Math.round(3 * scale) * 2;

        if (checked) {
            g.setColor(MARK);
            g.fillRoundRect(0, top, box, box, radius, radius);
            g.setColor(new Color(30, 30, 30));
            g.setStroke(new BasicStroke((float) Math.max(1.6, 2 * scale),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawPolyline(
                    new int[] { (int) (box * 0.24), (int) (box * 0.44), (int) (box * 0.78) },
                    new int[] { top + (int) (box * 0.52), top + (int) (box * 0.72),
                            top + (int) (box * 0.30) }, 3);
        } else {
            g.setColor(Theme.FIELD_FILL);
            g.fillRoundRect(0, top, box, box, radius, radius);
            g.setColor(Theme.FIELD_BORDER);
            g.drawRoundRect(0, top, box - 1, box - 1, radius, radius);
        }

        g.setFont(getFont());
        g.setColor(Theme.TEXT);
        FontMetrics metrics = g.getFontMetrics();
        int textY = (getHeight() + metrics.getAscent() - metrics.getDescent()) / 2;
        g.drawString(caption, box + (int) Math.round(8 * scale), textY);
        g.dispose();
    }
}
