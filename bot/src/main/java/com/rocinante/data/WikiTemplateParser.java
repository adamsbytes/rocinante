package com.rocinante.data;

import com.rocinante.data.model.DropTable;
import com.rocinante.data.model.ItemSource;
import com.rocinante.data.model.ShopInventory;
import com.rocinante.data.model.WeaponInfo;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

import javax.inject.Singleton;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for OSRS Wiki templates and infoboxes.
 *
 * <p>Per REQUIREMENTS.md Section 8A.2.3, implements parsing with:
 * <ul>
 *   <li>Multiple fallback patterns for each data type</li>
 *   <li>Data range validation</li>
 *   <li>Malformed template handling</li>
 *   <li>Parse failure logging with wiki URLs</li>
 * </ul>
 *
 * <p>Drop Table Template Fallback Order:
 * <ol>
 *   <li>{{DropsTableHead}} + {{DropsLine}} — Most common modern format</li>
 *   <li>{{DropTableNew}} — Newer unified format</li>
 *   <li>Simple wikitable with Item/Quantity/Rarity headers — Legacy</li>
 *   <li>{{Drops}} — Older deprecated format</li>
 * </ol>
 */
@Slf4j
@Singleton
public class WikiTemplateParser {

    // ========================================================================
    // Validation Constants
    // ========================================================================

    /**
     * Maximum valid item ID (RuneLite range).
     */
    private static final int MAX_ITEM_ID = 30000;

    /**
     * Valid rarity strings.
     */
    private static final Set<String> VALID_RARITIES = Set.of(
            "always", "common", "uncommon", "rare", "very rare",
            "1/1", "guaranteed", "certain"
    );

    // ========================================================================
    // Regex Patterns
    // ========================================================================

    // Template extraction patterns
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile(
            "\\{\\{([^{}|]+)([^{}]*(?:\\{\\{[^{}]*}}[^{}]*)*)}}", Pattern.DOTALL);

    // Parameter pattern that handles wiki links [[...]] which may contain | characters
    // Value part: match either wiki links [[...]] or non-pipe characters, non-greedy
    private static final Pattern PARAM_PATTERN = Pattern.compile(
            "\\|\\s*([^=|]+?)\\s*=\\s*((?:\\[\\[[^\\]]*\\]\\]|[^|])*?)(?=\\||$)", Pattern.DOTALL);

    private static final Pattern UNNAMED_PARAM_PATTERN = Pattern.compile(
            "\\|\\s*([^=|]+?)\\s*(?=\\||$)", Pattern.DOTALL);

    // DropsLine pattern
    private static final Pattern DROPS_LINE_PATTERN = Pattern.compile(
            "\\{\\{DropsLine([^}]*)}}", Pattern.CASE_INSENSITIVE);

    // Infobox patterns
    private static final Pattern INFOBOX_MONSTER_PATTERN = Pattern.compile(
            "\\{\\{Infobox Monster([^}]*(?:\\{\\{[^}]*}}[^}]*)*)}}", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern INFOBOX_ITEM_PATTERN = Pattern.compile(
            "\\{\\{Infobox Item([^}]*(?:\\{\\{[^}]*}}[^}]*)*)}}", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern INFOBOX_BONUSES_PATTERN = Pattern.compile(
            "\\{\\{Infobox Bonuses([^}]*(?:\\{\\{[^}]*}}[^}]*)*)}}", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // Store template pattern
    private static final Pattern STORE_PATTERN = Pattern.compile(
            "\\{\\{Store([^}]*(?:\\{\\{[^}]*}}[^}]*)*)}}", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // ItemSources template pattern
    private static final Pattern ITEM_SOURCES_PATTERN = Pattern.compile(
            "\\{\\{ItemSources([^}]*(?:\\{\\{[^}]*}}[^}]*)*)}}", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // Wikitable pattern
    private static final Pattern WIKITABLE_PATTERN = Pattern.compile(
            "\\{\\|[^}]*class\\s*=\\s*\"[^\"]*wikitable[^\"]*\"[^}]*\\|}",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // Drop rate pattern (e.g., "1/128", "1/100", "Always")
    private static final Pattern DROP_RATE_PATTERN = Pattern.compile(
            "(\\d+)/(\\d+)|always|common|uncommon|rare|very\\s*rare",
            Pattern.CASE_INSENSITIVE);

    // Quantity pattern (e.g., "1", "1-3", "10 (noted)")
    private static final Pattern QUANTITY_PATTERN = Pattern.compile(
            "(\\d+)(?:\\s*[-–]\\s*(\\d+))?(?:\\s*\\(noted\\))?");

    // ========================================================================
    // Drop Table Parsing
    // ========================================================================

    /**
     * Parse a drop table from wiki page content.
     * Uses fallback chain to try multiple template formats.
     *
     * @param wikitext    the raw wikitext content
     * @param monsterName the monster name for logging
     * @param wikiUrl     the wiki page URL for logging
     * @return parsed drop table, or empty drop table if parsing fails
     */
    public DropTable parseDropTable(String wikitext, String monsterName, String wikiUrl) {
        if (wikitext == null || wikitext.isEmpty()) {
            log.warn("Empty wikitext for drop table: {}", wikiUrl);
            return DropTable.EMPTY;
        }

        List<DropTable.Drop> drops = new ArrayList<>();

        // Try fallback chain
        // 1. DropsTableHead + DropsLine (most common)
        drops = tryParseDropsLine(wikitext);
        if (!drops.isEmpty()) {
            log.debug("Parsed {} drops using DropsLine format for {}", drops.size(), monsterName);
        }

        // 2. DropTableNew format
        if (drops.isEmpty()) {
            drops = tryParseDropTableNew(wikitext);
            if (!drops.isEmpty()) {
                log.debug("Parsed {} drops using DropTableNew format for {}", drops.size(), monsterName);
            }
        }

        // 3. Simple wikitable
        if (drops.isEmpty()) {
            drops = tryParseWikitable(wikitext);
            if (!drops.isEmpty()) {
                log.debug("Parsed {} drops using wikitable format for {}", drops.size(), monsterName);
            }
        }

        // 4. Legacy Drops template
        if (drops.isEmpty()) {
            drops = tryParseLegacyDrops(wikitext);
            if (!drops.isEmpty()) {
                log.debug("Parsed {} drops using legacy Drops format for {}", drops.size(), monsterName);
            }
        }

        if (drops.isEmpty()) {
            log.debug("No drops found for {} - wiki may not have drop table: {}", monsterName, wikiUrl);
        }

        // Extract combat level and slayer level from infobox
        int combatLevel = extractCombatLevel(wikitext);
        int slayerLevel = extractSlayerLevel(wikitext);

        return DropTable.builder()
                .monsterName(monsterName)
                .combatLevel(combatLevel)
                .slayerLevel(slayerLevel)
                .drops(drops)
                .wikiUrl(wikiUrl)
                .fetchedAt(Instant.now().toString())
                .build();
    }

    /**
     * Try parsing DropsLine templates.
     */
    private List<DropTable.Drop> tryParseDropsLine(String wikitext) {
        List<DropTable.Drop> drops = new ArrayList<>();

        Matcher matcher = DROPS_LINE_PATTERN.matcher(wikitext);
        while (matcher.find()) {
            try {
                String params = matcher.group(1);
                DropTable.Drop drop = parseDropLineParams(params);
                if (drop != null && isValidDrop(drop)) {
                    drops.add(drop);
                }
            } catch (Exception e) {
                log.trace("Failed to parse DropsLine: {}", e.getMessage());
            }
        }

        return drops;
    }

    /**
     * Parse parameters from a DropsLine template.
     */
    private DropTable.Drop parseDropLineParams(String params) {
        Map<String, String> paramMap = parseTemplateParams(params);

        String itemName = paramMap.getOrDefault("name", paramMap.get("Name"));
        if (itemName == null || itemName.isEmpty()) {
            return null;
        }

        // Clean wiki markup from item name
        itemName = cleanWikiMarkup(itemName);

        int itemId = parseIntSafe(paramMap.getOrDefault("id", "-1"), -1);
        String quantity = paramMap.getOrDefault("quantity", paramMap.getOrDefault("Quantity", "1"));
        String rarity = paramMap.getOrDefault("rarity", paramMap.getOrDefault("Rarity", "Unknown"));
        String dropRate = paramMap.getOrDefault("raritynotes", "");
        boolean membersOnly = "yes".equalsIgnoreCase(paramMap.get("members"));
        boolean noted = quantity.toLowerCase().contains("noted");

        // Parse quantity range
        int[] qtyRange = parseQuantityRange(quantity);

        // Parse drop rate
        double dropRateDecimal = parseDropRate(rarity, dropRate);

        return DropTable.Drop.builder()
                .itemId(itemId)
                .itemName(itemName)
                .quantity(quantity)
                .quantityMin(qtyRange[0])
                .quantityMax(qtyRange[1])
                .rarity(cleanRarity(rarity))
                .dropRate(dropRate)
                .dropRateDecimal(dropRateDecimal)
                .membersOnly(membersOnly)
                .noted(noted)
                .notes("")
                .build();
    }

    /**
     * Try parsing DropTableNew format.
     */
    private List<DropTable.Drop> tryParseDropTableNew(String wikitext) {
        List<DropTable.Drop> drops = new ArrayList<>();

        // Look for {{DropTableNew|...}} or {{Drops table|...}}
        Pattern pattern = Pattern.compile(
                "\\{\\{(?:DropTableNew|Drops table)\\s*\\|([^}]*)}}", Pattern.CASE_INSENSITIVE);

        Matcher matcher = pattern.matcher(wikitext);
        while (matcher.find()) {
            String content = matcher.group(1);
            // This format often has items as sub-templates or rows
            // Parse each row/item entry
            Matcher rowMatcher = Pattern.compile("\\{\\{[^}]*name\\s*=\\s*([^|]+)[^}]*}}").matcher(content);
            while (rowMatcher.find()) {
                try {
                    DropTable.Drop drop = parseDropLineParams(rowMatcher.group(0));
                    if (drop != null && isValidDrop(drop)) {
                        drops.add(drop);
                    }
                } catch (Exception e) {
                    log.trace("Failed to parse DropTableNew entry: {}", e.getMessage());
                }
            }
        }

        return drops;
    }

    /**
     * Try parsing simple wikitable format.
     */
    private List<DropTable.Drop> tryParseWikitable(String wikitext) {
        List<DropTable.Drop> drops = new ArrayList<>();

        // Find tables that look like drop tables
        Pattern tablePattern = Pattern.compile(
                "\\{\\|[^}]*?\\|-[^}]*?Item[^}]*?Quantity[^}]*?Rarity[^}]*?\\|}",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

        Matcher tableMatcher = tablePattern.matcher(wikitext);
        while (tableMatcher.find()) {
            String table = tableMatcher.group();
            // Parse rows (lines starting with |-)
            String[] rows = table.split("\\|-");
            for (String row : rows) {
                if (row.contains("Item") && row.contains("Quantity")) {
                    continue; // Header row
                }
                try {
                    DropTable.Drop drop = parseWikitableRow(row);
                    if (drop != null && isValidDrop(drop)) {
                        drops.add(drop);
                    }
                } catch (Exception e) {
                    log.trace("Failed to parse wikitable row: {}", e.getMessage());
                }
            }
        }

        return drops;
    }

    /**
     * Parse a single row from a wikitable.
     */
    private DropTable.Drop parseWikitableRow(String row) {
        // Extract cells (separated by || or newline |)
        String[] cells = row.split("\\|\\||\\n\\|");
        if (cells.length < 3) {
            return null;
        }

        // Assume order: Item, Quantity, Rarity
        String itemName = cleanWikiMarkup(cells[0].trim());
        String quantity = cells.length > 1 ? cells[1].trim() : "1";
        String rarity = cells.length > 2 ? cells[2].trim() : "Unknown";

        if (itemName.isEmpty() || itemName.startsWith("!") || itemName.startsWith("{|")) {
            return null;
        }

        int[] qtyRange = parseQuantityRange(quantity);
        double dropRateDecimal = parseDropRate(rarity, "");

        return DropTable.Drop.builder()
                .itemId(-1)
                .itemName(itemName)
                .quantity(quantity)
                .quantityMin(qtyRange[0])
                .quantityMax(qtyRange[1])
                .rarity(cleanRarity(rarity))
                .dropRate("")
                .dropRateDecimal(dropRateDecimal)
                .membersOnly(false)
                .noted(quantity.toLowerCase().contains("noted"))
                .notes("")
                .build();
    }

    /**
     * Try parsing legacy {{Drops}} template.
     */
    private List<DropTable.Drop> tryParseLegacyDrops(String wikitext) {
        List<DropTable.Drop> drops = new ArrayList<>();

        Pattern pattern = Pattern.compile(
                "\\{\\{Drops\\s*\\|([^}]*)}}", Pattern.CASE_INSENSITIVE);

        Matcher matcher = pattern.matcher(wikitext);
        while (matcher.find()) {
            String content = matcher.group(1);
            // Legacy format often uses unnamed params: |item|quantity|rarity
            String[] parts = content.split("\\|");
            if (parts.length >= 3) {
                try {
                    String itemName = cleanWikiMarkup(parts[0].trim());
                    String quantity = parts[1].trim();
                    String rarity = parts[2].trim();

                    int[] qtyRange = parseQuantityRange(quantity);
                    double dropRateDecimal = parseDropRate(rarity, "");

                    DropTable.Drop drop = DropTable.Drop.builder()
                            .itemId(-1)
                            .itemName(itemName)
                            .quantity(quantity)
                            .quantityMin(qtyRange[0])
                            .quantityMax(qtyRange[1])
                            .rarity(cleanRarity(rarity))
                            .dropRate("")
                            .dropRateDecimal(dropRateDecimal)
                            .membersOnly(false)
                            .noted(quantity.toLowerCase().contains("noted"))
                            .notes("")
                            .build();

                    if (isValidDrop(drop)) {
                        drops.add(drop);
                    }
                } catch (Exception e) {
                    log.trace("Failed to parse legacy Drops: {}", e.getMessage());
                }
            }
        }

        return drops;
    }

    // ========================================================================
    // Item Source Parsing
    // ========================================================================

    /**
     * Parse item sources from wiki page content.
     *
     * @param wikitext the raw wikitext content
     * @param itemName the item name
     * @param wikiUrl  the wiki page URL
     * @return parsed item sources
     */
    public ItemSource parseItemSources(String wikitext, String itemName, String wikiUrl) {
        if (wikitext == null || wikitext.isEmpty()) {
            log.warn("Empty wikitext for item sources: {}", wikiUrl);
            return ItemSource.EMPTY;
        }

        // Extract basic item info from Infobox Item
        Map<String, String> infobox = extractInfoboxParams(wikitext, INFOBOX_ITEM_PATTERN);

        int itemId = parseIntSafe(infobox.getOrDefault("id", "-1"), -1);
        String examine = cleanWikiMarkup(infobox.getOrDefault("examine", ""));
        boolean tradeable = "yes".equalsIgnoreCase(infobox.get("tradeable"));
        boolean membersOnly = "yes".equalsIgnoreCase(infobox.get("members"));
        String questReq = cleanWikiMarkup(infobox.getOrDefault("quest", ""));

        // Parse sources
        List<ItemSource.Source> sources = new ArrayList<>();

        // Try ItemSources template
        sources.addAll(parseItemSourcesTemplate(wikitext));

        // Also parse from infobox "source" field
        String sourceField = infobox.get("source");
        if (sourceField != null && !sourceField.isEmpty()) {
            sources.addAll(parseSourceField(sourceField));
        }

        // Look for drop sources in the page
        sources.addAll(parseDropSourcesFromPage(wikitext, itemName));

        // Look for shop sources
        sources.addAll(parseShopSourcesFromPage(wikitext));

        return ItemSource.builder()
                .itemId(itemId)
                .itemName(itemName)
                .examine(examine)
                .tradeable(tradeable)
                .membersOnly(membersOnly)
                .questRequirement(questReq)
                .sources(sources)
                .wikiUrl(wikiUrl)
                .fetchedAt(Instant.now().toString())
                .build();
    }

    /**
     * Parse ItemSources template.
     */
    private List<ItemSource.Source> parseItemSourcesTemplate(String wikitext) {
        List<ItemSource.Source> sources = new ArrayList<>();

        Matcher matcher = ITEM_SOURCES_PATTERN.matcher(wikitext);
        if (matcher.find()) {
            String content = matcher.group(1);
            // Parse each source entry
            Pattern entryPattern = Pattern.compile(
                    "\\{\\{[^}]*source\\s*=\\s*([^|]+)[^}]*}}", Pattern.CASE_INSENSITIVE);
            Matcher entryMatcher = entryPattern.matcher(content);
            while (entryMatcher.find()) {
                try {
                    Map<String, String> params = parseTemplateParams(entryMatcher.group(0));
                    ItemSource.Source source = parseSourceParams(params);
                    if (source != null) {
                        sources.add(source);
                    }
                } catch (Exception e) {
                    log.trace("Failed to parse ItemSources entry: {}", e.getMessage());
                }
            }
        }

        return sources;
    }

    /**
     * Parse source field from infobox.
     */
    private List<ItemSource.Source> parseSourceField(String sourceField) {
        List<ItemSource.Source> sources = new ArrayList<>();

        // Source field may contain multiple sources separated by <br> or newlines
        String[] sourceParts = sourceField.split("<br\\s*/?>|\\n");
        for (String part : sourceParts) {
            part = cleanWikiMarkup(part).trim();
            if (part.isEmpty()) {
                continue;
            }

            ItemSource.SourceType type = ItemSource.SourceType.fromWikiText(part);
            sources.add(ItemSource.Source.builder()
                    .type(type)
                    .name(part)
                    .location("")
                    .quantity("")
                    .cost(-1)
                    .dropRate("")
                    .requirements("")
                    .membersOnly(false)
                    .notes("")
                    .build());
        }

        return sources;
    }

    /**
     * Parse source from params map.
     */
    private ItemSource.Source parseSourceParams(Map<String, String> params) {
        String sourceName = params.getOrDefault("source", params.get("name"));
        if (sourceName == null || sourceName.isEmpty()) {
            return null;
        }

        sourceName = cleanWikiMarkup(sourceName);
        ItemSource.SourceType type = ItemSource.SourceType.fromWikiText(
                params.getOrDefault("type", sourceName));

        return ItemSource.Source.builder()
                .type(type)
                .name(sourceName)
                .location(cleanWikiMarkup(params.getOrDefault("location", "")))
                .quantity(params.getOrDefault("quantity", ""))
                .cost(parseIntSafe(params.getOrDefault("cost", "-1"), -1))
                .dropRate(params.getOrDefault("droprate", ""))
                .requirements(cleanWikiMarkup(params.getOrDefault("requirements", "")))
                .membersOnly("yes".equalsIgnoreCase(params.get("members")))
                .notes(cleanWikiMarkup(params.getOrDefault("notes", "")))
                .build();
    }

    /**
     * Parse drop sources from page content (looks for "Dropped by" section).
     */
    private List<ItemSource.Source> parseDropSourcesFromPage(String wikitext, String itemName) {
        List<ItemSource.Source> sources = new ArrayList<>();

        // Look for "Dropped by" or "Drop sources" section
        Pattern sectionPattern = Pattern.compile(
                "==\\s*(?:Dropped by|Drop sources)\\s*==([^=]*?)(?===|$)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

        Matcher matcher = sectionPattern.matcher(wikitext);
        if (matcher.find()) {
            String section = matcher.group(1);
            // Parse monster names from this section
            Pattern monsterPattern = Pattern.compile("\\[\\[([^\\]|]+)(?:\\|[^\\]]*)?\\]\\]");
            Matcher monsterMatcher = monsterPattern.matcher(section);
            while (monsterMatcher.find()) {
                String monster = monsterMatcher.group(1).trim();
                if (!monster.isEmpty() && !monster.equalsIgnoreCase(itemName)) {
                    sources.add(ItemSource.Source.builder()
                            .type(ItemSource.SourceType.MONSTER_DROP)
                            .name(monster)
                            .location("")
                            .quantity("")
                            .cost(-1)
                            .dropRate("")
                            .requirements("")
                            .membersOnly(false)
                            .notes("")
                            .build());
                }
            }
        }

        return sources;
    }

    /**
     * Parse shop sources from page content.
     */
    private List<ItemSource.Source> parseShopSourcesFromPage(String wikitext) {
        List<ItemSource.Source> sources = new ArrayList<>();

        // Look for "Shop locations" or "Sold at" section
        Pattern sectionPattern = Pattern.compile(
                "==\\s*(?:Shop locations|Sold at|Store locations)\\s*==([^=]*?)(?===|$)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

        Matcher matcher = sectionPattern.matcher(wikitext);
        if (matcher.find()) {
            String section = matcher.group(1);
            // Parse shop names from this section
            Pattern shopPattern = Pattern.compile("\\[\\[([^\\]|]+)(?:\\|[^\\]]*)?\\]\\]");
            Matcher shopMatcher = shopPattern.matcher(section);
            while (shopMatcher.find()) {
                String shop = shopMatcher.group(1).trim();
                if (!shop.isEmpty()) {
                    sources.add(ItemSource.Source.builder()
                            .type(ItemSource.SourceType.SHOP)
                            .name(shop)
                            .location("")
                            .quantity("")
                            .cost(-1)
                            .dropRate("")
                            .requirements("")
                            .membersOnly(false)
                            .notes("")
                            .build());
                }
            }
        }

        return sources;
    }

    // ========================================================================
    // Shop Inventory Parsing
    // ========================================================================

    /**
     * Parse shop inventory from wiki page content.
     *
     * @param wikitext the raw wikitext content
     * @param shopName the shop name
     * @param wikiUrl  the wiki page URL
     * @return parsed shop inventory
     */
    public ShopInventory parseShopInventory(String wikitext, String shopName, String wikiUrl) {
        if (wikitext == null || wikitext.isEmpty()) {
            log.warn("Empty wikitext for shop inventory: {}", wikiUrl);
            return ShopInventory.EMPTY;
        }

        // Try to find Store template
        Map<String, String> storeParams = extractInfoboxParams(wikitext, STORE_PATTERN);

        String shopkeeper = cleanWikiMarkup(storeParams.getOrDefault("owner", ""));
        int npcId = parseIntSafe(storeParams.getOrDefault("ownerid", "-1"), -1);
        String location = cleanWikiMarkup(storeParams.getOrDefault("location", ""));
        boolean membersOnly = "yes".equalsIgnoreCase(storeParams.get("members"));
        boolean generalStore = "yes".equalsIgnoreCase(storeParams.get("general"));
        String currency = storeParams.getOrDefault("currency", "Coins");

        // Parse shop items
        List<ShopInventory.ShopItem> items = parseStoreItems(wikitext);

        // Try to parse location coordinates
        WorldPoint worldPoint = parseLocation(storeParams);

        return ShopInventory.builder()
                .shopName(shopName)
                .shopkeeperName(shopkeeper)
                .shopkeeperNpcId(npcId)
                .location(location)
                .worldPoint(worldPoint)
                .membersOnly(membersOnly)
                .generalStore(generalStore)
                .currency(currency)
                .items(items)
                .wikiUrl(wikiUrl)
                .fetchedAt(Instant.now().toString())
                .build();
    }

    /**
     * Parse store items from wikitext.
     */
    private List<ShopInventory.ShopItem> parseStoreItems(String wikitext) {
        List<ShopInventory.ShopItem> items = new ArrayList<>();

        // Look for StoreLine templates
        Pattern storeLinePattern = Pattern.compile(
                "\\{\\{StoreLine\\s*\\|([^}]*)}}", Pattern.CASE_INSENSITIVE);

        Matcher matcher = storeLinePattern.matcher(wikitext);
        while (matcher.find()) {
            try {
                Map<String, String> params = parseTemplateParams(matcher.group(1));
                ShopInventory.ShopItem item = parseStoreLineParams(params);
                if (item != null) {
                    items.add(item);
                }
            } catch (Exception e) {
                log.trace("Failed to parse StoreLine: {}", e.getMessage());
            }
        }

        return items;
    }

    /**
     * Parse StoreLine template parameters.
     */
    private ShopInventory.ShopItem parseStoreLineParams(Map<String, String> params) {
        String itemName = cleanWikiMarkup(params.getOrDefault("name", ""));
        if (itemName.isEmpty()) {
            return null;
        }

        int itemId = parseIntSafe(params.getOrDefault("id", "-1"), -1);
        int stock = parseIntSafe(params.getOrDefault("stock", "0"), 0);
        int price = parseIntSafe(params.getOrDefault("sell", params.getOrDefault("price", "0")), 0);
        boolean playerSold = "yes".equalsIgnoreCase(params.get("sellprice"));
        boolean membersOnly = "yes".equalsIgnoreCase(params.get("members"));

        return ShopInventory.ShopItem.builder()
                .itemId(itemId)
                .itemName(itemName)
                .baseStock(stock)
                .basePrice(price)
                .playerSold(playerSold)
                .membersOnly(membersOnly)
                .restockRate(-1)
                .maxStock(-1)
                .notes("")
                .build();
    }

    // ========================================================================
    // Weapon Info Parsing
    // ========================================================================

    /**
     * Parse weapon information from wiki page content.
     *
     * @param wikitext the raw wikitext content
     * @param itemName the weapon name
     * @param itemId   the item ID
     * @param wikiUrl  the wiki page URL
     * @return parsed weapon info
     */
    public WeaponInfo parseWeaponInfo(String wikitext, String itemName, int itemId, String wikiUrl) {
        if (wikitext == null || wikitext.isEmpty()) {
            log.warn("Empty wikitext for weapon info: {}", wikiUrl);
            return WeaponInfo.EMPTY;
        }

        // Extract from Infobox Item
        Map<String, String> itemBox = extractInfoboxParams(wikitext, INFOBOX_ITEM_PATTERN);

        // Extract from Infobox Bonuses
        Map<String, String> bonusBox = extractInfoboxParams(wikitext, INFOBOX_BONUSES_PATTERN);

        // Parse attack speed
        int attackSpeed = parseAttackSpeed(itemBox, bonusBox, wikitext);

        // Parse combat style
        String combatStyle = itemBox.getOrDefault("combat_style", 
                bonusBox.getOrDefault("astyle", ""));
        combatStyle = cleanWikiMarkup(combatStyle);

        // Parse weapon category
        String weaponCategory = cleanWikiMarkup(itemBox.getOrDefault("slot", ""));

        // Parse special attack
        boolean hasSpec = wikitext.toLowerCase().contains("special attack");
        int specCost = parseSpecialAttackCost(wikitext);
        String specDesc = parseSpecialAttackDescription(wikitext);

        // Two-handed check
        boolean twoHanded = "2h".equalsIgnoreCase(weaponCategory) ||
                wikitext.toLowerCase().contains("two-handed");

        // Parse bonuses
        int stabBonus = parseIntSafe(bonusBox.getOrDefault("astab", "0"), 0);
        int slashBonus = parseIntSafe(bonusBox.getOrDefault("aslash", "0"), 0);
        int crushBonus = parseIntSafe(bonusBox.getOrDefault("acrush", "0"), 0);
        int magicBonus = parseIntSafe(bonusBox.getOrDefault("amagic", "0"), 0);
        int rangedBonus = parseIntSafe(bonusBox.getOrDefault("arange", "0"), 0);
        int strengthBonus = parseIntSafe(bonusBox.getOrDefault("str", "0"), 0);
        int rangedStrength = parseIntSafe(bonusBox.getOrDefault("rstr", "0"), 0);
        int magicDamage = parseIntSafe(bonusBox.getOrDefault("mdmg", "0"), 0);
        int prayerBonus = parseIntSafe(bonusBox.getOrDefault("prayer", "0"), 0);

        return WeaponInfo.builder()
                .itemId(itemId)
                .itemName(itemName)
                .attackSpeed(attackSpeed)
                .combatStyle(combatStyle)
                .weaponCategory(weaponCategory)
                .hasSpecialAttack(hasSpec || specCost > 0)
                .specialAttackCost(specCost)
                .specialAttackDescription(specDesc)
                .twoHanded(twoHanded)
                .stabBonus(stabBonus)
                .slashBonus(slashBonus)
                .crushBonus(crushBonus)
                .magicBonus(magicBonus)
                .rangedBonus(rangedBonus)
                .strengthBonus(strengthBonus)
                .rangedStrengthBonus(rangedStrength)
                .magicDamageBonus(magicDamage)
                .prayerBonus(prayerBonus)
                .wikiUrl(wikiUrl)
                .fetchedAt(Instant.now().toString())
                .build();
    }

    /**
     * Parse attack speed from infoboxes or directly from wikitext.
     */
    private int parseAttackSpeed(Map<String, String> itemBox, Map<String, String> bonusBox, String wikitext) {
        // Try aspeed field from infobox
        String aspeed = itemBox.getOrDefault("aspeed", bonusBox.getOrDefault("aspeed", ""));
        if (!aspeed.isEmpty()) {
            int speed = parseIntSafe(aspeed, -1);
            if (speed > 0 && speed <= 10) {
                return speed;
            }
        }

        // Try speed field from infoboxes
        String speed = itemBox.getOrDefault("speed", bonusBox.getOrDefault("speed", ""));
        if (!speed.isEmpty()) {
            int s = parseIntSafe(speed, -1);
            if (s > 0 && s <= 10) {
                return s;
            }
        }

        // Fallback: search for |speed = X directly in wikitext
        // This handles complex multi-version infoboxes where our regex might fail
        if (wikitext != null) {
            Pattern speedPattern = Pattern.compile("\\|\\s*speed\\s*=\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
            Matcher matcher = speedPattern.matcher(wikitext);
            if (matcher.find()) {
                int s = parseIntSafe(matcher.group(1), -1);
                if (s > 0 && s <= 10) {
                    return s;
                }
            }
        }

        return WeaponInfo.SPEED_UNKNOWN;
    }

    /**
     * Parse special attack cost from wikitext.
     *
     * <p>Wiki pages express spec costs in various ways:
     * <ul>
     *   <li>"uses 50% of the special attack bar"</li>
     *   <li>"costing 55% of the player's special attack energy"</li>
     *   <li>"consuming 25% of the wielder's special attack energy"</li>
     *   <li>"consumes 50% of special attack energy"</li>
     *   <li>"drains 25% special attack"</li>
     * </ul>
     *
     * <p>Key insight: the percentage must be FOLLOWED by "special attack" context,
     * not just any percentage in the text (e.g., "25% higher accuracy" is NOT spec cost).
     */
    private int parseSpecialAttackCost(String wikitext) {
        // Pattern 1: "uses/using X% of the/player's/wielder's special attack bar/energy"
        Pattern pattern1 = Pattern.compile(
                "(?:uses?|using)\\s+(\\d+)%\\s+(?:of\\s+)?(?:the\\s+)?(?:player'?s?|wielder'?s?)?\\s*special\\s*attack",
                Pattern.CASE_INSENSITIVE);

        // Pattern 2: "costing X% of the/player's special attack energy"
        Pattern pattern2 = Pattern.compile(
                "costing\\s+(\\d+)%\\s+(?:of\\s+)?(?:the\\s+)?(?:player'?s?|wielder'?s?)?\\s*special\\s*attack",
                Pattern.CASE_INSENSITIVE);

        // Pattern 3: "consuming X% of the/player's/wielder's special attack energy"
        Pattern pattern3 = Pattern.compile(
                "consuming\\s+(\\d+)%\\s+(?:of\\s+)?(?:the\\s+)?(?:player'?s?|wielder'?s?)?\\s*special\\s*attack",
                Pattern.CASE_INSENSITIVE);

        // Pattern 4: "consumes/consume X% of/special attack energy"
        Pattern pattern4 = Pattern.compile(
                "consumes?\\s+(\\d+)%\\s+(?:of\\s+)?(?:the\\s+)?(?:player'?s?|wielder'?s?)?\\s*special\\s*attack",
                Pattern.CASE_INSENSITIVE);

        // Pattern 5: "drains X% special attack" or "drains X% of special attack"
        Pattern pattern5 = Pattern.compile(
                "drains?\\s+(\\d+)%\\s+(?:of\\s+)?(?:the\\s+)?(?:player'?s?|wielder'?s?)?\\s*special\\s*attack",
                Pattern.CASE_INSENSITIVE);

        // Pattern 6: "requires X% special attack energy"
        Pattern pattern6 = Pattern.compile(
                "requires?\\s+(\\d+)%\\s+(?:of\\s+)?(?:the\\s+)?(?:player'?s?|wielder'?s?)?\\s*special\\s*attack",
                Pattern.CASE_INSENSITIVE);

        // Pattern 7: infobox template |scost = 50 or |specialcost = 50
        Pattern pattern7 = Pattern.compile(
                "\\|\\s*(?:scost|specialcost|spec_cost)\\s*=\\s*(\\d+)",
                Pattern.CASE_INSENSITIVE);

        // Pattern 8: "X% of the/player's/wielder's special attack energy" (percentage followed by special attack)
        Pattern pattern8 = Pattern.compile(
                "(\\d+)%\\s+(?:of\\s+)?(?:the\\s+)?(?:player'?s?|wielder'?s?)?\\s*special\\s*attack\\s*(?:bar|energy)?",
                Pattern.CASE_INSENSITIVE);

        // Try each pattern in order of specificity
        for (Pattern pattern : new Pattern[]{pattern1, pattern2, pattern3, pattern4, pattern5, pattern6, pattern7, pattern8}) {
            Matcher matcher = pattern.matcher(wikitext);
            if (matcher.find()) {
                int cost = parseIntSafe(matcher.group(1), -1);
                if (cost > 0 && cost <= 100) {
                    log.trace("Parsed spec cost {} using pattern: {}", cost, pattern.pattern());
                    return cost;
                }
            }
        }

        return -1;
    }

    /**
     * Parse special attack description from wikitext.
     */
    private String parseSpecialAttackDescription(String wikitext) {
        // Look for special attack section
        Pattern pattern = Pattern.compile(
                "===?\\s*Special attack\\s*===?\\s*([^=]+?)(?===|$)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

        Matcher matcher = pattern.matcher(wikitext);
        if (matcher.find()) {
            String desc = matcher.group(1).trim();
            // Clean up and truncate
            desc = cleanWikiMarkup(desc);
            if (desc.length() > 200) {
                desc = desc.substring(0, 200) + "...";
            }
            return desc;
        }

        return "";
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Extract parameters from an infobox template.
     */
    private Map<String, String> extractInfoboxParams(String wikitext, Pattern pattern) {
        Map<String, String> params = new HashMap<>();

        Matcher matcher = pattern.matcher(wikitext);
        if (matcher.find()) {
            String content = matcher.group(1);
            params = parseTemplateParams(content);
        }

        return params;
    }

    /**
     * Parse template parameters into a map.
     */
    private Map<String, String> parseTemplateParams(String content) {
        Map<String, String> params = new HashMap<>();

        // Prepend pipe if content doesn't start with one, so first param gets matched
        // e.g., "name=Air rune|id=556" -> "|name=Air rune|id=556"
        if (content != null && !content.isEmpty() && !content.trim().startsWith("|")) {
            content = "|" + content;
        }

        // Named parameters: |key = value
        Matcher namedMatcher = PARAM_PATTERN.matcher(content);
        while (namedMatcher.find()) {
            String key = namedMatcher.group(1).trim().toLowerCase();
            String value = namedMatcher.group(2).trim();
            params.put(key, value);
        }

        return params;
    }

    /**
     * Extract combat level from wikitext.
     */
    private int extractCombatLevel(String wikitext) {
        Map<String, String> params = extractInfoboxParams(wikitext, INFOBOX_MONSTER_PATTERN);
        return parseIntSafe(params.getOrDefault("combat", "0"), 0);
    }

    /**
     * Extract slayer level from wikitext.
     */
    private int extractSlayerLevel(String wikitext) {
        Map<String, String> params = extractInfoboxParams(wikitext, INFOBOX_MONSTER_PATTERN);
        return parseIntSafe(params.getOrDefault("slaylvl", "0"), 0);
    }

    /**
     * Parse location coordinates from params.
     */
    private WorldPoint parseLocation(Map<String, String> params) {
        String locParam = params.get("location");
        if (locParam == null) {
            return null;
        }

        // Try to parse coordinates from location string
        Pattern coordPattern = Pattern.compile("(\\d+),\\s*(\\d+)(?:,\\s*(\\d+))?");
        Matcher matcher = coordPattern.matcher(locParam);
        if (matcher.find()) {
            int x = parseIntSafe(matcher.group(1), 0);
            int y = parseIntSafe(matcher.group(2), 0);
            int z = matcher.group(3) != null ? parseIntSafe(matcher.group(3), 0) : 0;
            if (x > 0 && y > 0) {
                return new WorldPoint(x, y, z);
            }
        }

        return null;
    }

    /**
     * Parse quantity range from string.
     *
     * @param quantity quantity string (e.g., "1", "1-3", "10 (noted)")
     * @return [min, max] array
     */
    private int[] parseQuantityRange(String quantity) {
        if (quantity == null || quantity.isEmpty()) {
            return new int[]{1, 1};
        }

        Matcher matcher = QUANTITY_PATTERN.matcher(quantity);
        if (matcher.find()) {
            int min = parseIntSafe(matcher.group(1), 1);
            int max = matcher.group(2) != null ? parseIntSafe(matcher.group(2), min) : min;
            return new int[]{min, max};
        }

        return new int[]{1, 1};
    }

    /**
     * Parse drop rate to decimal.
     *
     * @param rarity  rarity string (e.g., "Rare", "1/128")
     * @param notes   additional rate notes
     * @return drop rate as decimal, or -1 if unknown
     */
    private double parseDropRate(String rarity, String notes) {
        String combined = (rarity + " " + notes).toLowerCase();

        // Check for exact fractions
        Matcher matcher = Pattern.compile("(\\d+)/(\\d+)").matcher(combined);
        if (matcher.find()) {
            double num = parseIntSafe(matcher.group(1), 1);
            double denom = parseIntSafe(matcher.group(2), 1);
            if (denom > 0) {
                return num / denom;
            }
        }

        // Estimate from rarity name
        if (combined.contains("always") || combined.contains("100%")) {
            return 1.0;
        }
        if (combined.contains("very rare")) {
            return 1.0 / 512; // Estimate
        }
        if (combined.contains("rare")) {
            return 1.0 / 128; // Estimate
        }
        if (combined.contains("uncommon")) {
            return 1.0 / 32; // Estimate
        }
        if (combined.contains("common")) {
            return 1.0 / 8; // Estimate
        }

        return -1;
    }

    /**
     * Clean wiki markup from a string.
     */
    private String cleanWikiMarkup(String text) {
        if (text == null) {
            return "";
        }

        // Remove wiki links [[link|display]] -> display or [[link]] -> link
        // Piped links: [[Dragon bones|Dragon bone]] -> Dragon bone
        text = text.replaceAll("\\[\\[([^\\]|]+)\\|([^\\]]+)\\]\\]", "$2");
        // Simple links: [[Dragon bones]] -> Dragon bones
        text = text.replaceAll("\\[\\[([^\\]]+)\\]\\]", "$1");

        // Remove templates {{template}}
        text = text.replaceAll("\\{\\{[^}]+}}", "");

        // Remove HTML tags
        text = text.replaceAll("<[^>]+>", "");

        // Remove bold/italic markup
        text = text.replaceAll("'{2,}", "");

        // Clean whitespace
        text = text.replaceAll("\\s+", " ").trim();

        return text;
    }

    /**
     * Clean rarity string.
     */
    private String cleanRarity(String rarity) {
        if (rarity == null) {
            return "Unknown";
        }
        rarity = cleanWikiMarkup(rarity).trim();
        
        // Normalize common values
        String lower = rarity.toLowerCase();
        if (lower.contains("always") || lower.equals("1/1")) {
            return "Always";
        }
        if (lower.contains("very rare")) {
            return "Very rare";
        }
        if (lower.contains("rare")) {
            return "Rare";
        }
        if (lower.contains("uncommon")) {
            return "Uncommon";
        }
        if (lower.contains("common")) {
            return "Common";
        }
        
        return rarity;
    }

    /**
     * Safely parse an integer.
     */
    private int parseIntSafe(String value, int defaultValue) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            // Remove any non-numeric characters except minus
            value = value.replaceAll("[^0-9-]", "");
            if (value.isEmpty()) {
                return defaultValue;
            }
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Validate a drop entry.
     */
    private boolean isValidDrop(DropTable.Drop drop) {
        if (drop == null) {
            return false;
        }
        if (drop.itemName() == null || drop.itemName().isEmpty()) {
            return false;
        }
        // Item ID validation (if present)
        if (drop.itemId() > 0 && drop.itemId() > MAX_ITEM_ID) {
            log.debug("Invalid item ID {} for drop {}", drop.itemId(), drop.itemName());
            return false;
        }
        return true;
    }
}

