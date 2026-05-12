package com.mss3;

import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.*;

/**
 * BOUNTY SYSTEM — random bounty placed on a player every 15 minutes.
 *
 * On placement: dramatic title shown to ALL online players + sound + chat.
 * On claim (target killed): killer receives the bounty money.
 *
 * Admins can toggle the system on/off via /no pro.
 */
public class BountyManager {
    /** How often a new bounty rolls. */
    private static final long BOUNTY_INTERVAL_MS = 15L * 60L * 1000L; // 15 min

    /** Bounty reward range. */
    private static final long MIN_BOUNTY =   50_000L; // $50K
    private static final long MAX_BOUNTY = 1_000_000L; // $1M

    /** Bounty lifetime (auto-expires if not claimed). */
    private static final long BOUNTY_LIFETIME_MS = 60L * 60L * 1000L; // 1 hour

    public boolean enabled = true;
    private long lastBountyMs = 0L;
    private final Random rng = new Random();

    /** Currently active bounties: target UUID -> Bounty info. */
    private final Map<UUID, ActiveBounty> activeBounties = new HashMap<>();

    private static class ActiveBounty {
        final UUID target;
        final String targetName;
        final long reward;
        final long placedAtMs;

        ActiveBounty(UUID target, String targetName, long reward) {
            this.target = target;
            this.targetName = targetName;
            this.reward = reward;
            this.placedAtMs = System.currentTimeMillis();
        }
    }

    public boolean hasActiveBounty(UUID playerId) {
        return activeBounties.containsKey(playerId);
    }

    public long getBountyAmount(UUID playerId) {
        ActiveBounty b = activeBounties.get(playerId);
        return b == null ? 0 : b.reward;
    }

    /** Toggle the system on/off (admin). Returns new state. */
    public boolean toggle() {
        enabled = !enabled;
        if (!enabled) activeBounties.clear();
        return enabled;
    }

    /** Periodic tick — try to roll new bounty + expire old ones. */
    public void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();

        // Expire old bounties
        activeBounties.entrySet().removeIf(e -> now - e.getValue().placedAtMs > BOUNTY_LIFETIME_MS);

        if (!enabled) return;

        // Roll new bounty?
        if (now - lastBountyMs < BOUNTY_INTERVAL_MS) return;
        lastBountyMs = now;

        // Pick a random online player (excluding those who already have a bounty)
        List<ServerPlayerEntity> candidates = new ArrayList<>();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (!activeBounties.containsKey(p.getUuid())) {
                candidates.add(p);
            }
        }
        if (candidates.size() < 2) return; // need at least 2 players for it to be meaningful

        ServerPlayerEntity target = candidates.get(rng.nextInt(candidates.size()));
        long reward = MIN_BOUNTY + (long) (rng.nextDouble() * (MAX_BOUNTY - MIN_BOUNTY));
        // Round to nearest 1000 for clean look
        reward = (reward / 1000L) * 1000L;

        placeBounty(server, target, reward);
    }

    private void placeBounty(MinecraftServer server, ServerPlayerEntity target, long reward) {
        ActiveBounty bounty = new ActiveBounty(target.getUuid(), target.getName().getString(), reward);
        activeBounties.put(target.getUuid(), bounty);

        // Dramatic broadcast to all players
        Text title = Text.literal("💀 BOUNTY 💀").styled(s ->
            s.withColor(Formatting.RED).withBold(true));
        Text subtitle = Text.literal(target.getName().getString() + " §6→ §e$" + Mss3Mod.formatMoney(reward))
            .styled(s -> s.withItalic(false));

        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            // Fade-in 10t, stay 60t (3 sec), fade-out 20t
            p.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 60, 20));
            p.networkHandler.sendPacket(new TitleS2CPacket(title));
            p.networkHandler.sendPacket(new SubtitleS2CPacket(subtitle));

            // Dramatic sound
            p.playSoundToPlayer(SoundEvents.ENTITY_WITHER_SPAWN, SoundCategory.MASTER, 0.4f, 1.0f);
            p.playSoundToPlayer(SoundEvents.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.MASTER, 0.3f, 1.5f);
        }

        // Chat announcement
        Text chat = Text.literal("§4§l[BOUNTY] §r§eมีค่าหัวบนหัวของ §c§l" + target.getName().getString() +
            " §r§eจำนวน §6§l$" + Mss3Mod.formatMoney(reward) + "§r§e!");
        server.getPlayerManager().broadcast(chat, false);
        server.getPlayerManager().broadcast(
            Text.literal("§7ฆ่าเขาเพื่อรับเงิน — หมดอายุใน 1 ชั่วโมง"), false);

        // Tell target specifically
        target.sendMessage(Text.literal("§c⚠ §lมีคนล่าหัวคุณแล้ว! ระวังตัว! ค่าหัว §6$" + Mss3Mod.formatMoney(reward)), false);
        target.playSoundToPlayer(SoundEvents.BLOCK_BELL_USE, SoundCategory.MASTER, 1.0f, 0.5f);

        Mss3Mod.LOGGER.info("[Bounty] Placed ${} bounty on {}", reward, target.getName().getString());
    }

    /** Called when a bountied player is killed. Returns the reward, or 0 if no bounty. */
    public long claimBounty(UUID victimId) {
        ActiveBounty b = activeBounties.remove(victimId);
        return b == null ? 0L : b.reward;
    }

    /** Broadcast claim message to all players. */
    public void announceClaim(MinecraftServer server, ServerPlayerEntity killer, ServerPlayerEntity victim, long reward) {
        Text title = Text.literal("💰 BOUNTY CLAIMED 💰").styled(s ->
            s.withColor(Formatting.GOLD).withBold(true));
        Text subtitle = Text.literal(killer.getName().getString() + " §6→ §e$" + Mss3Mod.formatMoney(reward))
            .styled(s -> s.withItalic(false));

        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            p.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 60, 20));
            p.networkHandler.sendPacket(new TitleS2CPacket(title));
            p.networkHandler.sendPacket(new SubtitleS2CPacket(subtitle));
            p.playSoundToPlayer(SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.MASTER, 0.8f, 1.2f);
        }

        Text chat = Text.literal("§6§l[BOUNTY] §r§e" + killer.getName().getString() +
            " §aเก็บค่าหัว §6$" + Mss3Mod.formatMoney(reward) + " §aจาก " + victim.getName().getString() + " §aสำเร็จ!");
        server.getPlayerManager().broadcast(chat, false);
    }

    /** /bounty - show active bounties */
    public void listBounties(ServerPlayerEntity p) {
        if (!enabled) {
            p.sendMessage(Text.literal("§7ระบบค่าหัวปิดอยู่"), false);
            return;
        }
        if (activeBounties.isEmpty()) {
            p.sendMessage(Text.literal("§7ไม่มีค่าหัวที่กำลัง active — รอ 15 นาทีเพื่อ roll ใหม่"), false);
            return;
        }
        p.sendMessage(Text.literal("§4§l═══ ค่าหัวที่ active ═══"), false);
        for (ActiveBounty b : activeBounties.values()) {
            long remaining = (BOUNTY_LIFETIME_MS - (System.currentTimeMillis() - b.placedAtMs)) / 60000L;
            p.sendMessage(Text.literal("§c" + b.targetName + " §7→ §e$" +
                Mss3Mod.formatMoney(b.reward) + " §7(" + remaining + " นาที)"), false);
        }
    }
}
