/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.paper;

import org.bukkit.entity.Player;

public class PaperUtil {

    /**
     * Runs a task that operates on the given player on the correct thread. On Folia this is the
     * player's owning region thread; on regular Paper it is the main server thread. This must be
     * used for any access to a player's state (teleporting, kicking, visibility, etc.).
     */
    public static void runForPlayer(Player player, Runnable runnable, PaperLibreLogin plugin) {
        PaperScheduler.runForEntity(plugin.getBootstrap(), player, runnable);
    }
}
