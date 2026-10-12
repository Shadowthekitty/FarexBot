package dev.farex.farexbot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Flips a chamber's trigger block the instant a request arrives: one rotation packet + one use packet, same tick. */
public final class ChamberPuller {
    public enum Result {
        OK("ok"),
        NOT_IN_WORLD("not_in_world"),
        WRONG_DIMENSION("wrong_dim"),
        TOO_FAR("too_far"),
        MISSING("missing"),
        REJECTED("rejected");

        public final String code;

        Result(String code) {
            this.code = code;
        }
    }

    private static final class PendingReset {
        final FarexConfig.Chamber chamber;
        int ticksLeft;

        PendingReset(FarexConfig.Chamber chamber, int ticksLeft) {
            this.chamber = chamber;
            this.ticksLeft = ticksLeft;
        }
    }

    private static final List<PendingReset> PENDING_RESETS = new ArrayList<>();

    private ChamberPuller() {
    }

    public static Result pull(Minecraft client, FarexConfig.Chamber chamber) {
        Result result = interact(client, chamber);
        if (result == Result.OK && chamber.resetAfterTicks > 0) {
            PENDING_RESETS.add(new PendingReset(chamber, chamber.resetAfterTicks));
        }
        return result;
    }

    public static void tick(Minecraft client) {
        List<PendingReset> due = new ArrayList<>();
        PENDING_RESETS.removeIf(reset -> {
            if (--reset.ticksLeft > 0) return false;
            due.add(reset);
            return true;
        });
        for (PendingReset reset : due) {
            interact(client, reset.chamber);
        }
    }

    private static Result interact(Minecraft client, FarexConfig.Chamber chamber) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null || client.gameMode == null) return Result.NOT_IN_WORLD;
        if (!FarexBot.dimensionId(level).equals(chamber.dimension)) return Result.WRONG_DIMENSION;

        BlockPos pos = chamber.pos();
        Vec3 target = Vec3.atCenterOf(pos);
        Vec3 eyes = player.getEyePosition();
        double reach = player.blockInteractionRange();
        if (eyes.distanceToSqr(target) > reach * reach) return Result.TOO_FAR;
        if (level.getBlockState(pos).isAir()) return Result.MISSING;

        // Face the block server-side before using it so anticheats that check rotation are happy.
        Vec3 d = target.subtract(eyes);
        float yaw = (float) Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.connection.send(
                new ServerboundMovePlayerPacket.Rot(yaw, pitch, player.onGround(), player.horizontalCollision));

        BlockHitResult hit = new BlockHitResult(target, Direction.UP, pos, false);
        InteractionResult result = client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        return result.consumesAction() ? Result.OK : Result.REJECTED;
    }
}
