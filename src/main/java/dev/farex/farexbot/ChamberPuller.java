package dev.farex.farexbot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/**
 * Flips a chamber's trigger block with exactly the packets and ordering a vanilla right-click produces, so
 * packet-order anticheats see nothing unusual:
 * <ol>
 *   <li>Tick N: turn to face the block. The game's own movement update sends the rotation, as for mouse movement.</li>
 *   <li>Tick N+1, at the start of the tick where vanilla handles input: use the block, then swing the arm.</li>
 * </ol>
 * No extra packets are sent and nothing happens between ticks. The click uses a real ray-traced face and hit point,
 * and is refused if the block is not in line of sight. Total delay is one to two ticks (50-100 ms).
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

    private static final class Action {
        final FarexConfig.Chamber chamber;
        final Consumer<Result> done;
        /** Ticks to wait before starting (used for the flip-back after a pull). */
        int delay;
        boolean aimed;

        Action(FarexConfig.Chamber chamber, int delay, Consumer<Result> done) {
            this.chamber = chamber;
            this.delay = delay;
            this.done = done;
        }
    }

    private static final Deque<Action> QUEUE = new ArrayDeque<>();

    private ChamberPuller() {
    }

    /** Queues a pull. {@code done} runs on the client thread once it has happened (or failed). */
    public static void pull(FarexConfig.Chamber chamber, Consumer<Result> done) {
        QUEUE.add(new Action(chamber, 0, result -> {
            if (result == Result.OK && chamber.resetAfterTicks > 0) {
                QUEUE.add(new Action(chamber, chamber.resetAfterTicks, r -> {
                    if (r != Result.OK) FarexBot.LOGGER.warn("Flip-back for {} failed: {}", chamber.friend, r);
                }));
            }
            done.accept(result);
        }));
    }

    /** Called at the start of every client tick, before vanilla handles input. */
    public static void tick(Minecraft client) {
        for (Action action : QUEUE) {
            if (action.delay > 0) action.delay--;
        }
        // One block interaction per tick at most, like a player.
        Action action = QUEUE.stream().filter(a -> a.delay <= 0).findFirst().orElse(null);
        if (action == null) return;

        if (!action.aimed) {
            Result result = aim(client, action.chamber);
            if (result != null) {
                QUEUE.remove(action);
                action.done.accept(result);
            } else {
                action.aimed = true;
            }
            return;
        }
        QUEUE.remove(action);
        action.done.accept(click(client, action.chamber));
    }

    /** @return null when aimed and ready to click next tick, otherwise the reason it can't be pulled. */
    private static Result aim(Minecraft client, FarexConfig.Chamber chamber) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null || client.gameMode == null) return Result.NOT_IN_WORLD;
        if (!FarexBot.dimensionId(level).equals(chamber.dimension)) return Result.WRONG_DIMENSION;

        BlockPos pos = chamber.pos();
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return Result.MISSING;
        BlockHitResult hit = findVisibleHit(player, level, pos, state);
        if (hit == null) return Result.NO_LINE_OF_SIGHT;
        Vec3 eyes = player.getEyePosition();
        double reach = player.blockInteractionRange();
        if (eyes.distanceToSqr(hit.getLocation()) > reach * reach) return Result.TOO_FAR;

        // Only change the client's rotation; this tick's normal movement update sends it to the server.
        Vec3 d = hit.getLocation().subtract(eyes);
        player.setYRot((float) Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0F);
        player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
        return null;
    }

    private static Result click(Minecraft client, FarexConfig.Chamber chamber) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null || client.gameMode == null) return Result.NOT_IN_WORLD;
        BlockPos pos = chamber.pos();
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return Result.MISSING;
        BlockHitResult hit = findVisibleHit(player, level, pos, state);
        if (hit == null) return Result.NO_LINE_OF_SIGHT;

        // Same as vanilla Minecraft.startUseItem: use the block, then swing if the client predicted success.
        InteractionHand hand = InteractionHand.MAIN_HAND;
        InteractionResult result = client.gameMode.useItemOn(player, hand, hit);
        if (result instanceof InteractionResult.Success success
                && success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
            player.swing(hand, player.getItemInHand(hand).getInteractAnimation(), false);
        }
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
