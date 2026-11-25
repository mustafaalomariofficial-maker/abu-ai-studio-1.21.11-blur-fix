package dev.abustudio.client;

import dev.abustudio.core.Activity;
import dev.abustudio.core.Agent;
import dev.abustudio.core.ArabicText;
import dev.abustudio.core.NetMonitor;
import dev.abustudio.core.RunState;
import dev.abustudio.game.Studio;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** Arabic chat: history, story/command input, live AI status, active model, and a clear STOP button. */
public class StudioScreen extends Screen
{
    private final Studio s;
    private TextFieldWidget input;
    private int scroll;

    /* Wrapped chat lines are rebuilt only when the chat log or the panel width changes. */
    private List<String> cachedLines = new ArrayList<>();
    private int cachedVersion = -1;
    private int cachedWidth = -1;

    /* The status header is rebuilt at most twice a second instead of every frame. */
    private String head1 = "", head2 = "", head3 = "";
    private boolean headWarn;
    private long headAt;

    public StudioScreen(Studio s)
    {
        super(Text.literal("استوديو الأنيميشن الذكي"));
        this.s = s;
    }

    private static Text ar(String t) { return Text.literal(ArabicText.visual(t)); }

    @Override
    protected void init()
    {
        ArabicText.clientHandlesBidi = textRenderer.isRightToLeft();

        int w = Math.min(width - 20, 560), x = (width - w) / 2, y = height - 30;
        input = new TextFieldWidget(textRenderer, x, y, w - 90, 20, Text.literal(""));
        input.setMaxLength(2000);
        input.setPlaceholder(ar("اكتب قصتك أو أمرك بالعربية..."));
        input.addFormatter((str, i) -> net.minecraft.text.OrderedText.styledForwardsVisitedString(ArabicText.visual(str), net.minecraft.text.Style.EMPTY));
        addDrawableChild(input);
        setInitialFocus(input);

        addDrawableChild(ButtonWidget.builder(ar("إرسال"), b -> send()).dimensions(x + w - 86, y, 86, 20).build());
        int by = 8, bw = (w - 15) / 4;
        addDrawableChild(ButtonWidget.builder(ar("⏹ إيقاف"), b -> { s.say("⏹ " + s.agent.stop()); }).dimensions(x, by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(ar("▶ استكمال"), b -> s.say("▶ " + s.agent.resume())).dimensions(x + bw + 5, by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(ar("📷 لقطة"), b -> s.say(dev.abustudio.game.ExtraTools.takeAndSendScreenshot(s, "auto"))).dimensions(x + 2 * (bw + 5), by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(ar("⚙ الإعدادات"), b -> client.setScreen(new SettingsScreen(s, this))).dimensions(x + 3 * (bw + 5), by, bw, 20).build());
    }

    private void send()
    {
        String t = input.getText().trim();
        if (t.isEmpty()) return;
        input.setText("");
        s.say("🧑 " + t);
        s.say(s.agent.submit(t));
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput k)
    {
        if (k.key() == 257 || k.key() == 335) { send(); return true; }
        return super.keyPressed(k);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v)
    {
        scroll = Math.max(0, scroll + (v > 0 ? 3 : -3));
        return true;
    }

    private void rebuildHead()
    {
        String problem = s.router.setupProblem();
        RunState st = s.agent.current();
        long now = System.currentTimeMillis();
        if (now - headAt < 500 && headAt != 0) return;
        headAt = now;

        headWarn = !problem.isEmpty();
        head1 = "الذكاء الاصطناعي: " + (headWarn ? "لا يوجد مزود مضبوط" : Activity.describe());

        String model = Activity.model();
        if (model.isBlank() && st != null && !st.lastModel.isBlank()) model = st.lastModel;
        if (model.isBlank()) model = s.router.lastUsed();
        head2 = "الموديل الذي رد: " + (model.isBlank() ? "لم يرد بعد" : model);

        head3 = "الاتصال: " + (NetMonitor.online() ? "متصل" : "مقطوع") + "  |  طابور تيليجرام: " + s.outbox.pending();
        if (headWarn) head3 = problem;
    }

    private void rebuildLines(int w)
    {
        int version = s.chatVersion.get();
        if (w == cachedWidth && version == cachedVersion) return;

        List<String> out = new ArrayList<>();
        for (String m : s.chatLog) out.addAll(ArabicText.wrap(m, w - 10, str -> textRenderer.getWidth(str)));
        cachedLines = out;
        cachedWidth = w;
        cachedVersion = version;
    }

    private void drawRight(DrawContext c, String text, int x, int w, int y, int color)
    {
        String v = ArabicText.visual(text);
        int tx = x + w - textRenderer.getWidth(v);
        c.drawText(textRenderer, v, tx, y, color, false);
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta)
    {
        // Minecraft 1.21.11 applies the screen background blur before this render call.
        int w = Math.min(width - 20, 560), x = (width - w) / 2;

        rebuildHead();
        drawRight(c, head1, x, w, 31, headWarn ? 0xFFFF7777 : 0xFFFFFFFF);
        drawRight(c, head2, x, w, 42, 0xFFAAAAAA);
        drawRight(c, head3, x, w, 53, headWarn ? 0xFFFFAA55 : 0xFFAAAAAA);

        int top = 66, bottom = height - 38, lineH = 11;
        rebuildLines(w);
        List<String> lines = cachedLines;
        int visible = Math.max(1, (bottom - top) / lineH);
        int max = Math.max(0, lines.size() - visible);
        scroll = Math.min(scroll, max);
        int start = Math.max(0, lines.size() - visible - scroll);
        c.fill(x - 2, top - 2, x + w + 2, bottom + 2, 0x88000000);

        for (int i = 0; i < visible && start + i < lines.size(); i++)
        {
            String l = lines.get(start + i);
            String v = ArabicText.visual(l);
            int lx = ArabicText.isRtl(l) ? x + w - 4 - textRenderer.getWidth(v) : x + 4;
            c.drawText(textRenderer, v, lx, top + i * lineH, 0xFFFFFFFF, false);
        }

        super.render(c, mx, my, delta);
    }

    @Override public boolean shouldPause() { return false; }
}
