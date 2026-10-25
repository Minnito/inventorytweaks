package inventar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

// Sortiert eine Reihe von Slots eines offenen Menüs.
// Überleben: echte Inventar-Klicks wie von Hand (der Server prüft jeden), erst Cursor leeren, dann halbe Stapel auffüllen,
// dann per Tausch in die Zielreihenfolge. Kreativ: Zielbelegung direkt setzen, wie es das Kreativ-Inventar selbst tut.
public final class Sortierer {
    public enum Art { TYP, NAME, SELTENHEIT }

    /** Ergebnis für die Meldung an den Spieler. */
    public enum Ergebnis { SORTIERT, CURSOR_VOLL }

    /** Zielbelegung: gleiche Gegenstände zu vollen Stapeln zusammengefasst, sortiert, leere Plätze am Ende. Gleich lang wie stacks. */
    public static List<ItemStack> ordnen(List<ItemStack> stacks, Art art) {
        List<ItemStack> haufen = new ArrayList<>();
        for (ItemStack s : stacks) {
            if (s.isEmpty()) continue;
            int rest = s.getCount();
            for (ItemStack h : haufen)
                if (rest > 0 && ItemStack.isSameItemSameComponents(h, s) && h.getCount() < h.getMaxStackSize()) {
                    int dazu = Math.min(rest, h.getMaxStackSize() - h.getCount());
                    h.grow(dazu);
                    rest -= dazu;
                }
            while (rest > 0) {
                int n = Math.min(rest, s.getMaxStackSize());
                haufen.add(s.copyWithCount(n));
                rest -= n;
            }
        }
        haufen.sort(reihenfolge(art));   // stabil: gleich Eingeordnetes bleibt in seiner Reihenfolge
        while (haufen.size() < stacks.size()) haufen.add(ItemStack.EMPTY);
        return haufen;
    }

    static Comparator<ItemStack> reihenfolge(Art art) {
        Comparator<ItemStack> id = Comparator.comparingInt(s -> Item.getId(s.getItem()));
        Comparator<ItemStack> name = Comparator.comparing(s -> s.getHoverName().getString());
        Comparator<ItemStack> menge = Comparator.comparingInt(ItemStack::getCount).reversed();
        return switch (art) {
            case TYP -> id.thenComparing(menge);
            case NAME -> name.thenComparing(id).thenComparing(menge);
            case SELTENHEIT -> Comparator.<ItemStack>comparingInt(s -> s.getRarity().ordinal()).reversed().thenComparing(name).thenComparing(menge);
        };
    }

    /** Sortiert die Slots (Menü-Indizes) im offenen Menü per Klicks. */
    // ponytail: alle Klicks in einem Tick; streng prüfende Anti-Cheats bräuchten Klicks über mehrere Ticks verteilt
    static Ergebnis sortieren(AbstractContainerMenu menu, int[] slots, Art art) {
        var k = new Klicker(menu);
        if (!menu.getCarried().isEmpty()) {
            for (int i : slots) if (menu.getSlot(i).getItem().isEmpty()) { k.klick(i); break; }
            if (!menu.getCarried().isEmpty()) return Ergebnis.CURSOR_VOLL;
        }
        // Halbe Stapel auffüllen: Stapel b aufnehmen, auf a legen; was nicht passt, zurück nach b
        for (int a = 0; a < slots.length; a++)
            for (int b = a + 1; b < slots.length; b++) {
                ItemStack sa = menu.getSlot(slots[a]).getItem(), sb = menu.getSlot(slots[b]).getItem();
                if (sa.isEmpty() || sa.getCount() >= sa.getMaxStackSize()) break;
                if (sb.isEmpty() || !ItemStack.isSameItemSameComponents(sa, sb)) continue;
                k.klick(slots[b]);
                k.klick(slots[a]);
                if (!menu.getCarried().isEmpty()) k.klick(slots[b]);
            }
        // In Zielreihenfolge tauschen. Nach dem Auffüllen gibt es je Sorte höchstens einen halben Stapel, darum ergibt
        // Aufnehmen–Ablegen–Zurücklegen auch bei gleicher Sorte einen sauberen Tausch.
        List<ItemStack> ziel = ordnen(belegung(menu, slots), art);
        for (int p = 0; p < slots.length; p++) {
            if (ItemStack.matches(menu.getSlot(slots[p]).getItem(), ziel.get(p))) continue;
            for (int q = p + 1; q < slots.length; q++)
                if (ItemStack.matches(menu.getSlot(slots[q]).getItem(), ziel.get(p))) {
                    k.tausch(slots[p], slots[q]);
                    break;
                }
        }
        return Ergebnis.SORTIERT;
    }

    /** Kreativ: Zielbelegung direkt in die Slots des Spielerinventars schreiben. */
    static void kreativ(AbstractContainerMenu inventar, int[] slots, Art art) {
        var mc = Minecraft.getInstance();
        List<ItemStack> ziel = ordnen(belegung(inventar, slots), art);
        for (int p = 0; p < slots.length; p++) {
            ItemStack s = ziel.get(p).copy();
            inventar.getSlot(slots[p]).set(s);
            mc.gameMode.handleCreativeModeItemAdd(s, slots[p]);
        }
    }

    static List<ItemStack> belegung(AbstractContainerMenu menu, int[] slots) {
        List<ItemStack> l = new ArrayList<>(slots.length);
        for (int i : slots) l.add(menu.getSlot(i).getItem().copy());
        return l;
    }

    private record Klicker(AbstractContainerMenu menu) {
        void klick(int slot) {
            var mc = Minecraft.getInstance();
            mc.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.PICKUP, mc.player);
        }

        void tausch(int a, int b) {
            ItemStack sa = menu.getSlot(a).getItem(), sb = menu.getSlot(b).getItem();
            if (sa.isEmpty()) {
                klick(b);
                klick(a);
                return;
            }
            // Gleiche Sorte: vom volleren Stapel aus beginnen, sonst prallt der halbe am vollen ab
            if (ItemStack.isSameItemSameComponents(sa, sb) && sa.getCount() < sb.getCount()) {
                int t = a;
                a = b;
                b = t;
            }
            klick(a);
            klick(b);
            if (!menu.getCarried().isEmpty()) klick(a);
        }
    }
}
