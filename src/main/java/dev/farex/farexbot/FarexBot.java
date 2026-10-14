package dev.farex.farexbot;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
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

        // Chamber clicks run at the start of the tick, where vanilla handles input.
        ClientTickEvents.START_CLIENT_TICK.register(ChamberPuller::tick);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (pullKey.consumeClick()) {
                requestPull(client);
            }
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

        String self = playerName(player);
        String nonce = Protocol.newNonce();
        String payload = Protocol.buildPull(self, config.loaderName, config.secret, nonce);

        // Self-test: paired with our own name, so skip the whisper and run the loader side locally.
        if (config.loaderName.equalsIgnoreCase(self)) {
            notify(client, Component.literal("Self-test: you are your own loader, pulling locally...")
                    .withStyle(ChatFormatting.YELLOW));
            executePull(Protocol.parsePull(payload), true);
            return;
        }

        PENDING_ACKS.values().removeIf(sentAt -> now - sentAt > ACK_TIMEOUT_MS);
        PENDING_ACKS.put(nonce, now);
        whisper(player, config.loaderName, payload);
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
        Protocol.Pull unpair = Protocol.parseUnpair(text);
        if (unpair != null) {
            onUnpair(unpair, sender);
            return !config.hideMessages;
        }
        Protocol.Ack ack = Protocol.parseAck(text);
        if (ack != null) {
            onAck(ack);
            return !config.hideMessages;
        }
        return true;
    }

    /** Loader side: a pull request arrived in chat. */
    private static void onPull(Protocol.Pull pull, GameProfile sender) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        // Our own outgoing whisper echoed back to us.
        if (pull.sender().equalsIgnoreCase(playerName(player))) return;
        // When the server tells us who really sent it, it must match the claimed name.
        if (sender != null && !sender.name().equalsIgnoreCase(pull.sender())) {
            LOGGER.warn("Pull claiming to be from {} was actually sent by {}", pull.sender(), sender.name());
            return;
        }
        executePull(pull, false);
    }

    private static void executePull(Protocol.Pull pull, boolean selfTest) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) return;

        FarexConfig.Chamber chamber = config.findChamber(pull.sender());
        if (chamber != null && chamber.secret == null) {
            notify(client, Component.literal("Got a pull request from " + pull.sender() + " but you are unpaired."
                    + " Re-pair with /farex chamber pair " + pull.sender() + " <secret>").withStyle(ChatFormatting.RED));
            return;
        }
        if (chamber == null) {
            notify(client, Component.literal("Got a pull request from " + pull.sender()
                    + " but you have no chamber for them. Use /farex chamber add " + pull.sender() + " <secret>")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (!Protocol.verifyPull(pull, playerName(player), chamber.secret)) {
            notify(client, Component.literal("Rejected pull from " + pull.sender()
                    + ": the secrets don't match, they paired with a different loader name, or your PC clocks"
                    + " are more than a minute apart.").withStyle(ChatFormatting.RED));
            return;
        }

        ChamberPuller.pull(chamber, result -> {
            LOGGER.info("Pull for {}: {}", chamber.friend, result);
            ChatFormatting color = result == ChamberPuller.Result.OK ? ChatFormatting.GREEN : ChatFormatting.RED;
            notify(client, Component.literal("Pulled " + chamber.friend + "'s chamber: " + result.code)
                    .withStyle(color));

            LocalPlayer current = client.player;
            if (config.sendAck && !selfTest && current != null) {
                whisper(current, chamber.friend, Protocol.buildAck(pull.nonce(), result.code, chamber.secret));
            }
        });
    }

    /** Friend side: forget our loader, and tell them to drop our chamber. */
    public static boolean unpairFromLoader(Minecraft client) {
        if (config.loaderName.isBlank()) return false;
        LocalPlayer player = client.player;
        String loader = config.loaderName;
        if (player != null && !config.secret.isBlank() && !loader.equalsIgnoreCase(playerName(player))) {
            whisper(player, loader, Protocol.buildUnpair(playerName(player), loader, config.secret));
        }
        config.loaderName = "";
        config.secret = "";
        config.save();
        return true;
    }

    /** Loader side: unpair a friend (their chamber position is kept), and tell them to forget us. */
    public static boolean unpairFriend(Minecraft client, String friend) {
        FarexConfig.Chamber chamber = config.findChamber(friend);
        if (chamber == null || chamber.secret == null) return false;
        LocalPlayer player = client.player;
        if (player != null && chamber.secret != null && !chamber.friend.equalsIgnoreCase(playerName(player))) {
            whisper(player, chamber.friend, Protocol.buildUnpair(playerName(player), chamber.friend, chamber.secret));
        }
        chamber.secret = null;
        config.save();
        return true;
    }

    /** Either side: the other player unpaired from us. */
    private static void onUnpair(Protocol.Pull unpair, GameProfile sender) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) return;
        String self = playerName(player);
        if (unpair.sender().equalsIgnoreCase(self)) return;
        if (sender != null && !sender.name().equalsIgnoreCase(unpair.sender())) return;

        // Our loader stopped loading for us.
        if (unpair.sender().equalsIgnoreCase(config.loaderName) && !config.secret.isBlank()
                && Protocol.verifyUnpair(unpair, self, config.secret)) {
            config.loaderName = "";
            config.secret = "";
            config.save();
            notify(client, Component.literal(unpair.sender() + " unpaired from you. Your chamber will no longer be pulled.")
                    .withStyle(ChatFormatting.GOLD));
            return;
        }
        // A friend no longer wants us to load their chamber. Keep its position so re-pairing is easy.
        FarexConfig.Chamber chamber = config.findChamber(unpair.sender());
        if (chamber != null && chamber.secret != null && Protocol.verifyUnpair(unpair, self, chamber.secret)) {
            chamber.secret = null;
            config.save();
            notify(client, Component.literal(chamber.friend + " unpaired. Their chamber is kept but won't be"
                    + " pulled until you re-pair with /farex chamber pair " + chamber.friend + " <secret>")
                    .withStyle(ChatFormatting.GOLD));
        }
    }

    private static void whisper(LocalPlayer player, String target, String payload) {
        player.connection.sendCommand(config.whisperCommand + " " + target + " " + payload);
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

    public static String version() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    static String playerName(LocalPlayer player) {
        return player.getGameProfile().name();
    }

    static String dimensionId(Level level) {
        return level.dimension().identifier().toString();
    }
}
