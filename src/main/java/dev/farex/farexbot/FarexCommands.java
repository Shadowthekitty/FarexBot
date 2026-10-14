package dev.farex.farexbot;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * /farex version                             show the installed version
 * /farex secret                              generate a random shared secret
 * /farex pair &lt;loader&gt; &lt;secret&gt;            (sender) who pulls my chamber
 * /farex pull                                (sender) same as the keybind
 * /farex unpair                              (sender) forget my loader and tell them
 * /farex unpair &lt;friend&gt;                     (loader) unpair a friend (chamber kept) and tell them
 * /farex chamber pair &lt;friend&gt; &lt;secret&gt;     (loader) re-pair a kept chamber
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
                .then(literal("version").executes(ctx -> {
                    ctx.getSource().sendFeedback(Component.literal("FarexBot " + FarexBot.version()));
                    return 1;
                }))
                .then(literal("secret").executes(FarexCommands::generateSecret))
                .then(literal("pair")
                        .then(argument("loader", StringArgumentType.word())
                                .then(argument("secret", StringArgumentType.word())
                                        .executes(FarexCommands::pair))))
                .then(literal("unpair")
                        .executes(FarexCommands::unpairLoader)
                        .then(argument("friend", StringArgumentType.word())
                                .executes(FarexCommands::unpairFriend)))
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
                        .then(literal("pair")
                                .then(argument("friend", StringArgumentType.word())
                                        .then(argument("secret", StringArgumentType.word())
                                                .executes(FarexCommands::repairChamber))))
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
        Component clickable = Component.literal(secret).setStyle(Style.EMPTY
                .withColor(ChatFormatting.GOLD)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.CopyToClipboard(secret))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy"))));
        ctx.getSource().sendFeedback(Component.literal("New secret (send it to your friend privately): ").append(clickable));
        return 1;
    }

    private static int pair(CommandContext<FabricClientCommandSource> ctx) {
        FarexBot.config.loaderName = StringArgumentType.getString(ctx, "loader");
        FarexBot.config.secret = StringArgumentType.getString(ctx, "secret");
        FarexBot.config.save();
        ctx.getSource().sendFeedback(Component.literal("Paired with loader " + FarexBot.config.loaderName
                + ". Press your FarexBot key to pull your pearl.").withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int unpairLoader(CommandContext<FabricClientCommandSource> ctx) {
        String loader = FarexBot.config.loaderName;
        if (!FarexBot.unpairFromLoader(ctx.getSource().getClient())) {
            ctx.getSource().sendError(Component.literal("You are not paired with a loader."
                    + " To drop a friend's chamber use /farex unpair <friend>"));
            return 0;
        }
        ctx.getSource().sendFeedback(Component.literal("Unpaired from " + loader + ". They have been told to drop"
                + " your chamber.").withStyle(ChatFormatting.GOLD));
        return 1;
    }

    private static int unpairFriend(CommandContext<FabricClientCommandSource> ctx) {
        String friend = StringArgumentType.getString(ctx, "friend");
        if (!FarexBot.unpairFriend(ctx.getSource().getClient(), friend)) {
            ctx.getSource().sendError(Component.literal("No paired chamber for " + friend));
            return 0;
        }
        ctx.getSource().sendFeedback(Component.literal("Unpaired " + friend + " and told them. Their chamber"
                + " position is kept; re-pair with /farex chamber pair " + friend + " <secret>")
                .withStyle(ChatFormatting.GOLD));
        return 1;
    }

    private static int setWhisper(CommandContext<FabricClientCommandSource> ctx) {
        String command = StringArgumentType.getString(ctx, "command");
        FarexBot.config.whisperCommand = command.startsWith("/") ? command.substring(1) : command;
        FarexBot.config.save();
        ctx.getSource().sendFeedback(Component.literal("Whispers will use /" + FarexBot.config.whisperCommand));
        return 1;
    }

    private static int addChamber(CommandContext<FabricClientCommandSource> ctx) {
        Minecraft client = ctx.getSource().getClient();
        if (!(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
                || client.level == null) {
            ctx.getSource().sendError(Component.literal("Look at the chamber's trapdoor / lever / button first."));
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
        chamber.dimension = FarexBot.dimensionId(client.level);
        chamber.x = pos.getX();
        chamber.y = pos.getY();
        chamber.z = pos.getZ();
        FarexBot.config.save();

        String block = client.level.getBlockState(pos).getBlock().getName().getString();
        ctx.getSource().sendFeedback(Component.literal("Chamber for " + friend + " set to " + block + " at "
                + pos.toShortString()).withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int repairChamber(CommandContext<FabricClientCommandSource> ctx) {
        String friend = StringArgumentType.getString(ctx, "friend");
        FarexConfig.Chamber chamber = FarexBot.config.findChamber(friend);
        if (chamber == null) {
            ctx.getSource().sendError(Component.literal("No chamber for " + friend
                    + ". Look at it and use /farex chamber add " + friend + " <secret>"));
            return 0;
        }
        chamber.secret = StringArgumentType.getString(ctx, "secret");
        FarexBot.config.save();
        ctx.getSource().sendFeedback(Component.literal("Re-paired " + chamber.friend + "'s chamber at "
                + chamber.x + ", " + chamber.y + ", " + chamber.z).withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int removeChamber(CommandContext<FabricClientCommandSource> ctx) {
        String friend = StringArgumentType.getString(ctx, "friend");
        FarexConfig.Chamber chamber = FarexBot.config.findChamber(friend);
        if (chamber == null) {
            ctx.getSource().sendError(Component.literal("No chamber for " + friend));
            return 0;
        }
        FarexBot.config.chambers.remove(chamber);
        FarexBot.config.save();
        ctx.getSource().sendFeedback(Component.literal("Removed chamber for " + friend));
        return 1;
    }

    private static int setReset(CommandContext<FabricClientCommandSource> ctx) {
        String friend = StringArgumentType.getString(ctx, "friend");
        FarexConfig.Chamber chamber = FarexBot.config.findChamber(friend);
        if (chamber == null) {
            ctx.getSource().sendError(Component.literal("No chamber for " + friend));
            return 0;
        }
        chamber.resetAfterTicks = IntegerArgumentType.getInteger(ctx, "ticks");
        FarexBot.config.save();
        ctx.getSource().sendFeedback(Component.literal(chamber.resetAfterTicks == 0
                ? "Reset disabled for " + friend
                : "Will flip " + friend + "'s chamber back after " + chamber.resetAfterTicks + " ticks"));
        return 1;
    }

    private static int testChamber(CommandContext<FabricClientCommandSource> ctx) {
        String friend = StringArgumentType.getString(ctx, "friend");
        FarexConfig.Chamber chamber = FarexBot.config.findChamber(friend);
        if (chamber == null) {
            ctx.getSource().sendError(Component.literal("No chamber for " + friend));
            return 0;
        }
        Minecraft client = ctx.getSource().getClient();
        ChamberPuller.pull(chamber, result -> FarexBot.notify(client, Component.literal("Test pull: " + result.code)
                .withStyle(result == ChamberPuller.Result.OK ? ChatFormatting.GREEN : ChatFormatting.RED)));
        return 1;
    }

    private static int listChambers(CommandContext<FabricClientCommandSource> ctx) {
        FarexConfig config = FarexBot.config;
        ctx.getSource().sendFeedback(Component.literal(config.loaderName.isBlank()
                ? "Sender: not paired"
                : "Sender: paired with " + config.loaderName + " (whisper: /" + config.whisperCommand + ")"));
        if (config.chambers.isEmpty()) {
            ctx.getSource().sendFeedback(Component.literal("Loader: no chambers"));
        }
        for (FarexConfig.Chamber c : config.chambers) {
            ctx.getSource().sendFeedback(Component.literal(" - " + c.friend + ": " + c.x + ", " + c.y + ", " + c.z
                    + " in " + c.dimension + (c.secret == null ? " (unpaired)" : "") + (c.resetAfterTicks > 0 ? " (reset " + c.resetAfterTicks + "t)" : "")));
        }
        return 1;
    }
}
