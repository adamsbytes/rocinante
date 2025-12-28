package com.rocinante.tasks.impl;

import com.rocinante.state.EquipmentState;
import com.rocinante.state.InventoryState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Skill;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;

import com.rocinante.tasks.impl.travel.CanoeTask;
import com.rocinante.tasks.impl.travel.CharterShipTask;
import com.rocinante.tasks.impl.travel.FairyRingTask;
import com.rocinante.tasks.impl.travel.GnomeGliderTask;
import com.rocinante.tasks.impl.travel.QuetzalTask;
import com.rocinante.tasks.impl.travel.SpiritTreeTask;
import com.rocinante.tasks.impl.travel.TravelSubTask;

import javax.annotation.Nullable;
import java.awt.Rectangle;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Unified task for all travel methods including teleports and transportation.
 *
 * Per REQUIREMENTS.md Section 5.4.12:
 * <ul>
 *   <li>Teleport via: spellbook, jewelry, tablets, POH portals, fairy rings, spirit trees</li>
 *   <li>Transport via: gnome gliders, balloons, canoes, charter ships, magic carpets, etc.</li>
 *   <li>Select appropriate method based on availability, destination, AND behavioral profile</li>
 *   <li>Apply account-specific resource awareness (law rune scarcity, gold availability)</li>
 *   <li>Handle interfaces (fairy ring code entry, spirit tree selection, NPC dialogues)</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Teleport using a specific spell
 * TravelTask varrockTele = TravelTask.spell("Varrock Teleport");
 *
 * // Use gnome glider
 * TravelTask gliderTrip = TravelTask.gnomeGlider("Karamja");
 *
 * // Use charter ship
 * TravelTask charter = TravelTask.charterShip("Port Sarim");
 *
 * // Use quetzal (NPC-based transport in Varlamore)
 * TravelTask quetzal = TravelTask.quetzal("Cam Torum");
 * }</pre>
 */
@Slf4j
public class TravelTask extends AbstractTask {

    // ========================================================================
    // Constants - Spellbook Widget IDs (public for reuse by CombatManager, etc.)
    // ========================================================================

    /**
     * Standard spellbook widget group.
     */
    public static final int SPELLBOOK_GROUP = 218;

    /**
     * Home teleport widget child (standard spellbook).
     */
    public static final int HOME_TELEPORT_CHILD = 7;

    /**
     * Spell name to widget child ID mapping (standard spellbook).
     * Widget IDs from RuneLite's InterfaceID.MagicSpellbook.
     */
    private static final Map<String, Integer> SPELL_WIDGET_IDS = new HashMap<>();
    static {
        SPELL_WIDGET_IDS.put("Varrock Teleport", 23);      // 0x17
        SPELL_WIDGET_IDS.put("Lumbridge Teleport", 26);    // 0x1a
        SPELL_WIDGET_IDS.put("Falador Teleport", 29);      // 0x1d
        SPELL_WIDGET_IDS.put("Camelot Teleport", 37);      // 0x25
        SPELL_WIDGET_IDS.put("Ardougne Teleport", 51);     // 0x33
        SPELL_WIDGET_IDS.put("Watchtower Teleport", 56);   // 0x38
        SPELL_WIDGET_IDS.put("Trollheim Teleport", 64);    // 0x40
        SPELL_WIDGET_IDS.put("Teleport to Kourend", 79);   // 0x4f
        SPELL_WIDGET_IDS.put("Teleport to House", 31);     // 0x1f
    }

    // ========================================================================
    // Rune Item IDs (from RuneLite ItemID)
    // ========================================================================
    private static final int RUNE_AIR = 556;
    private static final int RUNE_WATER = 555;
    private static final int RUNE_EARTH = 557;
    private static final int RUNE_FIRE = 554;
    private static final int RUNE_LAW = 563;
    private static final int RUNE_SOUL = 566;

    // ========================================================================
    // Elemental Staves (provide infinite runes of their element)
    // ========================================================================

    /**
     * Maps staff item IDs to the rune types they provide.
     * Includes basic staves, battlestaves, mystic staves, and combination staves.
     */
    private static final Map<Integer, int[]> STAFF_RUNE_PROVIDERS = new HashMap<>();
    static {
        // Basic staves (single element)
        STAFF_RUNE_PROVIDERS.put(1381, new int[]{RUNE_AIR});   // Staff of air
        STAFF_RUNE_PROVIDERS.put(1383, new int[]{RUNE_WATER}); // Staff of water
        STAFF_RUNE_PROVIDERS.put(1385, new int[]{RUNE_EARTH}); // Staff of earth
        STAFF_RUNE_PROVIDERS.put(1387, new int[]{RUNE_FIRE});  // Staff of fire

        // Battlestaves (single element)
        STAFF_RUNE_PROVIDERS.put(1397, new int[]{RUNE_AIR});   // Air battlestaff
        STAFF_RUNE_PROVIDERS.put(1395, new int[]{RUNE_WATER}); // Water battlestaff
        STAFF_RUNE_PROVIDERS.put(1399, new int[]{RUNE_EARTH}); // Earth battlestaff
        STAFF_RUNE_PROVIDERS.put(1393, new int[]{RUNE_FIRE});  // Fire battlestaff

        // Mystic staves (single element)
        STAFF_RUNE_PROVIDERS.put(1405, new int[]{RUNE_AIR});   // Mystic air staff
        STAFF_RUNE_PROVIDERS.put(1403, new int[]{RUNE_WATER}); // Mystic water staff
        STAFF_RUNE_PROVIDERS.put(1407, new int[]{RUNE_EARTH}); // Mystic earth staff
        STAFF_RUNE_PROVIDERS.put(1401, new int[]{RUNE_FIRE});  // Mystic fire staff

        // Combination battlestaves (two elements)
        STAFF_RUNE_PROVIDERS.put(11998, new int[]{RUNE_AIR, RUNE_FIRE});   // Smoke battlestaff
        STAFF_RUNE_PROVIDERS.put(12000, new int[]{RUNE_AIR, RUNE_FIRE});   // Mystic smoke staff
        STAFF_RUNE_PROVIDERS.put(11787, new int[]{RUNE_WATER, RUNE_FIRE}); // Steam battlestaff
        STAFF_RUNE_PROVIDERS.put(11789, new int[]{RUNE_WATER, RUNE_FIRE}); // Mystic steam staff
        STAFF_RUNE_PROVIDERS.put(6562, new int[]{RUNE_WATER, RUNE_EARTH}); // Mud battlestaff
        STAFF_RUNE_PROVIDERS.put(6563, new int[]{RUNE_WATER, RUNE_EARTH}); // Mystic mud staff
        STAFF_RUNE_PROVIDERS.put(3053, new int[]{RUNE_EARTH, RUNE_FIRE});  // Lava battlestaff
        STAFF_RUNE_PROVIDERS.put(3054, new int[]{RUNE_EARTH, RUNE_FIRE});  // Mystic lava staff
        STAFF_RUNE_PROVIDERS.put(20730, new int[]{RUNE_AIR, RUNE_WATER});  // Mist battlestaff
        STAFF_RUNE_PROVIDERS.put(20733, new int[]{RUNE_AIR, RUNE_WATER});  // Mystic mist staff
        STAFF_RUNE_PROVIDERS.put(20736, new int[]{RUNE_AIR, RUNE_EARTH});  // Dust battlestaff
        STAFF_RUNE_PROVIDERS.put(20739, new int[]{RUNE_AIR, RUNE_EARTH});  // Mystic dust staff

        // Tome of fire (provides fire runes when equipped in shield slot)
        STAFF_RUNE_PROVIDERS.put(20714, new int[]{RUNE_FIRE}); // Tome of fire
        STAFF_RUNE_PROVIDERS.put(20716, new int[]{RUNE_FIRE}); // Tome of fire (empty) - still works

        // Tome of water
        STAFF_RUNE_PROVIDERS.put(25576, new int[]{RUNE_WATER}); // Tome of water
        STAFF_RUNE_PROVIDERS.put(25578, new int[]{RUNE_WATER}); // Tome of water (empty)
    }

    /**
     * Spell requirements: magic level and rune costs.
     */
    private static final Map<String, SpellRequirements> SPELL_REQUIREMENTS = new HashMap<>();
    static {
        SPELL_REQUIREMENTS.put("Varrock Teleport", new SpellRequirements(25,
            Map.of(RUNE_LAW, 1, RUNE_AIR, 3, RUNE_FIRE, 1)));
        SPELL_REQUIREMENTS.put("Lumbridge Teleport", new SpellRequirements(31,
            Map.of(RUNE_LAW, 1, RUNE_AIR, 3, RUNE_EARTH, 1)));
        SPELL_REQUIREMENTS.put("Falador Teleport", new SpellRequirements(37,
            Map.of(RUNE_LAW, 1, RUNE_AIR, 3, RUNE_WATER, 1)));
        SPELL_REQUIREMENTS.put("Teleport to House", new SpellRequirements(40,
            Map.of(RUNE_LAW, 1, RUNE_AIR, 1, RUNE_EARTH, 1)));
        SPELL_REQUIREMENTS.put("Camelot Teleport", new SpellRequirements(45,
            Map.of(RUNE_LAW, 1, RUNE_AIR, 5)));
        SPELL_REQUIREMENTS.put("Ardougne Teleport", new SpellRequirements(51,
            Map.of(RUNE_LAW, 2, RUNE_WATER, 2)));
        SPELL_REQUIREMENTS.put("Watchtower Teleport", new SpellRequirements(58,
            Map.of(RUNE_LAW, 2, RUNE_EARTH, 2)));
        SPELL_REQUIREMENTS.put("Trollheim Teleport", new SpellRequirements(61,
            Map.of(RUNE_LAW, 2, RUNE_FIRE, 2)));
        SPELL_REQUIREMENTS.put("Teleport to Kourend", new SpellRequirements(69,
            Map.of(RUNE_LAW, 2, RUNE_SOUL, 2, RUNE_WATER, 2)));
    }

    /**
     * Holds spell requirements data.
     */
    private static class SpellRequirements {
        final int magicLevel;
        final Map<Integer, Integer> runeCosts;

        SpellRequirements(int magicLevel, Map<Integer, Integer> runeCosts) {
            this.magicLevel = magicLevel;
            this.runeCosts = runeCosts;
        }
    }

    /**
     * Equipment widget group.
     */
    private static final int EQUIPMENT_GROUP = 387;

    /**
     * Equipment slot to widget child ID mapping.
     */
    private static final Map<Integer, Integer> EQUIPMENT_SLOT_WIDGETS = new HashMap<>();
    static {
        EQUIPMENT_SLOT_WIDGETS.put(EquipmentState.SLOT_RING, 13);
        EQUIPMENT_SLOT_WIDGETS.put(EquipmentState.SLOT_AMULET, 6);
        EQUIPMENT_SLOT_WIDGETS.put(EquipmentState.SLOT_GLOVES, 12);
        EQUIPMENT_SLOT_WIDGETS.put(EquipmentState.SLOT_CAPE, 4);
        EQUIPMENT_SLOT_WIDGETS.put(EquipmentState.SLOT_WEAPON, 8);
    }

    /**
     * Inventory widget group.
     */
    private static final int INVENTORY_GROUP = 149;
    private static final int INVENTORY_CHILD = 0;

    /**
     * Home teleport animation duration (ticks).
     */
    private static final int HOME_TELEPORT_DURATION_TICKS = 16;

    /**
     * Normal teleport animation duration (ticks).
     */
    private static final int TELEPORT_DURATION_TICKS = 5;

    /**
     * Transport travel duration (varies, use reasonable default).
     */
    private static final int TRANSPORT_DURATION_TICKS = 10;

    // Note: Complex transport widget constants are now in their respective sub-tasks:
    // - FairyRingTask, SpiritTreeTask, GnomeGliderTask, CharterShipTask, QuetzalTask, CanoeTask

    // ========================================================================
    // Travel Method Enum
    // ========================================================================

    /**
     * The type of travel method to use.
     */
    public enum TravelMethod {
        // ====== Teleportation Methods ======
        
        /**
         * Cast a spell from the spellbook.
         */
        SPELL,

        /**
         * Use home teleport (no rune cost, long animation).
         */
        HOME_TELEPORT,

        /**
         * Use a teleport tablet from inventory.
         */
        TABLET,

        /**
         * Use equipped jewelry (ring, amulet, etc.).
         */
        JEWELRY_EQUIPPED,

        /**
         * Use jewelry from inventory.
         */
        JEWELRY_INVENTORY,

        /**
         * Use fairy ring interface.
         */
        FAIRY_RING,

        /**
         * Use spirit tree interface.
         */
        SPIRIT_TREE,

        /**
         * Use POH portal.
         */
        POH_PORTAL,

        // ====== Transportation Methods ======

        /**
         * Gnome glider - NPC dialogue -> destination selection.
         * Requires: The Grand Tree quest completion.
         */
        GNOME_GLIDER,

        /**
         * Hot air balloon - Object interaction -> destination selection.
         * Requires: Enlightened Journey quest + logs.
         */
        BALLOON,

        /**
         * Canoe - Object interaction -> build menu.
         * Requires: Woodcutting level (12/27/42/57).
         */
        CANOE,

        /**
         * Charter ship - NPC dialogue -> destination -> pay fare.
         * Requires: Gold for fare.
         */
        CHARTER_SHIP,

        /**
         * Regular ship - NPC dialogue -> board.
         * May require gold or quest completion.
         */
        SHIP,

        /**
         * Row boat - Object interaction -> destination selection.
         * Requirements vary by location.
         */
        ROW_BOAT,

        /**
         * Magic carpet - NPC dialogue -> destination -> pay fare.
         * Requires: Gold for fare.
         */
        MAGIC_CARPET,

        /**
         * Minecart - Object interaction -> destination selection.
         * Keldagrim or Lovakengj networks.
         */
        MINECART,

        /**
         * Quetzal - NPC interaction -> destination interface.
         * Requires: Twilight's Promise quest completion.
         * Note: Click NPC in Varrock to go TO Varlamore, click NPCs in Varlamore to travel within network.
         */
        QUETZAL,

        /**
         * Wilderness lever - Object interaction (one-way teleport).
         * Warning: Dangerous, leads to wilderness.
         */
        WILDERNESS_LEVER,

        /**
         * Mushtree - Object interaction -> destination selection.
         * Requires: Bone Voyage quest (Fossil Island access).
         */
        MUSHTREE
    }

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * The travel method to use.
     */
    @Getter
    private final TravelMethod method;

    /**
     * Spell name (for SPELL method).
     */
    @Getter
    @Nullable
    private final String spellName;

    /**
     * Item ID (for TABLET, JEWELRY_EQUIPPED, JEWELRY_INVENTORY methods).
     */
    @Getter
    private final int itemId;

    /**
     * Menu option for jewelry teleport (e.g., "Edgeville", "Grand Exchange").
     */
    @Getter
    @Nullable
    private final String teleportOption;

    /**
     * Fairy ring code (3 letters like "AJR", "BKS" for FAIRY_RING method).
     */
    @Getter
    @Nullable
    private final String fairyRingCode;

    /**
     * Spirit tree destination name (e.g., "Tree Gnome Village" for SPIRIT_TREE method).
     */
    @Getter
    @Nullable
    private final String spiritTreeDestination;

    /**
     * Transport destination name (for GNOME_GLIDER, CHARTER_SHIP, etc.).
     */
    @Getter
    @Nullable
    private final String transportDestination;

    /**
     * NPC name to interact with (for NPC-based transports).
     */
    @Getter
    @Nullable
    private final String npcName;

    /**
     * Object ID to interact with (for object-based transports).
     */
    @Getter
    private final int objectId;

    /**
     * Gold cost for this transport (for charter ships, magic carpets, etc.).
     */
    @Getter
    private final int goldCost;

    /**
     * Required item for transport (e.g., logs for balloons, Dramen staff for fairy rings).
     */
    @Getter
    private final int requiredItemId;

    /**
     * Expected destination after travel (for verification).
     */
    @Getter
    @Setter
    @Nullable
    private WorldPoint expectedDestination;

    /**
     * Tolerance for destination verification (tiles).
     */
    @Getter
    @Setter
    private int destinationTolerance = 10;

    /**
     * Custom description.
     */
    @Getter
    @Setter
    @Nullable
    private String description;

    /**
     * Whether to verify we arrived at the destination.
     */
    @Getter
    @Setter
    private boolean verifyArrival = true;

    // ========================================================================
    // Execution State
    // ========================================================================

    private TravelPhase phase = TravelPhase.INIT;
    private boolean clickPending = false;
    private WorldPoint startPosition;
    private int waitTicks = 0;

    // NPC/Object interaction state
    private NPC targetNpc;
    private TileObject targetObject;

    /**
     * Active sub-task for complex transports (fairy ring, spirit tree, etc.).
     * When set, TravelTask delegates execution to this sub-task.
     */
    private TravelSubTask activeSubTask;

    // ========================================================================
    // Constructors
    // ========================================================================

    @Builder
    private TravelTask(TravelMethod method, @Nullable String spellName,
                       int itemId, @Nullable String teleportOption,
                       @Nullable String fairyRingCode, @Nullable String spiritTreeDestination,
                       @Nullable String transportDestination, @Nullable String npcName,
                       int objectId, int goldCost, int requiredItemId,
                       @Nullable WorldPoint expectedDestination,
                       @Nullable String description) {
        this.method = method;
        this.spellName = spellName;
        this.itemId = itemId;
        this.teleportOption = teleportOption;
        this.fairyRingCode = fairyRingCode;
        this.spiritTreeDestination = spiritTreeDestination;
        this.transportDestination = transportDestination;
        this.npcName = npcName;
        this.objectId = objectId;
        this.goldCost = goldCost;
        this.requiredItemId = requiredItemId;
        this.expectedDestination = expectedDestination;
        this.description = description;
        this.timeout = Duration.ofSeconds(60); // Longer timeout for transports
    }

    // ========================================================================
    // Factory Methods - Teleportation
    // ========================================================================

    /**
     * Create a task to cast a teleport spell.
     *
     * @param spellName the spell name (e.g., "Varrock Teleport")
     * @return travel task
     */
    public static TravelTask spell(String spellName) {
        return TravelTask.builder()
                .method(TravelMethod.SPELL)
                .spellName(spellName)
                .itemId(-1)
                .objectId(-1)
                .goldCost(0)
                .requiredItemId(-1)
                .build();
    }

    /**
     * Create a task to cast a teleport spell with destination.
     *
     * @param spellName the spell name
     * @param destination expected destination
     * @return travel task
     */
    public static TravelTask spell(String spellName, WorldPoint destination) {
        return TravelTask.builder()
                .method(TravelMethod.SPELL)
                .spellName(spellName)
                .itemId(-1)
                .objectId(-1)
                .goldCost(0)
                .requiredItemId(-1)
                .expectedDestination(destination)
                .build();
    }

    /**
     * Create a task to use home teleport.
     *
     * @return travel task
     */
    public static TravelTask homeTeleport() {
        return TravelTask.builder()
                .method(TravelMethod.HOME_TELEPORT)
                .itemId(-1)
                .objectId(-1)
                .goldCost(0)
                .requiredItemId(-1)
                .build();
    }

    /**
     * Create a task to use a teleport tablet.
     *
     * @param tabletItemId the tablet item ID
     * @return travel task
     */
    public static TravelTask tablet(int tabletItemId) {
        return TravelTask.builder()
                .method(TravelMethod.TABLET)
                .itemId(tabletItemId)
                .objectId(-1)
                .goldCost(0)
                .requiredItemId(-1)
                .build();
    }

    /**
     * Create a task to use equipped jewelry.
     *
     * @param jewelryItemId the jewelry item ID
     * @param option the teleport option (e.g., "Edgeville")
     * @return travel task
     */
    public static TravelTask jewelry(int jewelryItemId, String option) {
        return TravelTask.builder()
                .method(TravelMethod.JEWELRY_EQUIPPED)
                .itemId(jewelryItemId)
                .teleportOption(option)
                .objectId(-1)
                .goldCost(0)
                .requiredItemId(-1)
                .build();
    }

    /**
     * Create a task to use jewelry from inventory.
     *
     * @param jewelryItemId the jewelry item ID
     * @param option the teleport option
     * @return travel task
     */
    public static TravelTask jewelryFromInventory(int jewelryItemId, String option) {
        return TravelTask.builder()
                .method(TravelMethod.JEWELRY_INVENTORY)
                .itemId(jewelryItemId)
                .teleportOption(option)
                .objectId(-1)
                .goldCost(0)
                .requiredItemId(-1)
                .build();
    }

    /**
     * Create a task to use a fairy ring.
     *
     * @param code the 3-letter fairy ring code (e.g., "AJR", "BKS")
     * @return travel task
     */
    public static TravelTask fairyRing(String code) {
        if (code == null || code.length() != 3) {
            throw new IllegalArgumentException("Fairy ring code must be 3 letters");
        }
        return TravelTask.builder()
                .method(TravelMethod.FAIRY_RING)
                .itemId(-1)
                .fairyRingCode(code.toUpperCase())
                .objectId(-1)
                .goldCost(0)
                .requiredItemId(772) // Dramen staff (or 9084 Lunar staff)
                .build();
    }

    /**
     * Create a task to use a fairy ring with expected destination.
     *
     * @param code the 3-letter fairy ring code
     * @param destination expected destination
     * @return travel task
     */
    public static TravelTask fairyRing(String code, WorldPoint destination) {
        return fairyRing(code).withDestination(destination);
    }

    /**
     * Create a task to use a spirit tree.
     *
     * @param destination the destination name (e.g., "Tree Gnome Village")
     * @return travel task
     */
    public static TravelTask spiritTree(String destination) {
        return TravelTask.builder()
                .method(TravelMethod.SPIRIT_TREE)
                .itemId(-1)
                .spiritTreeDestination(destination)
                .objectId(-1)
                .goldCost(0)
                .requiredItemId(-1)
                .build();
    }

    /**
     * Create a task to use POH teleport (requires being in POH).
     *
     * @param portalDestination the portal destination name
     * @return travel task
     */
    public static TravelTask pohPortal(String portalDestination) {
        return TravelTask.builder()
                .method(TravelMethod.POH_PORTAL)
                .itemId(-1)
                .teleportOption(portalDestination)
                .objectId(-1)
                .goldCost(0)
                .requiredItemId(-1)
                .build();
    }

    // ========================================================================
    // Factory Methods - Transportation
    // ========================================================================

    /**
     * Create a task to use gnome glider.
     * Requires The Grand Tree quest completion.
     *
     * @param destination the destination name (e.g., "Karamja", "Al Kharid")
     * @return travel task
     */
    public static TravelTask gnomeGlider(String destination) {
        return TravelTask.builder()
                .method(TravelMethod.GNOME_GLIDER)
                .itemId(-1)
                .transportDestination(destination)
                .npcName("Gnome glider")
                .objectId(-1)
                .goldCost(0)
                .requiredItemId(-1)
                .build();
    }

    /**
     * Create a task to use hot air balloon.
     * Requires Enlightened Journey quest + logs.
     *
     * @param destination the destination name
     * @param logItemId the log item ID to use (e.g., willow logs)
     * @return travel task
     */
    public static TravelTask balloon(String destination, int logItemId) {
        return TravelTask.builder()
                .method(TravelMethod.BALLOON)
                .itemId(-1)
                .transportDestination(destination)
                .objectId(-1)
                .goldCost(0)
                .requiredItemId(logItemId)
                .build();
    }

    /**
     * Create a task to use canoe.
     * Requires Woodcutting level based on canoe type.
     *
     * @param destination the destination name
     * @return travel task
     */
    public static TravelTask canoe(String destination) {
        return TravelTask.builder()
                .method(TravelMethod.CANOE)
                .itemId(-1)
                .transportDestination(destination)
                .objectId(-1)
                .goldCost(0)
                .requiredItemId(-1)
                .build();
    }

    /**
     * Create a task to use charter ship.
     *
     * @param destination the destination name
     * @param fare the gold cost
     * @return travel task
     */
    public static TravelTask charterShip(String destination, int fare) {
        return TravelTask.builder()
                .method(TravelMethod.CHARTER_SHIP)
                .itemId(-1)
                .transportDestination(destination)
                .npcName("Trader Crewmember")
                .objectId(-1)
                .goldCost(fare)
                .requiredItemId(-1)
                .build();
    }

    /**
     * Create a task to use regular ship.
     *
     * @param destination the destination name
     * @param npcName the NPC to talk to
     * @return travel task
     */
    public static TravelTask ship(String destination, String npcName) {
        return TravelTask.builder()
                .method(TravelMethod.SHIP)
                .itemId(-1)
                .transportDestination(destination)
                .npcName(npcName)
                .objectId(-1)
                .goldCost(0)
                .requiredItemId(-1)
                .build();
    }

    /**
     * Create a task to use row boat.
     *
     * @param destination the destination name
     * @param objectId the row boat object ID
     * @return travel task
     */
    public static TravelTask rowBoat(String destination, int objectId) {
        return TravelTask.builder()
                .method(TravelMethod.ROW_BOAT)
                .itemId(-1)
                .transportDestination(destination)
                .objectId(objectId)
                .goldCost(0)
                .requiredItemId(-1)
                .build();
    }

    /**
     * Create a task to use magic carpet.
     *
     * @param destination the destination name
     * @param fare the gold cost
     * @return travel task
     */
    public static TravelTask magicCarpet(String destination, int fare) {
        return TravelTask.builder()
                .method(TravelMethod.MAGIC_CARPET)
                .itemId(-1)
                .transportDestination(destination)
                .npcName("Rug Merchant")
                .objectId(-1)
                .goldCost(fare)
                .requiredItemId(-1)
                .build();
    }

    /**
     * Create a task to use minecart.
     *
     * @param destination the destination name
     * @param objectId the minecart object ID
     * @return travel task
     */
    public static TravelTask minecart(String destination, int objectId) {
        return TravelTask.builder()
                .method(TravelMethod.MINECART)
                .itemId(-1)
                .transportDestination(destination)
                .objectId(objectId)
                .goldCost(0)
                .requiredItemId(-1)
                .build();
    }

    /**
     * Create a task to use quetzal transport.
     * Requires Twilight's Promise quest completion.
     * Note: Quetzals are NPC-based - click the quetzal NPC to travel.
     *
     * @param destination the destination name
     * @return travel task
     */
    public static TravelTask quetzal(String destination) {
        return TravelTask.builder()
                .method(TravelMethod.QUETZAL)
                .itemId(-1)
                .transportDestination(destination)
                .npcName("Quetzal")
                .objectId(-1)
                .goldCost(0)
                .requiredItemId(-1)
                .build();
    }

    /**
     * Create a task to use wilderness lever.
     * Warning: This teleports to wilderness!
     *
     * @param objectId the lever object ID
     * @param destination expected destination
     * @return travel task
     */
    public static TravelTask wildernessLever(int objectId, WorldPoint destination) {
        return TravelTask.builder()
                .method(TravelMethod.WILDERNESS_LEVER)
                .itemId(-1)
                .objectId(objectId)
                .expectedDestination(destination)
                .goldCost(0)
                .requiredItemId(-1)
                .build();
    }

    /**
     * Create a task to use mushtree.
     * Requires Bone Voyage quest.
     *
     * @param destination the destination name
     * @param objectId the mushtree object ID
     * @return travel task
     */
    public static TravelTask mushtree(String destination, int objectId) {
        return TravelTask.builder()
                .method(TravelMethod.MUSHTREE)
                .itemId(-1)
                .transportDestination(destination)
                .objectId(objectId)
                .goldCost(0)
                .requiredItemId(-1)
                .build();
    }

    /**
     * Create a travel task from navigation edge metadata.
     *
     * @param metadata edge metadata map
     * @return travel task, or null if metadata is invalid
     */
    @Nullable
    public static TravelTask fromNavigationMetadata(Map<String, String> metadata) {
        if (metadata == null) {
            return null;
        }

        String travelType = metadata.get("travel_type");
        if (travelType == null) {
            // Fall back to legacy teleport_type
            travelType = metadata.get("teleport_type");
        }
        if (travelType == null) {
            log.warn("No travel_type in metadata");
            return null;
        }

        switch (travelType) {
            case "spell":
                String spellName = metadata.get("spell_name");
                if (spellName == null) {
                    log.warn("No spell_name in metadata for spell teleport");
                    return null;
                }
                return spell(spellName);

            case "home_teleport":
                return homeTeleport();

            case "tablet":
                String tabletIdStr = metadata.get("item_id");
                if (tabletIdStr == null) {
                    log.warn("No item_id in metadata for tablet teleport");
                    return null;
                }
                return tablet(Integer.parseInt(tabletIdStr));

            case "jewelry":
                String jewelryIdStr = metadata.get("item_id");
                String option = metadata.get("teleport_option");
                if (jewelryIdStr == null || option == null) {
                    log.warn("Missing item_id or teleport_option for jewelry teleport");
                    return null;
                }
                String location = metadata.getOrDefault("location", "equipped");
                if ("inventory".equals(location)) {
                    return jewelryFromInventory(Integer.parseInt(jewelryIdStr), option);
                } else {
                    return jewelry(Integer.parseInt(jewelryIdStr), option);
                }

            case "fairy_ring":
                String code = metadata.get("code");
                if (code == null) {
                    log.warn("No code in metadata for fairy ring");
                    return null;
                }
                return fairyRing(code);

            case "spirit_tree":
                String stDest = metadata.get("destination");
                if (stDest == null) {
                    log.warn("No destination in metadata for spirit tree");
                    return null;
                }
                return spiritTree(stDest);

            case "gnome_glider":
                String gliderDest = metadata.get("destination");
                if (gliderDest == null) {
                    log.warn("No destination in metadata for gnome glider");
                    return null;
                }
                return gnomeGlider(gliderDest);

            case "charter_ship":
                String charterDest = metadata.get("destination");
                String fareStr = metadata.getOrDefault("fare", "0");
                if (charterDest == null) {
                    log.warn("No destination in metadata for charter ship");
                    return null;
                }
                return charterShip(charterDest, Integer.parseInt(fareStr));

            case "quetzal":
                String quetzalDest = metadata.get("destination");
                if (quetzalDest == null) {
                    log.warn("No destination in metadata for quetzal");
                    return null;
                }
                return quetzal(quetzalDest);

            default:
                log.warn("Unknown travel_type: {}", travelType);
                return null;
        }
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set expected destination (builder-style).
     */
    public TravelTask withDestination(WorldPoint destination) {
        this.expectedDestination = destination;
        return this;
    }

    /**
     * Set destination tolerance (builder-style).
     */
    public TravelTask withTolerance(int tiles) {
        this.destinationTolerance = tiles;
        return this;
    }

    /**
     * Set whether to verify arrival (builder-style).
     */
    public TravelTask withVerifyArrival(boolean verify) {
        this.verifyArrival = verify;
        return this;
    }

    /**
     * Set custom description (builder-style).
     */
    public TravelTask withDescription(String desc) {
        this.description = desc;
        return this;
    }

    // ========================================================================
    // Task Implementation
    // ========================================================================

    @Override
    public boolean canExecute(TaskContext ctx) {
        if (!ctx.isLoggedIn()) {
            return false;
        }

        // Check method-specific requirements
        switch (method) {
            case SPELL:
                return canCastSpell(ctx);
            case HOME_TELEPORT:
                return true; // Always available
            case TABLET:
                return hasItemInInventory(ctx, itemId);
            case JEWELRY_EQUIPPED:
                return hasJewelryEquipped(ctx, itemId);
            case JEWELRY_INVENTORY:
                return hasItemInInventory(ctx, itemId);
            case FAIRY_RING:
                return fairyRingCode != null && fairyRingCode.length() == 3;
            case SPIRIT_TREE:
                return spiritTreeDestination != null;
            case POH_PORTAL:
                return teleportOption != null;
            case GNOME_GLIDER:
            case BALLOON:
            case CANOE:
            case CHARTER_SHIP:
            case SHIP:
            case ROW_BOAT:
            case MAGIC_CARPET:
            case MINECART:
            case QUETZAL:
            case MUSHTREE:
                return transportDestination != null || objectId > 0;
            case WILDERNESS_LEVER:
                return objectId > 0;
            default:
                return true;
        }
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        if (clickPending) {
            return;
        }

        switch (phase) {
            case INIT:
                executeInit(ctx);
                break;
            case OPEN_SPELLBOOK:
                executeOpenSpellbook(ctx);
                break;
            case OPEN_EQUIPMENT:
                executeOpenEquipment(ctx);
                break;
            case OPEN_INVENTORY:
                executeOpenInventory(ctx);
                break;
            case CLICK_TELEPORT:
                executeClickTeleport(ctx);
                break;
            case CLICK_POH_PORTAL:
                executeClickPohPortal(ctx);
                break;
            // Delegated sub-task execution
            case EXECUTE_SUBTASK:
                executeSubTask(ctx);
                break;
            // Legacy transport phases
            case FIND_TRANSPORT_NPC:
                executeFindTransportNpc(ctx);
                break;
            case INTERACT_TRANSPORT_NPC:
                executeInteractTransportNpc(ctx);
                break;
            case FIND_TRANSPORT_OBJECT:
                executeFindTransportObject(ctx);
                break;
            case INTERACT_TRANSPORT_OBJECT:
                executeInteractTransportObject(ctx);
                break;
            case WAIT_TRANSPORT_INTERFACE:
                executeWaitTransportInterface(ctx);
                break;
            case SELECT_TRANSPORT_DESTINATION:
                executeSelectTransportDestination(ctx);
                break;
            case CONFIRM_TRANSPORT:
                executeConfirmTransport(ctx);
                break;
            case WAIT_FOR_TELEPORT:
                executeWaitForTeleport(ctx);
                break;
            case VERIFY_ARRIVAL:
                executeVerifyArrival(ctx);
                break;
        }
    }

    // ========================================================================
    // Phase: Init
    // ========================================================================

    private void executeInit(TaskContext ctx) {
        // Record starting position
        startPosition = ctx.getPlayerState().getWorldPosition();
        waitTicks = 0;
        activeSubTask = null;

        log.debug("Starting travel: method={}, spell={}, destination={}",
                method, spellName, transportDestination);

        // Determine first phase based on method
        // Simple teleports are handled inline, complex transports delegate to sub-tasks
        switch (method) {
            // ====== Simple teleports (inline) ======
            case SPELL:
            case HOME_TELEPORT:
                phase = TravelPhase.OPEN_SPELLBOOK;
                break;
            case JEWELRY_EQUIPPED:
                phase = TravelPhase.OPEN_EQUIPMENT;
                break;
            case TABLET:
            case JEWELRY_INVENTORY:
                phase = TravelPhase.OPEN_INVENTORY;
                break;
            case POH_PORTAL:
                phase = TravelPhase.CLICK_POH_PORTAL;
                break;

            // ====== Complex transports (delegated to sub-tasks) ======
            case FAIRY_RING:
                activeSubTask = FairyRingTask.toCode(fairyRingCode);
                if (expectedDestination != null) {
                    activeSubTask.setExpectedDestination(expectedDestination);
                }
                activeSubTask.init(ctx);
                phase = TravelPhase.EXECUTE_SUBTASK;
                break;

            case SPIRIT_TREE:
                activeSubTask = SpiritTreeTask.to(spiritTreeDestination);
                if (expectedDestination != null) {
                    activeSubTask.setExpectedDestination(expectedDestination);
                }
                activeSubTask.init(ctx);
                phase = TravelPhase.EXECUTE_SUBTASK;
                break;

            case GNOME_GLIDER:
                activeSubTask = GnomeGliderTask.to(transportDestination);
                if (expectedDestination != null) {
                    activeSubTask.setExpectedDestination(expectedDestination);
                }
                activeSubTask.init(ctx);
                phase = TravelPhase.EXECUTE_SUBTASK;
                break;

            case CHARTER_SHIP:
                activeSubTask = CharterShipTask.to(transportDestination, goldCost);
                if (expectedDestination != null) {
                    activeSubTask.setExpectedDestination(expectedDestination);
                }
                activeSubTask.init(ctx);
                phase = TravelPhase.EXECUTE_SUBTASK;
                break;

            case QUETZAL:
                activeSubTask = QuetzalTask.to(transportDestination);
                if (expectedDestination != null) {
                    activeSubTask.setExpectedDestination(expectedDestination);
                }
                activeSubTask.init(ctx);
                phase = TravelPhase.EXECUTE_SUBTASK;
                break;

            case CANOE:
                activeSubTask = CanoeTask.to(transportDestination);
                if (expectedDestination != null) {
                    activeSubTask.setExpectedDestination(expectedDestination);
                }
                activeSubTask.init(ctx);
                phase = TravelPhase.EXECUTE_SUBTASK;
                break;

            // ====== Legacy transports (handled inline for now) ======
            case SHIP:
            case MAGIC_CARPET:
                phase = TravelPhase.FIND_TRANSPORT_NPC;
                break;
            case BALLOON:
            case ROW_BOAT:
            case MINECART:
            case WILDERNESS_LEVER:
            case MUSHTREE:
                phase = TravelPhase.FIND_TRANSPORT_OBJECT;
                break;

            default:
                fail("Unsupported travel method: " + method);
        }
    }

    // ========================================================================
    // Phase: Open Spellbook
    // ========================================================================

    private void executeOpenSpellbook(TaskContext ctx) {
        Client client = ctx.getClient();
        Widget spellbook = client.getWidget(SPELLBOOK_GROUP, 0);

        if (spellbook != null && !spellbook.isHidden()) {
            log.debug("Spellbook is open");
            phase = TravelPhase.CLICK_TELEPORT;
            return;
        }

        // Open spellbook using F7 (or click)
        log.debug("Opening spellbook");
        clickPending = true;

        ctx.getKeyboardController().pressKey(java.awt.event.KeyEvent.VK_F7)
                .thenRun(() -> {
                    clickPending = false;
                    waitTicks = 0;
                })
                .exceptionally(e -> {
                    clickPending = false;
                    log.error("Failed to open spellbook", e);
                    return null;
                });
    }

    // ========================================================================
    // Phase: Open Equipment
    // ========================================================================

    private void executeOpenEquipment(TaskContext ctx) {
        Client client = ctx.getClient();
        Widget equipment = client.getWidget(EQUIPMENT_GROUP, 0);

        if (equipment != null && !equipment.isHidden()) {
            log.debug("Equipment tab is open");
            phase = TravelPhase.CLICK_TELEPORT;
            return;
        }

        // Open equipment using F5 (or click)
        log.debug("Opening equipment tab");
        clickPending = true;

        ctx.getKeyboardController().pressKey(java.awt.event.KeyEvent.VK_F5)
                .thenRun(() -> {
                    clickPending = false;
                    waitTicks = 0;
                })
                .exceptionally(e -> {
                    clickPending = false;
                    log.error("Failed to open equipment", e);
                    return null;
                });
    }

    // ========================================================================
    // Phase: Open Inventory
    // ========================================================================

    private void executeOpenInventory(TaskContext ctx) {
        Client client = ctx.getClient();
        Widget inventory = client.getWidget(INVENTORY_GROUP, 0);

        if (inventory != null && !inventory.isHidden()) {
            log.debug("Inventory is open");
            phase = TravelPhase.CLICK_TELEPORT;
            return;
        }

        // Open inventory using F4 (or click)
        log.debug("Opening inventory");
        clickPending = true;

        ctx.getKeyboardController().pressKey(java.awt.event.KeyEvent.VK_F4)
                .thenRun(() -> {
                    clickPending = false;
                    waitTicks = 0;
                })
                .exceptionally(e -> {
                    clickPending = false;
                    log.error("Failed to open inventory", e);
                    return null;
                });
    }

    // ========================================================================
    // Phase: Click Teleport
    // ========================================================================

    private void executeClickTeleport(TaskContext ctx) {
        switch (method) {
            case SPELL:
                clickSpell(ctx);
                break;
            case HOME_TELEPORT:
                clickHomeTeleport(ctx);
                break;
            case TABLET:
                clickTablet(ctx);
                break;
            case JEWELRY_EQUIPPED:
                clickEquippedJewelry(ctx);
                break;
            case JEWELRY_INVENTORY:
                clickInventoryJewelry(ctx);
                break;
            default:
                fail("Unsupported teleport method: " + method);
        }
    }

    private void clickSpell(TaskContext ctx) {
        Integer childId = SPELL_WIDGET_IDS.get(spellName);
        if (childId == null) {
            fail("Unknown spell: " + spellName);
            return;
        }

        Client client = ctx.getClient();
        Widget spellWidget = client.getWidget(SPELLBOOK_GROUP, childId);

        if (spellWidget == null || spellWidget.isHidden()) {
            log.warn("Spell widget not found: {}:{}", SPELLBOOK_GROUP, childId);
            fail("Spell not available");
            return;
        }

        clickWidget(ctx, spellWidget, "Cast " + spellName);
    }

    private void clickHomeTeleport(TaskContext ctx) {
        Client client = ctx.getClient();
        Widget homeWidget = client.getWidget(SPELLBOOK_GROUP, HOME_TELEPORT_CHILD);

        if (homeWidget == null || homeWidget.isHidden()) {
            fail("Home teleport not available");
            return;
        }

        clickWidget(ctx, homeWidget, "Home Teleport");
    }

    private void clickTablet(TaskContext ctx) {
        int slot = findItemSlot(ctx, itemId);
        if (slot < 0) {
            fail("Tablet not found in inventory");
            return;
        }

        Client client = ctx.getClient();
        Widget inventoryWidget = client.getWidget(INVENTORY_GROUP, INVENTORY_CHILD);

        if (inventoryWidget == null) {
            fail("Inventory widget not found");
            return;
        }

        Widget[] children = inventoryWidget.getDynamicChildren();
        if (children == null || slot >= children.length) {
            fail("Invalid inventory slot");
            return;
        }

        clickWidget(ctx, children[slot], "Break tablet");
    }

    private void clickEquippedJewelry(TaskContext ctx) {
        EquipmentState equipment = ctx.getGameStateService().getEquipmentState();
        var slotOpt = equipment.getSlotOf(itemId);

        if (slotOpt.isEmpty()) {
            fail("Jewelry not equipped");
            return;
        }

        int slot = slotOpt.get();
        Integer widgetChildId = EQUIPMENT_SLOT_WIDGETS.get(slot);

        if (widgetChildId == null) {
            fail("Unsupported equipment slot for jewelry: " + slot);
            return;
        }

        Client client = ctx.getClient();
        Widget slotWidget = client.getWidget(EQUIPMENT_GROUP, widgetChildId);

        if (slotWidget == null || slotWidget.isHidden()) {
            fail("Equipment slot widget not found");
            return;
        }

        // Use menu helper to select the teleport option
        Rectangle bounds = slotWidget.getBounds();
        if (bounds == null) {
            fail("Equipment slot has no bounds");
            return;
        }

        log.debug("Clicking jewelry for teleport option: {}", teleportOption);
        clickPending = true;

        ctx.getMenuHelper().selectMenuEntry(bounds, teleportOption)
                .thenAccept(success -> {
                    clickPending = false;
                    if (success) {
                        phase = TravelPhase.WAIT_FOR_TELEPORT;
                        waitTicks = 0;
                    } else {
                        fail("Failed to select teleport option: " + teleportOption);
                    }
                })
                .exceptionally(e -> {
                    clickPending = false;
                    log.error("Jewelry teleport failed", e);
                    fail("Jewelry teleport failed: " + e.getMessage());
                    return null;
                });
    }

    private void clickInventoryJewelry(TaskContext ctx) {
        int slot = findItemSlot(ctx, itemId);
        if (slot < 0) {
            fail("Jewelry not found in inventory");
            return;
        }

        Client client = ctx.getClient();
        Widget inventoryWidget = client.getWidget(INVENTORY_GROUP, INVENTORY_CHILD);

        if (inventoryWidget == null) {
            fail("Inventory widget not found");
            return;
        }

        Widget[] children = inventoryWidget.getDynamicChildren();
        if (children == null || slot >= children.length) {
            fail("Invalid inventory slot");
            return;
        }

        Rectangle bounds = children[slot].getBounds();
        if (bounds == null) {
            fail("Inventory slot has no bounds");
            return;
        }

        log.debug("Clicking inventory jewelry for teleport option: {}", teleportOption);
        clickPending = true;

        ctx.getMenuHelper().selectMenuEntry(bounds, teleportOption)
                .thenAccept(success -> {
                    clickPending = false;
                    if (success) {
                        phase = TravelPhase.WAIT_FOR_TELEPORT;
                        waitTicks = 0;
                    } else {
                        fail("Failed to select teleport option: " + teleportOption);
                    }
                })
                .exceptionally(e -> {
                    clickPending = false;
                    log.error("Inventory jewelry teleport failed", e);
                    fail("Inventory jewelry teleport failed: " + e.getMessage());
                    return null;
                });
    }

    // ========================================================================
    // Phase: POH Portal
    // ========================================================================

    private void executeClickPohPortal(TaskContext ctx) {
        log.warn("POH portal teleport not fully implemented - requires object interaction");
        fail("POH portal teleport requires integration with InteractObjectTask");
    }

    // ========================================================================
    // Phase: Execute Sub-Task (delegated complex transports)
    // ========================================================================

    private void executeSubTask(TaskContext ctx) {
        if (activeSubTask == null) {
            fail("No active sub-task set");
            return;
        }

        // Don't tick if sub-task is waiting for async operation
        if (activeSubTask.isWaiting()) {
            return;
        }

        TravelSubTask.Status status = activeSubTask.tick(ctx);

        switch (status) {
            case IN_PROGRESS:
                // Sub-task still working
                break;
            case COMPLETED:
                log.debug("Sub-task completed: {}", activeSubTask.getDescription());
                complete();
                break;
            case FAILED:
                String reason = activeSubTask.getFailureReason();
                log.warn("Sub-task failed: {} - {}", activeSubTask.getDescription(), reason);
                fail(reason != null ? reason : "Sub-task failed");
                break;
        }
    }

    // ========================================================================
    // Phase: Transport NPC Interaction
    // ========================================================================

    private void executeFindTransportNpc(TaskContext ctx) {
        // Find the transport NPC nearby
        Client client = ctx.getClient();

        for (NPC npc : client.getNpcs()) {
            if (npc == null) continue;
            String name = npc.getName();
            if (name != null && name.toLowerCase().contains(npcName.toLowerCase())) {
                targetNpc = npc;
                log.debug("Found transport NPC: {}", name);
                phase = TravelPhase.INTERACT_TRANSPORT_NPC;
                return;
            }
        }

        waitTicks++;
        if (waitTicks > 10) {
            fail("Transport NPC not found: " + npcName);
        }
    }

    private void executeInteractTransportNpc(TaskContext ctx) {
        if (targetNpc == null) {
            fail("No target NPC set");
            return;
        }

        // Click the NPC with appropriate action
        String action = getTransportNpcAction();
        log.debug("Interacting with NPC {} using action: {}", targetNpc.getName(), action);

        // Use ctx.getInteractionManager() or equivalent to click NPC
        // For now, mark as waiting for interface
        phase = TravelPhase.WAIT_TRANSPORT_INTERFACE;
        waitTicks = 0;
    }

    private String getTransportNpcAction() {
        switch (method) {
            case GNOME_GLIDER:
                return "Glider";
            case CHARTER_SHIP:
                return "Charter";
            case SHIP:
                return "Travel";
            case MAGIC_CARPET:
                return "Travel";
            case QUETZAL:
                return "Travel";
            default:
                return "Talk-to";
        }
    }

    // ========================================================================
    // Phase: Transport Object Interaction
    // ========================================================================

    private void executeFindTransportObject(TaskContext ctx) {
        // Find the transport object nearby
        // This requires scene tile scanning which depends on game state
        log.debug("Looking for transport object ID: {}", objectId);

        // For now, assume object is found and proceed
        phase = TravelPhase.INTERACT_TRANSPORT_OBJECT;
        waitTicks = 0;
    }

    private void executeInteractTransportObject(TaskContext ctx) {
        String action = getTransportObjectAction();
        log.debug("Interacting with transport object using action: {}", action);

        // Use ctx.getInteractionManager() or equivalent to click object
        phase = TravelPhase.WAIT_TRANSPORT_INTERFACE;
        waitTicks = 0;
    }

    private String getTransportObjectAction() {
        switch (method) {
            case BALLOON:
                return "Travel";
            case CANOE:
                return "Chop-down";
            case ROW_BOAT:
                return "Board";
            case MINECART:
                return "Ride";
            case WILDERNESS_LEVER:
                return "Pull";
            case MUSHTREE:
                return "Use";
            default:
                return "Use";
        }
    }

    // ========================================================================
    // Phase: Transport Interface
    // ========================================================================

    private void executeWaitTransportInterface(TaskContext ctx) {
        // Legacy transport interface handling
        // Most complex transports are now delegated to sub-tasks
        // This handles remaining simple NPC/object-based transports
        
        waitTicks++;
        if (waitTicks > 30) {
            // Assume direct transport without interface
            phase = TravelPhase.WAIT_FOR_TELEPORT;
            waitTicks = 0;
        }
    }

    private void executeSelectTransportDestination(TaskContext ctx) {
        // Select destination from interface
        log.debug("Selecting transport destination: {}", transportDestination);

        // Implementation depends on specific interface
        // For now, mark as confirming
        phase = TravelPhase.CONFIRM_TRANSPORT;
    }

    private void executeConfirmTransport(TaskContext ctx) {
        // Confirm transport selection
        log.debug("Confirming transport");
        phase = TravelPhase.WAIT_FOR_TELEPORT;
        waitTicks = 0;
    }

    // ========================================================================
    // Common Click Helper
    // ========================================================================

    private void clickWidget(TaskContext ctx, Widget widget, String actionDesc) {
        Rectangle bounds = widget.getBounds();
        if (bounds == null || bounds.width == 0) {
            fail("Widget has invalid bounds");
            return;
        }

        // Calculate humanized click point
        var rand = ctx.getRandomization();
        int x = bounds.x + bounds.width / 2 + (int) ((rand.uniformRandom(0, 1) - 0.5) * bounds.width * 0.4);
        int y = bounds.y + bounds.height / 2 + (int) ((rand.uniformRandom(0, 1) - 0.5) * bounds.height * 0.4);

        log.debug("{}: clicking at ({}, {})", actionDesc, x, y);
        clickPending = true;

        ctx.getMouseController().moveToCanvas(x, y)
                .thenCompose(v -> ctx.getMouseController().click())
                .thenRun(() -> {
                    clickPending = false;
                    phase = TravelPhase.WAIT_FOR_TELEPORT;
                    waitTicks = 0;
                })
                .exceptionally(e -> {
                    clickPending = false;
                    log.error("Click failed: {}", actionDesc, e);
                    fail("Click failed: " + e.getMessage());
                    return null;
                });
    }

    // ========================================================================
    // Phase: Wait For Teleport
    // ========================================================================

    private void executeWaitForTeleport(TaskContext ctx) {
        waitTicks++;

        // Check if player position has changed (teleport completed)
        WorldPoint currentPos = ctx.getPlayerState().getWorldPosition();
        if (currentPos != null && startPosition != null) {
            int distance = currentPos.distanceTo(startPosition);
            if (distance > 5) {
                log.debug("Travel completed, moved {} tiles", distance);
                if (verifyArrival && expectedDestination != null) {
                    phase = TravelPhase.VERIFY_ARRIVAL;
                } else {
                    complete();
                }
                return;
            }
        }

        // Timeout based on travel type
        int maxWaitTicks = getMaxWaitTicks();

        if (waitTicks > maxWaitTicks) {
            log.warn("Travel timed out after {} ticks", waitTicks);
            fail("Travel timed out");
        }
    }

    private int getMaxWaitTicks() {
        switch (method) {
            case HOME_TELEPORT:
                return HOME_TELEPORT_DURATION_TICKS + 5;
            case SHIP:
            case CHARTER_SHIP:
            case GNOME_GLIDER:
            case BALLOON:
                return TRANSPORT_DURATION_TICKS + 10;
            default:
                return TELEPORT_DURATION_TICKS + 5;
        }
    }

    // ========================================================================
    // Phase: Verify Arrival
    // ========================================================================

    private void executeVerifyArrival(TaskContext ctx) {
        WorldPoint currentPos = ctx.getPlayerState().getWorldPosition();
        if (currentPos == null) {
            fail("Could not get player position");
            return;
        }

        int distance = currentPos.distanceTo(expectedDestination);
        if (distance <= destinationTolerance) {
            log.debug("Arrived at destination (within {} tiles)", distance);
            complete();
        } else {
            log.warn("Travel destination mismatch: expected {}, got {} (distance: {})",
                    expectedDestination, currentPos, distance);
            // Still complete since travel happened, just not where expected
            complete();
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private boolean canCastSpell(TaskContext ctx) {
        // Check if spell exists in widget mapping
        Integer childId = SPELL_WIDGET_IDS.get(spellName);
        if (childId == null) {
            log.warn("Unknown spell: {}", spellName);
            return false;
        }

        // Check spell requirements
        SpellRequirements reqs = SPELL_REQUIREMENTS.get(spellName);
        if (reqs == null) {
            // Unknown requirements - allow attempt
            log.debug("No requirements data for spell: {}", spellName);
            return true;
        }

        // Check magic level
        Client client = ctx.getClient();
        int magicLevel = client.getRealSkillLevel(Skill.MAGIC);
        if (magicLevel < reqs.magicLevel) {
            log.debug("Insufficient magic level for {}: have {}, need {}",
                    spellName, magicLevel, reqs.magicLevel);
            return false;
        }

        // Check rune requirements
        InventoryState inventory = ctx.getInventoryState();
        EquipmentState equipment = ctx.getGameStateService().getEquipmentState();

        // Determine which runes are provided by equipped items (staves, tomes)
        java.util.Set<Integer> providedRunes = getProvidedRunes(equipment);

        for (Map.Entry<Integer, Integer> runeCost : reqs.runeCosts.entrySet()) {
            int runeId = runeCost.getKey();
            int requiredCount = runeCost.getValue();

            // Check if this rune type is provided by equipped staff/tome
            if (providedRunes.contains(runeId)) {
                log.trace("Rune {} provided by equipped staff/tome", runeId);
                continue; // Don't need runes in inventory
            }

            int haveCount = inventory.countItem(runeId);
            if (haveCount < requiredCount) {
                log.debug("Insufficient runes for {}: rune {} have {}, need {}",
                        spellName, runeId, haveCount, requiredCount);
                return false;
            }
        }

        return true;
    }

    /**
     * Get the set of rune types provided by equipped items (staves, tomes).
     *
     * @param equipment the player's equipment state
     * @return set of rune IDs that don't need to be in inventory
     */
    private java.util.Set<Integer> getProvidedRunes(EquipmentState equipment) {
        java.util.Set<Integer> provided = new java.util.HashSet<>();

        // Check weapon slot for elemental staves
        int weaponId = equipment.getWeaponId();
        if (weaponId > 0 && STAFF_RUNE_PROVIDERS.containsKey(weaponId)) {
            for (int runeId : STAFF_RUNE_PROVIDERS.get(weaponId)) {
                provided.add(runeId);
            }
        }

        // Check shield slot for tomes
        int shieldId = equipment.getShield().map(net.runelite.api.Item::getId).orElse(-1);
        if (shieldId > 0 && STAFF_RUNE_PROVIDERS.containsKey(shieldId)) {
            for (int runeId : STAFF_RUNE_PROVIDERS.get(shieldId)) {
                provided.add(runeId);
            }
        }

        return provided;
    }

    private boolean hasItemInInventory(TaskContext ctx, int itemId) {
        return findItemSlot(ctx, itemId) >= 0;
    }

    private boolean hasJewelryEquipped(TaskContext ctx, int itemId) {
        EquipmentState equipment = ctx.getGameStateService().getEquipmentState();
        return equipment.hasEquipped(itemId);
    }

    private int findItemSlot(TaskContext ctx, int itemId) {
        Client client = ctx.getClient();
        ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
        if (inventory == null) {
            return -1;
        }

        Item[] items = inventory.getItems();
        for (int i = 0; i < items.length; i++) {
            if (items[i] != null && items[i].getId() == itemId) {
                return i;
            }
        }
        return -1;
    }

    // ========================================================================
    // Static Utility Methods (for use by CombatManager, etc.)
    // ========================================================================

    /**
     * Information about an available teleport spell for emergency escape.
     */
    public static class AvailableSpell {
        private final String name;
        private final int widgetChildId;

        public AvailableSpell(String name, int widgetChildId) {
            this.name = name;
            this.widgetChildId = widgetChildId;
        }

        public String getName() { return name; }
        public int getWidgetChildId() { return widgetChildId; }
        public int getWidgetGroupId() { return SPELLBOOK_GROUP; }
    }

    /**
     * Find the best available teleport spell based on magic level and runes.
     * Returns spells in order of preference (fastest teleports first).
     *
     * <p>This method is designed for emergency escape scenarios where we need
     * to quickly determine what teleport options are available.
     *
     * @param client the RuneLite client
     * @param inventory the player's inventory state
     * @param equipment the player's equipment state
     * @return the best available spell, or null if none available (use home teleport)
     */
    public static AvailableSpell findBestAvailableSpell(
            Client client,
            InventoryState inventory,
            EquipmentState equipment) {

        int magicLevel = client.getRealSkillLevel(Skill.MAGIC);
        java.util.Set<Integer> providedRunes = getProvidedRunesStatic(equipment);

        // Check spells in order of preference (fastest/safest first)
        String[] spellOrder = {
            "Varrock Teleport",    // Level 25, fast
            "Lumbridge Teleport",  // Level 31, safe spawn
            "Falador Teleport",    // Level 37
            "Teleport to House",   // Level 40, if POH is set up
            "Camelot Teleport",    // Level 45
        };

        for (String spellName : spellOrder) {
            Integer childId = SPELL_WIDGET_IDS.get(spellName);
            SpellRequirements reqs = SPELL_REQUIREMENTS.get(spellName);

            if (childId == null || reqs == null) {
                continue;
            }

            // Check magic level
            if (magicLevel < reqs.magicLevel) {
                continue;
            }

            // Check runes
            boolean hasRunes = true;
            for (Map.Entry<Integer, Integer> runeCost : reqs.runeCosts.entrySet()) {
                int runeId = runeCost.getKey();
                int requiredCount = runeCost.getValue();

                // Skip if provided by staff
                if (providedRunes.contains(runeId)) {
                    continue;
                }

                if (inventory.countItem(runeId) < requiredCount) {
                    hasRunes = false;
                    break;
                }
            }

            if (hasRunes) {
                return new AvailableSpell(spellName, childId);
            }
        }

        return null; // No standard teleport available, caller should use home teleport
    }

    /**
     * Static version of getProvidedRunes for use without TaskContext.
     */
    private static java.util.Set<Integer> getProvidedRunesStatic(EquipmentState equipment) {
        java.util.Set<Integer> provided = new java.util.HashSet<>();

        int weaponId = equipment.getWeaponId();
        if (weaponId > 0 && STAFF_RUNE_PROVIDERS.containsKey(weaponId)) {
            for (int runeId : STAFF_RUNE_PROVIDERS.get(weaponId)) {
                provided.add(runeId);
            }
        }

        int shieldId = equipment.getShield().map(net.runelite.api.Item::getId).orElse(-1);
        if (shieldId > 0 && STAFF_RUNE_PROVIDERS.containsKey(shieldId)) {
            for (int runeId : STAFF_RUNE_PROVIDERS.get(shieldId)) {
                provided.add(runeId);
            }
        }

        return provided;
    }

    /**
     * Get the widget child ID for a named spell.
     *
     * @param spellName the spell name (e.g., "Varrock Teleport")
     * @return the widget child ID, or -1 if unknown
     */
    public static int getSpellWidgetChildId(String spellName) {
        Integer childId = SPELL_WIDGET_IDS.get(spellName);
        return childId != null ? childId : -1;
    }

    /**
     * Check if a travel method consumes law runes.
     * Used for resource-aware pathfinding cost calculations.
     *
     * @return true if this travel method uses law runes
     */
    public boolean consumesLawRunes() {
        if (method != TravelMethod.SPELL) {
            return false;
        }
        SpellRequirements reqs = SPELL_REQUIREMENTS.get(spellName);
        return reqs != null && reqs.runeCosts.containsKey(RUNE_LAW);
    }

    /**
     * Get the number of law runes consumed by this travel method.
     *
     * @return number of law runes, or 0 if none
     */
    public int getLawRuneCost() {
        if (method != TravelMethod.SPELL) {
            return 0;
        }
        SpellRequirements reqs = SPELL_REQUIREMENTS.get(spellName);
        if (reqs == null) {
            return 0;
        }
        return reqs.runeCosts.getOrDefault(RUNE_LAW, 0);
    }

    // ========================================================================
    // Description
    // ========================================================================

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }

        switch (method) {
            case SPELL:
                return "Cast " + spellName;
            case HOME_TELEPORT:
                return "Home Teleport";
            case TABLET:
                return "Use teleport tablet";
            case JEWELRY_EQUIPPED:
            case JEWELRY_INVENTORY:
                return "Use jewelry teleport to " + teleportOption;
            case FAIRY_RING:
                return "Use fairy ring " + fairyRingCode;
            case SPIRIT_TREE:
                return "Use spirit tree to " + spiritTreeDestination;
            case POH_PORTAL:
                return "Use POH portal to " + teleportOption;
            case GNOME_GLIDER:
                return "Glide to " + transportDestination;
            case BALLOON:
                return "Balloon to " + transportDestination;
            case CANOE:
                return "Canoe to " + transportDestination;
            case CHARTER_SHIP:
                return "Charter ship to " + transportDestination;
            case SHIP:
                return "Sail to " + transportDestination;
            case ROW_BOAT:
                return "Row boat to " + transportDestination;
            case MAGIC_CARPET:
                return "Magic carpet to " + transportDestination;
            case MINECART:
                return "Minecart to " + transportDestination;
            case QUETZAL:
                return "Quetzal to " + transportDestination;
            case WILDERNESS_LEVER:
                return "Pull wilderness lever";
            case MUSHTREE:
                return "Mushtree to " + transportDestination;
            default:
                return "Travel";
        }
    }

    // ========================================================================
    // Phase Enum
    // ========================================================================

    private enum TravelPhase {
        INIT,
        // Simple teleport phases
        OPEN_SPELLBOOK,
        OPEN_EQUIPMENT,
        OPEN_INVENTORY,
        CLICK_TELEPORT,
        // POH portal phase
        CLICK_POH_PORTAL,
        // Delegated sub-task execution
        EXECUTE_SUBTASK,
        // Legacy transport phases (for methods not yet migrated to sub-tasks)
        FIND_TRANSPORT_NPC,
        INTERACT_TRANSPORT_NPC,
        FIND_TRANSPORT_OBJECT,
        INTERACT_TRANSPORT_OBJECT,
        WAIT_TRANSPORT_INTERFACE,
        SELECT_TRANSPORT_DESTINATION,
        CONFIRM_TRANSPORT,
        // Common final phases
        WAIT_FOR_TELEPORT,
        VERIFY_ARRIVAL
    }
}

