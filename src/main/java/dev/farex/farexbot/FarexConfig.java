package dev.farex.farexbot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Stored in config/farexbot.json.
 *
 * <p>The same mod plays both roles:
 * <ul>
 *   <li><b>Sender</b> (the friend who owns the pearl): {@link #loaderName} + {@link #secret} say who to whisper
 *       when the keybind is pressed.</li>
 *   <li><b>Loader</b> (the account AFK at the chambers): {@link #chambers} maps each friend to the block
 *       (trapdoor / lever / button) that drops their pearl.</li>
 * </ul>
 */
public class FarexConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("farexbot.json");

    /** Sender side: the player sitting at your chamber. */
    public String loaderName = "";
    /** Sender side: secret shared with your loader. */
    public String secret = "";
    /** Whisper command used on this server (msg, w, tell, whisper...). */
    public String whisperCommand = "msg";
    /** Hide FarexBot whispers from chat on both sides. */
    public boolean hideMessages = true;
    /** Loader side: whisper back whether the pull worked. */
    public boolean sendAck = true;
    /** Loader side: the chambers this account is responsible for. */
    public List<Chamber> chambers = new ArrayList<>();

    public static class Chamber {
        public String friend;
        public String secret;
        public String dimension;
        public int x, y, z;
        /** If > 0, interact again after this many ticks (e.g. close a trapdoor again). */
        public int resetAfterTicks = 0;

        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }
    }

    public Chamber findChamber(String friend) {
        String key = friend.toLowerCase(Locale.ROOT);
        for (Chamber c : chambers) {
            if (c.friend != null && c.friend.toLowerCase(Locale.ROOT).equals(key)) {
                return c;
            }
        }
        return null;
    }

    public static FarexConfig load() {
        if (Files.exists(PATH)) {
            try (Reader reader = Files.newBufferedReader(PATH)) {
                FarexConfig config = GSON.fromJson(reader, FarexConfig.class);
                if (config != null) {
                    if (config.chambers == null) config.chambers = new ArrayList<>();
                    return config;
                }
            } catch (Exception e) {
                FarexBot.LOGGER.error("Could not read {}, using defaults", PATH, e);
            }
        }
        FarexConfig config = new FarexConfig();
        config.save();
        return config;
    }

    public void save() {
        try {
            Files.createDirectories(PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(PATH)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            FarexBot.LOGGER.error("Could not save {}", PATH, e);
        }
    }
}
