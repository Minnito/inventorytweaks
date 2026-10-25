package inventar;

import client.api.AddonConfig;
import client.api.Keys;
import client.api.Module;
import client.api.Notify;
import client.api.Page;
import client.api.ServerInfo;
import client.api.Screenshots;
import client.api.Setting;
import client.api.Sounds;
import client.api.Tasks;
import client.api.Testing;
import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.stream.IntStream;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

// Inventar-Helfer: Sortieren per Mittelklick (Sortierer.java), Shift + Linksklick ziehen zum schnellen Verschieben
// und Warnung bei fast kaputtem Werkzeug.
// Sortiert wird, was gerade offen ist: in Kiste, Fass, Shulkerkiste, Spender, Trichter der Behälter, sonst das eigene
// Inventar über der Hotbar. Hotbar, Rüstung und Nebenhand bleiben immer, wo sie sind. Keine Patches nötig.
public final class Inventar implements Module {
    static final String ID = "inventar";
    private static final String WARNTON = "minecraft:entity.iron_golem.repair";

    private final AddonConfig cfg = AddonConfig.of(ID);
    // Keys.key kennt nur Tastatur-Tasten; Standard ist hier die mittlere Maustaste (Mausrad drücken)
    private final KeyMapping sortKey = new KeyMapping("key." + ID + ".sort", InputConstants.Type.MOUSE, InputConstants.MOUSE_BUTTON_MIDDLE, Keys.category(ID));
    private boolean sortieren = cfg.bool("sortieren", true), ziehen = cfg.bool("ziehen", true);
    private Sortierer.Art art = art(cfg.text("art", ""));
    private boolean warnung = cfg.bool("warnung", true), ton = cfg.bool("ton", true);
    private int schwelle = cfg.number("schwelle", 10), lautstaerke = cfg.number("lautstaerke", 100);
    private boolean tonGespielt;
    private int warnTicks;

    // Gesperrte Hotbar-Slots (0–8): nicht wegwerfen, löschen oder bewegen. Statisch, weil die Patches in Hooks.java sie lesen
    static final boolean[] GESPERRT = new boolean[9];
    private static final Field ZIEL = feld(CreativeModeInventoryScreen.class.getName() + "$SlotWrapper", "target");

    {
        for (String s : cfg.list("gesperrt", List.of())) {
            int n = Integer.parseInt(s.trim()) - 1;
            if (n >= 0 && n < 9) GESPERRT[n] = true;
        }
    }

    /** Liegt der Slot auf einem gesperrten Hotbar-Platz? Kreativ-Hüllen-Slots werden dafür ausgepackt. */
    static boolean gesperrt(Slot slot) {
        if (slot == null) return false;
        if (ZIEL != null && ZIEL.getDeclaringClass().isInstance(slot)) {
            try {
                slot = (Slot) ZIEL.get(slot);
            } catch (IllegalAccessException e) {
                return false;
            }
        }
        int i = slot.getContainerSlot();
        return slot.container instanceof Inventory && i >= 0 && i < 9 && GESPERRT[i];
    }

    static boolean irgendeinerGesperrt() {
        for (boolean b : GESPERRT) if (b) return true;
        return false;
    }

    /** Liegt genau dieser Stapel auf einem gesperrten Hotbar-Platz? Hotbar und Menüs zeichnen dasselbe ItemStack-Objekt. */
    static boolean gesperrt(ItemStack stack) {
        var p = Minecraft.getInstance().player;
        if (p == null || stack.isEmpty()) return false;
        for (int i = 0; i < 9; i++) if (GESPERRT[i] && p.getInventory().getItem(i) == stack) return true;
        return false;
    }

    private static final Identifier AUSWAHL = Identifier.withDefaultNamespace("hud/hotbar_selection");
    private static final int ROT = 0xFFFF3030;

    /** Gesperrter Gegenstand in der Hand → Auswahlrahmen der Hotbar rot. */
    static boolean rotAuswaehlen(ItemStack stack) {
        var p = Minecraft.getInstance().player;
        return p != null && Minecraft.getInstance().gui.screen() == null && GESPERRT[p.getInventory().getSelectedSlot()]
            && stack == p.getInventory().getSelectedItem() && !stack.isEmpty();
    }

    // Vanillas Auswahlrahmen rot eingefärbt darüber, relativ zum Gegenstand (Vanilla: Rahmen 4 Pixel links/oberhalb,
    // 24 × 23) – so passt er auch, wenn der Client die Hotbar verschiebt. Nur im Spiel: in Menüs ist es nicht die Hotbar
    @Override
    public void itemOverlay(GuiGraphicsExtractor g, ItemStack stack, int x, int y) {
        if (rotAuswaehlen(stack)) g.blitSprite(RenderPipelines.GUI_TEXTURED, AUSWAHL, x - 4, y - 4, 24, 23, ROT);
    }

    private static Set<Integer> gesperrteNummern() {
        Set<Integer> s = new java.util.TreeSet<>();
        for (int i = 0; i < 9; i++) if (GESPERRT[i]) s.add(i + 1);
        return s;
    }

    private static Sortierer.Art art(String name) {
        for (var a : Sortierer.Art.values()) if (a.name().equals(name)) return a;
        return Sortierer.Art.TYP;
    }

    @Override
    public List<KeyMapping> keys() {
        return List.of(sortKey);
    }

    // Tasten kommen auch bei offenem Menü an (nur abfangen geht dann nicht) – genau dort wird sortiert
    @Override
    public boolean key(int key, int action, int modifiers) {
        if (action != InputConstants.PRESS) return false;
        var e = new KeyEvent(key, 0, modifiers);
        if (sortKey.matches(e)) ausloesen();
        // F (mit Nebenhand tauschen) im Spiel: gesperrter Slot bleibt, wo er ist. Wegwerfen mit Q fängt Hooks.wegwerfen ab
        var mc = Minecraft.getInstance();
        return mc.player != null && mc.gui.screen() == null && mc.options.keySwapOffhand.matches(e) && GESPERRT[mc.player.getInventory().getSelectedSlot()];
    }

    @Override
    public boolean mouse(int button, int action, int modifiers) {
        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
            // Shift + Linksklick gedrückt halten: Vanilla verschiebt den ersten Slot, jeden weiteren unter der Maus wir
            var mc = Minecraft.getInstance();
            gezogen.clear();
            zieht = false;
            if (action == InputConstants.PRESS && ziehen && mc.hasShiftDown()
                && mc.gui.screen() instanceof AbstractContainerScreen<?> s) {
                zieht = true;
                Slot erster = unterMaus(s);
                if (erster != null) gezogen.add(erster);
            }
        }
        if (action == InputConstants.PRESS && sortKey.matchesMouse(new MouseButtonEvent(0, 0, new MouseButtonInfo(button, modifiers)))) ausloesen();
        return false;
    }

    private void ausloesen() {
        var mc = Minecraft.getInstance();
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) return;
        if (screen.getFocused() instanceof EditBox) return;   // Suchfeld im Kreativ-Inventar, Amboss-Name …
        // Kreativ: Mittelklick auf einen Gegenstand kopiert ihn (Vanilla) – dort nicht zusätzlich sortieren
        Slot slot = unterMaus(screen);
        if (screen instanceof CreativeModeInventoryScreen && sortKey.matchesMouse(MITTE) && slot != null && slot.hasItem()) return;
        sortieren(screen);
    }

    // ───── Shift + Linksklick ziehen ─────

    private static final MouseButtonEvent MITTE = new MouseButtonEvent(0, 0, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_MIDDLE, 0));
    private static final Field HOVERED = hovered();
    private static final Method SLOT_CLICKED = slotClicked();
    // Slots selbst, nicht ihre Nummer: im Kreativ-Inventar haben alle Hüllen-Slots die Nummer 0
    private final Set<Slot> gezogen = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean zieht;

    // Jedes Bild: neuer Slot unter der Maus → mit Shift-Klick-Logik ins andere Inventar
    @Override
    public void world(float partialTick) {
        var mc = Minecraft.getInstance();
        if (!zieht) return;
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> s) || !mc.hasShiftDown()) {
            zieht = false;
            return;
        }
        verschieben(s, unterMaus(s));
    }

    /**
     * Slot einmal je Zug so behandeln wie einen Shift-Klick des Spielers: über slotClicked des Bildschirms, damit jedes
     * Menü seine eigene Regel nutzt – Kiste ↔ Inventar, Inventar ↔ Hotbar, Kreativ (in Gegenstands-Tabs löscht Vanilla
     * dabei Hotbar-Gegenstände; gesperrte Slots schützt Hooks.kreativKlick).
     */
    private void verschieben(AbstractContainerScreen<?> s, Slot slot) {
        var mc = Minecraft.getInstance();
        if (slot == null || gesperrt(slot) || !gezogen.add(slot)) return;
        AbstractContainerMenu menu = s.getMenu();
        if (!slot.hasItem() || !slot.mayPickup(mc.player) || !menu.getCarried().isEmpty()) return;
        try {
            SLOT_CLICKED.invoke(s, slot, slot.index, 0, ContainerInput.QUICK_MOVE);
        } catch (ReflectiveOperationException | RuntimeException e) {
            mc.gameMode.handleContainerInput(menu.containerId, slot.index, 0, ContainerInput.QUICK_MOVE, mc.player);
        }
    }

    private static Method slotClicked() {
        try {
            Method m = AbstractContainerScreen.class.getDeclaredMethod("slotClicked", Slot.class, int.class, int.class, ContainerInput.class);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("[inventar] slotClicked nicht gefunden, Ziehen nur über Menü-Klicks: " + e);
            return null;
        }
    }

    /** Slot unter der Maus – Vanilla merkt ihn sich beim Zeichnen in einem geschützten Feld. */
    static Slot unterMaus(AbstractContainerScreen<?> s) {
        try {
            return HOVERED == null ? null : (Slot) HOVERED.get(s);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    static Field feld(String klasse, String name) {
        try {
            Field f = Class.forName(klasse, false, Minecraft.class.getClassLoader()).getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("[inventar] " + klasse + "." + name + " nicht gefunden: " + e);
            return null;
        }
    }

    private static Field hovered() {
        try {
            Field f = AbstractContainerScreen.class.getDeclaredField("hoveredSlot");
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("[inventar] Schnell verschieben aus: " + e);
            return null;
        }
    }

    void sortieren(AbstractContainerScreen<?> screen) {
        var p = Minecraft.getInstance().player;
        if (!sortieren || p == null) return;
        if (!ServerInfo.allowed("inventory")) {
            Notify.show(T.t("name"), T.t("server.gesperrt"));
            return;
        }
        if (screen instanceof CreativeModeInventoryScreen) {
            if (p.isCreative()) Sortierer.kreativ(p.inventoryMenu, spielerSlots(p.inventoryMenu), art);
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        int[] slots = lager(menu) ? behaelterSlots(menu) : spielerSlots(menu);
        if (Sortierer.sortieren(menu, slots, art) == Sortierer.Ergebnis.CURSOR_VOLL) Notify.show(T.t("name"), T.t("cursor_voll"));
    }

    /** Reine Lager-Behälter; Öfen, Werkbänke usw. haben Ein- und Ausgabefelder, die nicht durchgemischt werden dürfen. */
    private static boolean lager(AbstractContainerMenu m) {
        return m instanceof ChestMenu || m instanceof ShulkerBoxMenu || m instanceof DispenserMenu || m instanceof HopperMenu;
    }

    private static int[] behaelterSlots(AbstractContainerMenu m) {
        return IntStream.range(0, m.slots.size()).filter(i -> !(m.getSlot(i).container instanceof Inventory)).toArray();
    }

    /** Die 27 Plätze über der Hotbar (Inventar-Platz 9–35), egal in welchem Menü sie liegen. */
    static int[] spielerSlots(AbstractContainerMenu m) {
        return IntStream.range(0, m.slots.size()).filter(i -> {
            var s = m.getSlot(i);
            return s.container instanceof Inventory && s.getContainerSlot() >= Inventory.getSelectionSize() && s.getContainerSlot() < Inventory.INVENTORY_SIZE;
        }).toArray();
    }

    // ───── Haltbarkeitswarnung ─────

    /** Rest-Haltbarkeit in Prozent; 100 für alles, was nicht kaputtgehen kann. */
    static float prozent(ItemStack s) {
        if (s.isEmpty() || !s.isDamageableItem()) return 100;
        return (s.getMaxDamage() - s.getDamageValue()) * 100f / s.getMaxDamage();
    }

    @Override
    public void tick() {
        var p = Minecraft.getInstance().player;
        float rest = p == null ? 100 : prozent(p.getMainHandItem());
        if (!warnung || rest >= schwelle) {
            tonGespielt = false;   // erst wieder warnen, wenn ein Werkzeug neu unter die Schwelle fällt
            warnTicks = 0;
            return;
        }
        if (warnTicks++ % 20 == 0)   // einmal je Sekunde erneuern, die Zeile über der Hotbar bleibt so stehen
            p.sendOverlayMessage(Component.literal("⚠ " + T.t("warnung", String.format(T.locale(), "%.1f", rest)) + " ⚠").withStyle(ChatFormatting.RED));
        if (ton && !tonGespielt) {
            Sounds.play(WARNTON, lautstaerke / 100f, 2);
            tonGespielt = true;
        }
    }

    // ───── Einstellungen ─────

    @Override
    public List<Setting> options() {
        // Kurze Unterseiten wie bei MiniMap und Zoom: der Client baut die Liste nach jeder Änderung neu auf und springt
        // dabei nach oben – passt jede Seite ohne Scrollen auf den Bildschirm, merkt man davon nichts
        return List.of(
            seite("sortieren", () -> List.of(
                schalter("sortieren", () -> sortieren, v -> sortieren = v),
                Setting.choice(T.t("o.art"), T.t("o.art.info"), List.of(Sortierer.Art.values()), () -> art, v -> {
                    art = v;
                    cfg.set("art", v.name());
                }, v -> T.t("art." + v.name().toLowerCase(Locale.ROOT))).visibleIf(() -> sortieren),
                Setting.key(sortKey),
                schalter("ziehen", () -> ziehen, v -> ziehen = v))),
            seite("sperre", () -> List.of(
                Setting.multi(T.t("o.gesperrt"), T.t("o.gesperrt.info"), IntStream.rangeClosed(1, 9).boxed().toList(), Inventar::gesperrteNummern, neu -> {
                    for (int i = 0; i < 9; i++) GESPERRT[i] = neu.contains(i + 1);
                    cfg.set("gesperrt", gesperrteNummern().stream().map(String::valueOf).toList());
                }, n -> T.t("slot", n)))),
            seite("warnung", () -> List.of(
                schalter("warnung", () -> warnung, v -> warnung = v),
                zahl("schwelle", 1, 50, () -> schwelle, v -> schwelle = v).visibleIf(() -> warnung),
                schalter("ton", () -> ton, v -> ton = v).visibleIf(() -> warnung),
                zahl("lautstaerke", 0, 100, () -> lautstaerke, v -> lautstaerke = v).visibleIf(() -> warnung && ton))));
    }

    private static Setting seite(String name, java.util.function.Supplier<List<Setting>> inhalt) {
        String titel = T.t("seite." + name);
        return Setting.button(titel, T.t("seite." + name + ".info"), T.t("oeffnen"), () -> Page.open(titel, inhalt));
    }

    // Name und Beschreibung aus o.<key> und o.<key>.info; gespeichert unter <key>
    private Setting schalter(String k, BooleanSupplier get, Consumer<Boolean> set) {
        return Setting.toggle(T.t("o." + k), T.t("o." + k + ".info"), get, v -> {
            set.accept(v);
            cfg.set(k, v);
        });
    }

    private Setting zahl(String k, int min, int max, IntSupplier get, IntConsumer set) {
        return Setting.slider(T.t("o." + k), T.t("o." + k + ".info"), min, max, get, v -> {
            set.accept(v);
            cfg.set(k, v);
        }, v -> v + " %");
    }

    // ───── Selbsttest (gradlew runTest) ─────

    @Override
    public void join(String server) {
        if (Testing.active()) Tasks.later(40, this::selbsttest);
    }

    private void selbsttest() {
        var leer = ItemStack.EMPTY;
        List<ItemStack> roh = List.of(new ItemStack(Items.DIRT, 5), new ItemStack(Items.STONE, 10), leer,
            new ItemStack(Items.STONE, 60), new ItemStack(Items.DIAMOND_SWORD));
        var typ = Sortierer.ordnen(roh, Sortierer.Art.TYP);
        Testing.check(typ.size() == 5 && typ.get(0).is(Items.STONE) && typ.get(0).getCount() == 64 && typ.get(1).getCount() == 6
            && typ.get(2).is(Items.DIRT) && typ.get(3).is(Items.DIAMOND_SWORD) && typ.get(4).isEmpty(), "Typ: zusammengefasst, nach Sorte, Leeres hinten");
        var selten = Sortierer.ordnen(List.of(new ItemStack(Items.STONE), new ItemStack(Items.ENCHANTED_GOLDEN_APPLE)), Sortierer.Art.SELTENHEIT);
        Testing.check(selten.get(0).is(Items.ENCHANTED_GOLDEN_APPLE), "Seltenheit: Seltenes zuerst");
        Testing.check(prozent(new ItemStack(Items.STONE)) == 100 && prozent(new ItemStack(Items.DIAMOND_PICKAXE)) == 100, "Haltbarkeit: Stein und neue Spitzhacke = 100 %");
        Testing.check(!T.t("name").startsWith(ID + ".") && !T.t("art.typ").startsWith(ID + "."), "Übersetzungen geladen (" + T.locale() + ")");

        Testing.check(sortKey.matchesMouse(MITTE) && !sortKey.matches(new KeyEvent(InputConstants.KEY_B, 0, 0)), "Sortieren liegt auf der mittleren Maustaste");
        Testing.check(options().size() == 3, "Einstellungen: drei kurze Unterseiten");

        // Jeder Schritt wartet auf den echten Zustand statt eine feste Zeit: kurz nach dem Betreten ist der Testserver
        // ausgelastet, und Befehle kommen mal nach 5, mal nach 40 Ticks beim Client an.

        // Überleben: echte Klicks im Inventar, ausgelöst per Mittelklick wie beim Spieler
        Testing.command("gamemode survival");
        Testing.command("clear @s");
        Testing.command("item replace entity @s inventory.0 with dirt 5");
        Testing.command("item replace entity @s inventory.3 with stone 10");
        Testing.command("item replace entity @s inventory.7 with stone 60");
        Testing.command("item replace entity @s inventory.12 with diamond_sword");
        Testing.command("item replace entity @s hotbar.4 with torch 3");
        var mc = Minecraft.getInstance();
        var inv = mc.player.inventoryMenu;
        Testing.await("Überleben vorbereitet", () -> belegt(inv, 9, 45) == 5 && ist(inv, 9, Items.DIRT, 5) && ist(inv, 12, Items.STONE, 10)
            && ist(inv, 16, Items.STONE, 60) && ist(inv, 21, Items.DIAMOND_SWORD, 1) && ist(inv, 40, Items.TORCH, 3), 200, () -> {
            mc.gui.setScreen(new InventoryScreen(mc.player));
            mouse(InputConstants.MOUSE_BUTTON_MIDDLE, InputConstants.PRESS, 0);
            bestaetigt("Überleben: Inventar per Mittelklick sortiert", () -> ist(inv, 9, Items.STONE, 64) && ist(inv, 10, Items.STONE, 6)
                && ist(inv, 11, Items.DIRT, 5) && ist(inv, 12, Items.DIAMOND_SWORD, 1) && belegt(inv, 13, 36) == 0
                && ist(inv, 40, Items.TORCH, 3) && inv.getCarried().isEmpty(), () -> {
                // Ziehen im eigenen Inventar: Schwert in die Hotbar (erster freier Platz 36) und wieder zurück auf Platz 12
                var s = (AbstractContainerScreen<?>) mc.gui.screen();
                gezogen.clear();
                verschieben(s, inv.getSlot(12));
                bestaetigt("Ziehen: Inventar → Hotbar", () -> inv.getSlot(12).getItem().isEmpty() && ist(inv, 36, Items.DIAMOND_SWORD, 1), () -> {
                    verschieben(s, inv.getSlot(36));
                    bestaetigt("Ziehen: Hotbar → Inventar", () -> ist(inv, 12, Items.DIAMOND_SWORD, 1) && inv.getSlot(36).getItem().isEmpty(), () -> {
                        mc.player.closeContainer();   // meldet dem Server das Schließen, sonst kommen keine Inventar-Updates mehr an
                        kreativTest(mc);
                    });
                });
            });
        });
    }

    // Kiste neben dem Spieler: Mittelklick sortiert nur die Kiste, Ziehen verschiebt aus dem Inventar hinein
    private void kisteTest(Minecraft mc) {
        BlockPos pos = mc.player.blockPosition().east(2);
        String xyz = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        Testing.command("setblock " + xyz + " air");   // Testwelt bleibt zwischen Läufen: alte Kiste samt Inhalt weg
        Testing.command("setblock " + xyz + " chest");
        Testing.command("item replace block " + xyz + " container.0 with dirt 3");
        Testing.command("item replace block " + xyz + " container.5 with stone 20");
        Testing.command("item replace block " + xyz + " container.8 with stone 50");
        Testing.command("gamemode survival");
        Testing.command("clear @s");
        Testing.command("item replace entity @s inventory.0 with stone 64");
        Testing.command("item replace entity @s inventory.1 with stone 6");
        Testing.command("item replace entity @s inventory.3 with diamond_sword");
        var inv = mc.player.inventoryMenu;
        Testing.await("Kiste steht", () -> mc.level.getBlockState(pos).is(Blocks.CHEST) && !mc.player.isCreative() && belegt(inv, 9, 45) == 3
            && ist(inv, 9, Items.STONE, 64) && ist(inv, 10, Items.STONE, 6) && ist(inv, 12, Items.DIAMOND_SWORD, 1), 200, () -> {
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
            Testing.await("Kiste offen und gefüllt", () -> mc.gui.screen() instanceof AbstractContainerScreen<?> s && s.getMenu() instanceof ChestMenu m
                && belegt(m, 0, 27) == 3 && ist(m, 0, Items.DIRT, 3) && ist(m, 5, Items.STONE, 20) && ist(m, 8, Items.STONE, 50), 200, () -> {
                var s = (AbstractContainerScreen<?>) mc.gui.screen();
                var m = s.getMenu();
                mouse(InputConstants.MOUSE_BUTTON_MIDDLE, InputConstants.PRESS, 0);
                // Inventar-Teil der Kiste: Platz 9–12 des Spielers = Menü 27–30 (Stein 64, Stein 6, leer, Schwert)
                bestaetigt("Kiste: nur die Kiste sortiert, Inventar bleibt", () -> ist(m, 0, Items.STONE, 64) && ist(m, 1, Items.STONE, 6)
                    && ist(m, 2, Items.DIRT, 3) && belegt(m, 3, 27) == 0 && ist(m, 27, Items.STONE, 64) && ist(m, 28, Items.STONE, 6), () -> {
                    // Ziehen ohne echte Maus und Shift: die Slots, über die die Maus fährt, direkt übergeben
                    Testing.check(HOVERED != null, "Slot unter der Maus lesbar (hoveredSlot)");
                    gezogen.clear();
                    verschieben(s, m.getSlot(30));   // Schwert
                    verschieben(s, m.getSlot(30));   // gleicher Slot zweimal: nur einmal verschieben
                    verschieben(s, m.getSlot(28));   // 6 Stein → auf den halben Stapel in der Kiste
                    bestaetigt("Ziehen: Gegenstände wandern in die Kiste", () -> m.getSlot(30).getItem().isEmpty() && m.getSlot(28).getItem().isEmpty()
                        && ist(m, 1, Items.STONE, 12) && IntStream.range(0, 27).anyMatch(i -> m.getSlot(i).getItem().is(Items.DIAMOND_SWORD)), () -> {
                        mc.player.closeContainer();
                        Testing.done();   // Kiste zuletzt: danach kommen beim Test-Client keine Inventar-Updates mehr zuverlässig an
                    });
                });
            });
        });
    }

    private void kreativTest(Minecraft mc) {
        Testing.command("gamemode creative");
        Testing.command("clear @s");
        Testing.command("item replace entity @s inventory.2 with stone 30");
        Testing.command("item replace entity @s inventory.5 with apple 3");
        Testing.command("item replace entity @s inventory.9 with stone 40");
        var inv = mc.player.inventoryMenu;
        // Kreativ schickt die Client-Belegung zum Server – darum erst sortieren, wenn der Client den Testaufbau wirklich hat
        Testing.await("Kreativ vorbereitet", () -> mc.player.isCreative() && belegt(inv, 9, 45) == 3 && ist(inv, 11, Items.STONE, 30)
            && ist(inv, 14, Items.APPLE, 3) && ist(inv, 18, Items.STONE, 40), 200, () -> {
            mc.gui.setScreen(new CreativeModeInventoryScreen(mc.player, mc.player.connection.enabledFeatures(), false));
            sortieren((AbstractContainerScreen<?>) mc.gui.screen());
            mc.player.closeContainer();
            bestaetigt("Kreativ: Inventar sortiert und vom Server übernommen", () -> ist(inv, 9, Items.STONE, 64) && ist(inv, 10, Items.STONE, 6)
                && ist(inv, 11, Items.APPLE, 3) && belegt(inv, 12, 36) == 0, () -> kreativZiehen(mc));
        });
    }

    // Kreativ-Inventar, Tab „Inventar“: Apfel (Platz 11) per Ziehen in die Hotbar
    private void kreativZiehen(Minecraft mc) {
        var inv = mc.player.inventoryMenu;
        var s = new CreativeModeInventoryScreen(mc.player, mc.player.connection.enabledFeatures(), false);
        mc.gui.setScreen(s);
        try {
            Method tab = CreativeModeInventoryScreen.class.getDeclaredMethod("selectTab", CreativeModeTab.class);
            tab.setAccessible(true);
            tab.invoke(s, CreativeModeTabs.tabs().stream().filter(t -> t.getType() == CreativeModeTab.Type.INVENTORY).findFirst().orElseThrow());
        } catch (ReflectiveOperationException e) {
            Testing.fail("Kreativ-Tab Inventar wählen", e);
        }
        Testing.check(s.isInventoryOpen(), "Kreativ: Tab „Inventar“ offen");
        Slot apfel = s.getMenu().slots.stream().filter(sl -> sl.container instanceof Inventory && sl.getContainerSlot() == 11).findFirst().orElse(null);
        gezogen.clear();
        verschieben(s, apfel);
        bestaetigt("Kreativ: Ziehen Inventar → Hotbar", () -> inv.getSlot(11).getItem().isEmpty()
            && IntStream.range(36, 45).anyMatch(i -> inv.getSlot(i).getItem().is(Items.APPLE)), () -> {
            mc.player.closeContainer();
            warnTest(mc);
        });
    }

    // Hotbar-Sperre: Slot 1 gesperrt (nur im Speicher, nicht in der Config). Jede Art, die Fackel loszuwerden, muss scheitern
    private void sperreTest(Minecraft mc) {
        Testing.command("gamemode survival");
        Testing.command("clear @s");
        Testing.command("item replace entity @s hotbar.0 with torch 5");
        Testing.command("item replace entity @s inventory.0 with dirt 2");
        var inv = mc.player.inventoryMenu;
        boolean vorher = GESPERRT[0];
        Testing.await("Sperre vorbereitet", () -> !mc.player.isCreative() && ist(inv, 36, Items.TORCH, 5) && ist(inv, 9, Items.DIRT, 2), 200, () -> {
            GESPERRT[0] = true;
            Testing.check(gesperrt(inv.getSlot(36)) && !gesperrt(inv.getSlot(37)) && !gesperrt(inv.getSlot(9)), "Sperre erkennt Hotbar-Slot 1");
            Testing.check(gesperrt(inv.getSlot(36).getItem()) && !gesperrt(inv.getSlot(9).getItem()), "Gesperrter Gegenstand erkannt");
            var s = new InventoryScreen(mc.player);
            mc.gui.setScreen(s);
            gezogen.clear();
            verschieben(s, inv.getSlot(36));                                                              // Ziehen
            mc.gameMode.handleContainerInput(inv.containerId, 36, 0, ContainerInput.QUICK_MOVE, mc.player);  // Shift-Klick
            mc.gameMode.handleContainerInput(inv.containerId, 36, 1, ContainerInput.THROW, mc.player);       // Strg+Q im Menü
            mc.gameMode.handleContainerInput(inv.containerId, 9, 0, ContainerInput.SWAP, mc.player);         // Taste 1 über der Erde
            mc.gameMode.handleContainerInput(inv.containerId, 36, 0, ContainerInput.PICKUP, mc.player);      // aufnehmen
            mc.player.closeContainer();
            mc.player.getInventory().setSelectedSlot(0);
            mc.gameMode.dropItem(mc.player, true);                                                          // Q im Spiel
            bestaetigt("Sperre: Fackel bleibt (Ziehen, Shift-Klick, Q, Ziffertaste, Aufnehmen, Q im Spiel)",
                () -> ist(inv, 36, Items.TORCH, 5) && ist(inv, 9, Items.DIRT, 2) && inv.getCarried().isEmpty(), () -> {
                Testing.check(rotAuswaehlen(inv.getSlot(36).getItem()) && !rotAuswaehlen(inv.getSlot(9).getItem()), "Roter Auswahlrahmen für gesperrten Gegenstand in der Hand");
                // Bildschirmfoto der Hotbar mit rotem Rahmen zum Ansehen: client-modules/inventar/rahmen-test.png
                Screenshots.capture(bild -> {
                    try (bild) {
                        Files.write(AddonConfig.dir(ID).resolve("rahmen-test.png"), Screenshots.png(bild));
                    } catch (IOException e) {
                        System.err.println("[inventar] Testbild: " + e);
                    }
                });
                Testing.command("gamemode creative");
                Testing.await("Kreativ für Sperre", () -> mc.player.isCreative(), 200, () -> {
                    var k = new CreativeModeInventoryScreen(mc.player, mc.player.connection.enabledFeatures(), false);
                    mc.gui.setScreen(k);
                    Slot fackel = k.getMenu().slots.stream().filter(Inventar::gesperrt).findFirst().orElse(null);
                    Testing.check(fackel != null, "Kreativ: gesperrter Hotbar-Slot im Kreativ-Inventar erkannt");
                    try {
                        if (fackel != null) SLOT_CLICKED.invoke(k, fackel, fackel.index, 1, ContainerInput.THROW);   // Q im Kreativ
                    } catch (ReflectiveOperationException e) {
                        Testing.fail("Kreativ-Klick", e);
                    }
                    mc.gui.setScreen(null);
                    bestaetigt("Sperre: Fackel bleibt im Kreativ-Inventar", () -> ist(inv, 36, Items.TORCH, 5), () -> {
                        GESPERRT[0] = vorher;
                        kisteTest(mc);
                    });
                });
            });
        });
    }

    // Erst warten, bis der Zustand stimmt, dann eine Sekunde später nochmal prüfen: so zählt nur, was der Server bestätigt hat
    // (lehnt er Klicks ab, setzt er das Inventar zurück)
    private static void bestaetigt(String name, BooleanSupplier zustand, Runnable weiter) {
        Testing.await(name, zustand, 200, () -> Tasks.later(20, () -> {
            Testing.check(zustand.getAsBoolean(), name + " – vom Server bestätigt");
            weiter.run();
        }));
    }

    private static boolean ist(AbstractContainerMenu m, int slot, Item item, int anzahl) {
        ItemStack s = m.getSlot(slot).getItem();
        return s.is(item) && s.getCount() == anzahl;
    }

    private static long belegt(AbstractContainerMenu m, int von, int bis) {
        return IntStream.range(von, bis).filter(i -> !m.getSlot(i).getItem().isEmpty()).count();
    }

    private void warnTest(Minecraft mc) {
        Testing.command("gamemode survival");
        Testing.command("item replace entity @s hotbar.0 with diamond_pickaxe[damage=1550]");
        mc.player.getInventory().setSelectedSlot(0);
        Testing.await("Haltbarkeitswarnung", () -> warnTicks > 0 && tonGespielt, 200, () -> {
            float rest = prozent(mc.player.getMainHandItem());
            Testing.check(rest > 0.5f && rest < 1, "Rest-Haltbarkeit berechnet (" + rest + " %)");
            Testing.command("clear @s");
            Testing.await("Warnung endet ohne Werkzeug", () -> warnTicks == 0 && !tonGespielt, 100, () -> sperreTest(mc));
        });
    }
}
