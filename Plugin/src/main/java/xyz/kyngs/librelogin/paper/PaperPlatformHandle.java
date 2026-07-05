/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.paper;

import static xyz.kyngs.librelogin.paper.protocol.ProtocolUtil.getServerVersion;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.google.common.base.MoreObjects;
import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import xyz.kyngs.librelogin.api.PlatformHandle;
import xyz.kyngs.librelogin.api.server.ServerPing;

public class PaperPlatformHandle implements PlatformHandle<Player, World> {

    private final PaperLibreLogin plugin;

    public PaperPlatformHandle(PaperLibreLogin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Audience getAudienceForPlayer(Player player) {
        return player;
    }

    @Override
    public UUID getUUIDForPlayer(Player player) {
        return player.getUniqueId();
    }

    @Override
    public CompletableFuture<Throwable> movePlayer(Player player, World to) {
        var result = new CompletableFuture<Throwable>();
        PaperUtil.runForPlayer(
                player,
                () ->
                        player.teleportAsync(to.getSpawnLocation())
                                .whenComplete(
                                        (success, throwable) -> {
                                            if (throwable != null) {
                                                result.complete(throwable);
                                            } else {
                                                result.complete(
                                                        success
                                                                ? null
                                                                : new RuntimeException(
                                                                        "Unknown cause"));
                                            }
                                        }),
                plugin);
        return result;
    }

    @Override
    public void kick(Player player, Component reason) {
        PaperUtil.runForPlayer(player, () -> player.kick(reason), plugin);
    }

    @Override
    public World getServer(String name, boolean limbo) {
        var world = Bukkit.getWorld(name);

        if (world != null) return world;

        if (PaperScheduler.FOLIA && limbo) {
            return null;
        }

        var file = new File(name);
        var exists = file.exists();

        if (exists) {
            plugin.getLogger().info("Found world file for " + name + ", loading...");
        } else {
            plugin.getLogger().info("World file for " + name + " not found, creating...");
        }

        if (PaperScheduler.FOLIA) {
            if (exists) {
                plugin.getLogger()
                        .warn(
                                "World "
                                        + name
                                        + " exists, but Folia does not support loading worlds at"
                                        + " runtime. Load it before startup or use an already"
                                        + " loaded world in LibreLogin's configuration.");
            } else {
                plugin.getLogger()
                        .warn(
                                "World "
                                        + name
                                        + " does not exist, and Folia does not support creating"
                                        + " worlds at runtime. Create/load it before startup or use"
                                        + " an already loaded world in LibreLogin's configuration.");
            }
            return null;
        }

        var creator = new WorldCreator(name);

        if (limbo) {
            creator.generator("librelogin:void");
        }

        world = Bukkit.createWorld(creator);

        if (limbo) {
            world.setSpawnLocation(
                    new Location(world, 0.5, world.getHighestBlockYAt(0, 0) + 1, 0.5));

            // Version-safe world configuration
            try {
                if (getServerVersion().isOlderThan(ServerVersion.V_1_21_9))
                    world.setKeepSpawnInMemory(true);
            } catch (NoSuchMethodError | NoClassDefFoundError ignored) {
                // Method may not exist in this version
            }

            try {
                if (getServerVersion().isOlderThan(ServerVersion.V_1_21_11)) {
                    world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
                    world.setGameRule(GameRule.DO_INSOMNIA, false);
                } else {
                    world.setGameRule(GameRules.ADVANCE_TIME, false);
                    world.setGameRule(GameRules.SPAWN_PHANTOMS, false);
                }
            } catch (NoSuchFieldError | NoClassDefFoundError e) {
                // GameRules class or fields may not exist in this version
                // Fall back to standard GameRule enum
                try {
                    world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
                    world.setGameRule(GameRule.DO_INSOMNIA, false);
                } catch (Exception ignored) {
                }
            }
        }

        return world;
    }

    @Override
    public boolean usesLoginLocationProtection() {
        return PaperScheduler.FOLIA;
    }

    @Override
    public void protectLoginLocation(Player player) {
        plugin.getLoginProtection().protect(player);
    }

    @Override
    public void unprotectLoginLocation(Player player) {
        plugin.getLoginProtection().unprotect(player);
    }

    @Override
    public Class<World> getServerClass() {
        return World.class;
    }

    @Override
    public Class<Player> getPlayerClass() {
        return Player.class;
    }

    @Override
    public String getIP(Player player) {
        var address = player.getAddress();
        if (address == null) {
            // Fallback: try the virtual host or return a placeholder
            return "0.0.0.0";
        }
        return address.getAddress().getHostAddress();
    }

    @Override
    public ServerPing ping(World server) {
        return new ServerPing(Integer.MAX_VALUE);
    }

    @Override
    public Collection<World> getServers() {
        return Bukkit.getWorlds();
    }

    @Override
    public String getServerName(World server) {
        return server.getName();
    }

    @Override
    public int getConnectedPlayers(World server) {
        return server.getPlayerCount();
    }

    @Override
    public String getPlayersServerName(Player player) {
        return player.getWorld().getName();
    }

    @Override
    public String getPlayersVirtualHost(Player player) {
        return null;
    }

    @Override
    public String getUsernameForPlayer(Player player) {
        return player.getName();
    }

    @Override
    public String getPlatformIdentifier() {
        return "paper";
    }

    @Override
    public ProxyData getProxyData() {
        return new ProxyData(
                Bukkit.getName() + " " + Bukkit.getVersion(),
                getServers().stream().map(this::fromWorld).toList(),
                Arrays.stream(Bukkit.getPluginManager().getPlugins())
                        .map(
                                plugin ->
                                        MoreObjects.toStringHelper(plugin)
                                                .add("name", plugin.getName())
                                                .add(
                                                        "version",
                                                        plugin.getDescription().getVersion())
                                                .add(
                                                        "authors",
                                                        plugin.getDescription().getAuthors())
                                                .toString())
                        .toList(),
                plugin.getServerHandler().getLimboServers().stream().map(this::fromWorld).toList(),
                plugin.getServerHandler().getLobbyServers().values().stream()
                        .map(this::fromWorld)
                        .toList());
    }

    private String fromWorld(World world) {
        return MoreObjects.toStringHelper(world)
                .add("name", world.getName())
                .add("environment", world.getEnvironment())
                .add("difficulty", world.getDifficulty())
                .add("players", world.getPlayers().size())
                .toString();
    }
}
