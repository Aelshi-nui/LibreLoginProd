/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.paper;

import static xyz.kyngs.librelogin.paper.protocol.ProtocolUtil.getServerVersion;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.reflection.Reflection;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientEncryptionResponse;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientLoginStart;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerDisconnect;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerEncryptionRequest;
import io.netty.channel.Channel;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.crypto.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import xyz.kyngs.librelogin.api.database.User;
import xyz.kyngs.librelogin.common.AuthenticLibreLogin;
import xyz.kyngs.librelogin.common.config.ConfigurationKeys;
import xyz.kyngs.librelogin.common.config.MessageKeys;
import xyz.kyngs.librelogin.common.listener.AuthenticListeners;
import xyz.kyngs.librelogin.common.util.GeneralUtil;
import xyz.kyngs.librelogin.paper.protocol.ClientPublicKey;
import xyz.kyngs.librelogin.paper.protocol.EncryptionUtil;
import xyz.kyngs.librelogin.paper.protocol.ProtocolUtil;

public class PaperListeners extends AuthenticListeners<PaperLibreLogin, Player, World>
        implements Listener {

    private static final String ENCRYPTION_CLASS_NAME = "MinecraftEncryption";
    private static final Class<?> ENCRYPTION_CLASS;
    private static Method encryptMethod;
    private static Method cipherMethod;
    private static final boolean IS_DEOBFUSCATION_NEEDED =
            getServerVersion().isOlderThan(ServerVersion.V_1_21_11);

    static {
        if (IS_DEOBFUSCATION_NEEDED) {
            Class<?> clazz = null;
            try {
                clazz = Class.forName("net.minecraft.util." + ENCRYPTION_CLASS_NAME);
            } catch (ClassNotFoundException e) {
                // Class may not exist in this version - encryption will use fallback methods
                try {
                    // Try alternative class names used in different MC versions
                    clazz = Class.forName("net.minecraft.util.CryptManager");
                } catch (ClassNotFoundException ignored) {
                }
            }
            ENCRYPTION_CLASS = clazz;
        } else {
            ENCRYPTION_CLASS = null;
        }
    }

    private final KeyPair keyPair = EncryptionUtil.generateKeyPair();
    private final Random random = new SecureRandom();
    private final Cache<Object, EncryptionData> encryptionDataCache =
            Caffeine.newBuilder().expireAfterWrite(2, TimeUnit.MINUTES).build();
    private final FloodgateHelper floodgateHelper;
    private final Cache<UUID, String> ipCache;
    private final Cache<UUID, User> readOnlyUserCache;
    private final Cache<UUID, Location> spawnLocationCache;
    private final Cache<UUID, Location> joinedWhileDead;

    public PaperListeners(PaperLibreLogin plugin) {
        super(plugin);

        floodgateHelper = this.plugin.floodgateEnabled() ? new FloodgateHelper() : null;

        ipCache = Caffeine.newBuilder().expireAfterWrite(30, TimeUnit.MINUTES).build();

        readOnlyUserCache = Caffeine.newBuilder().expireAfterWrite(30, TimeUnit.MINUTES).build();

        spawnLocationCache = Caffeine.newBuilder().expireAfterWrite(30, TimeUnit.MINUTES).build();

        joinedWhileDead = Caffeine.newBuilder().expireAfterWrite(30, TimeUnit.MINUTES).build();
    }

    public Cache<UUID, Location> getSpawnLocationCache() {
        return spawnLocationCache;
    }

    /**
     * Kicks the player with the given UUID (if currently online) on the correct thread for both
     * Paper and Folia. Used from asynchronous login handling where a direct kick is not possible.
     */
    private void kickByUuid(UUID uuid, Component reason) {
        PaperScheduler.runGlobal(
                plugin.getBootstrap(),
                () -> {
                    var p = plugin.getPlayerForUUID(uuid);
                    if (p != null) {
                        PaperScheduler.runForEntity(plugin.getBootstrap(), p, () -> p.kick(reason));
                    }
                });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getLoginProtection().unprotectNow(event.getPlayer());
        GeneralUtil.runAsync(() -> onPlayerDisconnect(event.getPlayer()));
    }

    @EventHandler
    public void onKick(PlayerKickEvent event) {
        plugin.getLoginProtection().unprotectNow(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        var location = joinedWhileDead.getIfPresent(event.getPlayer().getUniqueId());
        if (location == null) {
            return;
        }

        if (event.getRespawnReason() != PlayerRespawnEvent.RespawnReason.PLUGIN) {
            return;
        }

        event.setRespawnLocation(location);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPostLogin(PlayerLoginEvent event) {
        ipCache.put(event.getPlayer().getUniqueId(), event.getAddress().getHostAddress());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        var puuid = player.getUniqueId();

        var data = readOnlyUserCache.getIfPresent(puuid);
        if (data == null && !plugin.fromFloodgate(player.getName())) {
            // Try to fetch from database as a fallback before kicking
            try {
                data = plugin.getDatabaseProvider().getByName(player.getName());
            } catch (Exception ignored) {
            }
            if (data == null) {
                plugin.getPlatformHandle()
                        .kick(player, Component.text("Internal error, please try again later."));
                return;
            }
        }

        readOnlyUserCache.invalidate(puuid);
        if (player.isDead()) {
            // Preserve spawn location cache - don't invalidate it here
            // It will be used after respawn in onPlayerRespawn
            if (!PaperScheduler.FOLIA) {
                player.spigot().respawn();
            }
        } else {
            joinedWhileDead.invalidate(puuid);
        }

        onPostLogin(player, data);
        if (PaperScheduler.FOLIA
                && (!plugin.getAuthorizationProvider().isAuthorized(player)
                        || plugin.getAuthorizationProvider().isAwaiting2FA(player))) {
            plugin.getLoginProtection().protectNow(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (plugin.fromFloodgate(event.getName())) return;

        var user = plugin.getDatabaseProvider().getByName(event.getName());

        if (user == null) {
            // Player doesn't exist yet in the database - this can happen if the packet
            // handler hasn't finished creating the user. Allow the login to proceed;
            // onJoin will handle the fallback lookup.
            return;
        }

        var newProfile = Bukkit.createProfileExact(user.getUuid(), event.getName());

        event.setPlayerProfile(newProfile);

        readOnlyUserCache.put(user.getUuid(), user);
    }

    // Changed to an async variant of this event
    // cuz the previous one is deprecated and causes a bug (Issue #52)
    @SuppressWarnings("UnstableApiUsage") // let me
    @EventHandler(priority = EventPriority.HIGHEST)
    public void chooseWorld(AsyncPlayerSpawnLocationEvent event) {
        var puuid = event.getConnection().getProfile().getId();

        if (PaperScheduler.FOLIA && plugin.getServerHandler().getLimboServers().isEmpty()) {
            ipCache.invalidate(puuid);
            return;
        }

        // Skip processing for Floodgate (Bedrock) players - let them through normally
        if (plugin.fromFloodgate(puuid)) {
            return;
        }

        var ip = ipCache.getIfPresent(puuid);
        if (ip == null) {
            // Fallback: try to get IP from the connection directly
            try {
                var addr = event.getConnection().getAddress();
                if (addr instanceof java.net.InetSocketAddress inetAddr) {
                    ip = inetAddr.getAddress().getHostAddress();
                }
            } catch (Exception ignored) {
            }
            if (ip == null) {
                kickByUuid(puuid, Component.text("Internal error, please try again later."));
                return;
            }
        }

        var world = chooseServer(puuid, ip, readOnlyUserCache.getIfPresent(puuid));
        ipCache.invalidate(puuid);
        if (world.value() == null) {
            kickByUuid(
                    puuid,
                    plugin.getMessages()
                            .getMessage("kick-no-" + (world.key() ? "lobby" : "limbo")));
        } else {
            Function<World, Boolean> isLimbo =
                    (w) ->
                            plugin.getConfiguration()
                                    .get(ConfigurationKeys.LIMBO)
                                    .contains(w.getName());
            Location eventSpawnLocation = event.getSpawnLocation();
            if (!event.isNewPlayer()
                    && !isLimbo.apply(eventSpawnLocation.getWorld())
                    && isLimbo.apply(world.value())) {
                spawnLocationCache.put(puuid, eventSpawnLocation);
            } else {
                return;
            }

            var loc = world.value().getSpawnLocation();
            joinedWhileDead.put(puuid, loc);
            event.setSpawnLocation(loc);
        }
    }

    /* Commented out when migrating to PacketEvents
    //Unused, might be useful in the future
    public void setUUID(Player player, String username) {
        var profile = plugin.getDatabaseProvider().getByName(username);

        try {
            var network = getNetworkManager(player);

            var clazz = network.getClass();
            var accessor = Accessors.getFieldAccessorOrNull(clazz, "spoofedUUID", UUID.class);
            accessor.set(network, profile.getUuid());
        } catch (Exception e) {
            e.printStackTrace();
            kickPlayer("Internal error", player);
        }
    }*/

    public void asyncPacketReceive(PacketReceiveEvent event) {
        var user = event.getUser();
        var type = event.getPacketType();

        plugin.getLogger()
                .debug(
                        "Packet received "
                                + type
                                + " from "
                                + user.getName()
                                + " ("
                                + user.getAddress().toString()
                                + ")");

        if (type == PacketType.Login.Client.LOGIN_START) {
            var packet = new WrapperLoginClientLoginStart(event);
            var sessionKey = event.getChannel();

            encryptionDataCache.invalidate(sessionKey);

            if (plugin.floodgateEnabled()) {
                var success = floodgateHelper.processFloodgateTasks(event, packet);
                // don't continue execution if the player was kicked by Floodgate
                if (!success) {
                    return;
                }
            }
            var username = packet.getUsername();

            Optional<ClientPublicKey> clientKey;

            if (getServerVersion().isNewerThanOrEquals(ServerVersion.V_1_19_3)) {
                clientKey = Optional.empty();
            } else {
                var signature = packet.getSignatureData();

                clientKey =
                        signature.map(
                                data -> {
                                    var expires = data.getTimestamp();
                                    var key = data.getPublicKey();
                                    var signatureData = data.getSignature();

                                    return new ClientPublicKey(expires, key, signatureData);
                                });
            }

            if (Bukkit.getPlayer(username) != null) {
                kickPlayer(
                        plugin.getMessages().getMessage(MessageKeys.KICK_ALREADY_CONNECTED.key()),
                        user);
                return;
            }

            if (plugin.fromFloodgate(username)) {
                // Floodgate player, do not handle, only retransmit the packet. The UUID will be set
                // by Floodgate
                receiveFakeStartPacket(
                        username, clientKey.orElse(null), event.getChannel(), UUID.randomUUID());
                return;
            }
            var preLoginResult = onPreLogin(username, user.getAddress().getAddress());
            switch (preLoginResult.state()) {
                case DENIED -> {
                    assert preLoginResult.message() != null;
                    kickPlayer(preLoginResult.message(), user);
                }
                case FORCE_ONLINE -> {
                    byte[] token;
                    try {
                        token = EncryptionUtil.generateVerifyToken(random);

                        var newPacket =
                                new WrapperLoginServerEncryptionRequest(
                                        "", keyPair.getPublic(), token);

                        encryptionDataCache.put(
                                sessionKey,
                                new EncryptionData(
                                        username,
                                        token,
                                        clientKey.orElse(null),
                                        preLoginResult.user().getUuid()));

                        PacketEvents.getAPI()
                                .getProtocolManager()
                                .sendPacket(event.getChannel(), newPacket);
                    } catch (Exception e) {
                        plugin.getLogger()
                                .error(
                                        "Failed to send encryption begin packet for player "
                                                + username
                                                + "! Kicking player.");
                        e.printStackTrace();
                        kickPlayer("Internal error", user);
                    }
                }
                default -> {
                    // The original event has been cancelled, so we need to send a fake start
                    // packet. It should be safe to set a random UUID as it will be replaced by the
                    // real one later
                    receiveFakeStartPacket(
                            username,
                            clientKey.orElse(null),
                            event.getChannel(),
                            UUID.randomUUID());
                }
            }
        } else {
            var packet = new WrapperLoginClientEncryptionResponse(event);
            var sharedSecret = packet.getEncryptedSharedSecret();

            var data = encryptionDataCache.asMap().remove(event.getChannel());

            if (data == null) {
                kickPlayer("Illegal encryption state", user);
                return;
            }

            var expectedToken = data.token().clone();

            if (!verifyNonce(packet, data.publicKey(), expectedToken)) {
                kickPlayer("Invalid nonce", user);
                return;
            }

            // Verify session
            var privateKey = keyPair.getPrivate();

            SecretKey loginKey;

            try {
                loginKey = EncryptionUtil.decryptSharedKey(privateKey, sharedSecret);
            } catch (GeneralSecurityException securityEx) {
                kickPlayer("Cannot decrypt shared secret", user);
                return;
            }

            try {
                if (!enableEncryption(loginKey, user, event.getChannel())) {
                    return;
                }
            } catch (Exception e) {
                kickPlayer("Cannot decrypt shared secret", user);
                return;
            }

            var serverId = EncryptionUtil.getServerIdHashString("", loginKey, keyPair.getPublic());
            var username = data.username();
            var address = user.getAddress();

            try {
                if (hasJoined(username, serverId, address.getAddress())) {
                    receiveFakeStartPacket(
                            username, data.publicKey(), event.getChannel(), data.uuid());
                } else {
                    plugin.getLogger().warn("Mojang rejected the premium session for " + username);
                    kickPlayer("Invalid session", user);
                }
            } catch (IOException e) {
                if (e instanceof SocketTimeoutException) {
                    plugin.getLogger()
                            .warn("Session verification timed out (5 seconds) for " + username);
                } else {
                    plugin.getLogger().warn("Session verification failed for " + username, e);
                }
                kickPlayer("Cannot verify session", user);
            }
        }
    }

    public void onPacketReceive(PacketReceiveEvent event) {
        event.setCancelled(true);

        var copy = event.clone();

        AuthenticLibreLogin.EXECUTOR.execute(
                () -> {
                    try {
                        asyncPacketReceive(copy);
                    } catch (Throwable throwable) {
                        plugin.getLogger()
                                .error(
                                        "Failed to process "
                                                + copy.getPacketType()
                                                + " during login for "
                                                + copy.getUser().getAddress(),
                                        throwable);
                        try {
                            kickPlayer("Internal login error", copy.getUser());
                        } catch (Throwable kickFailure) {
                            throwable.addSuppressed(kickFailure);
                        }
                    } finally {
                        copy.cleanUp();
                    }
                });
    }

    /**
     * fake a new login packet in order to let the server handle all the other stuff
     *
     * @author games647 and FastLogin contributors
     */
    private void receiveFakeStartPacket(
            String username, ClientPublicKey clientKey, Object channel, UUID uuid) {
        WrapperLoginClientLoginStart startPacket;
        if (getServerVersion().isNewerThanOrEquals(ServerVersion.V_1_20)) {
            startPacket =
                    new WrapperLoginClientLoginStart(
                            getServerVersion().toClientVersion(),
                            username,
                            clientKey == null ? null : clientKey.toSignatureData(),
                            uuid);
        } else if (getServerVersion().isNewerThanOrEquals(ServerVersion.V_1_19)) {
            startPacket =
                    new WrapperLoginClientLoginStart(
                            getServerVersion().toClientVersion(),
                            username,
                            clientKey == null ? null : clientKey.toSignatureData());
        } else {
            startPacket =
                    new WrapperLoginClientLoginStart(
                            getServerVersion().toClientVersion(), username);
        }
        PacketEvents.getAPI().getProtocolManager().receivePacketSilently(channel, startPacket);
    }

    public boolean hasJoined(String username, String serverHash, InetAddress hostIp)
            throws IOException {
        // Encode username to prevent URL injection
        var encodedUsername = URLEncoder.encode(username, StandardCharsets.UTF_8);
        String url;
        if (hostIp instanceof Inet6Address
                || plugin.getConfiguration().get(ConfigurationKeys.ALLOW_PROXY_CONNECTIONS)) {
            url =
                    String.format(
                            "https://sessionserver.mojang.com/session/minecraft/hasJoined?username=%s&serverId=%s",
                            encodedUsername, serverHash);
        } else {
            var encodedIP = URLEncoder.encode(hostIp.getHostAddress(), StandardCharsets.UTF_8);
            url =
                    String.format(
                            "https://sessionserver.mojang.com/session/minecraft/hasJoined?username=%s&serverId=%s&ip=%s",
                            encodedUsername, serverHash, encodedIP);
        }

        var conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.connect();
        int responseCode = conn.getResponseCode();
        conn.disconnect();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            return true;
        }
        if (responseCode == HttpURLConnection.HTTP_NO_CONTENT) {
            return false;
        }

        // Never treat rate limits or server errors as successful authentication.
        throw new IOException("Mojang session server returned HTTP " + responseCode);
    }

    /**
     * @author games647 and FastLogin contributors, kyngs
     */
    private boolean enableEncryption(
            SecretKey loginKey,
            com.github.retrooper.packetevents.protocol.player.User user,
            Object channel)
            throws IllegalArgumentException {
        try {
            Object networkManager = ProtocolUtil.findNetworkManager(channel);

            if (networkManager == null) {
                plugin.getLogger()
                        .error(
                                "Could not find the active network connection for premium login "
                                        + user.getAddress());
                kickPlayer("Internal login error", user);
                return false;
            }

            initializeEncryptionMethods(networkManager.getClass());
            if (encryptMethod == null) {
                plugin.getLogger()
                        .error(
                                "Could not find an encryption method on "
                                        + networkManager.getClass().getName());
                kickPlayer("Internal login error", user);
                return false;
            }

            if (channel instanceof Channel nettyChannel
                    && !nettyChannel.eventLoop().inEventLoop()) {
                var future =
                        nettyChannel
                                .eventLoop()
                                .submit(
                                        () -> {
                                            try {
                                                enableEncryptionNow(networkManager, loginKey);
                                            } catch (ReflectiveOperationException
                                                    | GeneralSecurityException e) {
                                                throw new IllegalStateException(e);
                                            }
                                        });
                future.syncUninterruptibly();
            } else {
                enableEncryptionNow(networkManager, loginKey);
            }
        } catch (Exception ex) {
            plugin.getLogger()
                    .error(
                            "Could not enable premium-login encryption for " + user.getAddress(),
                            ex);
            kickPlayer("Internal login error", user);
            return false;
        }

        return true;
    }

    private static void initializeEncryptionMethods(Class<?> networkManagerClass) {
        if (encryptMethod != null) return;

        synchronized (PaperListeners.class) {
            if (encryptMethod != null) return;

            Method method =
                    Reflection.getMethod(networkManagerClass, "setupEncryption", SecretKey.class);
            if (method == null) {
                method =
                        Reflection.getMethod(
                                networkManagerClass, "setEncryptionKey", SecretKey.class);
            }
            if (method == null) {
                method = Reflection.getMethod(networkManagerClass, "encrypt", SecretKey.class);
            }

            if (method == null) {
                for (var candidate : networkManagerClass.getDeclaredMethods()) {
                    if (java.util.Arrays.equals(
                            candidate.getParameterTypes(), new Class<?>[] {SecretKey.class})) {
                        candidate.setAccessible(true);
                        method = candidate;
                        break;
                    }
                }
            }

            Method helper = null;
            if (method == null) {
                method =
                        Reflection.getMethod(
                                networkManagerClass,
                                "setEncryptionKey",
                                Cipher.class,
                                Cipher.class);
                if (method == null) {
                    for (var candidate : networkManagerClass.getDeclaredMethods()) {
                        if (java.util.Arrays.equals(
                                candidate.getParameterTypes(),
                                new Class<?>[] {Cipher.class, Cipher.class})) {
                            candidate.setAccessible(true);
                            method = candidate;
                            break;
                        }
                    }
                }

                if (method != null && ENCRYPTION_CLASS != null) {
                    helper = Reflection.getMethod(ENCRYPTION_CLASS, "a", int.class, Key.class);
                    if (helper == null) {
                        helper =
                                Reflection.getMethod(
                                        ENCRYPTION_CLASS, "getCipher", int.class, Key.class);
                    }
                }
            }

            cipherMethod = helper;
            encryptMethod = method;
        }
    }

    private static void enableEncryptionNow(Object networkManager, SecretKey loginKey)
            throws ReflectiveOperationException, GeneralSecurityException {
        if (encryptMethod.getParameterCount() == 1) {
            encryptMethod.invoke(networkManager, loginKey);
            return;
        }

        if (cipherMethod == null) {
            throw new GeneralSecurityException("Could not find the server cipher factory");
        }

        Object decryptionCipher = cipherMethod.invoke(null, Cipher.DECRYPT_MODE, loginKey);
        Object encryptionCipher = cipherMethod.invoke(null, Cipher.ENCRYPT_MODE, loginKey);
        encryptMethod.invoke(networkManager, decryptionCipher, encryptionCipher);
    }

    private void kickPlayer(
            String reason, com.github.retrooper.packetevents.protocol.player.User player) {
        kickPlayer(Component.text(reason), player);
    }

    private void kickPlayer(
            Component reason, com.github.retrooper.packetevents.protocol.player.User player) {
        // Cannot use Player#kick(Component) because it doesn't work in the login state
        var kickPacket = new WrapperLoginServerDisconnect(reason);
        var channel = player.getChannel();
        try {
            // send kick packet at login state
            PacketEvents.getAPI().getProtocolManager().sendPacket(channel, kickPacket);
        } finally {
            // Queue the close after the packet write on the same event loop. Closing immediately
            // can discard the reason and leave the client with only a generic "Disconnected".
            if (channel instanceof Channel nettyChannel) {
                try {
                    nettyChannel.eventLoop().execute(player::closeConnection);
                } catch (RuntimeException ignored) {
                    player.closeConnection();
                }
            } else {
                player.closeConnection();
            }
        }
    }

    /**
     * @author games647 and FastLogin contributors
     */
    private boolean verifyNonce(
            WrapperLoginClientEncryptionResponse packet,
            ClientPublicKey clientPublicKey,
            byte[] expectedToken) {
        try {
            if (getServerVersion().isNewerThanOrEquals(ServerVersion.V_1_19)
                    && !getServerVersion().isNewerThanOrEquals(ServerVersion.V_1_19_3)) {
                if (clientPublicKey == null) {
                    return EncryptionUtil.verifyNonce(
                            expectedToken,
                            keyPair.getPrivate(),
                            packet.getEncryptedVerifyToken().get());
                } else {
                    PublicKey publicKey = clientPublicKey.key();
                    var optSignature = packet.getSaltSignature();
                    if (optSignature.isEmpty()) {
                        return false;
                    }
                    var signature = optSignature.get();

                    return EncryptionUtil.verifySignedNonce(
                            expectedToken,
                            publicKey,
                            signature.getSalt(),
                            signature.getSignature());
                }
            } else {
                byte[] nonce = packet.getEncryptedVerifyToken().get();
                return EncryptionUtil.verifyNonce(expectedToken, keyPair.getPrivate(), nonce);
            }
        } catch (NoSuchAlgorithmException
                | InvalidKeyException
                | SignatureException
                | NoSuchPaddingException
                | IllegalBlockSizeException
                | BadPaddingException signatureEx) {
            return false;
        }
    }
}
