package dev.farex.farexbot;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
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
    private static KeyMapping pullKey;
    private static long lastSentAt;
    /** Sender side: nonce -> time sent, so we only show acks for our own requests. */
    private static final Map<String, Long> PENDING_ACKS = new HashMap<>();

    @Override
    public void onInitializeClient() {
        config = FarexConfig.load();

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, MOD_ID));
        pullKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.farexbot.pull", InputConstants.Type.KEYBOARD, InputConstants.KEY_G, category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (pullKey.consumeClick()) {
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

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                FarexCommands.register(dispatcher));
    }

    /** Sender side: whisper a signed pull request to our loader. */
    public static void requestPull(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) return;
        if (config.loaderName.isBlank() || config.secret.isBlank()) {
            notify(client, Component.literal("Not paired. Use /farex pair <loader> <secret>")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSentAt < SEND_COOLDOWN_MS) return;
        lastSentAt = now;

        String nonce = Protocol.newNonce();
        String payload = Protocol.buildPull(playerName(player), config.loaderName, config.secret, nonce);
        PENDING_ACKS.values().removeIf(sentAt -> now - sentAt > ACK_TIMEOUT_MS);
        PENDING_ACKS.put(nonce, now);
        player.connection.sendCommand(config.whisperCommand + " " + config.loaderName + " " + payload);
        notify(client, Component.literal("Pearl request sent to " + config.loaderName + "...")
                .withStyle(ChatFormatting.YELLOW));
    }

    /** @return false to hide the message from chat. */
    private static boolean handleIncoming(Component message, GameProfile sender) {
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
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) return;
        String self = playerName(player);

        // Our own outgoing whisper echoed back to us.
        if (pull.sender().equalsIgnoreCase(self)) return;
        // When the server tells us who really sent it, it must match the claimed name.
        if (sender != null && !sender.name().equalsIgnoreCase(pull.sender())) return;

        FarexConfig.Chamber chamber = config.findChamber(pull.sender());
        if (chamber == null || chamber.secret == null) return;
        if (!Protocol.verifyPull(pull, self, chamber.secret)) {
            LOGGER.warn("Rejected pull request claiming to be from {}", pull.sender());
            return;
        }

        ChamberPuller.Result result = ChamberPuller.pull(client, chamber);
        LOGGER.info("Pull for {}: {}", chamber.friend, result);
        ChatFormatting color = result == ChamberPuller.Result.OK ? ChatFormatting.GREEN : ChatFormatting.RED;
        notify(client, Component.literal("Pulled " + chamber.friend + "'s chamber: " + result.code).withStyle(color));

        if (config.sendAck) {
            String ack = Protocol.buildAck(pull.nonce(), result.code, chamber.secret);
            player.connection.sendCommand(config.whisperCommand + " " + chamber.friend + " " + ack);
        }
    }

    /** Sender side. */
    private static void onAck(Protocol.Ack ack) {
        if (!PENDING_ACKS.containsKey(ack.nonce())) return;
        if (config.secret.isBlank() || !Protocol.verifyAck(ack, config.secret)) return;
        PENDING_ACKS.remove(ack.nonce());

        Minecraft client = Minecraft.getInstance();
        if (ack.status().equals(ChamberPuller.Result.OK.code)) {
            notify(client, Component.literal("Pearl pulled! Teleporting...").withStyle(ChatFormatting.GREEN));
        } else {
            notify(client, Component.literal("Loader could not pull: " + ack.status().replace('_', ' '))
                    .withStyle(ChatFormatting.RED));
        }
    }

    public static void notify(Minecraft client, Component text) {
        if (client.player != null) {
            client.player.sendSystemMessage(
                    Component.literal("[FarexBot] ").withStyle(ChatFormatting.AQUA).append(text));
        }
    }

    static String playerName(LocalPlayer player) {
        return player.getGameProfile().name();
    }

    static String dimensionId(Level level) {
        return level.dimension().identifier().toString();
    }
}
