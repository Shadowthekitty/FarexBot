package dev.farex.farexbot;

import com.mojang.authlib.GameProfile;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class FarexBot implements ClientModInitializer {
    public static final String MOD_ID = "farexbot";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final long SEND_COOLDOWN_MS = 1000;
    private static final long ACK_TIMEOUT_MS = 30_000;

    public static FarexConfig config;
    private static KeyBinding pullKey;
    private static long lastSentAt;
    /** Sender side: nonce -> time sent, so we only show acks for our own requests. */
    private static final Map<String, Long> PENDING_ACKS = new HashMap<>();

    @Override
    public void onInitializeClient() {
        config = FarexConfig.load();

        pullKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.farexbot.pull", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.farexbot"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (pullKey.wasPressed()) {
                requestPull(client);
            }
            ChamberPuller.tick(client);
        });

        // Vanilla /msg arrives as a chat message with a verified sender profile.
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signedMessage, sender, params, timestamp) ->
                handleIncoming(message, sender));
        // Plugin servers (Essentials etc.) often deliver whispers as system messages instead.
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) ->
                overlay || handleIncoming(message, null));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                FarexCommands.register(dispatcher));
    }

    /** Sender side: whisper a signed pull request to our loader. */
    public static void requestPull(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;
        if (config.loaderName.isBlank() || config.secret.isBlank()) {
            notify(client, Text.literal("Not paired. Use /farex pair <loader> <secret>").formatted(Formatting.RED));
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSentAt < SEND_COOLDOWN_MS) return;
        lastSentAt = now;

        String nonce = Protocol.newNonce();
        String self = player.getGameProfile().getName();
        String payload = Protocol.buildPull(self, config.loaderName, config.secret, nonce);
        PENDING_ACKS.values().removeIf(sentAt -> now - sentAt > ACK_TIMEOUT_MS);
        PENDING_ACKS.put(nonce, now);
        player.networkHandler.sendChatCommand(config.whisperCommand + " " + config.loaderName + " " + payload);
        notify(client, Text.literal("Pearl request sent to " + config.loaderName + "...").formatted(Formatting.YELLOW));
    }

    /** @return false to hide the message from chat. */
    private static boolean handleIncoming(Text message, GameProfile sender) {
        String text = message.getString();
        Protocol.Pull pull = Protocol.parsePull(text);
        if (pull != null) {
            onPull(pull, sender);
            return !config.hideMessages;
        }
        Protocol.Ack ack = Protocol.parseAck(text);
        if (ack != null) {
            onAck(ack);
            return !config.hideMessages;
        }
        return true;
    }

    /** Loader side. */
    private static void onPull(Protocol.Pull pull, GameProfile sender) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) return;
        String self = player.getGameProfile().getName();

        // Our own outgoing whisper echoed back to us.
        if (pull.sender().equalsIgnoreCase(self)) return;
        // When the server tells us who really sent it, it must match the claimed name.
        if (sender != null && !sender.getName().equalsIgnoreCase(pull.sender())) return;

        FarexConfig.Chamber chamber = config.findChamber(pull.sender());
        if (chamber == null || chamber.secret == null) return;
        if (!Protocol.verifyPull(pull, self, chamber.secret)) {
            LOGGER.warn("Rejected pull request claiming to be from {}", pull.sender());
            return;
        }

        ChamberPuller.Result result = ChamberPuller.pull(client, chamber);
        LOGGER.info("Pull for {}: {}", chamber.friend, result);
        Formatting color = result == ChamberPuller.Result.OK ? Formatting.GREEN : Formatting.RED;
        notify(client, Text.literal("Pulled " + chamber.friend + "'s chamber: " + result.code).formatted(color));

        if (config.sendAck) {
            String ack = Protocol.buildAck(pull.nonce(), result.code, chamber.secret);
            player.networkHandler.sendChatCommand(config.whisperCommand + " " + chamber.friend + " " + ack);
        }
    }

    /** Sender side. */
    private static void onAck(Protocol.Ack ack) {
        if (!PENDING_ACKS.containsKey(ack.nonce())) return;
        if (config.secret.isBlank() || !Protocol.verifyAck(ack, config.secret)) return;
        PENDING_ACKS.remove(ack.nonce());

        MinecraftClient client = MinecraftClient.getInstance();
        if (ack.status().equals(ChamberPuller.Result.OK.code)) {
            notify(client, Text.literal("Pearl pulled! Teleporting...").formatted(Formatting.GREEN));
        } else {
            notify(client, Text.literal("Loader could not pull: " + ack.status().replace('_', ' '))
                    .formatted(Formatting.RED));
        }
    }

    public static void notify(MinecraftClient client, Text text) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal("[FarexBot] ").formatted(Formatting.AQUA).append(text), false);
        }
    }
}
