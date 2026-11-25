package dev.abustudio.client;

import dev.abustudio.core.*;
import dev.abustudio.game.Studio;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** List of providers + add buttons. */
public class ProvidersScreen extends Screen
{
    final Studio s;
    final Screen parent;

    public ProvidersScreen(Studio s, Screen parent) { super(Text.literal("المزودون")); this.s = s; this.parent = parent; }

    @Override
    protected void init()
    {
        int w = Math.min(width - 20, 460), x = (width - w) / 2, y = 24;

        for (ProviderConfig p : s.cfg.providers)
        {
            int ok = (int) p.probes.values().stream().filter(pr -> pr.toolsOk).count();
            String label = p.name + (p.enabled ? "" : " (معطل)") + " — " + p.models.size() + " موديل، " + ok + " ناجح بالأدوات";
            addDrawableChild(ButtonWidget.builder(SettingsScreen.ar(label), b -> client.setScreen(new ProviderEditScreen(s, this, p, false))).dimensions(x, y, w, 20).build());
            y += 22;
            if (y > height - 90) break;
        }

        y = height - 78;
        String[][] types = {{"openai", "OpenAI"}, {"groq", "Groq"}, {"gemini", "Google"}, {"ollama", "Ollama"}, {"lmstudio", "LM Studio"}, {"omniroute", "OmniRoute"}};
        int bw = (w - 5 * 3) / 6;
        for (int i = 0; i < types.length; i++)
        {
            String ty = types[i][0];
            addDrawableChild(ButtonWidget.builder(Text.literal("➕ " + types[i][1]), b ->
            {
                String id = ty + "-" + Integer.toString((int) (System.currentTimeMillis() % 100000), 36);
                client.setScreen(new ProviderEditScreen(s, this, ProviderConfig.preset(ty, id), true));
            }).dimensions(x + i * (bw + 3), y, bw, 20).build());
        }
        addDrawableChild(ButtonWidget.builder(SettingsScreen.ar("رجوع"), b -> client.setScreen(parent)).dimensions(x, height - 26, w, 20).build());
    }

    @Override
    public void render(DrawContext c, int mx, int my, float d)
    {
        // Minecraft 1.21.11 applies the screen background blur before this render call.
        String t = ArabicText.visual("المزودون (اضغط على مزود لتعديله، أو أضف جديدًا بالأسفل)");
        c.drawText(textRenderer, t, (width - textRenderer.getWidth(t)) / 2, 8, 0xFFFFFFFF, false);
        super.render(c, mx, my, d);
    }
}
