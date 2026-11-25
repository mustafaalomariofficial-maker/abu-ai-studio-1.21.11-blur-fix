# AbuPixels AI Studio — استوديو الأنيميشن الذكي

An AI animation studio addon for BBS FS on Fabric: write an Arabic story in game, and an agent plans and builds the BBS film with tools.

- Minecraft 1.21.11, Fabric, Java 21, BBS FS 2.7-1.21.11 (official Modrinth release, SHA-512 verified in CI)
- Arabic UI and chat: shaping and right-to-left order come from Minecraft itself when the game language is right to left, and from the mod when it is not
- Live AI status: idle, thinking, running tool, elapsed wait, no provider configured, errors
- Shows the provider and model that actually answered, updated on failover
- Provider list, model discovery, capability probing, automatic failover, local providers, Telegram bot

## Install

1. Fabric Loader for 1.21.11 and Fabric API
2. BBS FS 2.7-1.21.11
3. This JAR in `mods/`

Press `K` in game (or `/abu say`) to open the studio. A key is required first: see [API-SETUP-ar.md](API-SETUP-ar.md).
No API key is bundled in this mod or in this repository.

## Build

CI builds and tests on GitHub Actions and publishes the JAR as a release asset. Locally: `gradle build`
(the BBS jar is downloaded by CI into `libs/`).

## Not verified yet

In-game rendering of shaped Arabic on a real client, screen responsiveness with a long chat log, a real Groq
key round trip, and Telegram replies with a real bot token were not exercised by the automated tests.

MIT.
