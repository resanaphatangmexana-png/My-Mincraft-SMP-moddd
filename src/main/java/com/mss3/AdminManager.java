package com.mss3;

import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Admin rank manager — uses vanilla scoreboard team to give players:
 *  - Cyan/aqua-colored name
 *  - "(Admin) " prefix in tab list, chat, and nameplate
 *
 * Persists across server restarts via player data flag (PlayerData.isAdmin).
 */
public class AdminManager {
    public static final String TEAM_NAME = "mss3_admin";
    private Scoreboard scoreboard;

    public void initialize(MinecraftServer server) {
        scoreboard = server.getScoreboard();

        // Create or update the admin team
        Team team = scoreboard.getTeam(TEAM_NAME);
        if (team == null) {
            team = scoreboard.addTeam(TEAM_NAME);
            Mss3Mod.LOGGER.info("[AdminManager] Created admin team.");
        }
        // Always re-apply formatting so it stays consistent across restarts
        team.setPrefix(Text.literal("(Admin) ").styled(s -> s.withColor(Formatting.AQUA).withBold(true)));
        team.setColor(Formatting.AQUA);
        team.setShowFriendlyInvisibles(true);
        team.setCollisionRule(AbstractTeam.CollisionRule.ALWAYS);
        team.setFriendlyFireAllowed(true);

        // Re-apply team to all admin players already online
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            PlayerData data = Mss3State.get(server).getOrCreatePlayer(p.getUuid());
            if (data.isAdmin) applyTeam(p);
        }
    }

    /** Add player to admin team. */
    public void applyTeam(ServerPlayerEntity player) {
        if (scoreboard == null) return;
        Team team = scoreboard.getTeam(TEAM_NAME);
        if (team == null) return;
        scoreboard.addScoreHolderToTeam(player.getNameForScoreboard(), team);
        Mss3Mod.LOGGER.info("[AdminManager] Added {} to admin team.", player.getName().getString());
    }

    /** Remove player from admin team. */
    public void removeFromTeam(ServerPlayerEntity player) {
        if (scoreboard == null) return;
        Team team = scoreboard.getTeam(TEAM_NAME);
        if (team == null) return;
        scoreboard.removeScoreHolderFromTeam(player.getNameForScoreboard(), team);
        Mss3Mod.LOGGER.info("[AdminManager] Removed {} from admin team.", player.getName().getString());
    }
}
