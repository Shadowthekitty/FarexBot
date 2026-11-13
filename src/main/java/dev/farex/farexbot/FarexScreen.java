package dev.farex.farexbot;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;
import java.util.function.Consumer;

/**
 * The FarexBot menu: everything the /farex commands do, on one screen.
 *
 * <pre>
 *   My loader        [loader name] [secret] [Pair] [Unpair]
 *   Chambers I load  one row per friend: [Test] [Reset] [Pair/Unpair] [x]
 *   Add chamber      [friend name] [secret] [New secret] [Add chamber]
 *   Settings         [Hide whispers] [Confirm pulls] whisper command
 * </pre>
 */
public class FarexScreen extends Screen {
    private static final int PANEL_WIDTH = 320;
    private static final int ROW_HEIGHT = 22;
    private static final int CHAMBERS_Y = 72;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFA0A0A0;
    private static final int GREEN = 0xFF55FF55;
    private static final int RED = 0xFFFF5555;
    private static final int[] RESET_STEPS = {0, 10, 20, 40, 60, 100, 200};
    private static final String NAME_PATTERN = "[A-Za-z0-9_]{1,16}";

    private final FarexConfig config = FarexBot.config;
    /** The block the player was looking at when the menu opened: the target for "Add chamber". */
    private final BlockPos targetPos;
    private final String targetName;
    private final String targetDimension;

    private String loaderInput;
    private String secretInput;
    private String friendInput = "";
    private String newSecretInput = "";
    private int page;
    private Component status = Component.empty();
    private int statusColor = WHITE;

    private int left;
    private int rowsPerPage;
    private int addY;
    private int settingsY;
    private int statusY;

    public FarexScreen() {
        super(Component.literal("FarexBot " + FarexBot.version()));
        loaderInput = config.loaderName;
        secretInput = config.secret;

        Minecraft client = Minecraft.getInstance();
        if (client.level != null && client.hitResult instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK) {
            targetPos = hit.getBlockPos();
            targetName = client.level.getBlockState(targetPos).getBlock().getName().getString();
            targetDimension = FarexBot.dimensionId(client.level);
        } else {
            targetPos = null;
            targetName = null;
            targetDimension = null;
        }
    }

    @Override
    protected void init() {
        left = width / 2 - PANEL_WIDTH / 2;
        int right = left + PANEL_WIDTH;
        rowsPerPage = Math.clamp((height - CHAMBERS_Y - 100) / ROW_HEIGHT, 1, 8);
        addY = CHAMBERS_Y + rowsPerPage * ROW_HEIGHT + 2;
        settingsY = addY + 36;
        statusY = settingsY + 24;
        page = Math.clamp(page, 0, pageCount() - 1);

        // My loader
        box(left, 34, 96, "Loader name", loaderInput, 16, value -> loaderInput = value);
        box(left + 100, 34, 96, "Secret", secretInput, 64, value -> secretInput = value);
        button(left + 200, 34, 58, "Pair", "Set this player as the one who pulls your chamber", b -> pairLoader());
        button(left + 262, 34, 58, "Unpair", "Forget your loader and tell them", b -> unpairLoader())
                .active = !config.loaderName.isBlank();

        // Chambers I load
        List<FarexConfig.Chamber> chambers = config.chambers;
        for (int i = 0; i < rowsPerPage; i++) {
            int index = page * rowsPerPage + i;
            if (index >= chambers.size()) break;
            FarexConfig.Chamber chamber = chambers.get(index);
            int y = CHAMBERS_Y + i * ROW_HEIGHT;
            boolean paired = chamber.secret != null;

            button(right - 178, y, 36, "Test", "Pull this chamber right now", b -> testChamber(chamber));
            button(right - 138, y, 62, chamber.resetAfterTicks > 0 ? "Reset " + chamber.resetAfterTicks + "t" : "Reset off",
                    "Flip the block back this many ticks after a pull (20 ticks = 1 second)", b -> cycleReset(chamber));
            if (paired) {
                button(right - 72, y, 48, "Unpair", "Stop pulling for this friend and tell them. The position is kept.",
                        b -> unpairFriend(chamber));
            } else {
                button(right - 72, y, 48, "Pair", "Re-pair using the secret typed in the Add chamber row",
                        b -> repairChamber(chamber));
            }
            button(right - 20, y, 20, "x", "Delete this chamber", b -> removeChamber(chamber));
        }

        // Add chamber
        box(left, addY + 12, 80, "Friend name", friendInput, 16, value -> friendInput = value);
        box(left + 84, addY + 12, 80, "Secret", newSecretInput, 64, value -> newSecretInput = value);
        button(left + 168, addY + 12, 70, "New secret", "Generate a secret and copy it to your clipboard",
                b -> generateSecret());
        button(left + 242, addY + 12, 78, "Add chamber", "Register the block you were looking at for this friend",
                b -> addChamber()).active = targetPos != null;

        // Settings
        button(left, settingsY, 106, "Hide whispers: " + onOff(config.hideMessages),
                "Hide FarexBot's whispers from your chat", b -> {
                    config.hideMessages = !config.hideMessages;
                    saveAndRebuild();
                });
        button(left + 110, settingsY, 106, "Confirm pulls: " + onOff(config.sendAck),
                "As a loader, whisper back whether the pull worked", b -> {
                    config.sendAck = !config.sendAck;
                    saveAndRebuild();
                });
        box(left + 268, settingsY, 52, "msg", config.whisperCommand, 16, value -> {
            String command = value.startsWith("/") ? value.substring(1) : value;
            if (command.matches("[A-Za-z0-9_:-]+")) config.whisperCommand = command;
        });

        // Bottom row
        int bottom = height - 24;
        button(left, bottom, 110, "Pull my pearl", "Ask your loader to pull your chamber (same as the keybind)", b -> {
            onClose();
            FarexBot.requestPull(minecraft);
        }).active = !config.loaderName.isBlank();
        button(width / 2 - 34, bottom, 20, "<", "Previous page", b -> {
            page--;
            rebuildWidgets();
        }).active = page > 0;
        button(width / 2 + 14, bottom, 20, ">", "Next page", b -> {
            page++;
            rebuildWidgets();
        }).active = page < pageCount() - 1;
        button(right - 110, bottom, 110, "Done", null, b -> onClose());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, 8, WHITE);

        if (config.loaderName.isBlank()) {
            graphics.text(font, "My loader: not paired", left, 22, GRAY);
        } else {
            graphics.text(font, "My loader: " + config.loaderName, left, 22, GREEN);
        }

        graphics.text(font, "Chambers I load (" + config.chambers.size() + ")", left, CHAMBERS_Y - 12, WHITE);
        if (config.chambers.isEmpty()) {
            graphics.text(font, "None yet. Add one below.", left, CHAMBERS_Y + 6, GRAY);
        }
        for (int i = 0; i < rowsPerPage; i++) {
            int index = page * rowsPerPage + i;
            if (index >= config.chambers.size()) break;
            FarexConfig.Chamber chamber = config.chambers.get(index);
            int y = CHAMBERS_Y + i * ROW_HEIGHT;
            boolean paired = chamber.secret != null;
            graphics.text(font, chamber.friend + (paired ? "" : " (unpaired)"), left, y + 1, paired ? GREEN : RED);
            graphics.text(font, chamber.x + ", " + chamber.y + ", " + chamber.z + " " + shortDimension(chamber.dimension),
                    left, y + 11, GRAY);
        }

        if (targetPos != null) {
            graphics.text(font, "Add chamber at: " + targetName + " (" + targetPos.toShortString() + ")", left, addY, WHITE);
        } else {
            graphics.text(font, "Add chamber: close this and look at the trapdoor/lever first", left, addY, GRAY);
        }

        graphics.text(font, "Whisper /", left + 222, settingsY + 6, GRAY);
        graphics.centeredText(font, (page + 1) + "/" + pageCount(), width / 2, height - 18, GRAY);
        graphics.centeredText(font, status, width / 2, statusY, statusColor);
    }

    @Override
    public void removed() {
        config.save();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void pairLoader() {
        String loader = loaderInput.trim();
        String secret = secretInput.trim();
        if (!loader.matches(NAME_PATTERN)) {
            fail("Type your loader's exact player name.");
            return;
        }
        if (!validSecret(secret)) {
            fail("Type the secret your loader gave you (no spaces).");
            return;
        }
        config.loaderName = loader;
        config.secret = secret;
        ok("Paired with " + loader + ". Press your pull key to come home.");
        saveAndRebuild();
    }

    private void unpairLoader() {
        String loader = config.loaderName;
        if (FarexBot.unpairFromLoader(minecraft)) {
            loaderInput = "";
            secretInput = "";
            ok("Unpaired from " + loader + ".");
        }
        rebuildWidgets();
    }

    private void testChamber(FarexConfig.Chamber chamber) {
        ok("Pulling " + chamber.friend + "'s chamber...");
        ChamberPuller.pull(chamber, result -> {
            if (result == ChamberPuller.Result.OK) {
                ok("Test pull for " + chamber.friend + " worked.");
            } else {
                fail("Test pull for " + chamber.friend + " failed: " + result.code.replace('_', ' '));
            }
        });
    }

    private void cycleReset(FarexConfig.Chamber chamber) {
        int next = RESET_STEPS[0];
        for (int step : RESET_STEPS) {
            if (step > chamber.resetAfterTicks) {
                next = step;
                break;
            }
        }
        chamber.resetAfterTicks = next;
        saveAndRebuild();
    }

    private void unpairFriend(FarexConfig.Chamber chamber) {
        if (FarexBot.unpairFriend(minecraft, chamber.friend)) {
            ok("Unpaired " + chamber.friend + ". Their chamber position is kept.");
        }
        rebuildWidgets();
    }

    private void repairChamber(FarexConfig.Chamber chamber) {
        String secret = newSecretInput.trim();
        if (!validSecret(secret)) {
            fail("Type or generate a secret in the Add chamber row first.");
            return;
        }
        chamber.secret = secret;
        ok("Re-paired " + chamber.friend + ". Send them the secret.");
        saveAndRebuild();
    }

    private void removeChamber(FarexConfig.Chamber chamber) {
        config.chambers.remove(chamber);
        ok("Deleted " + chamber.friend + "'s chamber.");
        saveAndRebuild();
    }

    private void generateSecret() {
        newSecretInput = Protocol.newSecret();
        minecraft.keyboardHandler.setClipboard(newSecretInput);
        ok("Secret copied to your clipboard. Send it to your friend privately.");
        rebuildWidgets();
    }

    private void addChamber() {
        String friend = friendInput.trim();
        String secret = newSecretInput.trim();
        if (targetPos == null) return;
        if (!friend.matches(NAME_PATTERN)) {
            fail("Type your friend's exact player name.");
            return;
        }
        if (!validSecret(secret)) {
            fail("Type a secret or press New secret.");
            return;
        }
        FarexConfig.Chamber chamber = config.findChamber(friend);
        if (chamber == null) {
            chamber = new FarexConfig.Chamber();
            config.chambers.add(chamber);
        }
        chamber.friend = friend;
        chamber.secret = secret;
        chamber.dimension = targetDimension;
        chamber.x = targetPos.getX();
        chamber.y = targetPos.getY();
        chamber.z = targetPos.getZ();
        friendInput = "";
        ok("Chamber for " + friend + " set to " + targetName + ". Send them the secret.");
        saveAndRebuild();
    }

    private void saveAndRebuild() {
        config.save();
        rebuildWidgets();
    }

    private int pageCount() {
        return Math.max(1, (config.chambers.size() + rowsPerPage - 1) / Math.max(1, rowsPerPage));
    }

    private void ok(String message) {
        status = Component.literal(message);
        statusColor = GREEN;
    }

    private void fail(String message) {
        status = Component.literal(message);
        statusColor = RED;
    }

    private static boolean validSecret(String secret) {
        return secret.matches("\\S{1,64}");
    }

    private static String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }

    private static String shortDimension(String dimension) {
        if (dimension == null) return "";
        return dimension.startsWith("minecraft:") ? dimension.substring("minecraft:".length()) : dimension;
    }

    private EditBox box(int x, int y, int boxWidth, String hint, String value, int maxLength, Consumer<String> responder) {
        EditBox box = new EditBox(font, x, y, boxWidth, 20, Component.literal(hint));
        box.setMaxLength(maxLength);
        box.setValue(value);
        box.setHint(Component.literal(hint).withStyle(ChatFormatting.DARK_GRAY));
        box.setResponder(responder);
        return addRenderableWidget(box);
    }

    private Button button(int x, int y, int buttonWidth, String label, String tooltip, Button.OnPress onPress) {
        Button.Builder builder = Button.builder(Component.literal(label), onPress).bounds(x, y, buttonWidth, 20);
        if (tooltip != null) builder.tooltip(Tooltip.create(Component.literal(tooltip)));
        return addRenderableWidget(builder.build());
    }
}
