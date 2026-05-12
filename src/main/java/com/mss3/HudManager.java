package com.mss3;

import net.minecraft.network.packet.s2c.play.ScoreboardDisplayS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardObjectiveUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreUpdateS2CPacket;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.number.BlankNumberFormat;
import net.minecraft.scoreboard.number.FixedNumberFormat;
import net.minecraft.scoreboard.number.NumberFormat;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.stat.Stats;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sidebar HUD manager. Sends scoreboard packets directly to each player so
 * they see THEIR OWN data without needing a client mod.
 *
 * Strategy:
 *  - Each player gets a unique objective named "mss3_<uuid-short>"
 *  - Sent only to that player via their connection
 *  - We use FixedNumberFormat to show custom text in the value column
 *
 * Visible result on the right side of player's screen:
 *
 *   Mincraft Ss3
 *   ─────────────
 *   $ Money       7.08K
 *   ⚔ Kills          12
 *   ☠ Deaths          3
 *   ⏱ Playtime  4d 10h
 *
 *   Asia          (30ms)
 */
public class HudManager {
    private final Map<UUID, ScoreboardObjective> objectives = new HashMap<>();

    private ScoreboardObjective makeObjective(ServerPlayerEntity player) {
        String name = "mss3_" + player.getUuid().toString().substring(0, 8);
        MutableText title = Text.literal(Mss3Mod.DISPLAY_TITLE).formatted(Formatting.GOLD, Formatting.BOLD);
        return new ScoreboardObjective(
            null,                                   // scoreboard reference (null = standalone)
            name,
            ScoreboardCriterion.DUMMY,
            title,
            ScoreboardCriterion.RenderType.INTEGER,
            false,                                  // displayAutoUpdate
            null                                    // numberFormat (none default)
        );
    }

    /** Called once when a player joins — register & show the sidebar. */
    public void attach(ServerPlayerEntity player) {
        ScoreboardObjective obj = makeObjective(player);
        objectives.put(player.getUuid(), obj);

        // 1) Create objective (mode 0 = ADD)
        player.networkHandler.sendPacket(
            new ScoreboardObjectiveUpdateS2CPacket(obj, ScoreboardObjectiveUpdateS2CPacket.ADD_MODE)
        );
        // 2) Set sidebar display
        player.networkHandler.sendPacket(
            new ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, obj)
        );
        // 3) Initial data push
        update(player);
    }

    public void detach(ServerPlayerEntity player) {
        ScoreboardObjective obj = objectives.remove(player.getUuid());
        if (obj == null) return;
        player.networkHandler.sendPacket(
            new ScoreboardObjectiveUpdateS2CPacket(obj, ScoreboardObjectiveUpdateS2CPacket.REMOVE_MODE)
        );
    }

    /** Push fresh data lines to player. Call every second-ish. */
    public void update(ServerPlayerEntity player) {
        ScoreboardObjective obj = objectives.get(player.getUuid());
        if (obj == null) {
            attach(player);
            obj = objectives.get(player.getUuid());
            if (obj == null) return;
        }

        PlayerData data = Mss3State.get(player.getServer()).getOrCreatePlayer(player.getUuid());

        // Read vanilla stats
        int kills = player.getStatHandler().getStat(
            Stats.CUSTOM.getOrCreateStat(Stats.MOB_KILLS));
        int deaths = player.getStatHandler().getStat(
            Stats.CUSTOM.getOrCreateStat(Stats.DEATHS));
        long playTicks = player.getStatHandler().getStat(
            Stats.CUSTOM.getOrCreateStat(Stats.PLAY_TIME));
        int ping = player.networkHandler.getLatency();

        // Build lines. Higher score = displayed higher on the sidebar.
        // Each line: holder = label text, value = colored stat
        pushLine(player, obj, "money",   8, "§e$ §fMoney",
            colored("§a" + Mss3Mod.formatMoney(data.money)));
        pushLine(player, obj, "kills",   7, "§c⚔ §fKills",
            colored("§e" + kills));
        pushLine(player, obj, "deaths",  6, "§4☠ §fDeaths",
            colored("§c" + deaths));
        pushLine(player, obj, "playtime",5, "§e⏱ §fPlaytime",
            colored("§a" + Mss3Mod.formatPlaytime(playTicks)));
        pushLine(player, obj, "spacer",  4, "§r ", blank());
        pushLine(player, obj, "region",  3, "§7" + data.region,
            colored(pingColor(ping) + "(" + ping + "ms)"));
    }

    private void pushLine(ServerPlayerEntity player, ScoreboardObjective obj,
                          String key, int order, String labelLegacy, NumberFormat valueFormat) {
        // Score holder name uses an invisible-but-unique marker so each line is distinct
        // Use legacy formatting codes (§) in the holder name; client renders them.
        // But holders must be unique strings, so we add a zero-width-ish suffix per line.
        String holder = labelLegacy + "§r§" + ((char) ('0' + (order % 10)));
        Text displayName = Text.literal(labelLegacy);

        player.networkHandler.sendPacket(new ScoreboardScoreUpdateS2CPacket(
            holder,                  // ScoreHolder name (string)
            obj.getName(),           // Objective name
            order,                   // numeric score (sort order)
            Optional.of(displayName),
            Optional.of(valueFormat)
        ));
    }

    private static FixedNumberFormat colored(String legacyText) {
        // Decode legacy codes to a Text for FixedNumberFormat
        return new FixedNumberFormat(Text.literal(legacyText));
    }

    private static BlankNumberFormat blank() { return BlankNumberFormat.INSTANCE; }

    private static String pingColor(int ping) {
        if (ping < 50)  return "§a";
        if (ping < 150) return "§e";
        return "§c";
    }
}
