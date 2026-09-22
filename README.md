# EMI Reclocked

A behavior-preserving performance add-on for [EMI](https://modrinth.com/mod/emi) and
[Reliable EMI (REMI)](https://modrinth.com/mod/reliable-emi) on NeoForge 1.21.1. Every fix here
produces the *same* final item index, recipe list and search result EMI would produce on its
own - it only removes redundant work or adds diagnostics along the way, so reload freezes get
shorter without anything looking or behaving differently.

This is a companion to [EmiAccelerator](https://modrinth.com/mod/emiaccelerator), not a
replacement for it. EmiAccelerator already disk-caches EMI's item list across sessions and
defers `EmiSearch.bake()` to a background thread - this mod deliberately does not duplicate
either of those. It targets the parts of EMI's reload pipeline EmiAccelerator doesn't touch:
the tag-reload phase and the recipe-bake phase. Run both together.

If your pack also relies on JEI plugins through EMI's JEMI compatibility layer, consider
[TMRV](https://modrinth.com/mod/tmrv) as well - it replaces JEI-plugin loading with direct
EMI-API mappers, which avoids a chunk of plugin-`initialize()`/`register()` cost during reload
that this mod does not attempt to address.

## What each fix does

All four fixes were written by reading and disassembling EMI's actual NeoForge 1.21.1 build
(`dev.emi:emi` / Modrinth version `5sIPA1To`, EMI 1.1.24), not by guessing from documentation.

### Fix #1 - tag sort (default on)

`EmiTags.reloadTags(Registry)` sorts every registry's tags by member count:
```java
tags.stream().sorted((a, b) -> Long.compare(b.stream().count(), a.stream().count()))
```
`EmiTagKey#stream()` re-derives the tag's member list from the live registry **on every single
comparison** - up to O(n log n) redundant registry traversals per reload, across every tagged
registry (items, blocks, fluids, any custom `EmiRegistryAdapter` registry), on every reload.
`EmiTagKey#getList()` already holds an equivalent, up-to-date cached copy (`EmiTagKey.reload()`
refreshes it for every tag before `EmiTags.reload()` ever calls `reloadTags`). `EmiTagsSortMixin`
redirects just that one `Stream#sorted` call to compute each tag's size once via
`getList().size()` instead. Same order, strictly less work.

### Fix #2 - skip the unsorted recipe pre-bake (**opt-in**, off by default)

`EmiRecipes.bake()` builds its full recipe index (`byInput`/`byOutput`/`byCategory`/
`byWorkstation`/`byId`, walking every recipe's inputs/outputs/catalysts) **twice** per reload:
once synchronously, unsorted, so something is visible immediately, then again on a background
thread, sorted, which replaces the first one a moment later. `EmiRecipesBakeMixin` can skip the
first build entirely and leave `EmiRecipes.manager` on whatever it already held (the previous
reload's result, or empty on first load) until the sorted build finishes.

This is the one change in this mod that is **not** purely behavior-preserving: with it on, the
recipe book/search keeps showing the previous reload's recipes (or nothing, on first world
load) for slightly longer instead of showing fresh-but-unsorted ones immediately. Off by
default. Enable it with `skipUnsortedRecipeBake = true` in the mod's config if the halved
recipe-bake cost is worth that tradeoff for your pack.

### Fix #3 - per-creative-tab reload timing (default on, diagnostic only)

`EmiStackList.reload()` does:
```java
client.submit(() -> { /* every creative tab's buildContents() */ }).join();
```
This forces every installed mod's creative-tab population onto the main render thread and
blocks the reload thread on it - this is the most likely actual source of the "long freeze" on
world load / data reload, since it stalls the frame loop for as long as the slowest tab takes.
EMI already logs the total duration of this step; it does not say *which* tab was slow.

`EmiStackListReloadGuardMixin` opens a guard flag around EMI's `submit(...)/.join()` call
(open before the task is even queued, closed only once `.join()` confirms it finished, to avoid
a race with the main thread picking the task up early). `ItemGroupTimingMixin` times
`CreativeModeTab#buildContents` - the real, public, stable vanilla method EMI's call ends up
invoking per tab - and logs any tab that takes 5ms or more, but **only** while that guard is
open, so a player opening their own creative inventory is never logged. Output looks like:
```
[EMI Reclocked] Creative tab 'Alex's Caves' took 41ms to populate during reload
```
This is exactly the data needed to point a follow-up optimization pass at a specific mod
instead of guessing - use it once you're back on your main pack.

### Fix #4 - crafting remainder shortcut (default on)

`EmiShapedRecipe`/`EmiShapelessRecipe`'s shared `setRemainders(...)` helper resolves each input
slot's crafting remainder (e.g. a water bucket leaving behind an empty bucket) by, for every
candidate item in that slot, copying every *other* slot's first candidate into a scratch 3x3
`TransientCraftingContainer`, building a full `CraftingInput` from it, and calling
`recipe.getRemainingItems(input)` just to read back the one index that corresponds to the slot
being tested - repeated once per candidate item, for every shaped/shapeless recipe any plugin
registers.

`Recipe#getRemainingItems(CraftingInput)`'s default implementation doesn't touch the grid at all:
it's a flat loop mapping each slot's own item through `ItemStack#hasCraftingRemainingItem()` /
`getCraftingRemainingItem()`, independent of every other slot. Vanilla only overrides it for
`BannerDuplicateRecipe` and `BookCloningRecipe` - both of which EMI already handles through their
own dedicated recipe classes, never through `EmiShapedRecipe`/`EmiShapelessRecipe`. So for every
recipe that actually reaches this method, EMI was rebuilding a 9-slot crafting grid per candidate
item to re-derive an answer that only ever depended on that one item.

`EmiRecipeRemainderMixin` checks, once per recipe *class* (reflection result cached), whether
`getRemainingItems` is still `Recipe`'s literal default anywhere in that class's hierarchy. Only
then does it replace the whole method with the equivalent flat per-item check. Any recipe class
that overrides `getRemainingItems` - or if the reflective check fails for any reason - is left
completely untouched and falls through to EMI's original grid-simulation path, so this can never
disagree with a recipe's own custom remainder logic; it only skips work that was provably always
going to reproduce vanilla's default answer. This was found by pointing fix #3's diagnostics at a
large real modpack and disassembling the single most expensive step that turned up.

### Fix #5 - cheap exception for missing item ids (default on)

`ItemEmiStackSerializer.create(...)` resolves a saved item id via
`EmiPort.getItemRegistry().getHolder(id).orElseThrow()`. Any EMI data file that references an
item id absent from the current instance - a stale `index/stacks` removal/filter list a mod ships
for a different pack, an item renamed or removed since the data was written, etc. - throws here.
The exception is always immediately caught by `EmiStackSerializer#deserialize`'s generic
`catch (Exception)` (logs "Error parsing NBT in deserialized stack", falls back to
`EmiStack.EMPTY` - both unchanged by this mixin), so only the construction cost matters, not the
exception's contents. `Optional#orElseThrow()`'s default `NoSuchElementException` fills in a full
stack trace on construction, and this call sits many frames deep under every other mod's mixins
into the EMI reload path, so each fill-in walks a long frame stack. One real pack test with a
data file referencing ~985 ids that no longer resolved saw exactly this pattern land squarely in
the index-baking phase.

`EmiItemStackSerializerMixin` redirects just the `orElseThrow()` call to throw an exception whose
`fillInStackTrace()` is a no-op instead, when the value is genuinely absent - the caught type, the
log message, and the `EmiStack.EMPTY` fallback are all unchanged; only the cost of the
already-discarded stack trace is removed.

### Fix #6 - REMI reload-step parallelization: investigated, not shipped

REMI's `EmiReloadManagerReloadWorkerMixin` runs `StackGroupManager.reload()`,
`StackManager.reload()`, `CreativeModeTabManager.reload()` and `WorkstationSidebarManager.
reload()` sequentially right after EMI's own bake. These looked like plausible parallelization
candidates, but reading REMI's source confirmed a real data dependency: `StackManager.reload()`
calls `StackGroupManager.buildGroupedEmiStacksAndStackGroupToContents(...)`, which requires the
group *definitions* `StackGroupManager.reload()` just loaded from JSON. Parallelizing them would
be a race, not a safe win, so this was dropped per this mod's "behavior-preserving only" rule
rather than shipped as a guess.

### Fix #7 - plugin `register()` loop parallelization: investigated, not shipped

`EmiReloadManager$ReloadWorker` calls every loaded plugin's `register(EmiRegistry)` sequentially
on one thread - on one real pack this loop alone took ~70s out of a 172s reload, split across
~30 plugins (one JEI-compat bridge alone took 24.7s; EMI's own vanilla plugin, helped some by fix
#4, still took several seconds). `EmiRegistry`'s own methods are all either pure writes into
EMI's static registries or reads (`getRecipeManager()`, `isStackDisabled(...)`) backed by state
that's fully built *before* this loop starts and never mutated during it - so calls made purely
through that interface would be safe to run concurrently and replay in original order afterward.
The real risk is everything *outside* that interface: `EmiRecipes`, `EmiStackList` and friends are
public statics, and a pack this size runs ~30 third-party plugins we have no way to audit for
reaching past `EmiRegistry` straight into those collections. Two threads calling
`ArrayList#add` on the same list concurrently is exactly the kind of silent, pack-dependent
corruption this mod's "behavior-preserving only" rule exists to avoid shipping as a guess - so
this stays uninvestigated further unless a way to detect or fence off that off-interface access
turns up.

Also investigated: the ~53s `[EMI] Reloading item groups on client thread` block (which fix #3
times the inside of, tab by tab) starts with a ~31s stretch where no tab has started building yet
at all. That isn't EMI doing hidden work - it's the render thread being busy with other startup
work (chunk/world loading, other mods' loading-screen hooks) before it even reaches the task EMI
queued for it. Nothing in EMI's reload pipeline is doing redundant work there, so there's nothing
for a behavior-preserving mixin to remove; it's a general render-thread-contention-during-world-
load problem, outside this mod's scope.

## Config

One option, in the mod's NeoForge client config: `skipUnsortedRecipeBake` (default `false`) -
see Fix #2 above.

## Building

```
./gradlew build
```
Output jar lands in `build/libs/`. Requires JDK 21.

## Testing locally

EMI isn't bundled - `./gradlew runClient` needs it present in `run/mods/` to actually do
anything:

1. `./gradlew runClient` once to generate the `run/` directory.
2. Drop an EMI NeoForge 1.21.1 jar (and REMI's, if you want to check the two coexist cleanly)
   into `run/mods/`.
3. `./gradlew runClient` again and watch the log during world load / `/reload` for:
   - No mixin apply errors on startup (would mean an EMI update changed one of the targeted
     methods - see the `@At` targets in `mixin/*.java`, each documents exactly what it expects).
   - `[EMI Reclocked] Creative tab '...' took ...ms` lines during reload (fix #3).
   - Toggle `skipUnsortedRecipeBake` in the config and confirm recipes still populate
     correctly after reload either way (fix #2).
   - No repeated `[EMI Reclocked] Could not verify getRemainingItems for ...` warnings for
     vanilla `ShapedRecipe`/`ShapelessRecipe` - if those show up, fix #4's reflective check is
     failing to recognize vanilla's own recipe classes and is falling back on every recipe (still
     correct, just not doing anything).
4. For a real read on which fixes matter for *your* pack, install this alongside your actual
   modpack, join a world, and compare the fix #3 log output and EMI's own existing
   "Reloaded EMI in ...ms" line with and without this mod installed.
