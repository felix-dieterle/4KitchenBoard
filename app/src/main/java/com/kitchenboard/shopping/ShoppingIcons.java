package com.kitchenboard.shopping;

import java.util.Locale;

/**
 * Maps shopping item names and category names to emoji icons.
 * All lookups are case-insensitive and keyword-based.
 */
public final class ShoppingIcons {

    private ShoppingIcons() {}

    /** Returns an emoji icon for a category name, or "🛒" as fallback. */
    public static String getCategoryIcon(String category) {
        if (category == null) return "🛒";
        String lower = category.toLowerCase(Locale.ROOT);
        if (contains(lower, "obst", "gemüse", "früchte", "salat", "frucht")) return "🥦";
        if (contains(lower, "fleisch", "wurst", "metzger", "schinken", "hähnchen",
                "rind", "schwein", "geflügel")) return "🥩";
        if (contains(lower, "fisch", "meeresfrüchte")) return "🐟";
        if (contains(lower, "milch", "dairy", "käse", "joghurt", "butter",
                "sahne", "quark", "molkerei")) return "🧀";
        if (contains(lower, "brot", "back", "bäcker", "brötchen")) return "🍞";
        if (contains(lower, "getränk", "drink", "saft", "wasser", "limo",
                "bier", "wein", "kaffee", "tee")) return "🥤";
        if (contains(lower, "tiefkühl", "frozen", "gefroren")) return "🧊";
        if (contains(lower, "süß", "süßig", "schokolade", "süßwaren", "konfekt",
                "snack", "chips")) return "🍫";
        if (contains(lower, "haushalt", "reinigung", "putzen", "wasch", "küche")) return "🧹";
        if (contains(lower, "hygiene", "pflege", "kosmetik", "shampoo", "seife",
                "drogerie")) return "🧴";
        if (contains(lower, "baby", "kind", "windel")) return "👶";
        if (contains(lower, "tierfutter", "tier", "hund", "katze")) return "🐾";
        if (contains(lower, "gewürz", "würzen", "kräuter", "soße", "sauce")) return "🧂";
        if (contains(lower, "nudel", "pasta", "reis", "getreide", "mehl",
                "vorrat", "konserv")) return "🥫";
        if (contains(lower, "andere", "sonstige", "other", "allgemein")) return "🛒";
        return "🛒";
    }

    /** Returns an emoji icon for an item name, or "" if no match is found. */
    public static String getItemIcon(String name) {
        if (name == null) return "";
        String lower = name.toLowerCase(Locale.ROOT);
        // Fruits
        if (contains(lower, "apfel")) return "🍎";
        if (contains(lower, "banane")) return "🍌";
        if (contains(lower, "orange", "mandarine", "clementine")) return "🍊";
        if (contains(lower, "zitrone", "limette")) return "🍋";
        if (contains(lower, "erdbeere")) return "🍓";
        if (contains(lower, "kirsche")) return "🍒";
        if (contains(lower, "weintraube", "traube")) return "🍇";
        if (contains(lower, "ananas")) return "🍍";
        if (contains(lower, "mango")) return "🥭";
        if (contains(lower, "pfirsich", "nektarine")) return "🍑";
        if (contains(lower, "birne")) return "🍐";
        if (contains(lower, "wassermelone", "melone")) return "🍉";
        if (contains(lower, "kiwi")) return "🥝";
        if (contains(lower, "kokosnuss")) return "🥥";
        if (contains(lower, "avocado")) return "🥑";
        if (contains(lower, "pflaume", "zwetschge")) return "🍑";
        if (contains(lower, "feige")) return "🍈";
        // Vegetables
        if (contains(lower, "tomate")) return "🍅";
        if (contains(lower, "karotte", "möhre")) return "🥕";
        if (contains(lower, "mais")) return "🌽";
        if (contains(lower, "paprika", "chili")) return "🌶";
        if (contains(lower, "brokkoli")) return "🥦";
        if (contains(lower, "gurke")) return "🥒";
        if (contains(lower, "salat", "kopfsalat", "eisbergsalat", "rucola")) return "🥬";
        if (contains(lower, "zwiebel")) return "🧅";
        if (contains(lower, "knoblauch")) return "🧄";
        if (contains(lower, "kartoffel", "pommes")) return "🥔";
        if (contains(lower, "pilz", "champignon")) return "🍄";
        if (contains(lower, "spinat", "feldsalat")) return "🥗";
        if (contains(lower, "erbse", "bohne")) return "🫘";
        if (contains(lower, "kürbis")) return "🎃";
        if (contains(lower, "blumenkohl", "wirsing", "kohl")) return "🥦";
        if (contains(lower, "sellerie")) return "🥬";
        if (contains(lower, "spargel")) return "🌿";
        if (contains(lower, "lauch", "porree")) return "🧅";
        if (contains(lower, "ingwer")) return "🫚";
        // Meat & Fish
        if (contains(lower, "hähnchen", "huhn", "hühnchen", "pute", "geflügel")) return "🍗";
        if (contains(lower, "steak", "rindfleisch", "hackfleisch", "rind")) return "🥩";
        if (contains(lower, "wurst", "bratwurst", "salami", "mortadella")) return "🌭";
        if (contains(lower, "schinken", "speck")) return "🥓";
        if (contains(lower, "lachs", "thunfisch", "forelle", "fisch", "kabeljau")) return "🐟";
        if (contains(lower, "shrimp", "garnele", "meeresfrüchte")) return "🍤";
        if (contains(lower, "ei", "eier")) return "🥚";
        if (contains(lower, "schweinefleisch", "schnitzel", "kotelett")) return "🥩";
        // Dairy
        if (contains(lower, "milch")) return "🥛";
        if (contains(lower, "käse", "mozzarella", "parmesan", "gouda")) return "🧀";
        if (contains(lower, "butter")) return "🧈";
        if (contains(lower, "joghurt", "quark", "skyr")) return "🥛";
        if (contains(lower, "sahne", "creme fraiche")) return "🥛";
        // Bakery
        if (contains(lower, "toast", "toastbrot")) return "🍞";
        if (contains(lower, "brot", "vollkornbrot", "weißbrot")) return "🍞";
        if (contains(lower, "brötchen", "semmel", "baguette")) return "🥐";
        if (contains(lower, "croissant")) return "🥐";
        if (contains(lower, "kuchen", "torte", "muffin")) return "🎂";
        if (contains(lower, "keks", "cookie", "cracker")) return "🍪";
        if (contains(lower, "brezel")) return "🥨";
        // Drinks
        if (contains(lower, "mineralwasser", "wasser", "sprudel")) return "💧";
        if (contains(lower, "kaffee", "espresso", "cappuccino")) return "☕";
        if (contains(lower, "tee")) return "🍵";
        if (contains(lower, "orangensaft", "apfelsaft", "saft")) return "🧃";
        if (contains(lower, "bier")) return "🍺";
        if (contains(lower, "wein", "rotwein", "weißwein", "sekt")) return "🍷";
        if (contains(lower, "cola", "limo", "fanta", "sprite", "softdrink")) return "🥤";
        if (contains(lower, "energy drink", "energydrink")) return "🥤";
        // Pantry
        if (contains(lower, "spaghetti", "nudel", "pasta", "penne", "fusilli")) return "🍝";
        if (contains(lower, "reis", "basmatireis")) return "🍚";
        if (contains(lower, "mehl")) return "🌾";
        if (contains(lower, "zucker")) return "🍬";
        if (contains(lower, "salz")) return "🧂";
        if (contains(lower, "pfeffer")) return "🧂";
        if (contains(lower, "olivenöl", "öl", "rapsöl")) return "🫒";
        if (contains(lower, "essig")) return "🫙";
        if (contains(lower, "ketchup")) return "🧴";
        if (contains(lower, "senf", "mayo", "mayonnaise")) return "🧴";
        if (contains(lower, "soße", "sauce", "tomatensoße")) return "🫙";
        if (contains(lower, "konserve", "dose", "dosentomaten")) return "🥫";
        if (contains(lower, "honig")) return "🍯";
        if (contains(lower, "marmelade", "konfitüre")) return "🍓";
        if (contains(lower, "nuss", "mandel", "erdnuss", "cashew")) return "🥜";
        if (contains(lower, "müsli", "haferflocken", "cornflakes")) return "🌾";
        if (contains(lower, "backpulver", "hefe")) return "🌾";
        if (contains(lower, "vanille", "zimt", "gewürz")) return "🧂";
        if (contains(lower, "suppenwürfel", "brühwürfel")) return "🍲";
        if (contains(lower, "tomatenmark")) return "🍅";
        // Sweets & Snacks
        if (contains(lower, "schokolade", "schoki", "tafel")) return "🍫";
        if (contains(lower, "bonbon", "lutscher", "süßigkeit")) return "🍬";
        if (contains(lower, "chips", "flips", "knabber", "popcorn")) return "🍿";
        if (contains(lower, "eiscreme", "speiseeis")) return "🍦";
        if (contains(lower, "gummibärchen", "gummi")) return "🍬";
        if (contains(lower, "riegel", "schokoriegel")) return "🍫";
        // Frozen food
        if (contains(lower, "tiefkühl", "frozen")) return "🧊";
        if (contains(lower, "pizza")) return "🍕";
        // Household
        if (contains(lower, "toilettenpapier", "klopapier", "klorolle")) return "🧻";
        if (contains(lower, "waschmittel", "waschpulver", "weichspüler")) return "🧺";
        if (contains(lower, "spülmittel", "spülpulver", "spültabs")) return "🧹";
        if (contains(lower, "müllbeutel", "mülltüte")) return "🗑";
        if (contains(lower, "alufolie", "frischhaltefolie")) return "🧷";
        if (contains(lower, "schwamm")) return "🧽";
        if (contains(lower, "glühbirne", "batterie")) return "🔦";
        if (contains(lower, "kerze")) return "🕯";
        if (contains(lower, "streichhölzer", "feuerzeug")) return "🔥";
        // Hygiene & Personal care
        if (contains(lower, "shampoo", "duschgel", "körperöl")) return "🚿";
        if (contains(lower, "zahnbürste")) return "🪥";
        if (contains(lower, "zahnpasta")) return "🦷";
        if (contains(lower, "seife")) return "🧼";
        if (contains(lower, "deo", "deodorant")) return "🧴";
        if (contains(lower, "rasierer", "rasierklinge")) return "🪒";
        if (contains(lower, "pflaster", "verband")) return "🩹";
        if (contains(lower, "sonnencreme", "sonnenschutz")) return "🧴";
        if (contains(lower, "taschentuch", "tempos")) return "🤧";
        // Baby & Kids
        if (contains(lower, "windel")) return "👶";
        if (contains(lower, "babynahrung", "gläschen")) return "🍼";
        // Pet food
        if (contains(lower, "hundefutter", "katzenfutter", "tierfutter")) return "🐾";
        return "";
    }

    private static boolean contains(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }
}
