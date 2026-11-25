package dev.abustudio.client;

import dev.abustudio.core.*;
import dev.abustudio.game.Studio;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** Add / edit / delete one provider, its address, auth method, key; list, test and choose models. */
public class ProviderEditScreen extends Screen
{
    final Studio s;
    final Screen parent;
    final ProviderConfig p;
    final boolean isNew;
    TextFieldWidget name, url, authName, key, addModel, preferred;
    volatile String msg = "";
    volatile Boolean connectionOk;

    public ProviderEditScreen(Studio s, Screen parent, ProviderConfig p, boolean isNew)
    {
        super(Text.literal("مزود"));
        this.s = s; this.parent = parent; this.p = p; this.isNew = isNew;
    }

    static Text ar(String t) { return SettingsScreen.ar(t); }

    @Override
    protected void init()
    {
        int w = Math.min(width - 20, 460), x = (width - w) / 2, y = 22;
        name = SettingsScreen.field(this, textRenderer, x, y, w, "اسم المزود", false, p.name); addDrawableChild(name); y += 24;
        url = SettingsScreen.field(this, textRenderer, x, y, w, "العنوان (Base URL) مثل http://192.168.1.5:1234/v1", false, p.baseUrl); addDrawableChild(url); y += 24;
        authName = SettingsScreen.field(this, textRenderer, x, y, w, "اسم الترويسة/المعامل (للمصادقة header/query)", false, p.authName); addDrawableChild(authName); y += 24;
        key = SettingsScreen.field(this, textRenderer, x, y, w, s.secrets.has("provider:" + p.id) ? "المفتاح محفوظ ✓ (اتركه فارغًا للإبقاء عليه)" : "مفتاح API (يُحفظ مشفرًا)", true, ""); addDrawableChild(key); y += 24;

        int half = (w - 4) / 2;
        addDrawableChild(ButtonWidget.builder(ar("المصادقة: " + p.authMode), b ->
        {
            String[] o = {"bearer", "header", "query", "none"};
            p.authMode = o[(java.util.Arrays.asList(o).indexOf(p.authMode) + 1) % o.length];
            b.setMessage(ar("المصادقة: " + p.authMode));
        }).dimensions(x, y, half, 20).build());
        addDrawableChild(ButtonWidget.builder(ar(p.enabled ? "الحالة: مفعّل" : "الحالة: معطّل"), b ->
        {
            p.enabled = !p.enabled; b.setMessage(ar(p.enabled ? "الحالة: مفعّل" : "الحالة: معطّل"));
        }).dimensions(x + half + 4, y, half, 20).build());
        y += 24;
        addDrawableChild(ButtonWidget.builder(ar(p.local == null ? "الموقع: تلقائي" : p.local ? "الموقع: محلي" : "الموقع: سحابي"), b ->
        {
            p.local = p.local == null ? Boolean.TRUE : p.local ? Boolean.FALSE : null;
            b.setMessage(ar(p.local == null ? "الموقع: تلقائي" : p.local ? "الموقع: محلي" : "الموقع: سحابي"));
        }).dimensions(x, y, half, 20).build());
        preferred = SettingsScreen.field(this, textRenderer, x + half + 4, y, half, "الموديل المفضل", false, p.preferredModel); addDrawableChild(preferred); y += 24;

        addModel = SettingsScreen.field(this, textRenderer, x, y, w - 84, "أضف اسم موديل يدويًا", false, "");
        addDrawableChild(addModel);
        addDrawableChild(ButtonWidget.builder(ar("إضافة"), b -> { String m = addModel.getText().trim(); if (!m.isEmpty() && !p.models.contains(m)) { p.models.add(m); addModel.setText(""); msg = "أُضيف الموديل " + m; } }).dimensions(x + w - 80, y, 80, 20).build());
        y += 24;

        int q = (w - 12) / 5;
        addDrawableChild(ButtonWidget.builder(ar("جلب الموديلات"), b -> { persist(); run("جلب الموديلات", () ->
        {
            try { msg = "وُجد " + s.prober.discover(p).size() + " موديل."; } catch (Llm.LlmException e) { msg = "❌ " + e.kind.arabic + " " + e.getMessage(); }
        }); }).dimensions(x, y, q, 20).build());
        addDrawableChild(ButtonWidget.builder(ar("فحص القدرات"), b -> { persist(); run("فحص القدرات", () ->
        {
            msg = String.join(" | ", s.prober.probeAll(p, 6));
        }); }).dimensions(x + q + 3, y, q, 20).build());
        addDrawableChild(ButtonWidget.builder(ar("اختبار"), b -> { persist(); run("اختبار الاتصال", this::testConnection); }).dimensions(x + 2 * (q + 3), y, q, 20).build());
        addDrawableChild(ButtonWidget.builder(ar("حفظ"), b -> { persist(); msg = "تم الحفظ."; }).dimensions(x + 3 * (q + 3), y, q, 20).build());
        addDrawableChild(ButtonWidget.builder(ar("🗑 حذف"), b ->
        {
            s.cfg.providers.removeIf(o -> o.id.equals(p.id)); s.secrets.remove("provider:" + p.id); s.saveConfig();
            client.setScreen(new ProvidersScreen(s, parent instanceof ProvidersScreen ps ? ps.parent : parent));
        }).dimensions(x + 4 * (q + 3), y, q, 20).build());
        y += 24;
        addDrawableChild(ButtonWidget.builder(ar("رجوع"), b -> client.setScreen(new ProvidersScreen(s, parent instanceof ProvidersScreen ps ? ps.parent : parent))).dimensions(x, y, w, 20).build());
    }

    void persist()
    {
        p.name = name.getText().trim().isEmpty() ? p.name : name.getText().trim();
        p.baseUrl = url.getText().trim();
        p.authName = authName.getText().trim();
        p.preferredModel = preferred.getText().trim();
        if (!key.getText().isBlank()) { s.secrets.put("provider:" + p.id, key.getText().trim()); key.setText(""); }
        if (s.cfg.provider(p.id) == null) s.cfg.providers.add(p);
        s.saveConfig();
    }

    void testConnection()
    {
        String key = s.secrets.get("provider:" + p.id);
        String model = p.preferredModel.isBlank() ? (p.models.isEmpty() ? "" : p.models.get(0)) : p.preferredModel;
        if (model.isBlank()) { msg = "اختر موديلًا أولًا: جلب الموديلات أو أضف اسمًا."; return; }

        try
        {
            Llm.Result r = LlmClient.chat(p, key, model, java.util.List.of(Llm.Msg.user("اكتب كلمة واحدة: جاهز")), null);
            boolean ok = r.text() != null && !r.text().isBlank();
            connectionOk = ok;
            msg = ok ? "حالة الاتصال: نجح (" + r.latencyMs() + "ms): " + r.text().trim() : "حالة الاتصال: رد فارغ";
            ProviderConfig.ModelProbe pr = p.probes.computeIfAbsent(model, k -> new ProviderConfig.ModelProbe());
            pr.replied = ok;
            pr.testedAt = System.currentTimeMillis();
            pr.note = ok ? "نجح اختبار الاتصال" : "رد فارغ";
            s.saveConfig();
        }
        catch (Llm.LlmException e)
        {
            connectionOk = false;
            msg = "حالة الاتصال: فشل — " + e.kind.arabic + (e.status > 0 ? " (HTTP " + e.status + ")" : "");
        }
    }

    void run(String what, Runnable r)
    {
        msg = what + "...";
        new Thread(() -> { try { r.run(); } catch (Throwable t) { msg = "❌ " + t.getClass().getSimpleName(); } }, "abu-provider").start();
    }

    @Override
    public void render(DrawContext c, int mx, int my, float d)
    {
        // Minecraft 1.21.11 applies the screen background blur before this render call.
        int w = Math.min(width - 20, 460), x = (width - w) / 2;
        int y = 22 + 24 * 8 + 4;
        for (String m : p.models)
        {
            if (y > height - 30) break;
            ProviderConfig.ModelProbe pr = p.probes.get(m);
            String l = m + (pr == null ? "  (لم يُفحص)" : pr.toolsOk ? "  ✅ أدوات " + pr.latencyMs + "ms" : pr.replied ? "  ⚠ بلا أدوات" : "  ❌ " + pr.note);
            c.drawText(textRenderer, ArabicText.visual(l), x, y, pr != null && pr.toolsOk ? 0xFF88FF88 : 0xFFCCCCCC, false);
            y += 10;
        }
        boolean keyExists = "none".equals(p.authMode) || s.secrets.has("provider:" + p.id);
        String connection = !keyExists ? "حالة الاتصال: المفتاح غير موجود — أدخله واحفظه" : "حالة الاتصال: المفتاح محفوظ، اضغط اختبار للتأكد";
        if (connectionOk != null) connection = connectionOk ? "حالة الاتصال: يعمل ✓" : "حالة الاتصال: لا يعمل — راجع المفتاح والعنوان";
        else
        {
            ProviderConfig.ModelProbe checked = p.probes.get(p.preferredModel.isBlank() ? (p.models.isEmpty() ? "" : p.models.get(0)) : p.preferredModel);
            if (keyExists && checked != null) connection = checked.replied ? "حالة الاتصال: نجح الرد" : "حالة الاتصال: فشل الفحص — " + checked.note;
        }
        String cv = ArabicText.visual(connection);
        c.drawText(textRenderer, cv, x, height - 24, 0xFFCCCCCC, false);
        String mm = ArabicText.visual(msg.length() > 120 ? msg.substring(0, 120) + "…" : msg);
        c.drawText(textRenderer, mm, x + w - textRenderer.getWidth(mm), height - 12, 0xFFFFDD66, false);
        super.render(c, mx, my, d);
    }
}
