package dev.reclocked.emireclocked.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.item.CreativeModeTab;
import dev.reclocked.emireclocked.timing.ReloadTimingGuard;

/**
 * Fix #3, part B. Vanilla's {@code CreativeModeTab#buildContents(...)} is what
 * {@code EmiStackList.reload()} is actually blocking the main thread on (via
 * {@link EmiStackListReloadGuardMixin}'s guarded {@code submit(...).join()}) - this only times
 * and logs a call while {@link ReloadTimingGuard#isActive()}, so a player simply opening their
 * creative inventory (which also calls this method, just never inside EMI's reload window) is
 * never logged.
 * <p>
 * This targets a plain public vanilla method by its stable, documented name and signature
 * (verified directly against the compiled EMI NeoForge jar's bytecode, not guessed from EMI's
 * Yarn-mapped source), so it does not depend on EMI's own internal lambda naming at all.
 */
@Mixin(CreativeModeTab.class)
public abstract class ItemGroupTimingMixin {

    @Unique
    private long emireclocked$startNanos;

    @Inject(method = "buildContents", at = @At("HEAD"))
    private void emireclocked$startTiming(CreativeModeTab.ItemDisplayParameters params, CallbackInfo ci) {
        if (ReloadTimingGuard.isActive()) {
            emireclocked$startNanos = System.nanoTime();
        }
    }

    @Inject(method = "buildContents", at = @At("RETURN"))
    private void emireclocked$endTiming(CreativeModeTab.ItemDisplayParameters params, CallbackInfo ci) {
        if (ReloadTimingGuard.isActive() && emireclocked$startNanos != 0) {
            long millis = (System.nanoTime() - emireclocked$startNanos) / 1_000_000L;
            CreativeModeTab self = (CreativeModeTab) (Object) this;
            ReloadTimingGuard.logGroupTiming(self.getDisplayName().getString(), millis);
            emireclocked$startNanos = 0;
        }
    }
}
