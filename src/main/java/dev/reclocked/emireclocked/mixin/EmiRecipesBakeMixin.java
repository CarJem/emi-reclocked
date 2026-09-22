package dev.reclocked.emireclocked.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.registry.EmiRecipes;
import dev.reclocked.emireclocked.config.EmiReclockedConfig;

/**
 * Fix #2 (opt-in, see {@link EmiReclockedConfig#skipUnsortedRecipeBake()} - off by default,
 * this is the one change in this mod that is not purely behavior-preserving).
 * <p>
 * {@code EmiRecipes.bake()} builds its recipe index (byInput/byOutput/byCategory/
 * byWorkstation/byId, walking every recipe's inputs/outputs/catalysts) twice per reload: once
 * synchronously with {@code doSort=false} so something is visible immediately, then again on a
 * background {@code Worker} thread with {@code doSort=true}, which replaces the first one
 * within moments for most packs.
 * <p>
 * The straightforward way to skip the first build is to wrap its {@code new Manager(...)}
 * constructor call directly - but {@code Manager} is a private nested class of
 * {@code EmiRecipes}, and Mixin's {@code @WrapOperation}/{@code @Redirect} require the
 * handler's return type to exactly match the constructor's real return type, which can't be
 * named from outside the class (confirmed by actually hitting
 * {@code InvalidInjectionException: ... expected dev.emi.emi.registry.EmiRecipes$Manager}
 * at runtime - a public supertype like {@code EmiRecipeManager} is not accepted as a
 * substitute). So instead of wrapping the constructor, this modifies one of its *arguments*:
 * when enabled, the {@code recipes} list passed into the first (unsorted) build is swapped for
 * an empty list, making that build's cost O(1) instead of O(recipes x ingredients) while still
 * letting the real constructor run (satisfying Mixin's validation, since {@code @ModifyArg}
 * only needs to match the argument's own type, not the constructor's return type).
 * <p>
 * With this on, the recipe book/search is briefly empty right after a reload starts, instead of
 * showing fresh-but-unsorted recipes immediately, until the async sorted build (unaffected by
 * this mixin - it's a separate constructor call in {@code EmiRecipes$Worker}) replaces it a
 * moment later.
 */
@Mixin(value = EmiRecipes.class, remap = false)
public class EmiRecipesBakeMixin {

    @ModifyArg(
        method = "bake",
        at = @At(
            value = "INVOKE",
            target = "Ldev/emi/emi/registry/EmiRecipes$Manager;<init>(Ljava/util/List;Ljava/util/Map;Ljava/util/List;Z)V"
        ),
        index = 2
    )
    private static List<EmiRecipe> emireclocked$maybeEmptyUnsortedBake(List<EmiRecipe> recipes) {
        if (EmiReclockedConfig.skipUnsortedRecipeBake()) {
            return List.of();
        }
        return recipes;
    }
}
