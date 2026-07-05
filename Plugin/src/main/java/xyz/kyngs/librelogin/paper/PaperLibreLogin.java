/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.paper;

import static xyz.kyngs.librelogin.common.config.ConfigurationKeys.DEBUG;
import static xyz.kyngs.librelogin.paper.protocol.ProtocolUtil.getServerVersion;

import co.aikar.commands.BukkitCommandIssuer;
import co.aikar.commands.CommandIssuer;
import co.aikar.commands.CommandManager;
import co.aikar.commands.PaperCommandManager;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import java.io.File;
import java.io.InputStream;
import java.util.UUID;
import net.kyori.adventure.audience.Audience;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.CustomChart;
import org.bstats.charts.SimplePie;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import xyz.kyngs.librelogin.api.Logger;
import xyz.kyngs.librelogin.api.database.User;
import xyz.kyngs.librelogin.api.event.exception.EventCancelledException;
import xyz.kyngs.librelogin.common.AuthenticLibreLogin;
import xyz.kyngs.librelogin.common.SLF4JLogger;
import xyz.kyngs.librelogin.common.image.AuthenticImageProjector;
import xyz.kyngs.librelogin.common.util.CancellableTask;
import xyz.kyngs.librelogin.paper.protocol.PacketListener;

public class PaperLibreLogin extends AuthenticLibreLogin<Player, World> {

    private final PaperBootstrap bootstrap;
    private final FoliaLoginProtection loginProtection;
    private PaperListeners listeners;
    private boolean started;
    private boolean packetEventsCleanedUp;
    private boolean commonCleanedUp;

    public PaperLibreLogin(PaperBootstrap bootstrap) {
        this.bootstrap = bootstrap;
        this.loginProtection = new FoliaLoginProtection(this);
        this.started = false;
        this.packetEventsCleanedUp = false;
        this.commonCleanedUp = false;

        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(bootstrap));

        PacketEvents.getAPI()
                .getSettings()
                //                .debug(true)
                .checkForUpdates(false)
                .bStats(false);
    }

    public PaperBootstrap getBootstrap() {
        return bootstrap;
    }

    public FoliaLoginProtection getLoginProtection() {
        return loginProtection;
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        return bootstrap.getResource(name);
    }

    @Override
    public File getDataFolder() {
        return bootstrap.getDataFolder();
    }

    @Override
    public String getVersion() {
        return bootstrap.getDescription().getVersion();
    }

    @Override
    public boolean isPresent(UUID uuid) {
        return Bukkit.getPlayer(uuid) != null;
    }

    @Override
    public boolean multiProxyEnabled() {
        return false;
    }

    @Override
    public Player getPlayerForUUID(UUID uuid) {
        return Bukkit.getPlayer(uuid);
    }

    @Override
    protected PaperPlatformHandle providePlatformHandle() {
        return new PaperPlatformHandle(this);
    }

    @Override
    protected Logger provideLogger() {
        return new SLF4JLogger(bootstrap.getSLF4JLogger(), () -> getConfiguration().get(DEBUG));
    }

    @Override
    public CommandManager<?, ?, ?, ?, ?, ?> provideManager() {
        return new PaperCommandManager(bootstrap);
    }

    @Override
    protected boolean mainThread() {
        return Bukkit.isPrimaryThread() && started;
    }

    @Override
    public Player getPlayerFromIssuer(CommandIssuer issuer) {
        var bukkitIssuer = (BukkitCommandIssuer) issuer;

        return bukkitIssuer.getPlayer();
    }

    @Override
    protected void disable() {
        started = false;
        loginProtection.unprotectAll();
        terminatePacketEvents();
        if (getDatabaseProvider() == null || commonCleanedUp) return; // Not initialized

        commonCleanedUp = true;
        super.disable();
    }

    @Override
    protected void enable() {

        logger = provideLogger();

        if (Bukkit.getOnlineMode()) {
            getLogger()
                    .error(
                            "!!!The server is running in online mode! LibreLogin won't start unless"
                                    + " you set it to false!!!");
            disable();
            return;
        }

        boolean isBehindProxy;
        try {
            if (getServerVersion().isNewerThanOrEquals(ServerVersion.V_1_21_4))
                isBehindProxy = Bukkit.getServer().getServerConfig().isProxyEnabled();
            else
                isBehindProxy =
                        Bukkit.spigot().getSpigotConfig().getBoolean("settings.bungeecord")
                                || Bukkit.spigot()
                                        .getPaperConfig()
                                        .getBoolean("settings.velocity-support.enabled");
        } catch (NoSuchMethodError | NoClassDefFoundError e) {
            // Fallback for versions where getServerConfig() doesn't exist
            try {
                isBehindProxy =
                        Bukkit.spigot().getSpigotConfig().getBoolean("settings.bungeecord")
                                || Bukkit.spigot()
                                        .getPaperConfig()
                                        .getBoolean("settings.velocity-support.enabled");
            } catch (Exception ex) {
                isBehindProxy = false;
            }
        }

        if (isBehindProxy) {
            getLogger().error("!!!This server is running under a proxy, LibreLogin won't start!!!");
            getLogger()
                    .error(
                            "If you want to use LibreLogin under a proxy, place it on the proxy and"
                                    + " remove it from the server.");
            disable();
            return;
        }

        try {
            super.enable();
        } catch (ShutdownException e) {
            return;
        }

        try {
            startPacketEvents();
        } catch (RuntimeException e) {
            getLogger().error("Failed to initialize PacketEvents, disabling LibreLogin", e);
            disable();
            return;
        }

        var provider = getEventProvider();

        provider.subscribe(
                provider.getTypes().authenticated,
                event -> {
                    var player = event.getPlayer();
                    if (player == null) return;
                    if (PaperScheduler.FOLIA) {
                        loginProtection.unprotect(player);
                    } else {
                        PaperUtil.runForPlayer(player, () -> player.setInvisible(false), this);
                    }
                });

        listeners = new PaperListeners(this);

        Bukkit.getPluginManager().registerEvents(listeners, bootstrap);
        Bukkit.getPluginManager().registerEvents(new Blockers(this), bootstrap);
        PacketEvents.getAPI().getEventManager().registerListener(new PacketListener(listeners));

        started = true;
    }

    private void startPacketEvents() {
        var api = PacketEvents.getAPI();
        if (!api.isLoaded()) {
            api.load();
            preloadPacketEventsConnectionClasses();
        }
        if (!api.isInitialized()) {
            api.init();
        }
        packetEventsCleanedUp = false;
    }

    private void terminatePacketEvents() {
        if (packetEventsCleanedUp) {
            return;
        }

        try {
            var api = PacketEvents.getAPI();

            if (api.isInitialized()) {
                api.terminate();
            } else if (api.isLoaded()) {
                api.getInjector().uninject();
                api.getEventManager().unregisterAllListeners();
            }
        } catch (Throwable throwable) {
            if (logger != null) {
                logger.warn("Failed to clean up PacketEvents injection", throwable);
            } else {
                bootstrap
                        .getSLF4JLogger()
                        .warn("Failed to clean up PacketEvents injection", throwable);
            }
        } finally {
            packetEventsCleanedUp = true;
        }
    }

    private void preloadPacketEventsConnectionClasses() {
        var classLoader = PaperLibreLogin.class.getClassLoader();
        var classNames =
                new String[] {
                    "io.github.retrooper.packetevents.injector.connection.ServerChannelHandler",
                    "io.github.retrooper.packetevents.injector.connection.PreChannelInitializer_v1_12",
                    "io.github.retrooper.packetevents.injector.connection.PreChannelInitializer_v1_8",
                    "io.github.retrooper.packetevents.injector.connection.PreChannelInitializer_v1_8$1",
                    "io.github.retrooper.packetevents.injector.connection.ServerConnectionInitializer",
                    "io.github.retrooper.packetevents.injector.handlers.PacketEventsDecoder",
                    "io.github.retrooper.packetevents.injector.handlers.PacketEventsEncoder"
                };

        try {
            for (var className : classNames) {
                Class.forName(className, true, classLoader);
            }
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Failed to preload PacketEvents connection classes", e);
        }
    }

    @Override
    public void authorize(Player player, User user, Audience audience) {
        if (PaperScheduler.FOLIA) {
            // On Folia, every access to the player (reads, visibility, teleport) must happen on the
            // region thread that owns the player.
            PaperUtil.runForPlayer(player, () -> authorizeNow(player, user), this);
        } else {
            authorizeNow(player, user);
        }
    }

    private void authorizeNow(Player player, User user) {
        try {

            var location = listeners.getSpawnLocationCache().getIfPresent(player.getUniqueId());

            if (location != null) {
                // We have a cached location from before the player was sent to limbo
                listeners.getSpawnLocationCache().invalidate(player.getUniqueId());
            } else {
                // No cached location - check if the player is currently in a limbo world
                var currentWorld = player.getWorld();
                var isInLimbo =
                        getConfiguration()
                                .get(
                                        xyz.kyngs.librelogin.common.config.ConfigurationKeys
                                                .LIMBO)
                                .contains(currentWorld.getName());

                if (isInLimbo) {
                    // Player is in limbo and we don't have their old location cached
                    // Teleport them to a lobby world spawn
                    var world = getServerHandler().chooseLobbyServer(user, player, true, false);

                    if (world == null) {
                        getPlatformHandle()
                                .kick(player, getMessages().getMessage("kick-no-lobby"));
                        return;
                    }

                    location = world.getSpawnLocation();
                } else {
                    // Player is already in a valid (non-limbo) world
                    // Keep them at their current position
                    location = player.getLocation();
                }
            }

            var finalLocation = location;

            if (PaperScheduler.FOLIA) {
                // Already running on the player's region thread.
                // Restore visibility — Blockers.onJoin sets invisible=true for limbo players
                player.setInvisible(false);
                player.teleportAsync(finalLocation);
            } else {
                PaperUtil.runForPlayer(
                        player,
                        () -> {
                            // Restore visibility — Blockers.onJoin sets invisible=true for limbo
                            // players
                            player.setInvisible(false);
                            player.teleportAsync(finalLocation);
                        },
                        this);
            }

        } catch (EventCancelledException ignored) {
        }
    }

    @Override
    public CancellableTask delay(Runnable runnable, long delayInMillis) {
        return PaperScheduler.runAsyncDelayed(bootstrap, runnable, delayInMillis);
    }

    @Override
    public CancellableTask repeat(Runnable runnable, long delayInMillis, long repeatInMillis) {
        return PaperScheduler.runAsyncRepeating(bootstrap, runnable, delayInMillis, repeatInMillis);
    }

    @Override
    public boolean pluginPresent(String pluginName) {
        return Bukkit.getPluginManager().isPluginEnabled(pluginName);
    }

    @Override
    protected AuthenticImageProjector<Player, World> provideImageProjector() {
        return null;
    }

    @Override
    protected void initMetrics(CustomChart... charts) {
        var metrics = new Metrics(bootstrap, Constants.BSTATS_ID);

        for (var chart : charts) {
            metrics.addCustomChart(chart);
        }

        var isVelocity = new SimplePie("is_velocity", () -> "Paper");

        metrics.addCustomChart(isVelocity);
    }

    @Override
    protected void shutdownProxy(int code) {
        bootstrap.disable();
        bootstrap.getServer().shutdown();
        throw new ShutdownException();
    }

    @Override
    public Audience getAudienceFromIssuer(CommandIssuer issuer) {
        return ((BukkitCommandIssuer) issuer).getIssuer();
    }
}
