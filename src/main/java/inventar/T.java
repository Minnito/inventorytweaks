package inventar;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

// Texte in der Sprache des Spiels: assets/inventar/lang/<sprache>.json (de_de, en_us; andere Sprachen fallen auf Englisch zurück).
// Immer beim Anzeigen holen, nicht in Feldern merken – so wirkt ein Sprachwechsel sofort.
final class T {
    static String t(String key, Object... args) {
        return I18n.get(Inventar.ID + "." + key, args);
    }

    /** Zahlenformat der Spielsprache (Deutsch: 0,30 – Englisch: 0.30). */
    static Locale locale() {
        String code = Minecraft.getInstance().getLanguageManager().getSelected();
        return Locale.forLanguageTag(code.replace('_', '-'));
    }
}
