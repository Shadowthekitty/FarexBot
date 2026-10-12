package dev.farex.farexbot;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * /farex secret                              generate a random shared secret
 * /farex pair &lt;loader&gt; &lt;secret&gt;            (sender) who pulls my chamber
 * /farex pull                                (sender) same as the keybind
 * /farex whisper &lt;command&gt;                  whisper command used on this server
 * /farex chamber add &lt;friend&gt; &lt;secret&gt;      (loader) register the block you are looking at
 * /farex chamber remove &lt;friend&gt;
 * /farex chamber reset &lt;friend&gt; &lt;ticks&gt;     (loader) re-flip the block after N ticks, 0 = off
 * /farex chamber test &lt;friend&gt;              (loader) pull it right now
 * /farex chamber list
 */
public final class FarexCommands {
    private FarexCommands() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("farex")
                .then(literal("secret").executes(FarexCommands::generateSecret))
                .then(literal("pair")
                        .then(argument("loader", StringArgumentType.word())
                                .then(argument("secret", StringArgumentType.word())
                                        .executes(FarexCommands::pair))))
                .then(literal("pull").executes(ctx -> {
                    FarexBot.requestPull(ctx.getSource().getClient());
                    return 1;
                }))
                .then(literal("whisper")
                        .then(argument("command", StringArgumentType.word())
                                .executes(FarexCommands::setWhisper)))
                .then(literal("chamber")
                        .then(literal("add")
                                .then(argument("friend", StringArgumentType.word())
                                        .then(argument("secret", StringArgumentType.word())
                                                .executes(FarexCommands::addChamber))))
                        .then(literal("remove")
                                .then(argument("friend", StringArgumentType.word())
                                        .executes(FarexCommands::removeChamber)))
                        .then(literal("reset")
                                .then(argument("friend", StringArgumentType.word())
                                        .then(argument("ticks", IntegerArgumentType.integer(0, 200))
                                                .executes(FarexCommands::setReset))))
                        .then(literal("test")
                                .then(argument("friend", StringArgumentType.word())
                                        .executes(FarexCommands::testChamber)))
                        .then(literal("list").executes(FarexCommands::listChambers))));
    }

    private static int generateSecret(CommandContext<FabricClientCommandSource> ctx) {
        String secret = Protocol.newSecret();
        Text clickable = Text.literal(secret).setStyle(Style.EMPTY
                .withColor(Formatting.GOLD)
                .withUnderline(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, secret))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Click to copy"))));
        ctx.getSource().sendFeedback(Text.literal("New secret (send it to your friend privately): ").append(clickable));
        return 1;
    }

    private static int pair(CommandContext<FabricClientCommandSource> ctx) {
        FarexBot.config.loaderName = StringArgumentType.getString(ctx, "loader");
        FarexBot.config.secret = StringArgumentType.getString(ctx, "secret");
        FarexBot.config.save();
        ctx.getSource().sendFeedback(Text.literal("Paired with loader " + FarexBot.config.loaderName
                + ". Press your FarexBot key to pull your pearl.").formatted(Formatting.GREEN));
        return 1;
    }

    private static int setWhisper(CommandContext<FabricClientCommandSource> ctx) {
        String command = StringArgumentType.getString(ctx, "command");
        FarexBot.config.whisperCommand = command.startsWith("/") ? command.substring(1) : command;
        FarexBot.config.save();
        ctx.getSource().sendFeedback(Text.literal("Whispers will use /" + FarexBot.config.whisperCommand));
        return 1;
    }

    private static int addChamber(CommandContext<FabricClientCommandSource> ctx) {
        MinecraftClient client = ctx.getSource().getClient();
        if (!(client.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
                || client.world == null) {
            ctx.getSource().sendError(Text.literal("Look at the chamber's trapdoor / lever / button first."));
            return 0;
        }
        String friend = StringArgumentType.getString(ctx, "friend");
        FarexConfig.Chamber chamber = FarexBot.config.findChamber(friend);
        if (chamber == null) {
            chamber = new FarexConfig.Chamber();
            FarexBot.config.chambers.add(chamber);
        }
        BlockPos pos = hit.getBlockPos();
        chamber.friend = friend;
        chamber.secret = StringArgumentType.getString(ctx, "secret");
        chamber.dimension = client.world.getRegistryKey().getValue().toString();
        chamber.x = pos.getX();
        chamber.y = pos.getY();
        chamber.z = pos.getZ();
        FarexBot.config.save();

        String block = client.world.getBlockState(pos).getBlock().getName().getString();
        ctx.getSource().sendFeedback(Text.literal("Chamber for " + friend + " set to " + block + " at "
                + pos.toShortString()).formatted(Formatting.GREEN));
        return 1;
    }

    private static int removeChamber(CommandContext<FabricClientCommandSource> ctx) {
        String friend = StringArgumentType.getString(ctx, "friend");
        FarexConfig.Chamber chamber = FarexBot.config.findChamber(friend);
        if (chamber == null) {
            ctx.getSource().sendError(Text.literal("No chamber for " + friend));
            return 0;
        }
        FarexBot.config.chambers.remove(chamber);
        FarexBot.config.save();
        ctx.getSource().sendFeedback(Text.literal("Removed chamber for " + friend));
        return 1;
    }

    private static int setReset(CommandContext<FabricClientCommandSource> ctx) {
        String friend = StringArgumentType.getString(ctx, "friend");
        FarexConfig.Chamber chamber = FarexBot.config.findChamber(friend);
        if (chamber == null) {
            ctx.getSource().sendError(Text.literal("No chamber for " + friend));
            return 0;
        }
        chamber.resetAfterTicks = IntegerArgumentType.getInteger(ctx, "ticks");
        FarexBot.config.save();
        ctx.getSource().sendFeedback(Text.literal(chamber.resetAfterTicks == 0
                ? "Reset disabled for " + friend
                : "Will flip " + friend + "'s chamber back after " + chamber.resetAfterTicks + " ticks"));
        return 1;
    }

    private static int testChamber(CommandContext<FabricClientCommandSource> ctx) {
        String friend = StringArgumentType.getString(ctx, "friend");
        FarexConfig.Chamber chamber = FarexBot.config.findChamber(friend);
        if (chamber == null) {
            ctx.getSource().sendError(Text.literal("No chamber for " + friend));
            return 0;
        }
        ChamberPuller.Result result = ChamberPuller.pull(ctx.getSource().getClient(), chamber);
        ctx.getSource().sendFeedback(Text.literal("Test pull: " + result.code));
        return result == ChamberPuller.Result.OK ? 1 : 0;
    }

    private static int listChambers(CommandContext<FabricClientCommandSource> ctx) {
        FarexConfig config = FarexBot.config;
        ctx.getSource().sendFeedback(Text.literal(config.loaderName.isBlank()
                ? "Sender: not paired"
                : "Sender: paired with " + config.loaderName + " (whisper: /" + config.whisperCommand + ")"));
        if (config.chambers.isEmpty()) {
            ctx.getSource().sendFeedback(Text.literal("Loader: no chambers"));
        }
        for (FarexConfig.Chamber c : config.chambers) {
            ctx.getSource().sendFeedback(Text.literal(" - " + c.friend + ": " + c.x + ", " + c.y + ", " + c.z
                    + " in " + c.dimension + (c.resetAfterTicks > 0 ? " (reset " + c.resetAfterTicks + "t)" : "")));
        }
        return 1;
    }
}
