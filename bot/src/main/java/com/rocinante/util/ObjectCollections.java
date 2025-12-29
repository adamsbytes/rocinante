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
}
