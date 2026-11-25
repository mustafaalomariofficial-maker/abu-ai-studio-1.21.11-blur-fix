package dev.abustudio;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.abustudio.core.Log;
import dev.abustudio.game.Studio;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;

public class AbuStudio implements ModInitializer
{
    @Override
    public void onInitialize()
    {
        Studio s = Studio.init();

        ServerLifecycleEvents.SERVER_STARTED.register(server ->
        {
            s.server = server;
            if (System.getProperty("abu.selftest") != null) { dev.abustudio.game.Studio.selfTest(server); return; }
            String pending = s.agent.describeInterrupted();
            if (pending != null) s.say(pending);
            if (s.cfg.providers.isEmpty()) s.say("👋 أهلًا! أضف مزود ذكاء اصطناعي من زر ⚙ في شات الاستوديو (مفتاح K) ثم اكتب قصتك بالعربية.");
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server ->
        {
            if (s.agent.isActive()) { s.agent.stop(); s.agent.join(8000); Log.info("حُفظت المهمة قبل إغلاق العالم."); }
            s.server = null;
        });

        ServerTickEvents.END_SERVER_TICK.register(s.npcs::tick);

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) ->
            dispatcher.register(CommandManager.literal("abu").requires(src -> true)
                .then(CommandManager.literal("status").executes(ctx -> { ctx.getSource().sendFeedback(() -> Text.literal(s.agent.status()), false); return 1; }))
                .then(CommandManager.literal("stop").executes(ctx -> { ctx.getSource().sendFeedback(() -> Text.literal(s.agent.stop()), false); return 1; }))
                .then(CommandManager.literal("resume").executes(ctx -> { ctx.getSource().sendFeedback(() -> Text.literal(s.agent.resume()), false); return 1; }))
                .then(CommandManager.literal("say").then(CommandManager.argument("text", StringArgumentType.greedyString()).executes(ctx ->
                {
                    String text = StringArgumentType.getString(ctx, "text");
                    s.say("🧑 " + text);
                    ctx.getSource().sendFeedback(() -> Text.literal(s.agent.submit(text)), false);
                    return 1;
                })))));
    }
}
