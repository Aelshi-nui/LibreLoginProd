/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.paper.protocol;

import static io.github.retrooper.packetevents.util.SpigotReflectionUtil.CHANNEL_CLASS;
import static io.github.retrooper.packetevents.util.SpigotReflectionUtil.NETWORK_MANAGER_CLASS;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.util.reflection.ReflectionObject;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import io.netty.channel.Channel;

public class ProtocolUtil {
    public static ServerVersion getServerVersion() {
        return PacketEvents.getAPI().getServerManager().getVersion();
    }

    public static Channel getChannel(Object networkManager) {
        try {
            ReflectionObject wrapper = new ReflectionObject(networkManager, NETWORK_MANAGER_CLASS);
            return (Channel) wrapper.readObject(0, CHANNEL_CLASS);
        } catch (Exception e) {
            return null;
        }
    }

    public static Object findNetworkManager(Object channel) {
        if (!(channel instanceof Channel targetChannel)) {
            return null;
        }

        // The active NMS Connection is itself a channel handler. Looking it up from the channel is
        // both exact and compatible with Folia 26.x, where PacketEvents' global connection-list
        // reflection can return an empty list.
        for (var handlerName : targetChannel.pipeline().names()) {
            var handler = targetChannel.pipeline().get(handlerName);
            if (handler != null && NETWORK_MANAGER_CLASS.isInstance(handler)) {
                return handler;
            }
        }

        var managers = SpigotReflectionUtil.getNetworkManagers();
        if (managers == null || managers.isEmpty()) {
            return null;
        }

        // First pass: try exact channel identity match (most reliable)
        for (Object manager : managers) {
            try {
                var managerChannel = (Channel) getChannel(manager);
                if (managerChannel != null && managerChannel == targetChannel) {
                    return manager;
                }
            } catch (Exception ignored) {
            }
        }

        // Second pass: try remote address match (original behavior)
        var targetAddress = targetChannel.remoteAddress();
        if (targetAddress != null) {
            for (Object manager : managers) {
                try {
                    var managerChannel = (Channel) getChannel(manager);
                    if (managerChannel != null
                            && managerChannel.remoteAddress() != null
                            && managerChannel.remoteAddress().equals(targetAddress)) {
                        return manager;
                    }
                } catch (Exception ignored) {
                }
            }
        }

        return null;
    }
}
