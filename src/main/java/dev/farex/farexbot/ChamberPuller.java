package dev.farex.farexbot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

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

    public static Result pull(MinecraftClient client, FarexConfig.Chamber chamber) {
        Result result = interact(client, chamber);
        if (result == Result.OK && chamber.resetAfterTicks > 0) {
            PENDING_RESETS.add(new PendingReset(chamber, chamber.resetAfterTicks));
        }
        return result;
    }

    public static void tick(MinecraftClient client) {
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

    private static Result interact(MinecraftClient client, FarexConfig.Chamber chamber) {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null || client.interactionManager == null) return Result.NOT_IN_WORLD;
        if (!world.getRegistryKey().getValue().toString().equals(chamber.dimension)) return Result.WRONG_DIMENSION;

        BlockPos pos = chamber.pos();
        Vec3d target = Vec3d.ofCenter(pos);
        Vec3d eyes = player.getEyePos();
        double reach = player.getBlockInteractionRange();
        if (eyes.squaredDistanceTo(target) > reach * reach) return Result.TOO_FAR;
        if (world.getBlockState(pos).isAir()) return Result.MISSING;

        // Face the block server-side before using it so anticheats that check rotation are happy.
        Vec3d d = target.subtract(eyes);
        float yaw = (float) Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        player.setYaw(yaw);
        player.setPitch(pitch);
        player.networkHandler.sendPacket(
                new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, player.isOnGround(), player.horizontalCollision));

        BlockHitResult hit = new BlockHitResult(target, Direction.UP, pos, false);
        ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
        if (!result.isAccepted()) return Result.REJECTED;
        player.swingHand(Hand.MAIN_HAND);
        return Result.OK;
    }
}
