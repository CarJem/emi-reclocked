package dev.reclocked.emireclocked.timing;

import dev.reclocked.emireclocked.EmiReclocked;

/**
 * EMI's {@code EmiStackList.reload()} forces every installed mod's creative-mode-tab
 * {@code updateEntries()} onto the main render thread via {@code client.submit(...).join()} -
 * this is the most likely source of the "long freeze" symptom on world load / data reload,
 * since it directly blocks the frame loop for as long as the slowest tab takes to populate.
 * EMI already logs the aggregate duration of that whole block; this guard lets us additionally
 * log a per-tab breakdown, without touching EMI's reload logic or timing anything outside of
 * an EMI-triggered reload (a player just opening their creative inventory should never be
 * logged here).
 * <p>
 * {@link dev.reclocked.emireclocked.mixin.EmiStackListReloadGuardMixin} flips this flag around
 * EMI's {@code client.submit(...)} call; {@link dev.reclocked.emireclocked.mixin.ItemGroupTimingMixin}
 * only times and logs {@code ItemGroup#updateEntries} while it is set.
 */
public final class ReloadTimingGuard {
    private static volatile boolean active = false;

    public static void begin() {
        active = true;
    }

    public static void end() {
        active = false;
    }

    public static boolean isActive() {
        return active;
    }

    public static void logGroupTiming(String groupName, long millis) {
        // Only surface groups worth looking at - a full pack can have hundreds of creative
        // tabs and most take well under a millisecond. 5ms is an arbitrary but generous floor;
        // raise/lower by editing here if it's too noisy or too quiet for a given pack.
        if (millis >= 5) {
            EmiReclocked.LOGGER.info("[EMI Reclocked] Creative tab '{}' took {}ms to populate during reload", groupName, millis);
        }
    }

    private ReloadTimingGuard() {
    }
}
