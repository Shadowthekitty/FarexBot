package dev.farex.farexbot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Flips a chamber's trigger block the instant a request arrives: one rotation packet + one use packet, same tick.
 * The click uses a real ray-traced face and hit point, and is refused if the block is not in line of sight.
 */
public final class ChamberPuller {
    public enum Result {
        OK("ok"),
        NOT_IN_WORLD("not_in_world"),
        WRONG_DIMENSION("wrong_dim"),
        TOO_FAR("too_far"),
        MISSING("missing"),
        NO_LINE_OF_SIGHT("no_sight"),
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
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return Result.MISSING;

        // Click exactly like a player would: on the point and face our crosshair actually lands on.
        BlockHitResult hit = findVisibleHit(player, level, pos, state);
        if (hit == null) return Result.NO_LINE_OF_SIGHT;
        Vec3 eyes = player.getEyePosition();
        double reach = player.blockInteractionRange();
        if (eyes.distanceToSqr(hit.getLocation()) > reach * reach) return Result.TOO_FAR;

        // Look at that point, tell the server, then click in the same tick.
        Vec3 d = hit.getLocation().subtract(eyes);
        float yaw = (float) Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.connection.send(
                new ServerboundMovePlayerPacket.Rot(yaw, pitch, player.onGround(), player.horizontalCollision));

        InteractionResult result = client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        return result.consumesAction() ? Result.OK : Result.REJECTED;
    }

    /**
     * Ray-traces from the eyes towards the block's shape (centre first, then each face centre) and returns the first
     * ray that hits this block unobstructed. The result carries the real face and surface point, as a normal
     * right-click would.
     */
    private static BlockHitResult findVisibleHit(LocalPlayer player, ClientLevel level, BlockPos pos, BlockState state) {
        VoxelShape shape = state.getShape(level, pos);
        AABB box = (shape.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : shape.bounds())
                .move(pos.getX(), pos.getY(), pos.getZ());
        Vec3 c = box.getCenter();
        double ix = box.getXsize() * 0.45, iy = box.getYsize() * 0.45, iz = box.getZsize() * 0.45;
        Vec3[] targets = {
                c,
                c.add(0, iy, 0), c.add(0, -iy, 0),
                c.add(ix, 0, 0), c.add(-ix, 0, 0),
                c.add(0, 0, iz), c.add(0, 0, -iz),
        };
        Vec3 eyes = player.getEyePosition();
        for (Vec3 target : targets) {
            // Extend slightly past the target so the ray reaches the surface instead of stopping short.
            Vec3 end = eyes.add(target.subtract(eyes).scale(1.05));
            BlockHitResult hit = level.clip(new ClipContext(
                    eyes, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) {
                return hit;
            }
        }
        return null;
    }
}
