package net.gravijet.velocity.core.support.discord;

import net.gravijet.velocity.core.support.manager.SupportManager;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;

public final class StatsChartGenerator {
    private StatsChartGenerator() {}

    // Discord dark theme palette
    private static final Color BG        = new Color(0x2B2D31);
    private static final Color BG_CARD   = new Color(0x1E1F22);
    private static final Color GRID      = new Color(0x3A3C42);
    private static final Color TEXT_PRI  = new Color(0xDBDEE1);
    private static final Color TEXT_SEC  = new Color(0x80848E);
    private static final Color AVG_LINE  = new Color(0xFEE75C);

    private static Color barColor(double avg) {
        if (avg >= 4.5) return new Color(0x23A55A); // green
        if (avg >= 3.5) return new Color(0x5865F2); // blurple
        if (avg >= 2.5) return new Color(0xF0B232); // yellow
        return new Color(0xDA373C);                  // red
    }

    public static File generate(Map<String, SupportManager.StaffStats> stats, double globalAvg,
                                int totalRatings, Logger logger) {
        System.setProperty("java.awt.headless", "true");

        final int BAR_W  = 88;
        final int GAP    = 20;
        final int LEFT   = 56;
        final int RIGHT  = 24;
        final int TOP    = 72;
        final int BOT    = 72;
        final int CHART_H = 240;

        int count  = Math.max(stats.size(), 1);
        int totalW = Math.max(520, LEFT + count * (BAR_W + GAP) + RIGHT);
        int totalH = TOP + CHART_H + BOT;

        BufferedImage img = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,         RenderingHints.VALUE_RENDER_QUALITY);

        // Background
        g.setColor(BG);
        g.fillRect(0, 0, totalW, totalH);

        // Card background for chart area
        g.setColor(BG_CARD);
        g.fillRoundRect(LEFT - 8, TOP - 8, totalW - LEFT - RIGHT + 16, CHART_H + 16, 8, 8);

        // Title
        g.setFont(new Font("SansSerif", Font.BOLD, 15));
        g.setColor(TEXT_PRI);
        String title = "Support Statistics";
        FontMetrics tfm = g.getFontMetrics();
        g.drawString(title, (totalW - tfm.stringWidth(title)) / 2, 26);

        // Subtitle
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        g.setColor(TEXT_SEC);
        String sub = String.format("Global average: %.2f / 5.00  •  %d total ratings", globalAvg, totalRatings);
        FontMetrics sfm = g.getFontMetrics();
        g.drawString(sub, (totalW - sfm.stringWidth(sub)) / 2, 44);

        // Y-axis grid lines and labels (0–5)
        for (int i = 0; i <= 5; i++) {
            int y = TOP + CHART_H - (int)(i * CHART_H / 5.0);
            g.setColor(GRID);
            g.setStroke(new BasicStroke(1f));
            g.drawLine(LEFT, y, totalW - RIGHT, y);
            g.setFont(new Font("SansSerif", Font.PLAIN, 10));
            g.setColor(TEXT_SEC);
            FontMetrics fm = g.getFontMetrics();
            String label = String.valueOf(i);
            g.drawString(label, LEFT - fm.stringWidth(label) - 5, y + 4);
        }

        // Global average dashed line
        int avgY = TOP + CHART_H - (int)(globalAvg * CHART_H / 5.0);
        g.setColor(AVG_LINE);
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f, new float[]{6f, 4f}, 0f));
        g.drawLine(LEFT, avgY, totalW - RIGHT, avgY);
        g.setStroke(new BasicStroke(1f));

        // Bars
        int x = LEFT + GAP / 2;
        for (Map.Entry<String, SupportManager.StaffStats> entry : stats.entrySet()) {
            SupportManager.StaffStats s = entry.getValue();
            double avg  = s.getAverage();
            int    barH = Math.max(6, (int)(avg * CHART_H / 5.0));
            int    barY = TOP + CHART_H - barH;

            // Bar with rounded top
            g.setColor(barColor(avg));
            g.fillRoundRect(x, barY, BAR_W, barH, 6, 6);
            // Square off the bottom half of the rounded rect
            g.fillRect(x, barY + 3, BAR_W, barH - 3);

            // Average label above bar
            g.setFont(new Font("SansSerif", Font.BOLD, 12));
            g.setColor(TEXT_PRI);
            String avgStr = String.format("%.2f", avg);
            FontMetrics afm = g.getFontMetrics();
            g.drawString(avgStr, x + (BAR_W - afm.stringWidth(avgStr)) / 2, barY - 6);

            // Staff name below bar
            g.setFont(new Font("SansSerif", Font.BOLD, 11));
            g.setColor(TEXT_PRI);
            String name = entry.getKey().length() > 10 ? entry.getKey().substring(0, 9) + "\u2026" : entry.getKey();
            FontMetrics nfm = g.getFontMetrics();
            g.drawString(name, x + (BAR_W - nfm.stringWidth(name)) / 2, TOP + CHART_H + 18);

            // Count below name
            g.setFont(new Font("SansSerif", Font.PLAIN, 10));
            g.setColor(TEXT_SEC);
            String cnt = s.totalSessions + (s.totalSessions == 1 ? " rating" : " ratings");
            FontMetrics cfm = g.getFontMetrics();
            g.drawString(cnt, x + (BAR_W - cfm.stringWidth(cnt)) / 2, TOP + CHART_H + 32);

            x += BAR_W + GAP;
        }

        // Average line legend (bottom-left)
        int legX = LEFT;
        int legY = totalH - 14;
        g.setColor(AVG_LINE);
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f, new float[]{6f, 4f}, 0f));
        g.drawLine(legX, legY, legX + 18, legY);
        g.setStroke(new BasicStroke(1f));
        g.setFont(new Font("SansSerif", Font.PLAIN, 10));
        g.setColor(TEXT_SEC);
        g.drawString(String.format("Average (%.2f/5)", globalAvg), legX + 24, legY + 4);

        } finally {
            g.dispose();
        }

        try {
            File f = File.createTempFile("support_stats_", ".png");
            ImageIO.write(img, "PNG", f);
            return f;
        } catch (Exception e) {
            logger.warn("Failed to write stats chart: {}", e.getMessage());
            return null;
        }
    }
}
