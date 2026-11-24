package dev.floons.client;

import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/** Unsichtbares Glas ueber ein eingebautes Resource-Pack (alle Glas-Texturen komplett durchsichtig). */
public final class FloonsGlass {
    public static final Identifier PACK_ID = Identifier.of("floonsclient", "invisible_glass");

    private FloonsGlass() {}

    public static void register() {
        FabricLoader.getInstance().getModContainer("floonsclient").ifPresent(c -> {
            ResourceManagerHelper.registerBuiltinResourcePack(PACK_ID, c,
                Text.literal("Floons - Unsichtbares Glas"), ResourcePackActivationType.NORMAL);
            ResourceManagerHelper.registerBuiltinResourcePack(Identifier.of("floonsclient", "ore_cloak"), c,
                Text.literal("Floons - Anti-Leak Bloecke"), ResourcePackActivationType.NORMAL);
            ResourceManagerHelper.registerBuiltinResourcePack(Identifier.of("floonsclient", "map_numbers"), c,
                Text.literal("Floons - Papier-Zahlen als Karte"), ResourcePackActivationType.ALWAYS_ENABLED);
        });
    }

    private static String findId(ResourcePackManager pm) { return findId(pm, "invisible_glass"); }

    private static String findId(ResourcePackManager pm, String key) {
        for (ResourcePackProfile p : pm.getProfiles()) {
            if (p.getId().contains(key)) return p.getId();
        }
        return null;
    }

    public static boolean isOn() { return isOn("invisible_glass"); }

    public static boolean isOn(String key) {
        ResourcePackManager pm = MinecraftClient.getInstance().getResourcePackManager();
        String id = findId(pm, key);
        return id != null && pm.getEnabledIds().contains(id);
    }

    /** @return false, wenn das Pack nicht gefunden wurde. */
    public static boolean set(boolean on) { return set("invisible_glass", on); }

    /** Anti-Leak: Erze und Wertbloecke werden als Netherite-Block dargestellt. */
    public static boolean setCloak(boolean on) { return isOn("ore_cloak") == on || set("ore_cloak", on); }

    public static boolean set(String key, boolean on) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ResourcePackManager pm = mc.getResourcePackManager();
        String id = findId(pm, key);
        if (id == null) return false;
        List<String> enabled = new ArrayList<>(pm.getEnabledIds());
        if (on) { if (!enabled.contains(id)) enabled.add(id); } else enabled.remove(id);
        pm.setEnabledProfiles(enabled);
        mc.options.refreshResourcePacks(pm);
        return true;
    }

    public static boolean toggle() {
        boolean target = !isOn();
        return set(target);
    }
}
