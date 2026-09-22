package dev.reclocked.emireclocked.mixin;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import dev.emi.emi.registry.EmiTags;
import dev.emi.emi.runtime.EmiTagKey;

/**
 * Fix #1: {@code EmiTags.reloadTags(Registry)} sorts every registry's tags by member count via
 * {@code sorted((a, b) -> Long.compare(b.stream().count(), a.stream().count()))}. That
 * comparator calls {@code EmiTagKey#stream()} - which re-derives the tag's member list from
 * the live registry on every single pairwise comparison, i.e. up to O(n log n) redundant
 * registry traversals per reload - even though {@link EmiTagKey#getList()} already holds an
 * up-to-date cached copy of the exact same list ({@code EmiTagKey.reload()} refreshes it for
 * every tag before {@code EmiTags.reload()} ever calls {@code reloadTags}, so it is always
 * fresh by the time this runs).
 * <p>
 * This redirects only that one {@code Stream#sorted(Comparator)} call (the second of two in
 * the method - the first sorts by id string and is already cheap) to compute each tag's size
 * once via {@code getList().size()} instead. Same comparator semantics (descending by member
 * count), same resulting order, strictly less work.
 */
@Mixin(value = EmiTags.class, remap = false)
public class EmiTagsSortMixin {

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Redirect(
        method = "reloadTags",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/stream/Stream;sorted(Ljava/util/Comparator;)Ljava/util/stream/Stream;",
            ordinal = 1
        )
    )
    private static Stream emireclocked$fastCountSort(Stream tags, Comparator original) {
        List<EmiTagKey<?>> list = (List<EmiTagKey<?>>) (List) tags.collect(Collectors.toList());
        list.sort((a, b) -> Long.compare(b.getList().size(), a.getList().size()));
        return list.stream();
    }
}
