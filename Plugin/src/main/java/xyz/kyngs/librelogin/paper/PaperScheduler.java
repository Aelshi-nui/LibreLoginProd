/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.paper;

import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import xyz.kyngs.librelogin.common.util.CancellableTask;

/**
 * Scheduling helper that transparently supports both regular Paper/Spigot and Folia.
 *
 * <p>Folia replaces the classic {@link org.bukkit.scheduler.BukkitScheduler} with a stub that
 * throws {@link UnsupportedOperationException} on every call, so plugins must instead use the
 * region/global/async/entity schedulers. Those same scheduler APIs are also provided on regular
 * Paper (where they delegate to the main thread), which lets a single code path run on both
 * platforms.
 */
public final class PaperScheduler {

    /** Whether the server is running Folia (or a Folia-based fork). */
    public static final boolean FOLIA = detectFolia();

    private PaperScheduler() {}

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Runs a one-shot task away from the main/region threads after the given delay.
     *
     * @param delayMillis the delay before execution, in milliseconds
     */
    public static CancellableTask runAsyncDelayed(
            Plugin plugin, Runnable runnable, long delayMillis) {
        if (FOLIA) {
            var task =
                    Bukkit.getAsyncScheduler()
                            .runDelayed(
                                    plugin,
                                    t -> runnable.run(),
                                    Math.max(1, delayMillis),
                                    TimeUnit.MILLISECONDS);
            return task::cancel;
        }

        var task =
                Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, runnable, delayMillis / 50);
        return task::cancel;
    }

    /** Runs a repeating task away from the main/region threads. */
    public static CancellableTask runAsyncRepeating(
            Plugin plugin, Runnable runnable, long delayMillis, long periodMillis) {
        if (FOLIA) {
            var task =
                    Bukkit.getAsyncScheduler()
                            .runAtFixedRate(
                                    plugin,
                                    t -> runnable.run(),
                                    Math.max(1, delayMillis),
                                    Math.max(1, periodMillis),
                                    TimeUnit.MILLISECONDS);
            return task::cancel;
        }

        var task =
                Bukkit.getScheduler()
                        .runTaskTimerAsynchronously(
                                plugin, runnable, delayMillis / 50, periodMillis / 50);
        return task::cancel;
    }

    /**
     * Runs a task on the global region (Folia) or the main thread (Paper). Use for work that is not
     * tied to a specific entity or location.
     */
    public static void runGlobal(Plugin plugin, Runnable runnable) {
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().run(plugin, t -> runnable.run());
            return;
        }

        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    /**
     * Runs a task that operates on the given entity on the thread that owns it. On Folia this is the
     * entity's region thread; on regular Paper it is the main thread.
     */
    public static void runForEntity(Plugin plugin, Entity entity, Runnable runnable) {
        if (FOLIA) {
            entity.getScheduler().run(plugin, t -> runnable.run(), null);
            return;
        }

        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }
}
