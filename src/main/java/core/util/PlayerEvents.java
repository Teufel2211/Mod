package core.util;

import core.bounty.BountyManager;
import core.claims.ClaimManager;
import core.config.ConfigManager;
import core.economy.EconomyManager;
import core.wanted.WantedManager;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.ChunkPos;

import java.math.BigDecimal;
import java.util.Objects;

public final class PlayerEvents {
    private PlayerEvents() {}

    public static void onPlayerDeath(ServerPlayerEntity victim, DamageSource source) {
        if (victim == null) return;

        Safe.run("PlayerEvents.onPlayerDeath", () -> {
            // Economy + Wanted: wanted players lose money on death.
            if (WantedManager.isWanted(victim.getUuid())) {
                double fine = ConfigManager.getConfig() != null ? ConfigManager.getConfig().economy.deathCost : 0.0;
                if (fine > 0.0) {
                    EconomyManager.chargePlayer(victim.getUuid(), BigDecimal.valueOf(fine), EconomyManager.TransactionType.WANTED, "Wanted death penalty");
                }
            }

            // Bounty payout on player kills.
            var attacker = source != null ? source.getAttacker() : null;
            if (attacker instanceof ServerPlayerEntity killer) {
                onPlayerKill(killer, victim);
            }
        });
    }

    private static void onPlayerKill(ServerPlayerEntity killer, ServerPlayerEntity victim) {
        if (killer == null || victim == null) return;
        if (killer.getUuid().equals(victim.getUuid())) return;

        // Clan safe: clan members can’t claim bounties on each other.
        if (ConfigManager.getConfig() != null && ConfigManager.getConfig().bounty.preventClanMateKills) {
            String killerClan = core.clans.ClanManager.getPlayerClanName(killer.getUuid());
            String victimClan = core.clans.ClanManager.getPlayerClanName(victim.getUuid());
            if (killerClan != null && Objects.equals(killerClan, victimClan)) {
                return;
            }
        }

        // Claims + Bounty/Wanted: if PVP is disabled in this chunk, treat kill as illegal.
        if (ConfigManager.getConfig() != null && ConfigManager.getConfig().claim.enableClaims) {
            ChunkPos pos = new ChunkPos(victim.getBlockPos());
            ClaimManager.Claim claim = ClaimManager.getClaim(pos);
            if (claim != null && Boolean.FALSE.equals(claim.permissions.get(ClaimManager.ClaimPermission.PVP))) {
                WantedManager.addWanted(killer.getUuid(), WantedManager.WantedReason.PLAYER_KILL, 1);
                // Optional: auto system bounty on killer to discourage grief kills in protected areas.
                BountyManager.placeSystemBounty(killer.getUuid(), Math.max(50.0, ConfigManager.getConfig().bounty.minBountyAmount),
                    "Illegal kill in claim");
            }
        }

        // Normal bounty claim (pays killer).
        BountyManager.claimBounty(victim.getUuid(), killer.getUuid());
    }
}

