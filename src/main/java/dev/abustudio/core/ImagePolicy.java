package dev.abustudio.core;

/** Chooses screenshot size/quality from how the connection has actually been behaving. */
public final class ImagePolicy
{
    public record Choice(String level, int maxWidth, float jpegQuality) {}

    private ImagePolicy() {}

    public static Choice choose(String requested, double uploadBps, int recentFailures, boolean online)
    {
        String r = requested == null ? "" : requested.toLowerCase().trim();

        if (r.equals("high") || r.equals("عالية") || r.equals("عالي")) return new Choice("high", 1920, 0.88f);
        if (r.equals("medium") || r.equals("متوسطة") || r.equals("متوسط")) return new Choice("medium", 1280, 0.72f);
        if (r.equals("low") || r.equals("منخفضة") || r.equals("خفيفة")) return new Choice("low", 640, 0.5f);

        /* auto: slow/unstable → small file so it actually arrives */
        if (!online || recentFailures >= 2 || (uploadBps > 0 && uploadBps < 20_000)) return new Choice("low", 640, 0.5f);
        if (uploadBps == 0 || uploadBps < 150_000) return new Choice("medium", 960, 0.65f);
        return new Choice("high", 1280, 0.8f);
    }
}
