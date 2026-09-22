package dev.reclocked.emireclocked.mixin;

import java.util.NoSuchElementException;
import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import dev.emi.emi.stack.serializer.ItemEmiStackSerializer;

/**
 * Fix #5 (default on). {@code ItemEmiStackSerializer.create(...)} resolves a saved item id via
 * {@code EmiPort.getItemRegistry().getHolder(id).orElseThrow()} - every EMI data file that
 * references an item id no longer present in the current instance (a stale "index/stacks"
 * removal/filter list shipped by a mod for a different pack, a renamed/removed item since the
 * data was written, etc.) throws here. The exception is always the same shape and is always just
 * immediately caught by {@code EmiStackSerializer#deserialize}'s generic {@code catch (Exception)}
 * (which logs "Error parsing NBT in deserialized stack" and falls back to {@code EmiStack.EMPTY}
 * - unchanged by this mixin), so the only cost that matters is how expensive it is to construct,
 * not what it says. {@code Optional#orElseThrow()}'s default {@code NoSuchElementException} fills
 * in a full stack trace on construction - expensive on its own, and this call sits many frames
 * deep under every other mod's mixins into the EMI reload path, so each fill-in walks a long
 * frame stack. On a large pack with a data file referencing hundreds of ids that don't resolve,
 * this can be thrown and immediately discarded hundreds of times per reload - one real pack test
 * with ~985 such misses saw exactly this pattern coincide with a multi-second slowdown in the
 * index-baking phase.
 * <p>
 * This redirects just the {@code orElseThrow()} call to throw an exception whose
 * {@code fillInStackTrace()} is a no-op instead, when the value truly is absent - the caught type,
 * the log message, and the {@code EmiStack.EMPTY} fallback are all completely unchanged; only the
 * cost of the (already-being-thrown-away) stack trace is removed.
 */
@Mixin(value = ItemEmiStackSerializer.class, remap = false)
public class EmiItemStackSerializerMixin {

    @Redirect(
        method = "create",
        at = @At(value = "INVOKE", target = "Ljava/util/Optional;orElseThrow()Ljava/lang/Object;")
    )
    private Object emireclocked$cheapOrElseThrow(Optional<?> optional) {
        if (optional.isPresent()) {
            return optional.get();
        }

        throw new NoSuchElementException("No value present") {
            @Override
            public synchronized Throwable fillInStackTrace() {
                return this;
            }
        };
    }
}
