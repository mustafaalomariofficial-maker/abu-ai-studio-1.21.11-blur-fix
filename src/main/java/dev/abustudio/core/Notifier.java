package dev.abustudio.core;

/** Arabic notifications go to the in-game chat and to Telegram. */
public interface Notifier
{
    void notify(String arabicText);

    Notifier NONE = t -> Log.info("[إشعار] " + t);
}
