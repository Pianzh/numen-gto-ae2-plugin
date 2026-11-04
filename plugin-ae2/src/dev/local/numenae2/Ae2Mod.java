package dev.local.numenae2;

import com.dwinovo.numen.api.NumenPlugins;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Numen plugin for Applied Energistics 2.
 *
 * <p>Registers {@code ae2.*}: she can read what an ME network holds (per item, by name
 * search), and move goods in and out of it with the owner's consent. The read side is
 * the network's own storage inventory - no GUI, no virtual slots.
 *
 * <p>Inert unless both {@code numen} and {@code ae2} are loaded.
 */
@Mod(Ae2Mod.MODID)
public final class Ae2Mod {

    public static final String MODID = "numen_gto_ae2";
    public static final String BUILD = "ae2-1-net-view";

    private static final Logger LOG = LoggerFactory.getLogger(MODID);

    public Ae2Mod() {
        boolean hasNumen = ModList.get().isLoaded("numen");
        boolean hasAe2 = ModList.get().isLoaded("ae2");
        LOG.warn("[numen_gto_ae2] constructed build={} numen={} ae2={}", BUILD, hasNumen, hasAe2);
        if (!hasNumen || !hasAe2) {
            return;
        }
        try {
            NumenPlugins.register("ae2", numen -> Ae2Api.install(numen));
            LOG.warn("[numen_gto_ae2] registered ae2.*");
        } catch (Throwable t) {
            LOG.error("[numen_gto_ae2] registration failed", t);
        }
    }
}
