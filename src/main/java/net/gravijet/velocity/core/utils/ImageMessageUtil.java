package net.gravijet.velocity.core.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ImageMessageUtil {

    public static List<Component> createPlayerHeadLines(UUID playerId, int size) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://mc-heads.net/avatar/" + playerId.toString());
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (VelocityCore)");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(true);

            try (InputStream in = conn.getInputStream()) {
                BufferedImage image = ImageIO.read(in);
                if (image == null) throw new IllegalStateException("ImageIO returned null image");
                return convertImageToLines(image, size, size);
            }
        } catch (Exception e) {
            // Fallback: build a simple gray placeholder head (prevents plugin errors)
            List<Component> placeholder = new ArrayList<>();
            for (int y = 0; y < size; y++) {
                TextComponent.Builder line = Component.text();
                for (int x = 0; x < size; x++) {
                    line.append(Component.text("█").color(TextColor.color(120, 120, 120)));
                }
                placeholder.add(line.build());
            }
            return placeholder;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static List<Component> convertImageToLines(BufferedImage image, int height, int width) {
        if (image.getHeight() != height || image.getWidth() != width) {
            image = resizeImage(image, width, height);
        }

        List<Component> lines = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            TextComponent.Builder line = Component.text();
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                TextColor color = rgbToTextColor(rgb);
                line.append(Component.text("█").color(color));
            }
            lines.add(line.build());
        }
        return lines;
    }

    private static BufferedImage resizeImage(BufferedImage originalImage, int targetWidth, int targetHeight) {
        BufferedImage resizedImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics2D = resizedImage.createGraphics();
        graphics2D.drawImage(originalImage, 0, 0, targetWidth, targetHeight, null);
        graphics2D.dispose();
        return resizedImage;
    }

    private static TextColor rgbToTextColor(int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        return TextColor.color(red, green, blue);
    }
}