AbuPixels AI Studio 1.1.0 for Minecraft 1.21.11 (Fabric, Java 21, BBS FS 2.7-1.21.11)

Built and tested by GitHub Actions from this repository. The official BBS FS 2.7 jar is downloaded from Modrinth and its SHA-512 is verified during the build. No API key or bot token is stored in this repository.

Fixed
- Arabic no longer reads backwards. Minecraft already reshapes and reorders Arabic when the game language reads right to left; the mod applied the same transform again, so every string was reversed twice. ArabicText now passes logical text through in that case and only shapes and reorders when the client does not.
- K screen lag: wrapped chat lines are cached and rebuilt only when the chat log or the panel width changes, and the status header refreshes at most twice a second instead of every frame. Screen no longer calls renderBackground itself.
- AI activity is visible: idle, thinking, running tool name, elapsed seconds, no provider configured, errors.
- The provider and model that actually answered are shown, and update on failover and after switching models.
- Telegram: when no provider is configured, or every provider fails, the bot answers with an Arabic explanation and concrete setup steps instead of staying silent, and bot replies include the provider and model.

Added
- config/abu-ai-studio.json is read at start (groq_api_key, or a providers array with apiKey/model, plus telegram_token), so a key can be added without opening the game UI.
- Groq preset with openai/gpt-oss-20b, usable from the provider list.
- Connection status line in the studio screen, settings screen and provider editor, with an explicit connection test.

Still needs a real check
- Arabic shaping on a real client in both an Arabic and an English game language.
- K screen responsiveness with a long chat log in real play.
- A real Groq key round trip and Telegram replies with a real bot token.
- Telegram cannot answer while the game process is closed: the bot runs inside the game.
