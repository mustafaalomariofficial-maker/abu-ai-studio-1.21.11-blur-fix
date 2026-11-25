package dev.abustudio.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ActivityTest
{
    @AfterEach void reset() { Activity.idle(); }

    @Test
    void describesEveryStateInArabic()
    {
        Activity.thinking("Groq / openai/gpt-oss-20b");
        String thinking = Activity.describe();
        assertTrue(thinking.contains("Groq"), thinking);

        Activity.tool("fill_blocks");
        assertTrue(Activity.describe().contains("fill_blocks"), Activity.describe());

        Activity.error("نفدت الحصة");
        assertTrue(Activity.describe().contains("خطأ"), Activity.describe());

        Activity.waiting("لا يوجد مزود جاهز");
        assertTrue(Activity.describe().contains("بانتظار"), Activity.describe());

        Activity.answered("Groq / openai/gpt-oss-20b");
        assertEquals("Groq / openai/gpt-oss-20b", Activity.model());
        assertTrue(Activity.describe().contains("خامل"), Activity.describe());
    }
}
