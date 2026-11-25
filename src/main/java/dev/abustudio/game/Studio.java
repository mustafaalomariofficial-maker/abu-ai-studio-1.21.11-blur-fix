package dev.abustudio.game;

import dev.abustudio.core.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Wires everything together. One instance per game process. */
public final class Studio
{
    public interface ClientBridge
    {
        boolean available();
        /** Captures the game window, resizes and JPEG-encodes it. Must work on the render thread internally. */
        CompletableFuture<Path> screenshot(ImagePolicy.Choice choice);
    }

    public static Studio I;

    public final Path dir;
    public StudioConfig cfg;
    public final SecretStore secrets;
    public final Router router;
    public final Prober prober;
    public final Tools tools = new Tools();
    public final Agent agent;
    public final Telegram telegram;
    public final Outbox outbox;
    public final TelegramBot bot;
    public final Npcs npcs = new Npcs();

    public volatile MinecraftServer server;
    public volatile ClientBridge client;
    public final List<String> chatLog = new CopyOnWriteArrayList<>();
    public final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    /** Bumped on every chat line so the studio screen rebuilds its wrapped lines only when needed. */
    public final java.util.concurrent.atomic.AtomicInteger chatVersion = new java.util.concurrent.atomic.AtomicInteger();

    private Studio(Path dir)
    {
        this.dir = dir;
        this.cfg = Json.read(dir.resolve("config.json"), StudioConfig.class);
        if (cfg == null) cfg = new StudioConfig();
        this.secrets = new SecretStore(dir);
        secrets.importFile();
        String externalNote = ExternalConfig.apply(dir.getParent() == null ? dir : dir.getParent(), cfg, secrets);
        saveConfig();
        if (!externalNote.isBlank()) Log.info(externalNote);
        Http.readSeconds = cfg.readTimeoutSeconds;

        Notifier notifier = text ->
        {
            say("🔔 " + text);
            sendTelegram(text);
        };

        this.router = new Router(() -> cfg, secrets, notifier);
        this.prober = new Prober(() -> cfg, secrets, this::saveConfig);
        this.agent = new Agent(() -> cfg, router, tools, notifier, dir.resolve("runs"));
        agent.narrate = t -> say("🤖 " + t);
        agent.worldSummary = () -> GameTools.worldSummary(this);
        this.telegram = new Telegram(() -> cfg, secrets);
        this.outbox = new Outbox(dir.resolve("outbox"), telegram);
        outbox.onEvent = t -> { say("📡 " + t); };
        this.bot = new TelegramBot(() -> cfg, telegram, outbox, dir, new BotHooks(this));
        Log.sink = null;

        GameTools.register(this);
        BbsTools.register(this);
        ExtraTools.register(this);
    }

    public static synchronized Studio init()
    {
        if (I == null)
        {
            Path dir = FabricLoader.getInstance().getConfigDir().resolve("abu_studio");
            try { java.nio.file.Files.createDirectories(dir); } catch (Exception ignored) {}
            I = new Studio(dir);
            I.outbox.start();
            I.bot.start();
            Log.info("استوديو الأنيميشن الذكي جاهز. المزودون: " + I.cfg.providers.size() + " | تيليجرام: " + (I.telegram.hasToken() ? "رمز محفوظ" : "لا رمز بعد"));
        }
        return I;
    }

    public static void selfTest(MinecraftServer server) { SelfTest.run(I, server); }

    public void saveConfig()
    {
        try { Json.writeAtomic(dir.resolve("config.json"), Json.GSON.toJson(cfg)); }
        catch (Exception e) { Log.error("تعذر حفظ الإعدادات"); }
    }

    /** In-game message (history + chat screen + vanilla chat of the player). */
    public void say(String text)
    {
        String clean = Redactor.redact(text);
        chatLog.add(clean);
        chatVersion.incrementAndGet();
        while (chatLog.size() > 300) chatLog.remove(0);
        for (Consumer<String> l : listeners) { try { l.accept(clean); } catch (Exception ignored) {} }
        GameTools.chatToPlayers(this, clean);
        Log.info("[شات] " + clean.replace("\n", " "));
    }

    public void sendTelegram(String text)
    {
        if (cfg.telegramEnabled && telegram.hasToken()) outbox.text(cfg.telegramOwnerId, text);
    }
}
