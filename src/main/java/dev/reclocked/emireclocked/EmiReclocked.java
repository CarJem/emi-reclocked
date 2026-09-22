package dev.reclocked.emireclocked;

import dev.reclocked.emireclocked.config.EmiReclockedConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A behavior-preserving performance add-on for EMI / Reliable EMI (REMI). Every fix here
 * produces the same final index, recipe list and search result as stock EMI would - it just
 * removes redundant or unmeasured work along the way. See README.md for the source-level
 * justification for each fix.
 */
@Mod(EmiReclocked.MOD_ID)
public class EmiReclocked {
    public static final String MOD_ID = "emireclocked";
    public static final Logger LOGGER = LoggerFactory.getLogger("EMI Reclocked");

    public EmiReclocked(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, EmiReclockedConfig.SPEC);
        LOGGER.info("EMI Reclocked loaded - see README for what each fix does and how to verify it.");
    }
}
