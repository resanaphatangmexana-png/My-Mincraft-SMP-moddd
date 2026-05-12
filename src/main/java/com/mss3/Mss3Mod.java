package com.mss3;

import com.mojang.brigadier.arguments.LongArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mincraft Ss3 SMP — server-side mod with bounty + admin + shop + tpa systems.
 * Works on vanilla clients via standard MC packets.
 */
public class Mss3Mod implements ModInitializer {
    public static final String MOD_ID = "mss3smp";
    public static final String DISPLAY_TITLE = "Mincraft Ss3";
    public static final String DEFAULT_REGION = "Asia";
    public static final Logger LOGGER = LoggerFactory.getLogger("Mss3SMP");

    private static final int HUD_UPDATE_INTERVAL = 20;     // 1 sec
    private static final int BOUNTY_CHECK_INTERVAL = 200;  // 10 sec
    private int tickCounter = 0;

    public static MinecraftServer SERVER;
    public static HudManager HUD;
    public static TpaManager TPA;
    public static BountyManager BOUNTY;
    public static AdminManager ADMIN;

    @Override
    public void onInitialize() {
        LOGGER.info("[Mincraft Ss3] Initializing server mod...");

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            SERVER = server;
            HUD = new HudManager();
            TPA = new TpaManager();
            BOUNTY = new BountyManager();
            ADMIN = new AdminManager();
            ADMIN.initialize(server);
            LOGGER.info("[Mincraft Ss3] Ready! HUD + Shop + TPA + Bounty + Admin active.");
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            HUD = null; TPA = null; BOUNTY = null; ADMIN = null; SERVER = null;
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            PlayerData data = Mss3State.get(server).getOrCreatePlayer(player.getUuid());

            if (HUD != null) HUD.attach(player);

            if (data.isAdmin && ADMIN != null) ADMIN.applyTeam(player);
            if (data.isInvisible) applyInvisibility(player, true);

            player.sendMessage(Text.literal("§e§lMincraft Ss3 §rพร้อมเล่น!"), false);
            player.sendMessage(Text.literal("§7คำสั่ง: §e/shop §7| §e/tpa §7| §e/shopsell §7| §e/money"), false);
            if (BOUNTY != null && BOUNTY.hasActiveBounty(player.getUuid())) {
                player.sendMessage(Text.literal("§c⚠ คุณมีค่าหัวบนหัว! ระวังตัว!"), false);
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            if (HUD != null) HUD.detach(player);
            if (TPA != null) TPA.cleanup(player.getUuid());
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            if (HUD != null && tickCounter % HUD_UPDATE_INTERVAL == 0) {
                for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                    HUD.update(p);
                }
            }
            if (TPA != null && tickCounter % 20 == 0) TPA.tickExpiry(server);
            if (BOUNTY != null && tickCounter % BOUNTY_CHECK_INTERVAL == 0) BOUNTY.tick(server);
        });

        // Death tracking + bounty payout
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(source.getAttacker() instanceof ServerPlayerEntity killer)) return;
            if (killer.equals(entity)) return;

            if (!(entity instanceof ServerPlayerEntity victim)) {
                // Mob kill - small reward
                PlayerData killerData = Mss3State.get(SERVER).getOrCreatePlayer(killer.getUuid());
                killerData.money += 5;
                Mss3State.get(SERVER).markDirty();
                return;
            }

            // PvP kill
            if (BOUNTY != null && BOUNTY.hasActiveBounty(victim.getUuid())) {
                long reward = BOUNTY.claimBounty(victim.getUuid());
                PlayerData killerData = Mss3State.get(SERVER).getOrCreatePlayer(killer.getUuid());
                killerData.money += reward;
                Mss3State.get(SERVER).markDirty();
                BOUNTY.announceClaim(SERVER, killer, victim, reward);
            } else {
                PlayerData killerData = Mss3State.get(SERVER).getOrCreatePlayer(killer.getUuid());
                killerData.money += 100;
                Mss3State.get(SERVER).markDirty();
                killer.sendMessage(Text.literal("§a+$100 §7(PvP kill)"), true);
            }
        });

        registerCommands();
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) -> {

            // /shop — redstone item shop
            dispatcher.register(CommandManager.literal("shop").executes(ctx -> {
                ShopHandler.openMainMenu(ctx.getSource().getPlayerOrThrow());
                return 1;
            }));

            // /shopsell — sell bonemeal in hand
            dispatcher.register(CommandManager.literal("shopsell").executes(ctx -> {
                return ShopHandler.sellBonemeal(ctx.getSource().getPlayerOrThrow());
            }));

            // /tpa <player>
            dispatcher.register(CommandManager.literal("tpa")
                .then(CommandManager.argument("target", EntityArgumentType.player()).executes(ctx -> {
                    ServerPlayerEntity sender = ctx.getSource().getPlayerOrThrow();
                    ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "target");
                    if (sender.equals(target)) {
                        sender.sendMessage(Text.literal("§cคุณวาปไปหาตัวเองไม่ได้!"), false);
                        return 0;
                    }
                    TPA.sendRequest(sender, target);
                    return 1;
                })));

            // /yes — accept TPA
            dispatcher.register(CommandManager.literal("yes").executes(ctx ->
                TPA.accept(ctx.getSource().getPlayerOrThrow()) ? 1 : 0));

            // /no (multi-purpose):
            //   /no              - deny TPA
            //   /no pro          - admin: toggle bounty
            //   /no invit        - admin: stop invisibility
            dispatcher.register(CommandManager.literal("no")
                .executes(ctx -> TPA.deny(ctx.getSource().getPlayerOrThrow()) ? 1 : 0)
                .then(CommandManager.literal("pro").requires(this::requireAdmin).executes(ctx -> {
                    ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                    boolean nowEnabled = BOUNTY.toggle();
                    p.sendMessage(Text.literal(nowEnabled
                        ? "§aเปิดระบบค่าหัวแล้ว"
                        : "§cปิดระบบค่าหัวแล้ว"), false);
                    SERVER.getPlayerManager().broadcast(
                        Text.literal("§7[Admin] " + p.getName().getString() + " §7" +
                            (nowEnabled ? "§aเปิด" : "§cปิด") + " §7ระบบค่าหัว"), false);
                    return 1;
                }))
                .then(CommandManager.literal("invit").requires(this::requireAdmin).executes(ctx -> {
                    ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                    PlayerData data = Mss3State.get(SERVER).getOrCreatePlayer(p.getUuid());
                    if (!data.isInvisible) {
                        p.sendMessage(Text.literal("§7คุณไม่ได้หายตัวอยู่"), false);
                        return 0;
                    }
                    data.isInvisible = false;
                    Mss3State.get(SERVER).markDirty();
                    applyInvisibility(p, false);
                    p.sendMessage(Text.literal("§aหยุดหายตัวแล้ว"), false);
                    return 1;
                })));

            // /money [player]
            dispatcher.register(CommandManager.literal("money")
                .executes(ctx -> {
                    ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                    long bal = Mss3State.get(SERVER).getOrCreatePlayer(p.getUuid()).money;
                    p.sendMessage(Text.literal("§eเงินของคุณ: §f$" + formatMoney(bal)), false);
                    return 1;
                })
                .then(CommandManager.argument("player", EntityArgumentType.player()).executes(ctx -> {
                    ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                    ServerPlayerEntity t = EntityArgumentType.getPlayer(ctx, "player");
                    long bal = Mss3State.get(SERVER).getOrCreatePlayer(t.getUuid()).money;
                    p.sendMessage(Text.literal("§e" + t.getName().getString() + ": §f$" + formatMoney(bal)), false);
                    return 1;
                })));

            // /pay <player> <amount>
            dispatcher.register(CommandManager.literal("pay")
                .then(CommandManager.argument("player", EntityArgumentType.player())
                    .then(CommandManager.argument("amount", LongArgumentType.longArg(1)).executes(ctx -> {
                        ServerPlayerEntity sender = ctx.getSource().getPlayerOrThrow();
                        ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                        long amount = LongArgumentType.getLong(ctx, "amount");
                        return payCommand(sender, target, amount);
                    }))));

            // /admin <player> — op only (toggle admin rank)
            dispatcher.register(CommandManager.literal("admin")
                .requires(src -> src.hasPermissionLevel(2))
                .then(CommandManager.argument("player", EntityArgumentType.player()).executes(ctx -> {
                    ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                    PlayerData data = Mss3State.get(SERVER).getOrCreatePlayer(target.getUuid());
                    if (data.isAdmin) {
                        data.isAdmin = false;
                        ADMIN.removeFromTeam(target);
                        target.sendMessage(Text.literal("§7ยศ Admin ถูกถอด"), false);
                        ctx.getSource().sendFeedback(() ->
                            Text.literal("§7Removed admin from " + target.getName().getString()), true);
                    } else {
                        data.isAdmin = true;
                        ADMIN.applyTeam(target);
                        target.sendMessage(Text.literal("§b§l(Admin) §r§bคุณได้รับยศ Admin แล้ว!"), false);
                        SERVER.getPlayerManager().broadcast(
                            Text.literal("§b§l(Admin) " + target.getName().getString() + " §r§7ได้รับยศแอดมิน!"), false);
                        ctx.getSource().sendFeedback(() ->
                            Text.literal("§aGranted admin to " + target.getName().getString()), true);
                    }
                    Mss3State.get(SERVER).markDirty();
                    return 1;
                })));

            // /gift money <player> <amount> — admin only
            dispatcher.register(CommandManager.literal("gift").requires(this::requireAdmin)
                .then(CommandManager.literal("money")
                    .then(CommandManager.argument("player", EntityArgumentType.player())
                        .then(CommandManager.argument("amount", LongArgumentType.longArg(1)).executes(ctx -> {
                            ServerPlayerEntity admin = ctx.getSource().getPlayerOrThrow();
                            ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                            long amount = LongArgumentType.getLong(ctx, "amount");
                            PlayerData targetData = Mss3State.get(SERVER).getOrCreatePlayer(target.getUuid());
                            targetData.money += amount;
                            Mss3State.get(SERVER).markDirty();
                            admin.sendMessage(Text.literal("§aGifted §e$" + formatMoney(amount) +
                                " §aให้ " + target.getName().getString()), false);
                            target.sendMessage(Text.literal("§a§l(Gift) §r§eได้รับ $" + formatMoney(amount) +
                                " §aจาก " + admin.getName().getString()), false);
                            return 1;
                        })))));

            // /invit — admin invisibility
            dispatcher.register(CommandManager.literal("invit").requires(this::requireAdmin).executes(ctx -> {
                ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                PlayerData data = Mss3State.get(SERVER).getOrCreatePlayer(p.getUuid());
                if (data.isInvisible) {
                    p.sendMessage(Text.literal("§7คุณหายตัวอยู่แล้ว — ใช้ §e/no invit §7เพื่อหยุด"), false);
                    return 0;
                }
                data.isInvisible = true;
                Mss3State.get(SERVER).markDirty();
                applyInvisibility(p, true);
                p.sendMessage(Text.literal("§7คุณหายตัวแล้ว"), false);
                return 1;
            }));

            // /bounty — view current bounties
            dispatcher.register(CommandManager.literal("bounty").executes(ctx -> {
                BOUNTY.listBounties(ctx.getSource().getPlayerOrThrow());
                return 1;
            }));
        });
    }

    private boolean requireAdmin(ServerCommandSource src) {
        ServerPlayerEntity p = src.getPlayer();
        if (p == null) return src.hasPermissionLevel(2);
        if (src.hasPermissionLevel(2)) return true;
        PlayerData data = Mss3State.get(SERVER).getOrCreatePlayer(p.getUuid());
        return data.isAdmin;
    }

    private int payCommand(ServerPlayerEntity sender, ServerPlayerEntity target, long amount) {
        if (sender.equals(target)) {
            sender.sendMessage(Text.literal("§cคุณโอนให้ตัวเองไม่ได้!"), false);
            return 0;
        }
        Mss3State state = Mss3State.get(SERVER);
        PlayerData senderData = state.getOrCreatePlayer(sender.getUuid());
        if (senderData.money < amount) {
            sender.sendMessage(Text.literal("§cเงินไม่พอ! (มี $" + formatMoney(senderData.money) + ")"), false);
            return 0;
        }
        PlayerData targetData = state.getOrCreatePlayer(target.getUuid());
        senderData.money -= amount;
        targetData.money += amount;
        state.markDirty();
        sender.sendMessage(Text.literal("§aโอน §e$" + formatMoney(amount) + " §aให้ " + target.getName().getString()), false);
        target.sendMessage(Text.literal("§aได้รับ §e$" + formatMoney(amount) + " §aจาก " + sender.getName().getString()), false);
        return 1;
    }

    public static void applyInvisibility(ServerPlayerEntity p, boolean on) {
        if (on) {
            p.addStatusEffect(new StatusEffectInstance(
                StatusEffects.INVISIBILITY,
                StatusEffectInstance.INFINITE,
                0, false, false, false));
        } else {
            p.removeStatusEffect(StatusEffects.INVISIBILITY);
        }
    }

    public static String formatMoney(long m) {
        if (m >= 1_000_000_000L) return String.format("%.2fB", m / 1_000_000_000.0);
        if (m >= 1_000_000L)     return String.format("%.2fM", m / 1_000_000.0);
        if (m >= 1_000L)         return String.format("%.2fK", m / 1_000.0);
        return String.valueOf(m);
    }

    public static String formatPlaytime(long ticks) {
        long sec = ticks / 20;
        long d = sec / 86400, h = (sec % 86400) / 3600, m = (sec % 3600) / 60, s = sec % 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        return m + "m " + s + "s";
    }
}
