package dev.abustudio.client;

import dev.abustudio.core.ImagePolicy;
import dev.abustudio.game.Studio;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** Real screenshot of the game window → resized → JPEG at the chosen quality. */
final class ClientShots implements Studio.ClientBridge
{
    private final Studio s;

    ClientShots(Studio s) { this.s = s; }

    @Override
    public boolean available()
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null && mc.getWindow() != null && mc.getFramebuffer() != null;
    }

    @Override
    public CompletableFuture<Path> screenshot(ImagePolicy.Choice choice)
    {
        CompletableFuture<Path> out = new CompletableFuture<>();
        MinecraftClient mc = MinecraftClient.getInstance();

        mc.execute(() ->
        {
            try
            {
                ScreenshotRecorder.takeScreenshot(mc.getFramebuffer(), (NativeImage img) ->
                {
                    try
                    {
                        int w = img.getWidth(), h = img.getHeight();
                        BufferedImage full = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) full.setRGB(x, y, img.getColorArgb(x, y) & 0xFFFFFF);
                        img.close();

                        int tw = Math.min(choice.maxWidth(), w), th = Math.max(1, Math.round(h * (tw / (float) w)));
                        BufferedImage small = full;
                        if (tw < w)
                        {
                            small = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
                            Graphics2D g = small.createGraphics();
                            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                            g.drawImage(full.getScaledInstance(tw, th, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null);
                            g.dispose();
                        }

                        Path dir = s.dir.resolve("shots");
                        Files.createDirectories(dir);
                        Path file = dir.resolve("shot-" + System.currentTimeMillis() + ".jpg");
                        ImageWriter wr = ImageIO.getImageWritersByFormatName("jpg").next();
                        ImageWriteParam p = wr.getDefaultWriteParam();
                        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                        p.setCompressionQuality(choice.jpegQuality());
                        try (FileImageOutputStream os = new FileImageOutputStream(file.toFile()))
                        {
                            wr.setOutput(os);
                            wr.write(null, new IIOImage(small, null, null), p);
                        }
                        wr.dispose();
                        out.complete(file);
                    }
                    catch (Throwable t) { out.completeExceptionally(t); }
                });
            }
            catch (Throwable t) { out.completeExceptionally(t); }
        });

        return out.orTimeout(20, java.util.concurrent.TimeUnit.SECONDS);
    }
}
