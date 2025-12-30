package com.rocinante.util;

import net.runelite.api.ObjectID;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Collections of related object IDs for flexible task definitions.
 * 
 * <p>Lists are ordered by priority (first = preferred) for tasks that need
 * to select a single object from many options.
 * 
 * <p>Example usage:
 * <pre>{@code
 * // Cook on any nearby fire
 * new UseItemOnObjectTask(ItemID.RAW_SHRIMPS, ObjectCollections.COOKING_FIRES);
 * }</pre>
 */
public final class ObjectCollections {

    private ObjectCollections() {
        // Utility class
    }

    // ========================================================================
    // Fires (for cooking)
    // ========================================================================

    /**
     * Fire objects that can be used for cooking.
     * Includes player-made fires and permanent fire objects.
     */
    public static final List<Integer> COOKING_FIRES = Collections.unmodifiableList(Arrays.asList(
            // Standard player-made fires
            ObjectID.FIRE,           // 3769 - most common
            ObjectID.FIRE_9735,      // Tutorial Island fire
            ObjectID.FIRE_3775,
            ObjectID.FIRE_4265,
            ObjectID.FIRE_4266,
            ObjectID.FIRE_5249,
            ObjectID.FIRE_5499,
            ObjectID.FIRE_5981,
            ObjectID.FIRE_10433,
            ObjectID.FIRE_10660,
            ObjectID.FIRE_12796,
            ObjectID.FIRE_13337,
            ObjectID.FIRE_13881,
            ObjectID.FIRE_14169,
            ObjectID.FIRE_15156,
            ObjectID.FIRE_20000,
            ObjectID.FIRE_20001,
            ObjectID.FIRE_21620,
            ObjectID.FIRE_23046,
            ObjectID.FIRE_25155,
            ObjectID.FIRE_25156,
            ObjectID.FIRE_25465,
            ObjectID.FIRE_26185,
            ObjectID.FIRE_26186,
            ObjectID.FIRE_26575,
            ObjectID.FIRE_26576,
            ObjectID.FIRE_26577,
            ObjectID.FIRE_26578,
            ObjectID.FIRE_28791,
            ObjectID.FIRE_30021,
            ObjectID.FIRE_31798,
            ObjectID.FIRE_32297,
            ObjectID.FIRE_33311,
            ObjectID.FIRE_34682,
            ObjectID.FIRE_35810,
            ObjectID.FIRE_35811,
            ObjectID.FIRE_35812,
            ObjectID.FIRE_35912,
            ObjectID.FIRE_35913,
            ObjectID.FIRE_38427,
            ObjectID.FIRE_40728,
            ObjectID.FIRE_41316,
            ObjectID.FIRE_43146,
            ObjectID.FIRE_43475,
            ObjectID.FIRE_44021,
            ObjectID.FIRE_44022,
            ObjectID.FIRE_44023,
            ObjectID.FIRE_44024,
            ObjectID.FIRE_44025,
            ObjectID.FIRE_44026,
            ObjectID.FIRE_44027,
            ObjectID.FIRE_44028,
            ObjectID.FIRE_45334,
            ObjectID.FIRE_49342,
            ObjectID.FIRE_51581,
            ObjectID.FIRE_51582,
            ObjectID.FIRE_51728,
            ObjectID.FIRE_51729,
            ObjectID.FIRE_56001,
            ObjectID.FIRE_56326,
            ObjectID.FIRE_56327,
            ObjectID.FIRE_56328,
            ObjectID.FIRE_56370,
            ObjectID.FIRE_56599,
            ObjectID.FIRE_56638,
            ObjectID.FIRE_56661,
            ObjectID.FIRE_56662,
            ObjectID.FIRE_56707,
            ObjectID.FIRE_56708,
            ObjectID.FIRE_57311,
            ObjectID.FIRE_60179,
            // Fire pits
            ObjectID.FIREPIT,
            ObjectID.FIREPIT_WITH_HOOK,
            ObjectID.FIREPIT_WITH_HOOK_13530,
            ObjectID.FIREPIT_WITH_POT,
            ObjectID.FIREPIT_WITH_POT_13532,
            ObjectID.FIRE_PIT,
            ObjectID.FIRE_PIT_33310,
            ObjectID.FIRE_PIT_50354
    ));

    // ========================================================================
    // Trees (for woodcutting)
    // ========================================================================

    /**
     * Regular tree objects that can be cut for normal logs.
     */
    public static final List<Integer> TREES = Collections.unmodifiableList(Arrays.asList(
            ObjectID.TREE,
            ObjectID.TREE_1277,
            ObjectID.TREE_1278,
            ObjectID.TREE_1279,
            ObjectID.TREE_1280,
            ObjectID.TREE_9730,   // Tutorial Island
            ObjectID.TREE_9731,
            ObjectID.TREE_9732,
            ObjectID.TREE_9733
    ));

    /**
     * Oak tree objects.
     */
    public static final List<Integer> OAK_TREES = Collections.unmodifiableList(Arrays.asList(
            ObjectID.OAK_TREE,
            ObjectID.OAK_TREE_10820,
            ObjectID.OAK_TREE_9734,
            ObjectID.OAK_TREE_8462,
            ObjectID.OAK_TREE_8463,
            ObjectID.OAK_TREE_8464,
            ObjectID.OAK_TREE_8465,
            ObjectID.OAK_TREE_8466,
            ObjectID.OAK_TREE_8467,
            ObjectID.OAK_TREE_20806,
            ObjectID.OAK_TREE_37969,
            ObjectID.OAK_TREE_37970,
            ObjectID.OAK_TREE_42395,
            ObjectID.OAK_TREE_42831,
            ObjectID.OAK_TREE_51772,
            ObjectID.OAK_TREE_55913,
            ObjectID.OAK_TREE_58539
    ));

    /**
     * Willow tree objects.
     */
    public static final List<Integer> WILLOW_TREES = Collections.unmodifiableList(Arrays.asList(
            ObjectID.WILLOW_TREE,
            ObjectID.WILLOW_TREE_10819,
            ObjectID.WILLOW_TREE_10829,
            ObjectID.WILLOW_TREE_10831,
            ObjectID.WILLOW_TREE_10833,
            ObjectID.WILLOW_TREE_8481,
            ObjectID.WILLOW_TREE_8482,
            ObjectID.WILLOW_TREE_8483,
            ObjectID.WILLOW_TREE_8484,
            ObjectID.WILLOW_TREE_8485,
            ObjectID.WILLOW_TREE_8486,
            ObjectID.WILLOW_TREE_8487,
            ObjectID.WILLOW_TREE_8488
    ));

    /**
     * Maple tree objects.
     */
    public static final List<Integer> MAPLE_TREES = Collections.unmodifiableList(Arrays.asList(
            ObjectID.MAPLE_TREE,
            ObjectID.MAPLE_TREE_4674,
            ObjectID.MAPLE_TREE_5126,
            ObjectID.MAPLE_TREE_8435,
            ObjectID.MAPLE_TREE_8436,
            ObjectID.MAPLE_TREE_8437,
            ObjectID.MAPLE_TREE_8438,
            ObjectID.MAPLE_TREE_8439,
            ObjectID.MAPLE_TREE_8440,
            ObjectID.MAPLE_TREE_8441,
            ObjectID.MAPLE_TREE_8442,
            ObjectID.MAPLE_TREE_8443,
            ObjectID.MAPLE_TREE_8444,
            ObjectID.MAPLE_TREE_10832,
            ObjectID.MAPLE_TREE_36681,
            ObjectID.MAPLE_TREE_36682
    ));

    /**
     * Yew tree objects.
     */
    public static final List<Integer> YEW_TREES = Collections.unmodifiableList(Arrays.asList(
            ObjectID.YEW_TREE,
            ObjectID.YEW_TREE_5121,
            ObjectID.YEW_TREE_8503,
            ObjectID.YEW_TREE_8504,
            ObjectID.YEW_TREE_8505,
            ObjectID.YEW_TREE_8506,
            ObjectID.YEW_TREE_8507,
            ObjectID.YEW_TREE_8508,
            ObjectID.YEW_TREE_8509,
            ObjectID.YEW_TREE_8510,
            ObjectID.YEW_TREE_8511,
            ObjectID.YEW_TREE_8512,
            ObjectID.YEW_TREE_8513,
            ObjectID.YEW_TREE_10822,
            ObjectID.YEW_TREE_36683,
            ObjectID.YEW_TREE_42391,
            ObjectID.YEW_TREE_42427
    ));

    /**
     * Magic tree objects.
     */
    public static final List<Integer> MAGIC_TREES = Collections.unmodifiableList(Arrays.asList(
            ObjectID.MAGIC_TREE,
            ObjectID.MAGIC_TREE_5127,
            ObjectID.MAGIC_TREE_8396,
            ObjectID.MAGIC_TREE_8397,
            ObjectID.MAGIC_TREE_8398,
            ObjectID.MAGIC_TREE_8399,
            ObjectID.MAGIC_TREE_8400,
            ObjectID.MAGIC_TREE_8401,
            ObjectID.MAGIC_TREE_8402,
            ObjectID.MAGIC_TREE_8403,
            ObjectID.MAGIC_TREE_8404,
            ObjectID.MAGIC_TREE_8405,
            ObjectID.MAGIC_TREE_10834,
            ObjectID.MAGIC_TREE_36685
    ));

    /**
     * Redwood tree objects.
     */
    public static final List<Integer> REDWOOD_TREES = Collections.unmodifiableList(Arrays.asList(
            ObjectID.REDWOOD_TREE,
            ObjectID.REDWOOD_TREE_29669,
            ObjectID.REDWOOD_TREE_29670,
            ObjectID.REDWOOD_TREE_29671
    ));

    // ========================================================================
    // Rocks (for mining)
    // ========================================================================

    /**
     * Copper rock objects.
     */
    public static final List<Integer> COPPER_ROCKS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.COPPER_ROCKS,
            ObjectID.COPPER_ROCKS_10943,
            ObjectID.COPPER_ROCKS_11161,
            ObjectID.COPPER_ROCKS_37944
    ));

    /**
     * Tin rock objects.
     */
    public static final List<Integer> TIN_ROCKS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.TIN_ROCKS,
            ObjectID.TIN_ROCKS_11360,
            ObjectID.TIN_ROCKS_11361,
            ObjectID.TIN_ROCKS_37945
    ));

    /**
     * Iron rock objects.
     */
    public static final List<Integer> IRON_ROCKS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.IRON_ROCKS,
            ObjectID.IRON_ROCKS_11365,
            ObjectID.IRON_ROCKS_36203,
            ObjectID.IRON_ROCKS_42833
    ));

    /**
     * Coal rock objects.
     */
    public static final List<Integer> COAL_ROCKS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.COAL_ROCKS,
            ObjectID.COAL_ROCKS_11366,
            ObjectID.COAL_ROCKS_11367,
            ObjectID.COAL_ROCKS_36204
    ));

    /**
     * Silver rock objects.
     */
    public static final List<Integer> SILVER_ROCKS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.SILVER_ROCKS,
            ObjectID.SILVER_ROCKS_11369,
            ObjectID.SILVER_ROCKS_36205
    ));

    /**
     * Gold rock objects.
     */
    public static final List<Integer> GOLD_ROCKS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.GOLD_ROCKS,
            ObjectID.GOLD_ROCKS_11371,
            ObjectID.GOLD_ROCKS_36206
    ));

    /**
     * Mithril rock objects.
     */
    public static final List<Integer> MITHRIL_ROCKS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.MITHRIL_ROCKS,
            ObjectID.MITHRIL_ROCKS_11373,
            ObjectID.MITHRIL_ROCKS_36207
    ));

    /**
     * Adamantite rock objects.
     */
    public static final List<Integer> ADAMANTITE_ROCKS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.ADAMANTITE_ROCKS,
            ObjectID.ADAMANTITE_ROCKS_11375,
            ObjectID.ADAMANTITE_ROCKS_36208
    ));

    /**
     * Runite rock objects.
     */
    public static final List<Integer> RUNITE_ROCKS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.RUNITE_ROCKS,
            ObjectID.RUNITE_ROCKS_11377,
            ObjectID.RUNITE_ROCKS_36209
    ));

    // ========================================================================
    // Furnaces (for smelting)
    // ========================================================================

    /**
     * Furnace objects for smelting ore.
     */
    public static final List<Integer> FURNACES = Collections.unmodifiableList(Arrays.asList(
            ObjectID.FURNACE,
            ObjectID.FURNACE_2966,
            ObjectID.FURNACE_3294,
            ObjectID.FURNACE_4304,
            ObjectID.FURNACE_6189,
            ObjectID.FURNACE_6190,
            ObjectID.FURNACE_10082,  // Tutorial Island
            ObjectID.FURNACE_11009,
            ObjectID.FURNACE_11010,
            ObjectID.FURNACE_12100,
            ObjectID.FURNACE_12809,
            ObjectID.FURNACE_16469,
            ObjectID.FURNACE_16657,
            ObjectID.FURNACE_18525,
            ObjectID.FURNACE_18526,
            ObjectID.FURNACE_21879,
            ObjectID.FURNACE_22721,
            ObjectID.FURNACE_24009,
            ObjectID.FURNACE_26300,
            ObjectID.FURNACE_28565,
            ObjectID.FURNACE_30157,
            ObjectID.FURNACE_30158,
            ObjectID.FURNACE_33502,
            ObjectID.FURNACE_33503,
            ObjectID.FURNACE_33504,
            ObjectID.FURNACE_36195,
            ObjectID.FURNACE_36555,
            ObjectID.FURNACE_37947,
            ObjectID.FURNACE_39241,
            ObjectID.FURNACE_40949,
            ObjectID.FURNACE_42824,
            ObjectID.FURNACE_43891,
            ObjectID.FURNACE_43892,
            ObjectID.FURNACE_43893,
            ObjectID.FURNACE_43894,
            ObjectID.FURNACE_43895,
            ObjectID.FURNACE_47927,
            ObjectID.FURNACE_48171,
            ObjectID.FURNACE_50698,
            ObjectID.FURNACE_51509,
            ObjectID.FURNACE_56366,
            ObjectID.FURNACE_57788,
            ObjectID.FURNACE_60145
    ));

    // ========================================================================
    // Anvils (for smithing)
    // ========================================================================

    /**
     * Anvil objects for smithing.
     */
    public static final List<Integer> ANVILS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.ANVIL,
            ObjectID.ANVIL_2097,
            ObjectID.ANVIL_4306,
            ObjectID.ANVIL_6150,
            ObjectID.ANVIL_22725,
            ObjectID.ANVIL_28563,
            ObjectID.ANVIL_31623,
            ObjectID.ANVIL_32215,
            ObjectID.ANVIL_32216,
            ObjectID.ANVIL_39242,
            ObjectID.ANVIL_40725,
            ObjectID.ANVIL_42825,
            ObjectID.ANVIL_42860,
            ObjectID.ANVIL_44911,
            ObjectID.ANVIL_51501,
            ObjectID.ANVIL_51502,
            ObjectID.ANVIL_56368,
            ObjectID.ANVIL_58657
    ));

    // ========================================================================
    // Banks
    // ========================================================================

    /**
     * Bank booth objects.
     */
    public static final List<Integer> BANK_BOOTHS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.BANK_BOOTH,
            ObjectID.BANK_BOOTH_10083,
            ObjectID.BANK_BOOTH_10355,
            ObjectID.BANK_BOOTH_10357,
            ObjectID.BANK_BOOTH_10517,
            ObjectID.BANK_BOOTH_10527,
            ObjectID.BANK_BOOTH_10583,
            ObjectID.BANK_BOOTH_10584,
            ObjectID.BANK_BOOTH_8139,
            ObjectID.BANK_BOOTH_8140,
            ObjectID.BANK_BOOTH_11338,
            ObjectID.BANK_BOOTH_12798,
            ObjectID.BANK_BOOTH_12799,
            ObjectID.BANK_BOOTH_12800,
            ObjectID.BANK_BOOTH_12801,
            ObjectID.BANK_BOOTH_14367,
            ObjectID.BANK_BOOTH_14368,
            ObjectID.BANK_BOOTH_16642,
            ObjectID.BANK_BOOTH_16700,
            ObjectID.BANK_BOOTH_18491,
            ObjectID.BANK_BOOTH_20325,
            ObjectID.BANK_BOOTH_20326,
            ObjectID.BANK_BOOTH_20327,
            ObjectID.BANK_BOOTH_20328,
            ObjectID.BANK_BOOTH_22819,
            ObjectID.BANK_BOOTH_24101,
            ObjectID.BANK_BOOTH_24347,
            ObjectID.BANK_BOOTH_25808,
            ObjectID.BANK_BOOTH_27254,
            ObjectID.BANK_BOOTH_27260,
            ObjectID.BANK_BOOTH_27263,
            ObjectID.BANK_BOOTH_27265,
            ObjectID.BANK_BOOTH_27267,
            ObjectID.BANK_BOOTH_27292,
            ObjectID.BANK_BOOTH_27718,
            ObjectID.BANK_BOOTH_27719,
            ObjectID.BANK_BOOTH_27720,
            ObjectID.BANK_BOOTH_27721,
            ObjectID.BANK_BOOTH_28429,
            ObjectID.BANK_BOOTH_28430,
            ObjectID.BANK_BOOTH_28431,
            ObjectID.BANK_BOOTH_28432,
            ObjectID.BANK_BOOTH_28433,
            ObjectID.BANK_BOOTH_28546,
            ObjectID.BANK_BOOTH_28547,
            ObjectID.BANK_BOOTH_28548,
            ObjectID.BANK_BOOTH_28549,
            ObjectID.BANK_BOOTH_32666,
            ObjectID.BANK_BOOTH_36559,
            ObjectID.BANK_BOOTH_37959,
            ObjectID.BANK_BOOTH_39238,
            ObjectID.BANK_BOOTH_42837,
            ObjectID.BANK_BOOTH_50061,
            ObjectID.BANK_BOOTH_50901,
            ObjectID.BANK_BOOTH_57330,
            ObjectID.BANK_BOOTH_57892
    ));

    /**
     * Bank chest objects.
     */
    public static final List<Integer> BANK_CHESTS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.BANK_CHEST,
            ObjectID.BANK_CHEST_4483,
            ObjectID.BANK_CHEST_10562,
            ObjectID.BANK_CHEST_14382,
            ObjectID.BANK_CHEST_14886,
            ObjectID.BANK_CHEST_16695,
            ObjectID.BANK_CHEST_16696,
            ObjectID.BANK_CHEST_19051,
            ObjectID.BANK_CHEST_21301,
            ObjectID.BANK_CHEST_26707,
            ObjectID.BANK_CHEST_26711,
            ObjectID.BANK_CHEST_28594,
            ObjectID.BANK_CHEST_28595,
            ObjectID.BANK_CHEST_28816,
            ObjectID.BANK_CHEST_28861,
            ObjectID.BANK_CHEST_29321,
            ObjectID.BANK_CHEST_30087,
            ObjectID.BANK_CHEST_30267,
            ObjectID.BANK_CHEST_30796,
            ObjectID.BANK_CHEST_30926,
            ObjectID.BANK_CHEST_30989,
            ObjectID.BANK_CHEST_34343,
            ObjectID.BANK_CHEST_40473,
            ObjectID.BANK_CHEST_41315,
            ObjectID.BANK_CHEST_41493,
            ObjectID.BANK_CHEST_43697,
            ObjectID.BANK_CHEST_44630,
            ObjectID.BANK_CHEST_47345,
            ObjectID.BANK_CHEST_47419,
            ObjectID.BANK_CHEST_47420,
            ObjectID.BANK_CHEST_50748,
            ObjectID.BANK_CHEST_53014,
            ObjectID.BANK_CHEST_53015,
            ObjectID.BANK_CHEST_53260,
            ObjectID.BANK_CHEST_54933,
            ObjectID.BANK_CHEST_54934,
            ObjectID.BANK_CHEST_57958,
            ObjectID.BANK_CHEST_57959,
            ObjectID.BANK_CHEST_58128,
            ObjectID.BANK_CHEST_58129,
            ObjectID.BANK_CHEST_58663
    ));

    // ========================================================================
    // Ranges (for cooking)
    // ========================================================================

    /**
     * Cooking range objects.
     * Note: Ranges are preferred over fires for cooking as they have lower burn rates.
     */
    public static final List<Integer> COOKING_RANGES = Collections.unmodifiableList(Arrays.asList(
            ObjectID.RANGE,
            ObjectID.RANGE_7183,
            ObjectID.RANGE_7184,
            ObjectID.RANGE_9682,      // Tutorial Island range
            ObjectID.RANGE_9736,      // Tutorial Island range
            ObjectID.RANGE_12102,
            ObjectID.RANGE_12611,
            ObjectID.RANGE_21792,
            ObjectID.RANGE_22713,
            ObjectID.RANGE_22714,
            ObjectID.RANGE_25730,
            ObjectID.RANGE_26181,
            ObjectID.RANGE_26182,
            ObjectID.RANGE_26183,
            ObjectID.RANGE_26184,
            ObjectID.RANGE_27516,
            ObjectID.RANGE_27517,
            ObjectID.RANGE_27724,
            ObjectID.RANGE_31631,
            ObjectID.RANGE_35980,
            ObjectID.RANGE_36077,
            ObjectID.RANGE_36699,
            ObjectID.RANGE_37728,
            ObjectID.RANGE_39391,
            ObjectID.RANGE_39458,
            ObjectID.RANGE_39490,
            ObjectID.RANGE_40068,
            ObjectID.RANGE_40149,
            ObjectID.RANGE_47392,
            ObjectID.RANGE_47925,
            ObjectID.RANGE_48169,
            ObjectID.RANGE_50563,
            ObjectID.RANGE_54049,
            ObjectID.RANGE_59682
    ));

    // ========================================================================
    // Poll Booths
    // ========================================================================

    /**
     * Poll booth objects for voting.
     * Various styles exist throughout the game (blue, brown, red, green, etc.)
     */
    public static final List<Integer> POLL_BOOTHS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.POLL_BOOTH,            // 26492 - Blue standard
            ObjectID.POLL_BOOTH_26796,      // Blue highlight
            ObjectID.POLL_BOOTH_26797,      // Brown standard
            ObjectID.POLL_BOOTH_26798,      // Brown highlight
            ObjectID.POLL_BOOTH_26799,      // Red standard
            ObjectID.POLL_BOOTH_26800,      // Red highlight
            ObjectID.POLL_BOOTH_26801,      // Green standard
            ObjectID.POLL_BOOTH_26802,      // Green highlight
            ObjectID.POLL_BOOTH_26803,      // Swamp standard
            ObjectID.POLL_BOOTH_26804,      // Swamp highlight
            ObjectID.POLL_BOOTH_26805,      // Rotten standard
            ObjectID.POLL_BOOTH_26806,      // Rotten highlight
            ObjectID.POLL_BOOTH_26807,      // Grey standard
            ObjectID.POLL_BOOTH_26808,      // Grey highlight
            ObjectID.POLL_BOOTH_26809,      // Grey frame standard
            ObjectID.POLL_BOOTH_26810,      // Grey frame highlight
            ObjectID.POLL_BOOTH_26811,      // TzHaar standard
            ObjectID.POLL_BOOTH_26812,      // TzHaar highlight
            // Non-standard variants (only ID available, no named constant)
            26813,                          // Brown variant
            26814,                          // Red variant
            26815,                          // Green variant (Tutorial Island)
            26816,                          // Swamp variant
            26817,                          // Rotten variant
            26818,                          // Grey variant
            26819,                          // Grey frame variant
            26820,                          // TzHaar variant
            ObjectID.POLL_BOOTH_32546,      // Sanguine standard
            ObjectID.POLL_BOOTH_32547,      // Sanguine highlight
            ObjectID.POLL_BOOTH_33481,      // Brimstone standard
            ObjectID.POLL_BOOTH_33482,      // Brimstone highlight
            ObjectID.POLL_BOOTH_50047       // Green noop variant
    ));

    // ========================================================================
    // Precise Click Objects
    // ========================================================================

    /**
     * Objects that require precise clicking due to small hitboxes or proximity to other clickables.
     * These objects use a tighter Gaussian distribution for click point calculation.
     */
    public static final List<Integer> PRECISE_CLICK_OBJECTS = Collections.unmodifiableList(Arrays.asList(
            // Tutorial Island gates
            ObjectID.GATE_9470,             // Survival Expert gate
            ObjectID.GATE_9717,             // Mining to combat gate (left)
            ObjectID.GATE_9718,             // Mining to combat gate (right)
            ObjectID.GATE_9719,             // Tutorial gate
            ObjectID.GATE_9720,             // Tutorial gate
            
            // Common gates with small hitboxes
            ObjectID.GATE_1558,
            ObjectID.GATE_1559,
            ObjectID.GATE_1560,
            ObjectID.GATE_1561,
            ObjectID.GATE_1562,
            ObjectID.GATE_1563,
            ObjectID.GATE_1564,
            ObjectID.GATE_1567,
            ObjectID.GATE_1568,
            ObjectID.GATE_2050,
            ObjectID.GATE_2051,
            ObjectID.GATE_2058,
            
            // Tutorial Island ladder
            ObjectID.LADDER_9726
    ));

    /**
     * Check if an object ID requires precise clicking.
     * 
     * @param objectId the object ID to check
     * @return true if the object needs precise clicking
     */
    public static boolean requiresPreciseClick(int objectId) {
        return PRECISE_CLICK_OBJECTS.contains(objectId);
    }

    // ========================================================================
    // Altars (for prayer)
    // ========================================================================

    /**
     * Altar objects for restoring prayer.
     */
    public static final List<Integer> ALTARS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.ALTAR,
            ObjectID.ALTAR_2640,
            ObjectID.ALTAR_4008,
            ObjectID.ALTAR_6552,
            ObjectID.ALTAR_7814,
            ObjectID.ALTAR_8749,
            ObjectID.ALTAR_10639,
            ObjectID.ALTAR_10640,
            ObjectID.ALTAR_13179,
            ObjectID.ALTAR_13180,
            ObjectID.ALTAR_13181,
            ObjectID.ALTAR_13182,
            ObjectID.ALTAR_13183,
            ObjectID.ALTAR_13184,
            ObjectID.ALTAR_13185,
            ObjectID.ALTAR_13186,
            ObjectID.ALTAR_13187,
            ObjectID.ALTAR_13188,
            ObjectID.ALTAR_13189,
            ObjectID.ALTAR_13190,
            ObjectID.ALTAR_13191,
            ObjectID.ALTAR_13192
    ));

    // ========================================================================
    // Spinning Wheels (for crafting)
    // ========================================================================

    /**
     * Spinning wheel objects for crafting.
     */
    public static final List<Integer> SPINNING_WHEELS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.SPINNING_WHEEL,
            ObjectID.SPINNING_WHEEL_8748,
            ObjectID.SPINNING_WHEEL_14889,
            ObjectID.SPINNING_WHEEL_20365,
            ObjectID.SPINNING_WHEEL_21304,
            ObjectID.SPINNING_WHEEL_25824,
            ObjectID.SPINNING_WHEEL_26143,
            ObjectID.SPINNING_WHEEL_30934,
            ObjectID.SPINNING_WHEEL_40735,
            ObjectID.SPINNING_WHEEL_55330,
            ObjectID.SPINNING_WHEEL_56964,
            ObjectID.SPINNING_WHEEL_58649
    ));

    // ========================================================================
    // Water Sources
    // ========================================================================

    /**
     * Water source objects (wells, fountains) for filling containers.
     */
    public static final List<Integer> WATER_SOURCES = Collections.unmodifiableList(Arrays.asList(
            // Fountains
            ObjectID.FOUNTAIN,
            ObjectID.FOUNTAIN_879,
            ObjectID.FOUNTAIN_880,
            ObjectID.FOUNTAIN_2864,
            ObjectID.FOUNTAIN_3641,
            ObjectID.FOUNTAIN_5125,
            ObjectID.FOUNTAIN_6232,
            ObjectID.FOUNTAIN_7143,
            ObjectID.FOUNTAIN_10436,
            ObjectID.SMALL_FOUNTAIN,
            ObjectID.LARGE_FOUNTAIN,
            ObjectID.ORNAMENTAL_FOUNTAIN,
            // Wells
            ObjectID.WELL,
            ObjectID.WELL_884,
            ObjectID.WELL_3264,
            ObjectID.WELL_3305,
            ObjectID.WELL_3359,
            ObjectID.WELL_3485,
            ObjectID.WELL_3646,
            ObjectID.WELL_4004,
            ObjectID.WELL_4005,
            ObjectID.WELL_6097,
            ObjectID.WELL_6249,
            ObjectID.WELL_6549,
            ObjectID.WELL_8747,
            ObjectID.WELL_8927
    ));

    // ========================================================================
    // Thieving Stalls
    // ========================================================================

    /**
     * Bakery stall objects (level 5 Thieving).
     * 16 XP per steal. Gives cake, bread, or chocolate slice.
     * 2.5 second respawn time.
     */
    public static final List<Integer> BAKERY_STALLS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.BAKERY_STALL,
            ObjectID.BAKERY_STALL_6163,
            ObjectID.BAKERY_STALL_6569,
            ObjectID.BAKERS_STALL,
            ObjectID.BAKERS_STALL_11730,
            ObjectID.BAKERY_STALL_44031
    ));

    /**
     * Silk stall objects (level 20 Thieving).
     * 24 XP per steal. Gives silk.
     * 5 second respawn time.
     */
    public static final List<Integer> SILK_STALLS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.SILK_STALL,
            ObjectID.SILK_STALL_6568,
            ObjectID.SILK_STALL_11729,
            ObjectID.SILK_STALL_20344,
            ObjectID.SILK_STALL_36569,
            ObjectID.SILK_STALL_41755,
            ObjectID.SILK_STALL_51933,
            ObjectID.SILK_STALL_58101
    ));

    /**
     * Fur stall objects (level 35 Thieving).
     * 36 XP per steal. Gives grey wolf fur.
     * 10 second respawn time.
     */
    public static final List<Integer> FUR_STALLS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.FUR_STALL,
            ObjectID.FUR_STALL_4278,
            ObjectID.FUR_STALL_6571,
            ObjectID.FUR_STALL_11732
    ));

    /**
     * Silver stall objects (level 50 Thieving).
     * 54 XP per steal. Gives silver ore.
     * 30 second respawn time.
     */
    public static final List<Integer> SILVER_STALLS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.SILVER_STALL,
            ObjectID.SILVER_STALL_6164
    ));

    /**
     * Spice stall objects (level 65 Thieving).
     * 81 XP per steal. Gives spice.
     * 80 second respawn time.
     */
    public static final List<Integer> SPICE_STALLS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.SPICE_STALL,
            ObjectID.SPICE_STALL_6572,
            ObjectID.SPICE_STALL_11733
    ));

    /**
     * Gem stall objects (level 75 Thieving).
     * 160 XP per steal. Gives sapphire, emerald, ruby, or diamond.
     * 180 second respawn time.
     */
    public static final List<Integer> GEM_STALLS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.GEM_STALL,
            ObjectID.GEM_STALL_6162,
            ObjectID.GEM_STALL_6570,
            ObjectID.GEM_STALL_11731
    ));

    /**
     * Tea stall objects (level 5 Thieving).
     * 16 XP per steal. Gives cup of tea.
     * 7 second respawn time.
     */
    public static final List<Integer> TEA_STALLS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.TEA_STALL,
            ObjectID.TEA_STALL_6574
    ));

    /**
     * Fish stall objects (level 42 Thieving, Piscarilius only).
     * 42 XP per steal. Gives raw fish.
     */
    public static final List<Integer> FISH_STALLS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.FISH_STALL,
            ObjectID.FISH_STALL_4705,
            ObjectID.FISH_STALL_4707
    ));

    /**
     * Fruit stall objects in Hosidius (level 25 Thieving).
     * 28.5 XP per steal. Gives various fruits.
     * 2.5 second respawn time.
     */
    public static final List<Integer> FRUIT_STALLS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.FRUIT_STALL,
            ObjectID.FRUIT_STALL_28823
    ));

    /**
     * Seed stall objects in Draynor Village (level 27 Thieving).
     * 10 XP per steal. Gives various seeds.
     */
    public static final List<Integer> SEED_STALLS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.SEED_STALL,
            ObjectID.SEED_STALL_7053
    ));

    /**
     * Vegetable stall objects (level 2 Thieving).
     * 10 XP per steal. Gives various vegetables.
     */
    public static final List<Integer> VEG_STALLS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.VEG_STALL,
            ObjectID.VEG_STALL_4708,
            ObjectID.VEGETABLE_STALL
    ));

    /**
     * Crafting stall objects in Ape Atoll/Keldagrim (level 5 Thieving).
     * 16 XP per steal.
     */
    public static final List<Integer> CRAFTING_STALLS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.CRAFTING_STALL,
            ObjectID.CRAFTING_STALL_6166
    ));

    /**
     * Food stall objects in Ape Atoll (level 5 Thieving).
     * 16 XP per steal.
     */
    public static final List<Integer> FOOD_STALLS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.FOOD_STALL
    ));

    /**
     * Magic stall objects in Ape Atoll (level 65 Thieving).
     * 100 XP per steal.
     */
    public static final List<Integer> MAGIC_STALLS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.MAGIC_STALL
    ));

    /**
     * Scimitar stall objects in Ape Atoll (level 65 Thieving).
     * 100 XP per steal.
     */
    public static final List<Integer> SCIMITAR_STALLS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.SCIMITAR_STALL
    ));

    /**
     * Herb stall objects in Kourend (level 25 Thieving).
     * 10 XP per steal. Gives grimy herbs.
     */
    public static final List<Integer> HERB_STALLS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.HERB_STALL
    ));

    // ========================================================================
    // Prayer Altars (for bone offering)
    // ========================================================================

    /**
     * Chaos Temple altar in the Wilderness (level 38 Wilderness).
     * 50% chance to NOT consume the bone when offering.
     * Special handling required for the bone save mechanic.
     */
    public static final List<Integer> CHAOS_ALTARS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.CHAOS_ALTAR,
            ObjectID.CHAOS_ALTAR_411,
            ObjectID.CHAOS_ALTAR_412,
            ObjectID.CHAOS_ALTAR_26258
    ));

    /**
     * POH altar objects in player-owned houses.
     * Requires 2 lit burners for maximum 350% XP bonus.
     * Note: Only includes altars with verified RuneLite constants.
     */
    public static final List<Integer> POH_GILDED_ALTARS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.ALTAR_13197,
            ObjectID.ALTAR_13198,
            ObjectID.ALTAR_13199
    ));

    /**
     * Ectofuntus for prayer training (requires ghost speak amulet).
     * Requires grinding bones and collecting slime.
     */
    public static final List<Integer> ECTOFUNTUS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.ECTOFUNTUS
    ));

    /**
     * All altars suitable for offering bones (excludes regular prayer restore altars).
     * Includes POH altars and Chaos Altar variants.
     */
    public static final List<Integer> BONE_OFFERING_ALTARS = Collections.unmodifiableList(Arrays.asList(
            ObjectID.ALTAR_13199,
            ObjectID.ALTAR_13198,
            ObjectID.ALTAR_13197,
            ObjectID.CHAOS_ALTAR,
            ObjectID.CHAOS_ALTAR_411,
            ObjectID.CHAOS_ALTAR_412,
            ObjectID.CHAOS_ALTAR_26258
    ));
}
