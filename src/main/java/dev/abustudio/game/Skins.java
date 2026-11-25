package dev.abustudio.game;

import mchorse.bbs_mod.BBSMod;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;

/** Installs a custom skin PNG for the helper bot into BBS assets (so BBS models can use it as a texture link). */
public final class Skins
{
    private Skins() {}

    public static String install(Studio s, Path png) throws Exception
    {
        if (!Files.isRegularFile(png)) throw new IllegalArgumentException("الملف غير موجود: " + png.getFileName());
        if (Files.size(png) > 1_000_000) throw new IllegalArgumentException("الملف كبير (>1MB)");
        BufferedImage img = ImageIO.read(png.toFile());
        if (img == null) throw new IllegalArgumentException("ليس ملف PNG صالحًا");
        int w = img.getWidth(), h = img.getHeight();
        if (!((w == 64 && (h == 64 || h == 32)) || (w == 128 && h == 128))) throw new IllegalArgumentException("مقاس السكن غير مدعوم (" + w + "x" + h + "). المطلوب 64x64 أو 64x32.");

        Path dest = BBSMod.getAssetsFolder().toPath().resolve("textures").resolve("abu_studio").resolve("bot.png");
        Files.createDirectories(dest.getParent());
        Files.copy(png, dest, StandardCopyOption.REPLACE_EXISTING);
        s.cfg.botSkinLink = "assets:textures/abu_studio/bot.png";
        s.saveConfig();
        return "تم تثبيت السكن (" + w + "x" + h + "). أعد استدعاء البوت (npc_remove ثم bot_summon) ليظهر به.";
    }
}
