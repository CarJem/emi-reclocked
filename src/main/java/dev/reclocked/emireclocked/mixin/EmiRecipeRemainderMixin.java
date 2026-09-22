package dev.reclocked.emireclocked.mixin;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.emi.emi.EmiPort;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.recipe.EmiShapedRecipe;
import dev.emi.emi.runtime.EmiLog;
import dev.reclocked.emireclocked.EmiReclocked;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeInput;

/**
 * Fix #4 (default on). {@code EmiShapedRecipe.setRemainders(...)} - shared by both
 * {@code EmiShapedRecipe} and {@code EmiShapelessRecipe}'s constructors, so this runs for every
 * vanilla-style shaped/shapeless recipe any plugin registers - resolves each input slot's
 * crafting remainder by, for every candidate item in that slot, copying every *other* slot's
 * first candidate into a scratch 3x3 {@code TransientCraftingContainer}, building a full
 * {@code CraftingInput} from it, and calling {@code recipe.getRemainingItems(input)} just to read
 * back the one index that corresponds to the slot being tested.
 * <p>
 * {@code Recipe#getRemainingItems(CraftingInput)}'s default implementation (verified directly
 * against NeoForge's vanilla sources) does not do anything with the grid at all - it is a flat
 * loop that maps each slot's own item through {@code ItemStack#hasCraftingRemainingItem()} /
 * {@code getCraftingRemainingItem()}, independent of every other slot. Vanilla only overrides it
 * for {@code BannerDuplicateRecipe} and {@code BookCloningRecipe} - both handled by EMI through
 * their own dedicated {@code EmiBannerDuplicateRecipe}/{@code EmiBookCloningRecipe} classes, never
 * through {@code EmiShapedRecipe}/{@code EmiShapelessRecipe} - so for every recipe that actually
 * reaches this method, EMI is rebuilding a 9-slot crafting grid per candidate item just to
 * re-derive a result that only ever depended on that one candidate item to begin with.
 * <p>
 * This injects at the head of {@code setRemainders} and, only when the recipe's own
 * {@code getRemainingItems} is confirmed - once per recipe *class*, via reflection, then cached -
 * to still be {@code Recipe}'s default (i.e. not overridden anywhere in its type hierarchy),
 * replaces the whole method with the equivalent flat per-item check and cancels the original.
 * Any recipe class that overrides {@code getRemainingItems} (or if the reflective check fails for
 * any reason) is left untouched and falls through to EMI's original grid-simulation path, so this
 * can never disagree with a recipe's own custom remainder logic - it only skips work that was
 * provably going to reproduce vanilla's default answer anyway.
 */
@Mixin(value = EmiShapedRecipe.class, remap = false)
public class EmiRecipeRemainderMixin {

    @Unique
    private static final Map<Class<?>, Boolean> emireclocked$usesDefaultRemainingItems = new ConcurrentHashMap<>();

    @Inject(method = "setRemainders", at = @At("HEAD"), cancellable = true)
    private static void emireclocked$fastRemainders(List<EmiIngredient> input, CraftingRecipe recipe, CallbackInfo ci) {
        if (!emireclocked$usesDefaultRemainingItems(recipe)) {
            return;
        }

        try {
            for (EmiIngredient ingredient : input) {
                if (ingredient.isEmpty()) {
                    continue;
                }

                for (EmiStack stack : ingredient.getEmiStacks()) {
                    ItemStack itemStack = stack.getItemStack();
                    if (itemStack.hasCraftingRemainingItem()) {
                        stack.setRemainder(EmiStack.of(itemStack.getCraftingRemainingItem()));
                    }
                }
            }
        } catch (Exception e) {
            EmiLog.error("Exception thrown setting remainders for " + EmiPort.getId(recipe), e);
        }

        ci.cancel();
    }

    @Unique
    private static boolean emireclocked$usesDefaultRemainingItems(CraftingRecipe recipe) {
        return emireclocked$usesDefaultRemainingItems.computeIfAbsent(recipe.getClass(), cls -> {
            try {
                // Recipe<T extends RecipeInput> erases getRemainingItems(T) to
                // getRemainingItems(RecipeInput), not getRemainingItems(CraftingInput) - the
                // bound of T, not CraftingRecipe's concrete type argument.
                Method method = cls.getMethod("getRemainingItems", RecipeInput.class);
                return method.getDeclaringClass() == Recipe.class;
            } catch (Exception e) {
                EmiReclocked.LOGGER.warn(
                    "[EMI Reclocked] Could not verify getRemainingItems for {}, falling back to EMI's original remainder logic for it",
                    cls.getName());
                return false;
            }
        });
    }
}
