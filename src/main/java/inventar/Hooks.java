package inventar;

import java.lang.reflect.Field;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

// Hotbar-Sperre (module.json „patches“, greifen nach einem Neustart). Alle Inventar-Aktionen des Spielers laufen durch diese
// drei Stellen; abgelehnt wird vor dem lokalen Klick, so bleiben Client und Server gleich. Fehler schalten nur die Sperre ab.
public final class Hooks {
    private static final Field MUELLEIMER = Inventar.feld(CreativeModeInventoryScreen.class.getName(), "destroyItemSlot");
    private static boolean kaputt;

    /** MultiPlayerGameMode.handleContainerInput (ersetzen): Klicks, Shift-Klick, Ziffertasten, Q im Menü. */
    public static boolean klick(MultiPlayerGameMode gm, int containerId, int slotId, int button, ContainerInput input, Player p) {
        try {
            if (kaputt || input == ContainerInput.CLONE) return false;
            if (ziffertaste(input, button)) return true;
            AbstractContainerMenu m = p.containerMenu;
            return m.containerId == containerId && slotId >= 0 && slotId < m.slots.size() && Inventar.gesperrt(m.getSlot(slotId));
        } catch (RuntimeException e) {
            return aus(e);
        }
    }

    /** MultiPlayerGameMode.dropItem (ersetzen): Q im Spiel. */
    public static boolean wegwerfen(MultiPlayerGameMode gm, LocalPlayer p, boolean ganzerStapel) {
        return !kaputt && Inventar.GESPERRT[p.getInventory().getSelectedSlot()];
    }

    /** CreativeModeInventoryScreen.slotClicked (ersetzen): das Kreativ-Inventar klickt an handleContainerInput vorbei. */
    public static boolean kreativKlick(CreativeModeInventoryScreen s, Slot slot, int slotId, int button, ContainerInput input) {
        try {
            if (kaputt || input == ContainerInput.CLONE) return false;
            if (ziffertaste(input, button) || Inventar.gesperrt(slot)) return true;
            // Shift-Klick auf den Mülleimer leert das ganze Inventar – mit gesperrten Slots nicht
            return slot != null && input == ContainerInput.QUICK_MOVE && MUELLEIMER != null && slot == MUELLEIMER.get(s) && Inventar.irgendeinerGesperrt();
        } catch (RuntimeException | IllegalAccessException e) {
            return aus(e);
        }
    }

    // Ziffertaste 1–9 über einem Slot tauscht mit diesem Hotbar-Platz
    private static boolean ziffertaste(ContainerInput input, int button) {
        return input == ContainerInput.SWAP && button >= 0 && button < 9 && Inventar.GESPERRT[button];
    }

    private static boolean aus(Exception e) {
        kaputt = true;
        System.err.println("[inventar] Hotbar-Sperre abgeschaltet: " + e);
        return false;
    }
}
