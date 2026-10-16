package dev.egor.fakeplayer;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import io.papermc.paper.util.KeepAlive;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;

/**
 * A server-side player with no client. Physics run here every tick; actions mimic what a vanilla
 * client would do (same packet handlers / game mode calls), so plugins and protections see a normal player.
 * Compiled against Purpur 26.3 internals: rebuild after server updates.
 */
final class Bot extends ServerPlayer {
    enum Action { ATTACK, USE, JUMP, DROP, DROP_STACK, SWAP_HANDS }

    /** interval 0 = hold every tick (continuous), otherwise click every {@code interval} ticks. */
    private static final class Schedule {
        final int interval;
        final boolean once;
        int wait;

        Schedule(int interval, boolean once) {
            this.interval = interval;
            this.once = once;
        }
    }

    private final FakePlayerPlugin plugin;
    private final EnumMap<Action, Schedule> actions = new EnumMap<>(Action.class);
    private net.minecraft.network.Connection link;
    float forward, strafe;
    boolean sneaking, sprinting;
    // "client" side state, filled from packets the server sends us and applied on our next tick
    private Vec3 pendingVelocity;
    private int pendingTeleport = -1;
    private boolean pendingLoaded = true;
    private BlockPos miningPos;
    private float miningProgress;
    private int blockDelay, useDelay, sequence;

    private Bot(FakePlayerPlugin plugin, MinecraftServer server, ServerLevel level, GameProfile profile, ClientInformation info) {
        super(server, level, profile, info);
        this.plugin = plugin;
    }

    /** Mirrors PrepareSpawnTask.Ready#spawn, but at the given position. */
    static Bot spawn(FakePlayerPlugin plugin, GameProfile profile, ServerLevel level, double x, double y, double z, float yaw, float pitch) {
        MinecraftServer server = level.getServer();
        // 0x7F = all skin layers visible (hat, jacket, sleeves...)
        ClientInformation info = new ClientInformation("en_us", 2, ChatVisiblity.FULL, true, 0x7F, HumanoidArm.RIGHT, false, true, ParticleStatus.MINIMAL);
        Bot bot = new Bot(plugin, server, level, profile, info);
        Optional<ValueInput> data = server.getPlayerList().loadPlayerData(new NameAndId(profile))
            .map(tag -> TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), tag));
        data.ifPresent(bot::load);
        if (bot.getHealth() <= 0) bot.setHealth(bot.getMaxHealth());
        bot.snapTo(x, y, z, yaw, pitch);
        bot.link = new BotConnection();
        server.getPlayerList().placeNewPlayer(bot.link, bot, new CommonListenerCookie(profile, 0, info, false, null, new HashSet<>(), new KeepAlive()));
        data.ifPresent(d -> {
            bot.loadAndSpawnEnderPearls(d);
            bot.loadAndSpawnParentVehicle(d);
        });
        return bot;
    }

    @Override
    public void tick() {
        connection.resetPosition(); // what a real connection's tick does before handling movement
        if (pendingLoaded) {
            pendingLoaded = false;
            connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        }
        if (pendingTeleport >= 0) {
            int id = pendingTeleport;
            pendingTeleport = -1;
            connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(id, getX(), getY(), getZ(), getYRot(), getXRot()));
        }
        if (pendingVelocity != null) {
            setDeltaMovement(pendingVelocity);
            pendingVelocity = null;
        }
        tickActions();
        super.tick();
        doTick(); // a real player's movement tick is driven by its connection; ours by the entity tick
        if (tickCount % 10 == 0) level().getChunkSource().move(this); // keep chunk loading centered on the bot
    }

    /** Packets the server sends to this player. Everything else is dropped. */
    private void receive(Packet<?> packet) {
        switch (packet) {
            // vanilla restores a hit player's velocity right after sending knockback, expecting the client to apply it
            case ClientboundSetEntityMotionPacket p when p.id() == getId() -> pendingVelocity = p.movement();
            case ClientboundPlayerPositionPacket p -> pendingTeleport = p.id();
            case ClientboundRespawnPacket p -> pendingLoaded = true;
            case ClientboundDisconnectPacket p -> despawnLater(p.reason()); // /kick etc.
            default -> {}
        }
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (getHealth() > 0) return; // death event cancelled
        setHealth(getMaxHealth()); // saved data must not be dead on the next spawn
        despawnLater(Component.literal("Died"));
    }

    /** Disconnects like a real player: quit event, player data saved, removed from the world. */
    void despawn(Component reason) {
        link.disconnect(reason);
        link.handleDisconnection(); // no-op if already handled
    }

    private void despawnLater(Component reason) {
        if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> despawn(reason));
        else despawn(reason);
    }

    // ---- actions ----

    void setAction(Action action, int interval, boolean once) {
        if (actions.put(action, new Schedule(interval, once)) != null) release(action);
    }

    void stopAll() {
        actions.keySet().forEach(this::release);
        actions.clear();
        forward = strafe = 0;
        sneaking = sprinting = false;
    }

    void look(float yaw, float pitch) {
        setYRot(Mth.wrapDegrees(yaw));
        setXRot(Mth.clamp(pitch, -90, 90));
        setYHeadRot(getYRot());
    }

    private void tickActions() {
        float speed = sneaking ? (float) getAttributeValue(Attributes.SNEAKING_SPEED) : 1f;
        zza = forward * speed; // left strafe is positive, like the vanilla client
        xxa = strafe * speed;
        setShiftKeyDown(sneaking);
        setSprinting(sprinting && forward > 0 && !sneaking);
        for (Iterator<Map.Entry<Action, Schedule>> it = actions.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Action, Schedule> e = it.next();
            Action action = e.getKey();
            Schedule s = e.getValue();
            if (--s.wait > 0) {
                release(action);
                continue;
            }
            if (s.interval > 0) release(action); // a click, not a hold
            run(action, s.interval == 0);
            s.wait = s.interval;
            if (s.once) {
                it.remove();
                release(action);
            }
        }
    }

    private void run(Action action, boolean hold) {
        switch (action) {
            case ATTACK -> attack(hold);
            case USE -> use();
            case JUMP -> {
                if (hold) setJumping(true);
                else if (onGround()) jumpFromGround();
            }
            case DROP -> playerAction(ServerboundPlayerActionPacket.Action.DROP_ITEM, BlockPos.ZERO, Direction.DOWN);
            case DROP_STACK -> playerAction(ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS, BlockPos.ZERO, Direction.DOWN);
            case SWAP_HANDS -> playerAction(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN);
        }
    }

    private void release(Action action) {
        switch (action) {
            case ATTACK -> abortMining();
            case USE -> {
                useDelay = 0;
                releaseUsingItem();
            }
            case JUMP -> setJumping(false);
            default -> {}
        }
    }

    /** Left click on whatever the bot is looking at. Holding mines blocks but doesn't hit entities (like Carpet). */
    private void attack(boolean hold) {
        HitResult hit = target();
        if (hit instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK) {
            mine(b);
            return;
        }
        abortMining();
        if (hold) return;
        if (hit instanceof EntityHitResult e) connection.handleAttack(new ServerboundAttackPacket(e.getEntity().getId()));
        swing(InteractionHand.MAIN_HAND);
    }

    /** Same timing as the vanilla client: server checks the progress when we send STOP. */
    private void mine(BlockHitResult hit) {
        if (blockDelay > 0) {
            blockDelay--;
            return;
        }
        BlockPos pos = hit.getBlockPos();
        Direction side = hit.getDirection();
        ServerLevel level = level();
        if (!pos.equals(miningPos)) {
            abortMining();
            float progress = level.getBlockState(pos).getDestroyProgress(this, level, pos);
            playerAction(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, side);
            if (gameMode.getGameModeForPlayer().isCreative()) blockDelay = 5;
            else if (progress < 1f) {
                miningPos = pos;
                miningProgress = 0;
            }
        } else {
            miningProgress += level.getBlockState(pos).getDestroyProgress(this, level, pos);
            if (miningProgress >= 1f) {
                playerAction(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, side);
                miningPos = null;
                blockDelay = 5;
            }
        }
        swing(InteractionHand.MAIN_HAND);
    }

    private void abortMining() {
        if (miningPos == null) return;
        playerAction(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, miningPos, Direction.DOWN);
        miningPos = null;
    }

    /** Right click, same order as the vanilla client: entity/block with each hand, then the item itself. */
    private void use() {
        if (useDelay > 0) {
            useDelay--;
            return;
        }
        if (isUsingItem()) return;
        HitResult hit = target();
        ServerLevel level = level();
        for (InteractionHand hand : InteractionHand.values()) {
            InteractionResult result = InteractionResult.PASS;
            if (hit instanceof EntityHitResult e) {
                Entity target = e.getEntity();
                Vec3 at = e.getLocation().subtract(target.position());
                EquipmentSlot slot = hand == InteractionHand.MAIN_HAND ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND;
                if (new PlayerInteractAtEntityEvent(getBukkitEntity(), target.getBukkitEntity(), new Vector(at.x, at.y, at.z), slot).callEvent()) {
                    result = interactOn(target, hand, at);
                }
            } else if (hit instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK && mayInteract(level, b.getBlockPos())) {
                result = gameMode.useItemOn(this, level, getItemInHand(hand), hand, b);
                if (result instanceof InteractionResult.Fail) return;
            }
            ItemStack stack = getItemInHand(hand);
            if (!result.consumesAction() && !stack.isEmpty()) result = gameMode.useItem(this, level, stack, hand);
            if (result.consumesAction()) {
                if (result instanceof InteractionResult.Success s && s.shouldSwing()) swing(hand);
                useDelay = 4; // vanilla right click repeat delay
                return;
            }
        }
    }

    private HitResult target() {
        Vec3 eye = getEyePosition();
        Vec3 reach = getViewVector(1f).scale(entityInteractionRange());
        HitResult block = pick(blockInteractionRange(), 1f, false);
        double maxSq = reach.lengthSqr();
        if (block.getType() != HitResult.Type.MISS) maxSq = Math.min(maxSq, block.getLocation().distanceToSqr(eye));
        EntityHitResult entity = ProjectileUtil.getEntityHitResult(this, eye, eye.add(reach),
            getBoundingBox().expandTowards(reach).inflate(1), e -> !e.isSpectator() && e.isPickable(), maxSq);
        return entity != null ? entity : block;
    }

    private void swing(InteractionHand hand) {
        swing(hand, SwingAnimation.DEFAULT, false);
    }

    private void playerAction(ServerboundPlayerActionPacket.Action action, BlockPos pos, Direction side) {
        connection.handlePlayerAction(new ServerboundPlayerActionPacket(action, pos, side, sequence));
        sequence = (sequence + 1) & Integer.MAX_VALUE;
    }

    /** A connection to nowhere. Writes are swallowed: 1.15 used a bare EmbeddedChannel, which queues every packet forever (OOM). */
    private static final class BotConnection extends net.minecraft.network.Connection {
        BotConnection() {
            super(PacketFlow.SERVERBOUND);
            channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                    ReferenceCountUtil.release(msg);
                    promise.setSuccess();
                }
            });
            address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
            preparing = false;
        }

        @Override
        public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
            if (getPlayer() instanceof Bot bot) bot.receive(packet);
        }
    }
}
