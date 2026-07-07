/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.paper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class FoliaLoginProtection {

    private static final int LOGIN_BLINDNESS_TICKS = 20 * 60 * 60;

    private final PaperLibreLogin plugin;
    private final Map<UUID, State> protectedPlayers = new ConcurrentHashMap<>();

    public FoliaLoginProtection(PaperLibreLogin plugin) {
        this.plugin = plugin;
    }

    public void protect(Player player) {
        if (!PaperScheduler.FOLIA) return;
        PaperUtil.runForPlayer(player, () -> protectNow(player), plugin);
    }

    public void protectNow(Player player) {
        if (!PaperScheduler.FOLIA) return;
        if (plugin.getAuthorizationProvider().isAuthorized(player)
                && !plugin.getAuthorizationProvider().isAwaiting2FA(player)) {
            clearProtection(player);
            return;
        }

        protectedPlayers.computeIfAbsent(player.getUniqueId(), ignored -> State.capture(player));
        player.setInvulnerable(true);
        player.setCollidable(false);
        player.setInvisible(true);
        player.setFireTicks(0);
        player.addPotionEffect(
                new PotionEffect(
                        PotionEffectType.BLINDNESS,
                        LOGIN_BLINDNESS_TICKS,
                        0,
                        false,
                        false,
                        false));
    }

    public void unprotect(Player player) {
        if (!PaperScheduler.FOLIA) return;
        PaperUtil.runForPlayer(player, () -> unprotectNow(player), plugin);
    }

    public void unprotectNow(Player player) {
        if (!PaperScheduler.FOLIA) return;

        var state = protectedPlayers.remove(player.getUniqueId());
        if (state == null || state.matchesLoginProtection()) {
            clearProtection(player);
            return;
        }

        player.setInvulnerable(state.invulnerable());
        player.setCollidable(state.collidable());
        player.setInvisible(state.invisible());
        player.setNoDamageTicks(0);
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        if (state.blindness() != null) {
            player.addPotionEffect(state.blindness());
        }
    }

    private void clearProtection(Player player) {
        protectedPlayers.remove(player.getUniqueId());
        player.setInvulnerable(false);
        player.setCollidable(true);
        player.setInvisible(false);
        player.setNoDamageTicks(0);
        player.removePotionEffect(PotionEffectType.BLINDNESS);
    }

    public void unprotectAll() {
        if (!PaperScheduler.FOLIA) return;

        for (var uuid : protectedPlayers.keySet()) {
            var player = plugin.getPlayerForUUID(uuid);
            if (player != null) {
                unprotect(player);
            } else {
                protectedPlayers.remove(uuid);
            }
        }
    }

    private record State(
            boolean invulnerable, boolean collidable, boolean invisible, PotionEffect blindness) {

        private static State capture(Player player) {
            return new State(
                    player.isInvulnerable(),
                    player.isCollidable(),
                    player.isInvisible(),
                    player.getPotionEffect(PotionEffectType.BLINDNESS));
        }

        private boolean matchesLoginProtection() {
            return invulnerable && !collidable && invisible && blindness != null;
        }
    }
}
