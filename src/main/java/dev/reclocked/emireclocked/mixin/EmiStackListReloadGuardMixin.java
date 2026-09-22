package dev.reclocked.emireclocked.mixin;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.emi.emi.registry.EmiStackList;
import dev.reclocked.emireclocked.timing.ReloadTimingGuard;
import net.minecraft.client.Minecraft;

/**
 * Fix #3, part A. {@code EmiStackList.reload()} does
 * {@code client.submit(() -> {...every creative tab's updateEntries()...}).join()}, which is
 * EMI's own already-logged "Reloading item groups on client thread" step and the most likely
 * source of a world-load/data-reload freeze, since it blocks the main render thread for as
 * long as the slowest tab takes.
 * <p>
 * {@code submit(...)} itself only enqueues the task and returns immediately - the tab-building
 * work actually happens later, on the main thread, whenever it gets to run. So the timing
 * guard is opened here (before the task is even queued, avoiding a race where the main thread
 * could start the task before we're watching) and closed only once {@code .join()} - the call
 * that actually blocks until that work is done - returns.
 * {@link dev.reclocked.emireclocked.mixin.ItemGroupTimingMixin} reads this guard to decide
 * whether to log a given tab's build time.
 */
@Mixin(value = EmiStackList.class, remap = false)
public class EmiStackListReloadGuardMixin {

    @WrapOperation(
        method = "reload",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;submit(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;"
        )
    )
    private static CompletableFuture<?> emireclocked$openGuardBeforeSubmit(
            Minecraft client, Supplier<?> task, Operation<CompletableFuture<?>> original) {
        ReloadTimingGuard.begin();
        return original.call(client, task);
    }

    @WrapOperation(
        method = "reload",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/concurrent/CompletableFuture;join()Ljava/lang/Object;"
        )
    )
    private static Object emireclocked$closeGuardAfterJoin(
            CompletableFuture<?> future, Operation<Object> original) {
        try {
            return original.call(future);
        } finally {
            ReloadTimingGuard.end();
        }
    }
}
