package dev.abustudio.core;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/** Resumable download (HTTP Range) into a .part file, retried across network drops; optional hash check. */
public final class Downloader
{
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();

    private Downloader() {}

    public static void download(String url, Path dest, long maxBytes, String sha, String shaAlgo, Control control, int maxAttempts) throws Exception
    {
        Files.createDirectories(dest.toAbsolutePath().getParent());
        Path part = dest.resolveSibling(dest.getFileName() + ".part");
        Exception last = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++)
        {
            if (control != null && control.isStopped()) throw new Control.StopException();

            try
            {
                long have = Files.exists(part) ? Files.size(part) : 0;
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120)).header("User-Agent", "abu-studio/1.0");
                if (have > 0) b.header("Range", "bytes=" + have + "-");
                HttpResponse<InputStream> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());

                if (r.statusCode() == 416) { have = 0; Files.deleteIfExists(part); continue; }
                if (r.statusCode() >= 400) throw new IOException("HTTP " + r.statusCode());
                boolean resumed = r.statusCode() == 206;
                if (!resumed && have > 0) { Files.deleteIfExists(part); have = 0; }

                try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE, resumed ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING))
                {
                    byte[] buf = new byte[1 << 15];
                    int n;
                    long total = have;
                    while ((n = in.read(buf)) > 0)
                    {
                        total += n;
                        if (total > maxBytes) { Files.deleteIfExists(part); throw new IOException("الملف أكبر من الحد المسموح"); }
                        out.write(buf, 0, n);
                        if (control != null && control.isStopped()) throw new Control.StopException();
                    }
                }

                if (sha != null && !sha.isBlank())
                {
                    MessageDigest md = MessageDigest.getInstance(shaAlgo == null ? "SHA-512" : shaAlgo);
                    try (InputStream in = Files.newInputStream(part)) { byte[] buf = new byte[1 << 15]; int n; while ((n = in.read(buf)) > 0) md.update(buf, 0, n); }
                    if (!HexFormat.of().formatHex(md.digest()).equalsIgnoreCase(sha.trim()))
                    {
                        Files.deleteIfExists(part);
                        throw new SecurityException("البصمة (hash) لا تطابق؛ حُذف الملف");
                    }
                }

                Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
            catch (SecurityException | Control.StopException e) { throw e; }
            catch (Exception e)
            {
                last = e;
                if (control != null) control.sleep(Math.min(2000L * attempt, 15000));
                else Thread.sleep(Math.min(2000L * attempt, 15000));
            }
        }

        throw new IOException("فشل التنزيل بعد " + maxAttempts + " محاولات: " + (last == null ? "" : Redactor.redact(String.valueOf(last.getMessage()))));
    }
}
