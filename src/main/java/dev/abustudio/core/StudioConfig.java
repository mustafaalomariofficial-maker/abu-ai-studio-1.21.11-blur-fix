package dev.abustudio.core;

import java.util.ArrayList;
import java.util.List;

/** Persisted settings (config/abu_studio/config.json). Contains NO secrets. */
public class StudioConfig
{
    public int version = 1;
    public List<ProviderConfig> providers = new ArrayList<>();

    public boolean telegramEnabled = true;
    /** The only Telegram user (and private chat) allowed to give commands. */
    public long telegramOwnerId = 6259839862L;
    public String telegramBotUsername = "AbuPixels_bot";
    public String telegramBaseUrl = "https://api.telegram.org";
    /** Commands that waited in Telegram while the game was closed are NOT executed if older than this. */
    public int staleCommandMinutes = 15;

    public String botName = "مساعد";
    /** Skin PNG copied into BBS assets (relative asset link), empty = default. */
    public String botSkinLink = "";

    public int readTimeoutSeconds = 120;
    public int maxAgentSteps = 400;
    public boolean backupBeforeWorldEdit = true;
    public boolean allowCommandTool = true;
    /** auto | low | medium | high */
    public String screenshotQuality = "auto";
    public String playerName = "";

    public ProviderConfig provider(String id)
    {
        for (ProviderConfig p : providers) if (p.id.equals(id)) return p;
        return null;
    }
}
