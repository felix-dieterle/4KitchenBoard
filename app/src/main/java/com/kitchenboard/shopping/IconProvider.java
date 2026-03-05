package com.kitchenboard.shopping;

import com.kitchenboard.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Provides icon drawable resources for shopping categories and a curated list of 20
 * common German supermarkets / everyday stores.
 *
 * All category icons are from Google Material Design Icons (Apache 2.0 license).
 */
public final class IconProvider {

    private IconProvider() {}

    // ── Category icons ────────────────────────────────────────────────────────

    /**
     * Returns the drawable resource ID that best matches the given category name,
     * using keyword matching. Falls back to {@link R.drawable#ic_cat_other} when no
     * keyword matches.
     */
    public static int iconForCategory(String category) {
        if (category == null) return R.drawable.ic_cat_other;
        String lower = category.toLowerCase(Locale.GERMAN);

        if (containsAny(lower, "obst", "gemüse", "gemuse", "frucht", "früchte", "fruchte",
                "salat", "kräuter", "krauter", "pilze", "mushroom", "vegetabl", "produce",
                "apfel", "banana", "tomate", "kartoffel")) {
            return R.drawable.ic_cat_produce;
        }
        if (containsAny(lower, "milch", "dairy", "käse", "kase", "joghurt", "yogurt",
                "butter", "sahne", "quark", "ei", "egg")) {
            return R.drawable.ic_cat_dairy;
        }
        if (containsAny(lower, "fleisch", "wurst", "meat", "geflügel", "gefluegel",
                "poultry", "hähnchen", "haehnchen", "rind", "schwein", "pork", "beef",
                "lamb", "lamm", "hack")) {
            return R.drawable.ic_cat_meat;
        }
        if (containsAny(lower, "fisch", "fish", "meeresfrüchte", "meeresfruchte",
                "seafood", "lachs", "thunfisch", "garnelen", "shrimp")) {
            return R.drawable.ic_cat_fish;
        }
        if (containsAny(lower, "back", "brot", "bread", "brötchen", "brotchen",
                "kuchen", "gebäck", "gebeck", "bakery", "bäckerei", "backerei",
                "croissant", "bagel", "toast")) {
            return R.drawable.ic_cat_bakery;
        }
        if (containsAny(lower, "tiefkühl", "tiefkuhl", "frozen", "gefroren", "eis",
                "eiswürfel", "eiskrem", "icecream")) {
            return R.drawable.ic_cat_frozen;
        }
        if (containsAny(lower, "getränk", "getraenk", "getrank", "bever", "saft",
                "juice", "wasser", "water", "bier", "beer", "wein", "wine", "limo",
                "cola", "soft", "drink", "coffee", "kaffee", "tee", "tea")) {
            return R.drawable.ic_cat_beverages;
        }
        if (containsAny(lower, "konserv", "dosen", "canned", "dose", "eingemacht",
                "fertiggericht", "ready meal", "jar", "glas")) {
            return R.drawable.ic_cat_canned;
        }
        if (containsAny(lower, "nudel", "pasta", "reis", "rice", "noodle",
                "spaghetti", "hülsenfrucht", "hulsenfrucht", "linsen", "bohne",
                "bean", "erbse", "pea", "grain", "getreide")) {
            return R.drawable.ic_cat_pasta;
        }
        if (containsAny(lower, "süß", "suss", "süßes", "süßwaren", "sweets",
                "snack", "chips", "schokolad", "chocolate", "keks", "cookie",
                "candy", "gummi", "eis", "dessert")) {
            return R.drawable.ic_cat_sweets;
        }
        if (containsAny(lower, "aufstrich", "marmelad", "jam", "honig", "honey",
                "spread", "nussmus", "peanut", "hazelnut", "nutella")) {
            return R.drawable.ic_cat_spreads;
        }
        if (containsAny(lower, "öl", "ol", "oil", "gewürz", "gewurz", "spice",
                "salz", "salt", "pfeffer", "pepper", "essig", "vinegar",
                "soße", "sose", "sauce", "ketchup", "senf", "mustard")) {
            return R.drawable.ic_cat_condiments;
        }
        if (containsAny(lower, "müsli", "musli", "cerealien", "cereal",
                "frühstück", "fruhstuck", "breakfast", "porridge", "granola",
                "haferflocken", "oat", "cornflakes")) {
            return R.drawable.ic_cat_cereals;
        }
        if (containsAny(lower, "haushalt", "household", "putzmittel", "reiniger",
                "cleaning", "spülmittel", "spulmittel", "waschpulver",
                "waschmittel", "laundry", "papier", "paper", "tissue",
                "toilettenpapier", "müllbeutel", "mullbeutel")) {
            return R.drawable.ic_cat_household;
        }
        if (containsAny(lower, "körperpflege", "korperpflege", "personal care",
                "pflege", "hygiene", "shampoo", "duschgel", "shower",
                "deo", "deodorant", "zahnpast", "toothpaste", "rasur",
                "kosmetik", "cosmetic", "creme", "lotion")) {
            return R.drawable.ic_cat_personal_care;
        }
        if (containsAny(lower, "tier", "pet", "hund", "dog", "katze", "cat",
                "tierfutter", "dog food", "cat food", "vogel", "bird")) {
            return R.drawable.ic_cat_pets;
        }
        if (containsAny(lower, "baby", "säugling", "saugling", "kleinkind",
                "windel", "diaper", "babynahrung", "infant")) {
            return R.drawable.ic_cat_baby;
        }

        return R.drawable.ic_cat_other;
    }

    /** Returns the full list of all available category icons as selectable entries. */
    public static List<CategoryIcon> allCategoryIcons() {
        List<CategoryIcon> list = new ArrayList<>();
        list.add(new CategoryIcon("Obst & Gemüse",     R.drawable.ic_cat_produce));
        list.add(new CategoryIcon("Milch & Eier",      R.drawable.ic_cat_dairy));
        list.add(new CategoryIcon("Fleisch & Wurst",   R.drawable.ic_cat_meat));
        list.add(new CategoryIcon("Fisch",             R.drawable.ic_cat_fish));
        list.add(new CategoryIcon("Backwaren",         R.drawable.ic_cat_bakery));
        list.add(new CategoryIcon("Tiefkühlkost",      R.drawable.ic_cat_frozen));
        list.add(new CategoryIcon("Getränke",          R.drawable.ic_cat_beverages));
        list.add(new CategoryIcon("Konserven",         R.drawable.ic_cat_canned));
        list.add(new CategoryIcon("Nudeln & Reis",     R.drawable.ic_cat_pasta));
        list.add(new CategoryIcon("Süßes & Snacks",    R.drawable.ic_cat_sweets));
        list.add(new CategoryIcon("Aufstriche",        R.drawable.ic_cat_spreads));
        list.add(new CategoryIcon("Öle & Gewürze",     R.drawable.ic_cat_condiments));
        list.add(new CategoryIcon("Müsli & Frühstück", R.drawable.ic_cat_cereals));
        list.add(new CategoryIcon("Haushalt",          R.drawable.ic_cat_household));
        list.add(new CategoryIcon("Körperpflege",      R.drawable.ic_cat_personal_care));
        list.add(new CategoryIcon("Tiernahrung",       R.drawable.ic_cat_pets));
        list.add(new CategoryIcon("Babyprodukte",      R.drawable.ic_cat_baby));
        list.add(new CategoryIcon("Sonstiges",         R.drawable.ic_cat_other));
        return list;
    }

    // ── Store icons ───────────────────────────────────────────────────────────

    /** Returns the curated list of 20 common German supermarkets and everyday stores. */
    public static List<KnownStore> knownStores() {
        List<KnownStore> stores = new ArrayList<>();
        // Full-range supermarkets
        stores.add(new KnownStore("REWE",             R.drawable.ic_store, "#CC0000"));
        stores.add(new KnownStore("EDEKA",            R.drawable.ic_store, "#FFCC00"));
        stores.add(new KnownStore("Kaufland",         R.drawable.ic_store, "#CC0000"));
        stores.add(new KnownStore("Globus",           R.drawable.ic_store, "#E30613"));
        stores.add(new KnownStore("tegut",            R.drawable.ic_store, "#DC0032"));
        stores.add(new KnownStore("Marktkauf",        R.drawable.ic_store, "#E30613"));
        // Discount supermarkets
        stores.add(new KnownStore("Aldi Nord",        R.drawable.ic_store, "#003DA5"));
        stores.add(new KnownStore("Aldi Süd",         R.drawable.ic_store, "#0066B3"));
        stores.add(new KnownStore("Lidl",             R.drawable.ic_store, "#0050AA"));
        stores.add(new KnownStore("Penny",            R.drawable.ic_store, "#CC0000"));
        stores.add(new KnownStore("Netto",            R.drawable.ic_store, "#FFCC00"));
        stores.add(new KnownStore("Norma",            R.drawable.ic_store, "#E30613"));
        stores.add(new KnownStore("NP Discount",      R.drawable.ic_store, "#E30613"));
        // Warehouse / wholesale
        stores.add(new KnownStore("Metro",            R.drawable.ic_store, "#003F8A"));
        stores.add(new KnownStore("Real",             R.drawable.ic_store, "#E30613"));
        stores.add(new KnownStore("Selgros",          R.drawable.ic_store, "#003882"));
        // Drugstores
        stores.add(new KnownStore("dm",               R.drawable.ic_store, "#CC0066"));
        stores.add(new KnownStore("Rossmann",         R.drawable.ic_store, "#E30613"));
        stores.add(new KnownStore("Müller",           R.drawable.ic_store, "#E30613"));
        // Department / variety
        stores.add(new KnownStore("Woolworth",        R.drawable.ic_store, "#003DA5"));
        return stores;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    // ── Data classes ──────────────────────────────────────────────────────────

    /** Pairs a human-readable label with a drawable resource ID for a shopping category. */
    public static final class CategoryIcon {
        public final String label;
        public final int drawableRes;

        public CategoryIcon(String label, int drawableRes) {
            this.label = label;
            this.drawableRes = drawableRes;
        }
    }

    /**
     * Represents a well-known store with a drawable icon resource and a brand colour
     * (hex string, e.g. {@code "#CC0000"}) used to tint the generic store icon.
     */
    public static final class KnownStore {
        public final String name;
        public final int iconRes;
        /** Brand color as an Android {@link android.graphics.Color} hex string. */
        public final String colorHex;

        public KnownStore(String name, int iconRes, String colorHex) {
            this.name = name;
            this.iconRes = iconRes;
            this.colorHex = colorHex;
        }
    }
}
