package dev.reclocked.emireclocked.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * All defaults here preserve EMI's stock behavior exactly. The only toggle that changes
 * observable behavior ({@link #skipUnsortedRecipeBake}) defaults to off - see README.md
 * "Fix #2" for what turning it on actually trades away.
 */
public final class EmiReclockedConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue SKIP_UNSORTED_RECIPE_BAKE = BUILDER
        .comment(
            "EMI builds its recipe index twice on every reload: once synchronously and " +
            "unsorted (so something is visible immediately), then again on a background " +
            "thread, sorted (which replaces the first one within moments for most packs). " +
            "Enabling this skips the first, unsorted build entirely and goes straight to " +
            "the sorted one - this halves the recipe-index construction cost, but the " +
            "recipe book/search will keep showing the PREVIOUS reload's recipes (or " +
            "nothing, on first world load) for slightly longer while the sorted build " +
            "finishes, instead of showing fresh-but-unsorted recipes immediately. " +
            "Off by default: this is the one change in this mod that is not purely " +
            "behavior-preserving."
        )
        .define("skipUnsortedRecipeBake", false);

    public static final ModConfigSpec SPEC = BUILDER.build();

    public static boolean skipUnsortedRecipeBake() {
        return SKIP_UNSORTED_RECIPE_BAKE.get();
    }

    private EmiReclockedConfig() {
    }
}
