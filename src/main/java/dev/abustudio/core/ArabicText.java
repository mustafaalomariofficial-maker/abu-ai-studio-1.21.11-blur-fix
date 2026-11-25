package dev.abustudio.core;

import java.text.Bidi;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Minecraft's font draws Arabic letters disconnected and left-to-right. This joins the letters (presentation forms
 * U+FE70..U+FEFF, which the game font contains) and reorders visually with java.text.Bidi, so Arabic reads
 * right-to-left and connected in our screens and in chat lines. (ICU4J is no longer bundled with 1.21.11.)
 */
public final class ArabicText
{
    private ArabicText() {}

    /** base letter -> {isolated, final, initial, medial}; 0 = form does not exist. */
    private static final java.util.Map<Character, int[]> FORMS = new java.util.HashMap<>();

    private static void f(int base, int iso, int fin, int ini, int med) { FORMS.put((char) base, new int[]{iso, fin, ini, med}); }

    static
    {
        f(0x621, 0xFE80, 0, 0, 0); f(0x622, 0xFE81, 0xFE82, 0, 0); f(0x623, 0xFE83, 0xFE84, 0, 0); f(0x624, 0xFE85, 0xFE86, 0, 0);
        f(0x625, 0xFE87, 0xFE88, 0, 0); f(0x626, 0xFE89, 0xFE8A, 0xFE8B, 0xFE8C); f(0x627, 0xFE8D, 0xFE8E, 0, 0);
        f(0x628, 0xFE8F, 0xFE90, 0xFE91, 0xFE92); f(0x629, 0xFE93, 0xFE94, 0, 0); f(0x62A, 0xFE95, 0xFE96, 0xFE97, 0xFE98);
        f(0x62B, 0xFE99, 0xFE9A, 0xFE9B, 0xFE9C); f(0x62C, 0xFE9D, 0xFE9E, 0xFE9F, 0xFEA0); f(0x62D, 0xFEA1, 0xFEA2, 0xFEA3, 0xFEA4);
        f(0x62E, 0xFEA5, 0xFEA6, 0xFEA7, 0xFEA8); f(0x62F, 0xFEA9, 0xFEAA, 0, 0); f(0x630, 0xFEAB, 0xFEAC, 0, 0);
        f(0x631, 0xFEAD, 0xFEAE, 0, 0); f(0x632, 0xFEAF, 0xFEB0, 0, 0); f(0x633, 0xFEB1, 0xFEB2, 0xFEB3, 0xFEB4);
        f(0x634, 0xFEB5, 0xFEB6, 0xFEB7, 0xFEB8); f(0x635, 0xFEB9, 0xFEBA, 0xFEBB, 0xFEBC); f(0x636, 0xFEBD, 0xFEBE, 0xFEBF, 0xFEC0);
        f(0x637, 0xFEC1, 0xFEC2, 0xFEC3, 0xFEC4); f(0x638, 0xFEC5, 0xFEC6, 0xFEC7, 0xFEC8); f(0x639, 0xFEC9, 0xFECA, 0xFECB, 0xFECC);
        f(0x63A, 0xFECD, 0xFECE, 0xFECF, 0xFED0); f(0x641, 0xFED1, 0xFED2, 0xFED3, 0xFED4); f(0x642, 0xFED5, 0xFED6, 0xFED7, 0xFED8);
        f(0x643, 0xFED9, 0xFEDA, 0xFEDB, 0xFEDC); f(0x644, 0xFEDD, 0xFEDE, 0xFEDF, 0xFEE0); f(0x645, 0xFEE1, 0xFEE2, 0xFEE3, 0xFEE4);
        f(0x646, 0xFEE5, 0xFEE6, 0xFEE7, 0xFEE8); f(0x647, 0xFEE9, 0xFEEA, 0xFEEB, 0xFEEC); f(0x648, 0xFEED, 0xFEEE, 0, 0);
        f(0x649, 0xFEEF, 0xFEF0, 0, 0); f(0x64A, 0xFEF1, 0xFEF2, 0xFEF3, 0xFEF4);
    }

    private static boolean transparent(char c) { return c >= 0x64B && c <= 0x65F || c == 0x670; }
    private static boolean joinsNext(char c) { int[] x = FORMS.get(c); return x != null && x[2] != 0; }
    private static boolean acceptsPrev(char c) { int[] x = FORMS.get(c); return x != null && x[1] != 0; }

    /** Connects Arabic letters (logical order in, logical order out, using presentation forms). */
    public static String shape(String in)
    {
        StringBuilder out = new StringBuilder();
        char[] c = in.toCharArray();

        for (int i = 0; i < c.length; i++)
        {
            int[] forms = FORMS.get(c[i]);
            if (forms == null) { out.append(c[i]); continue; }

            int p = i - 1;
            while (p >= 0 && transparent(c[p])) p--;
            int n = i + 1;
            while (n < c.length && transparent(c[n])) n++;
            boolean prev = p >= 0 && joinsNext(c[p]) && !(p >= 0 && lamAlefSecond(c, p));
            boolean next = n < c.length && acceptsPrev(c[n]);

            /* lam + alef ligature */
            if (c[i] == 0x644 && n < c.length && (c[n] == 0x627 || c[n] == 0x622 || c[n] == 0x623 || c[n] == 0x625))
            {
                int iso = c[n] == 0x622 ? 0xFEF5 : c[n] == 0x623 ? 0xFEF7 : c[n] == 0x625 ? 0xFEF9 : 0xFEFB;
                out.append((char) (prev ? iso + 1 : iso));
                for (int k = i + 1; k < n; k++) out.append(c[k]);
                i = n;
                continue;
            }

            int form;
            boolean dual = forms[2] != 0;
            if (prev && next && dual) form = forms[3];
            else if (prev) form = forms[1] != 0 ? forms[1] : forms[0];
            else if (next && dual) form = forms[2];
            else form = forms[0];
            out.append((char) form);
        }

        return out.toString();
    }

    private static boolean lamAlefSecond(char[] c, int p) { return false; }

    public static boolean hasArabic(String s)
    {
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if ((c >= 0x0600 && c <= 0x06FF) || (c >= 0x0750 && c <= 0x077F) || (c >= 0xFB50 && c <= 0xFEFF)) return true;
        }
        return false;
    }

    private static char mirror(char ch)
    {
        return switch (ch) { case '(' -> ')'; case ')' -> '('; case '[' -> ']'; case ']' -> '['; case '{' -> '}'; case '}' -> '{'; case '<' -> '>'; case '>' -> '<'; case '«' -> '»'; case '»' -> '«'; default -> ch; };
    }

    /**
     * True when the running Minecraft client already reshapes and reorders Arabic itself
     * (TextRenderer.mirror + Language.reorder, active when the game language reads right to left).
     * Then logical text must pass through untouched: applying both transforms here as well is what made
     * Arabic read backwards. The client updates this from textRenderer.isRightToLeft() every tick.
     */
    public static volatile boolean clientHandlesBidi = true;

    private static final java.util.Map<String, String> CACHE = java.util.Collections.synchronizedMap(
        new java.util.LinkedHashMap<String, String>(512, .75f, true) {
            protected boolean removeEldestEntry(java.util.Map.Entry<String,String> e) { return size() > 2048; }
        });

    /** Display string for one logical string. Cached per finished line; paragraphs keep their order. */
    public static String visual(String logical)
    {
        if (logical == null || logical.isEmpty()) return logical;
        if (clientHandlesBidi) return logical;
        String cached = CACHE.get(logical);
        if (cached != null) return cached;
        String[] lines = logical.split("\n", -1);
        for (int i = 0; i < lines.length; i++) lines[i] = visualLine(lines[i]);
        String result = String.join("\n", lines);
        CACHE.put(logical, result);
        return result;
    }

    private static String visualLine(String logical)
    {
        if (logical == null || logical.isEmpty() || !hasArabic(logical)) return logical;

        try
        {
            String shaped = shape(logical);
            Bidi bidi = new Bidi(shaped, Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT);
            int runs = bidi.getRunCount();
            byte[] levels = new byte[runs];
            Integer[] idx = new Integer[runs];
            for (int r = 0; r < runs; r++) { levels[r] = (byte) bidi.getRunLevel(r); idx[r] = r; }
            Bidi.reorderVisually(levels, 0, idx, 0, runs);
            StringBuilder sb = new StringBuilder();

            for (int k = 0; k < runs; k++)
            {
                int r = idx[k];
                String run = shaped.substring(bidi.getRunStart(r), bidi.getRunLimit(r));
                if ((bidi.getRunLevel(r) & 1) == 1)
                {
                    StringBuilder rev = new StringBuilder();
                    for (int j = run.length() - 1; j >= 0; j--) rev.append(mirror(run.charAt(j)));
                    sb.append(rev);
                }
                else sb.append(run);
            }

            return sb.toString();
        }
        catch (Exception e)
        {
            return logical;
        }
    }

    /** True when the paragraph should be right-aligned. */
    public static boolean isRtl(String s)
    {
        for (int i = 0; i < s.length(); i++)
        {
            Character.UnicodeBlock b = Character.UnicodeBlock.of(s.charAt(i));
            if (Character.isLetter(s.charAt(i))) return hasArabic(String.valueOf(s.charAt(i)));
        }
        return false;
    }

    /** Wraps LOGICAL text by measured width first (so line order is correct), then each line is shaped separately. */
    public static List<String> wrap(String logical, int maxWidth, ToIntFunction<String> width)
    {
        List<String> out = new ArrayList<>();

        for (String para : logical.split("\n", -1))
        {
            StringBuilder line = new StringBuilder();

            for (String word : para.split(" "))
            {
                String test = line.length() == 0 ? word : line + " " + word;
                if (line.length() > 0 && width.applyAsInt(visual(test)) > maxWidth) { out.add(line.toString()); line = new StringBuilder(word); }
                else line = new StringBuilder(test);
            }

            out.add(line.toString());
        }

        return out;
    }

    /** For vanilla chat: logical lines of about maxChars characters, each already visual. */
    public static List<String> chatLines(String logical, int maxChars)
    {
        List<String> res = new ArrayList<>();
        for (String l : wrap(logical, maxChars, String::length)) res.add(visual(l));
        return res;
    }
}
