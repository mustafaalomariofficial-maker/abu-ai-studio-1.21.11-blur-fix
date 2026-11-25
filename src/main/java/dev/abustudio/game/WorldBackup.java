package dev.abustudio.game;

import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.file.*;
import java.util.stream.Stream;
import java.util.zip.*;

/** Zip snapshot of the world folder before the AI changes it. Restore = close the world and unzip over it (see the guide). */
final class WorldBackup
{
    static final long LIMIT = 1_500_000_000L;

    private WorldBackup() {}

    static String create(Studio s, String label) throws Exception
    {
        GameTools.onServer(s, () -> { s.server.saveAll(true, true, false); return null; });
        Path root = s.server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
        long size = 0;
        try (Stream<Path> st = Files.walk(root)) { for (Path p : (Iterable<Path>) st.filter(Files::isRegularFile)::iterator) size += Files.size(p); }
        if (size > LIMIT) return "تخطيت النسخة الاحتياطية: حجم العالم " + size / 1_000_000 + "MB أكبر من الحد (1.5GB). انسخ العالم يدويًا قبل العمل الكبير.";

        Path out = s.dir.resolve("backups").resolve("world-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date()) + "-" + label.replaceAll("[^A-Za-z0-9_-]", "_") + ".zip");
        Files.createDirectories(out.getParent());

        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(out)); Stream<Path> st = Files.walk(root))
        {
            for (Path p : (Iterable<Path>) st.filter(Files::isRegularFile)::iterator)
            {
                if (p.getFileName().toString().equals("session.lock")) continue;
                try { z.putNextEntry(new ZipEntry(root.getFileName() + "/" + root.relativize(p).toString().replace('\\', '/'))); Files.copy(p, z); z.closeEntry(); }
                catch (IOException ignored) { /* file being written right now: skip, region data was flushed by saveAll */ }
            }
        }

        return out.toString() + " (" + Files.size(out) / 1024 + "KB)";
    }
}
