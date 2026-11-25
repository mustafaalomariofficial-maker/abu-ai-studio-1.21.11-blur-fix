package dev.abustudio.client;

import dev.abustudio.core.*;
import dev.abustudio.game.Skins;
import dev.abustudio.game.Studio;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.nio.file.Path;
import java.util.List;

/** Telegram token (masked), bot name & skin, general toggles. */
public class SettingsScreen extends Screen
{
    final Studio s;
    final Screen parent;
    TextFieldWidget token, botName, skin;
    volatile String msg = "";

    public SettingsScreen(Studio s, Screen parent)
    {
        super(Text.literal("الإعدادات"));
        this.s = s;
        this.parent = parent;
    }

    static Text ar(String t) { return Text.literal(ArabicText.visual(t)); }

    static TextFieldWidget field(Screen sc, net.minecraft.client.font.TextRenderer tr, int x, int y, int w, String placeholder, boolean masked, String value)
    {
        TextFieldWidget f = new TextFieldWidget(tr, x, y, w, 20, Text.literal(""));
        f.setMaxLength(600);
        f.setPlaceholder(ar(placeholder));
        f.addFormatter((str, i) -> OrderedText.styledForwardsVisitedString(masked ? "•".repeat(str.length()) : ArabicText.visual(str), Style.EMPTY));
        if (value != null) f.setText(value);
        return f;
    }

    @Override
    protected void init()
    {
        int w = Math.min(width - 20, 460), x = (width - w) / 2, y = 30;
        int sw = 100;

        token = field(this, textRenderer, x, y, w - sw - 4, "رمز بوت تيليجرام (يُحفظ مشفرًا ولا يُعرض)", true, "");
        addDrawableChild(token);
        addDrawableChild(ButtonWidget.builder(ar("حفظ الرمز"), b ->
        {
            if (!token.getText().isBlank()) { s.secrets.put("telegram_token", token.getText().trim()); token.setText(""); msg = "حُفظ رمز البوت بشكل مشفر."; }
        }).dimensions(x + w - sw, y, sw, 20).build());

        y += 40;
        botName = field(this, textRenderer, x, y, w - sw - 4, "اسم الشخصية المساعدة", false, s.cfg.botName);
        addDrawableChild(botName);
        addDrawableChild(ButtonWidget.builder(ar("حفظ الاسم"), b ->
        {
            if (!botName.getText().isBlank()) { s.cfg.botName = botName.getText().trim(); s.saveConfig(); msg = "تم تغيير اسم البوت (يُطبَّق عند استدعائه من جديد)."; }
        }).dimensions(x + w - sw, y, sw, 20).build());

        y += 40;
        skin = field(this, textRenderer, x, y, w - sw - 4, "مسار ملف السكن PNG (أو اسحب الملف إلى النافذة)", false, "");
        addDrawableChild(skin);
        addDrawableChild(ButtonWidget.builder(ar("تطبيق السكن"), b -> applySkin(Path.of(skin.getText().trim()))).dimensions(x + w - sw, y, sw, 20).build());

        y += 30;
        int half = (w - 4) / 2;
        addDrawableChild(ButtonWidget.builder(ar("🔌 المزودون"), b -> client.setScreen(new ProvidersScreen(s, this))).dimensions(x, y, half, 20).build());
        addDrawableChild(ButtonWidget.builder(ar("🧪 فحص كل المزودين"), b -> probeAll()).dimensions(x + half + 4, y, half, 20).build());

        y += 24;
        addDrawableChild(ButtonWidget.builder(ar("جودة الصور: " + s.cfg.screenshotQuality), b ->
        {
            String[] o = {"auto", "low", "medium", "high"};
            s.cfg.screenshotQuality = o[(java.util.Arrays.asList(o).indexOf(s.cfg.screenshotQuality) + 1) % o.length];
            s.saveConfig();
            b.setMessage(ar("جودة الصور: " + s.cfg.screenshotQuality));
        }).dimensions(x, y, half, 20).build());
        addDrawableChild(ButtonWidget.builder(ar(s.cfg.telegramEnabled ? "تيليجرام: مفعّل" : "تيليجرام: معطّل"), b ->
        {
            s.cfg.telegramEnabled = !s.cfg.telegramEnabled; s.saveConfig();
            b.setMessage(ar(s.cfg.telegramEnabled ? "تيليجرام: مفعّل" : "تيليجرام: معطّل"));
        }).dimensions(x + half + 4, y, half, 20).build());

        y += 24;
        addDrawableChild(ButtonWidget.builder(ar(s.cfg.backupBeforeWorldEdit ? "نسخة احتياطية قبل التعديل: نعم" : "نسخة احتياطية قبل التعديل: لا"), b ->
        {
            s.cfg.backupBeforeWorldEdit = !s.cfg.backupBeforeWorldEdit; s.saveConfig();
            b.setMessage(ar(s.cfg.backupBeforeWorldEdit ? "نسخة احتياطية قبل التعديل: نعم" : "نسخة احتياطية قبل التعديل: لا"));
        }).dimensions(x, y, half, 20).build());
        addDrawableChild(ButtonWidget.builder(ar(s.cfg.allowCommandTool ? "أداة الأوامر: مسموحة" : "أداة الأوامر: ممنوعة"), b ->
        {
            s.cfg.allowCommandTool = !s.cfg.allowCommandTool; s.saveConfig();
            b.setMessage(ar(s.cfg.allowCommandTool ? "أداة الأوامر: مسموحة" : "أداة الأوامر: ممنوعة"));
        }).dimensions(x + half + 4, y, half, 20).build());

        y += 28;
        addDrawableChild(ButtonWidget.builder(ar("رجوع"), b -> client.setScreen(parent)).dimensions(x, y, w, 20).build());
    }

    void applySkin(Path p)
    {
        try { msg = Skins.install(s, p); } catch (Exception e) { msg = "❌ " + e.getMessage(); }
    }

    void probeAll()
    {
        msg = "بدأ الفحص... (النتائج في الشات)";
        new Thread(() ->
        {
            for (ProviderConfig p : s.cfg.providers)
            {
                if (!p.enabled) continue;
                try { s.prober.discover(p); } catch (Llm.LlmException e) { s.say("⚠️ " + p.name + ": " + e.kind.arabic); }
                s.say("🧪 " + p.name + ":\n" + String.join("\n", s.prober.probeAll(p, 6)));
            }
            msg = "انتهى الفحص.";
        }, "abu-probe").start();
    }

    @Override
    public void onFilesDropped(List<Path> paths)
    {
        if (!paths.isEmpty()) applySkin(paths.get(0));
    }

    @Override
    public void render(DrawContext c, int mx, int my, float d)
    {
        // Minecraft 1.21.11 applies the screen background blur before this render call.
        int w = Math.min(width - 20, 460), x = (width - w) / 2;
        String t1 = "رمز تيليجرام: " + (s.telegram.hasToken() ? "محفوظ ✓" : "غير محفوظ") + " | المسموح له: " + s.cfg.telegramOwnerId;
        c.drawText(textRenderer, ArabicText.visual(t1), x + w - textRenderer.getWidth(ArabicText.visual(t1)), 18, 0xFFCCCCCC, false);
        String setup = s.router.setupProblem();
        String t2 = "حالة الذكاء: " + (setup.isEmpty() ? Activity.describe() : "لا يوجد مزود مضبوط");
        String t3 = "حالة الاتصال: " + (setup.isEmpty()
            ? (s.router.lastUsed().isEmpty() ? "المزود مضبوط ولم يُستخدم بعد" : "آخر رد من " + s.router.lastUsed())
            : setup);
        c.drawText(textRenderer, ArabicText.visual(t2), x + w - textRenderer.getWidth(ArabicText.visual(t2)), height - 46, setup.isEmpty() ? 0xFFCCCCCC : 0xFFFF8888, false);
        c.drawText(textRenderer, ArabicText.visual(t3), x + w - textRenderer.getWidth(ArabicText.visual(t3)), height - 32, 0xFFCCCCCC, false);
        String m = ArabicText.visual(msg);
        c.drawText(textRenderer, m, x + w - textRenderer.getWidth(m), height - 16, 0xFFFFDD66, false);
        super.render(c, mx, my, d);
    }
}
