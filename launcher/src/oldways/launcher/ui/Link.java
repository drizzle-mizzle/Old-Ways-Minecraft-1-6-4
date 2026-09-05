package oldways.launcher.ui;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Кнопка-ссылка: подчёркнутый текст без рамки и заливки.
 *
 * Нужна там, где действие второстепенное и не должно спорить с главной
 * кнопкой: «Аккаунт» и «Выход» под кнопкой «Играть».
 */
public class Link extends JComponent {

    private static final long serialVersionUID = 1L;

    private final String text;
    private final double scale;
    private final Color color;
    private boolean hot;
    private ActionListener listener;

    public Link(String text, Color color, double scale) {
        this.text = text;
        this.color = color;
        this.scale = scale;
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFont(Theme.font((float) (13 * scale), false));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hot = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hot = false;
                repaint();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (listener != null) {
                    listener.actionPerformed(new ActionEvent(
                            Link.this, ActionEvent.ACTION_PERFORMED, text));
                }
            }
        });
    }

    public void onClick(ActionListener listener) {
        this.listener = listener;
    }

    /** Ширина текста с подчёркиванием — по ней ставят ссылку в ряд. */
    public int textWidth() {
        return getFontMetrics(getFont()).stringWidth(text);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        if (hot) {   // тот же прирост, что и у обычных кнопок
            g.translate(getWidth() / 2.0, getHeight() / 2.0);
            g.scale(Theme.HOVER, Theme.HOVER);
            g.translate(-getWidth() / 2.0, -getHeight() / 2.0);
        }

        g.setFont(getFont());
        FontMetrics metrics = g.getFontMetrics();
        int width = metrics.stringWidth(text);
        int x = (getWidth() - width) / 2;
        int y = (getHeight() + metrics.getAscent() - metrics.getDescent()) / 2;

        g.setColor(hot ? color : dim(color));
        g.drawString(text, x, y);
        int line = y + Math.max(1, (int) Math.round(2 * scale));
        g.drawLine(x, line, x + width, line);
        g.dispose();
    }

    /** Без курсора ссылка чуть приглушена, чтобы не спорить с кнопкой. */
    private static Color dim(Color base) {
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), 200);
    }
}
