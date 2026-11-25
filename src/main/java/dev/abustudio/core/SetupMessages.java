package dev.abustudio.core;

/** The single Arabic explanation used when no usable provider is configured (screen, chat and Telegram). */
public final class SetupMessages
{
    private SetupMessages() {}

    public static String help(String problem)
    {
        return "⚠️ " + (problem == null || problem.isBlank() ? "لا يوجد مزود ذكاء اصطناعي مضبوط." : problem) + "\n\n"
            + "الخطوات: داخل اللعبة اضغط K ← ⚙ الإعدادات ← 🔌 المزودون ← ➕ Groq ← الصق المفتاح (من console.groq.com/keys) ← حفظ ← فحص القدرات.\n"
            + "أو ضع المفتاح في ملف config/abu-ai-studio.json داخل مجلد اللعبة:\n"
            + "{\"groq_api_key\":\"مفتاحك\"}\n"
            + "ثم أعد تشغيل اللعبة؛ وتظهر حالة الاتصال في شاشة الاستوديو وشاشة الإعدادات.\n"
            + "ملاحظة: لا يوجد مفتاح جاهز داخل المود، والبوت لا يعمل واللعبة مغلقة.";
    }
}
