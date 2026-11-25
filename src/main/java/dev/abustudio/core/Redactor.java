package dev.abustudio.core;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Masks secrets in any text that may reach logs, chat or Telegram. */
public final class Redactor
{
    private static final Set<String> known = ConcurrentHashMap.newKeySet();
    private static final Pattern[] PATTERNS = {
        Pattern.compile("\\b\\d{6,12}:[A-Za-z0-9_-]{30,}\\b"),          /* Telegram bot token */
        Pattern.compile("(?i)bot\\d{6,12}:[A-Za-z0-9_-]{20,}"),
        Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,}\\b"),                /* OpenAI-style */
        Pattern.compile("\\bgsk_[A-Za-z0-9]{16,}\\b"),                 /* Groq */
        Pattern.compile("\\bAIza[0-9A-Za-z_-]{20,}\\b"),               /* Google */
        Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{12,}"),
        Pattern.compile("(?i)([?&](?:key|api_key|apikey|token)=)[^&\\s\"']+"),
        Pattern.compile("(?i)(\"?(?:api[_-]?key|authorization|x-goog-api-key|token)\"?\\s*[:=]\\s*\"?)[^\"\\s,}]{8,}")
    };

    private Redactor() {}

    public static void register(String secret)
    {
        if (secret != null && secret.length() >= 6) known.add(secret);
    }

    public static void clear() { known.clear(); }

    public static String redact(String text)
    {
        if (text == null) return null;
        String out = text;

        for (String s : known) out = out.replace(s, "«مخفي»");
        for (Pattern p : PATTERNS)
        {
            out = p.matcher(out).replaceAll(m ->
            {
                String g = m.groupCount() >= 1 && m.group(1) != null ? java.util.regex.Matcher.quoteReplacement(m.group(1)) : "";
                return g + "«مخفي»";
            });
        }

        return out;
    }
}
