package dev.abustudio.client;

import dev.abustudio.game.Studio;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class AbuStudioClient implements ClientModInitializer
{
    private static KeyBinding open;

    @Override
    public void onInitializeClient()
    {
        Studio s = Studio.init();
        s.client = new ClientShots(s);

        open = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.abu_studio.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K,
            KeyBinding.Category.create(Identifier.of("abu_studio", "main"))));

        ClientTickEvents.END_CLIENT_TICK.register(mc ->
        {
            if (mc.textRenderer != null) dev.abustudio.core.ArabicText.clientHandlesBidi = mc.textRenderer.isRightToLeft();
            while (open.wasPressed()) if (mc.currentScreen == null) mc.setScreen(new StudioScreen(s));
        });
    }
}
