package dev.abustudio.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArabicTextTest
{
    @AfterEach void resetFlag() { ArabicText.clientHandlesBidi = true; }

    @Test
    void leavesTextAloneWhenMinecraftAlreadyReorders()
    {
        ArabicText.clientHandlesBidi = true;
        assertEquals("مرحبا", ArabicText.visual("مرحبا"));
        String mixed = "أولًا (Groq 20B)\nثانيًا [model-v2]";
        assertEquals(mixed, ArabicText.visual(mixed), "لا تحويل مزدوج ولا خلط بين الأسطر");
        assertEquals(mixed, ArabicText.visual(mixed), "الكاش يعيد نفس النص");
    }

    @Test
    void joinsLettersAndReversesOnlyWhenTheClientDoesNot()
    {
        ArabicText.clientHandlesBidi = false;
        assertEquals("\uFE8E\uFE92\uFEA3\uFEAE\uFEE3", ArabicText.visual("مرحبا"));
        assertEquals("abc", ArabicText.visual("abc"));
    }

    @Test
    void lamAlefLigatureAndMixedText()
    {
        ArabicText.clientHandlesBidi = false;
        assertEquals("\uFEFB", ArabicText.shape("لا"));
        String mixed = ArabicText.visual("ابنِ house الآن");
        assertTrue(mixed.contains("house"), mixed);
        assertTrue(ArabicText.hasArabic("قصة"));
        assertTrue(ArabicText.isRtl("قصة"));
        assertFalse(ArabicText.isRtl("story"));
    }

    @Test
    void keepsParagraphOrderAndCachesLines()
    {
        ArabicText.clientHandlesBidi = false;
        String one = ArabicText.visual("أولًا (Groq 20B)");
        String two = ArabicText.visual("ثانيًا [model-v2]");
        assertEquals(one + "\n" + two, ArabicText.visual("أولًا (Groq 20B)\nثانيًا [model-v2]"));
        assertSame(one, ArabicText.visual("أولًا (Groq 20B)"));
        assertTrue(one.contains("Groq 20B"));
        assertTrue(two.contains("model-v2"));
    }

    @Test
    void wrapsLogicalTextBeforeShaping()
    {
        var lines = ArabicText.wrap("هذه قصة طويلة جدا عن تنين وأميرة وقلعة كبيرة", 60, s -> s.length() * 6);
        assertTrue(lines.size() > 1);
        assertEquals("هذه قصة طويلة جدا عن تنين وأميرة وقلعة كبيرة", String.join(" ", lines));
    }
}
