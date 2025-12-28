package com.rocinante.util;

import net.runelite.api.NpcID;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Collections of related NPC IDs for flexible task definitions.
 * 
 * <p>Lists are ordered by priority (first = preferred) for tasks that need
 * to select a single NPC from many options.
 * 
 * <p>Example usage:
 * <pre>{@code
 * // Talk to any banker
 * new NpcQuestStep(NpcCollections.BANKERS, "Bank with any banker");
 * }</pre>
 */
public final class NpcCollections {

    private NpcCollections() {
        // Utility class
    }

    // ========================================================================
    // Bankers
    // ========================================================================

    /**
     * Banker NPCs that can be talked to for banking.
     */
    public static final List<Integer> BANKERS = Collections.unmodifiableList(Arrays.asList(
            NpcID.BANKER,
            NpcID.BANKER_1479,
            NpcID.BANKER_1480,
            NpcID.BANKER_1613,
            NpcID.BANKER_1618,
            NpcID.BANKER_1633,
            NpcID.BANKER_1634,
            NpcID.BANKER_2117,
            NpcID.BANKER_2118,
            NpcID.BANKER_2119,
            NpcID.BANKER_2292,
            NpcID.BANKER_2293,
            NpcID.BANKER_2368,
            NpcID.BANKER_2369,
            NpcID.BANKER_2633,
            NpcID.BANKER_2897,
            NpcID.BANKER_2898,
            NpcID.BANKER_3089,
            NpcID.BANKER_3090,
            NpcID.BANKER_3091,
            NpcID.BANKER_3092,
            NpcID.BANKER_3093,
            NpcID.BANKER_3094,
            NpcID.BANKER_3318,
            NpcID.BANKER_3887,
            NpcID.BANKER_3888,
            NpcID.BANKER_4054,
            NpcID.BANKER_4055,
            NpcID.BANKER_6859,
            NpcID.BANKER_6860,
            NpcID.BANKER_6861,
            NpcID.BANKER_6862,
            NpcID.BANKER_6863,
            NpcID.BANKER_6864,
            NpcID.BANKER_6939,
            NpcID.BANKER_6940,
            NpcID.BANKER_6941,
            NpcID.BANKER_6942,
            NpcID.BANKER_6969,
            NpcID.BANKER_6970,
            NpcID.BANKER_7057,
            NpcID.BANKER_7058,
            NpcID.BANKER_7059,
            NpcID.BANKER_7060,
            NpcID.BANKER_7077,
            NpcID.BANKER_7078,
            NpcID.BANKER_7079,
            NpcID.BANKER_7080,
            NpcID.BANKER_7081,
            NpcID.BANKER_7082,
            NpcID.BANKER_8321,
            NpcID.BANKER_8322,
            NpcID.BANKER_8589,
            NpcID.BANKER_8590,
            NpcID.BANKER_8666,
            NpcID.BANKER_9127,
            NpcID.BANKER_9128,
            NpcID.BANKER_9129,
            NpcID.BANKER_9130,
            NpcID.BANKER_9131,
            NpcID.BANKER_9132,
            NpcID.BANKER_9484,
            NpcID.BANKER_9718,
            NpcID.BANKER_9719,
            NpcID.BANKER_10389,
            NpcID.BANKER_10734,
            NpcID.BANKER_10735,
            NpcID.BANKER_10736,
            NpcID.BANKER_10737,
            NpcID.BANKER_13044,
            NpcID.BANKER_13045,
            NpcID.BANKER_13046,
            NpcID.BANKER_13047,
            NpcID.BANKER_13129,
            NpcID.BANKER_13212,
            NpcID.BANKER_13213,
            NpcID.BANKER_13214,
            NpcID.BANKER_13215,
            NpcID.BANKER_13216,
            NpcID.BANKER_13217,
            NpcID.BANKER_13218,
            NpcID.BANKER_13219,
            NpcID.BANKER_13220,
            NpcID.BANKER_13221,
            NpcID.BANKER_13222,
            NpcID.BANKER_13223,
            NpcID.BANKER_13224,
            NpcID.BANKER_13225,
            NpcID.BANKER_13226,
            NpcID.BANKER_13227,
            NpcID.BANKER_13931,
            NpcID.BANKER_13932,
            NpcID.BANKER_14671,
            NpcID.BANKER_14672,
            NpcID.BANKER_14673,
            NpcID.BANKER_14674,
            NpcID.BANKER_14778,
            NpcID.BANKER_14847,
            NpcID.BANKER_15496
    ));

    // ========================================================================
    // Shop Keepers
    // ========================================================================

    /**
     * General store shop keeper NPCs.
     */
    public static final List<Integer> SHOP_KEEPERS = Collections.unmodifiableList(Arrays.asList(
            NpcID.SHOP_KEEPER,
            NpcID.SHOP_KEEPER_2815,
            NpcID.SHOP_KEEPER_2817,
            NpcID.SHOP_KEEPER_2819,
            NpcID.SHOP_KEEPER_2821,
            NpcID.SHOP_KEEPER_2823,
            NpcID.SHOP_KEEPER_2825,
            NpcID.SHOP_KEEPER_2884,
            NpcID.SHOP_KEEPER_2888,
            NpcID.SHOP_KEEPER_2894,
            NpcID.SHOP_KEEPER_7769,
            NpcID.SHOP_KEEPER_7913
    ));

    // ========================================================================
    // Fishing NPCs
    // ========================================================================

    /**
     * Fishing spot NPCs for net/bait fishing.
     */
    public static final List<Integer> FISHING_SPOTS_NET_BAIT = Collections.unmodifiableList(Arrays.asList(
            NpcID.FISHING_SPOT,
            NpcID.FISHING_SPOT_1518,
            NpcID.FISHING_SPOT_3913,
            NpcID.FISHING_SPOT_7155,
            NpcID.FISHING_SPOT_7469,
            NpcID.FISHING_SPOT_7947,
            NpcID.FISHING_SPOT_8525,
            NpcID.FISHING_SPOT_8526
    ));

    /**
     * Fishing spot NPCs for lure/bait (fly fishing).
     */
    public static final List<Integer> FISHING_SPOTS_LURE_BAIT = Collections.unmodifiableList(Arrays.asList(
            NpcID.ROD_FISHING_SPOT,
            NpcID.ROD_FISHING_SPOT_1506,
            NpcID.ROD_FISHING_SPOT_1507,
            NpcID.ROD_FISHING_SPOT_1508,
            NpcID.ROD_FISHING_SPOT_1509,
            NpcID.ROD_FISHING_SPOT_1515,
            NpcID.ROD_FISHING_SPOT_1516,
            NpcID.ROD_FISHING_SPOT_1526,
            NpcID.ROD_FISHING_SPOT_1527,
            NpcID.ROD_FISHING_SPOT_7676
    ));

    /**
     * Fishing spot NPCs for cage/harpoon fishing.
     */
    public static final List<Integer> FISHING_SPOTS_CAGE_HARPOON = Collections.unmodifiableList(Arrays.asList(
            NpcID.FISHING_SPOT_1519,
            NpcID.FISHING_SPOT_1522,
            NpcID.FISHING_SPOT_3914,
            NpcID.FISHING_SPOT_4712,
            NpcID.FISHING_SPOT_5233,
            NpcID.FISHING_SPOT_5234,
            NpcID.FISHING_SPOT_7470
    ));

    // ========================================================================
    // Giant Rats (for combat training)
    // ========================================================================

    /**
     * Giant rat NPCs commonly found in low-level areas.
     */
    public static final List<Integer> GIANT_RATS = Collections.unmodifiableList(Arrays.asList(
            NpcID.GIANT_RAT,
            NpcID.GIANT_RAT_2511,
            NpcID.GIANT_RAT_2512,
            NpcID.GIANT_RAT_2856,
            NpcID.GIANT_RAT_2857,
            NpcID.GIANT_RAT_2858,
            NpcID.GIANT_RAT_2859,
            NpcID.GIANT_RAT_2860,
            NpcID.GIANT_RAT_2861,
            NpcID.GIANT_RAT_2862,
            NpcID.GIANT_RAT_2863,
            NpcID.GIANT_RAT_2864,
            NpcID.GIANT_RAT_3313,   // Tutorial Island
            NpcID.GIANT_RAT_3314,
            NpcID.GIANT_RAT_3315,
            NpcID.GIANT_RAT_7223,
            NpcID.GIANT_RAT_9483
    ));

    // ========================================================================
    // Cows (for combat training / hides)
    // ========================================================================

    /**
     * Cow NPCs.
     */
    public static final List<Integer> COWS = Collections.unmodifiableList(Arrays.asList(
            NpcID.COW,
            NpcID.COW_2791,
            NpcID.COW_2793,
            NpcID.COW_2795,
            NpcID.COW_5842,
            NpcID.COW_6401,
            NpcID.COW_10598
    ));

    // ========================================================================
    // Chickens (for combat training / feathers)
    // ========================================================================

    /**
     * Chicken NPCs.
     */
    public static final List<Integer> CHICKENS = Collections.unmodifiableList(Arrays.asList(
            NpcID.CHICKEN,
            NpcID.CHICKEN_1174,
            NpcID.CHICKEN_2804,
            NpcID.CHICKEN_2805,
            NpcID.CHICKEN_2806,
            NpcID.CHICKEN_3316,
            NpcID.CHICKEN_3661,
            NpcID.CHICKEN_3662,
            NpcID.CHICKEN_9488,
            NpcID.CHICKEN_10494,
            NpcID.CHICKEN_10495,
            NpcID.CHICKEN_10496,
            NpcID.CHICKEN_10497,
            NpcID.CHICKEN_10498,
            NpcID.CHICKEN_10499,
            NpcID.CHICKEN_10556
    ));

    // ========================================================================
    // Goblins (for combat training)
    // ========================================================================

    /**
     * Goblin NPCs.
     */
    public static final List<Integer> GOBLINS = Collections.unmodifiableList(Arrays.asList(
            NpcID.GOBLIN,
            NpcID.GOBLIN_656,
            NpcID.GOBLIN_657,
            NpcID.GOBLIN_658,
            NpcID.GOBLIN_659,
            NpcID.GOBLIN_660,
            NpcID.GOBLIN_661,
            NpcID.GOBLIN_662,
            NpcID.GOBLIN_663,
            NpcID.GOBLIN_664,
            NpcID.GOBLIN_665,
            NpcID.GOBLIN_666,
            NpcID.GOBLIN_667,
            NpcID.GOBLIN_668,
            NpcID.GOBLIN_674,
            NpcID.GOBLIN_677,
            NpcID.GOBLIN_678,
            NpcID.GOBLIN_2245,
            NpcID.GOBLIN_2246,
            NpcID.GOBLIN_2247,
            NpcID.GOBLIN_2248,
            NpcID.GOBLIN_2249,
            NpcID.GOBLIN_2484,
            NpcID.GOBLIN_2485,
            NpcID.GOBLIN_2486,
            NpcID.GOBLIN_2487,
            NpcID.GOBLIN_2488,
            NpcID.GOBLIN_2489,
            NpcID.GOBLIN_3028,
            NpcID.GOBLIN_3029,
            NpcID.GOBLIN_3030,
            NpcID.GOBLIN_3031,
            NpcID.GOBLIN_3032,
            NpcID.GOBLIN_3033,
            NpcID.GOBLIN_3034,
            NpcID.GOBLIN_3035,
            NpcID.GOBLIN_3036,
            NpcID.GOBLIN_3037,
            NpcID.GOBLIN_3038,
            NpcID.GOBLIN_3039,
            NpcID.GOBLIN_3040,
            NpcID.GOBLIN_3041,
            NpcID.GOBLIN_3042,
            NpcID.GOBLIN_3043,
            NpcID.GOBLIN_3044,
            NpcID.GOBLIN_3045,
            NpcID.GOBLIN_3046,
            NpcID.GOBLIN_3047,
            NpcID.GOBLIN_3048,
            NpcID.GOBLIN_3051,
            NpcID.GOBLIN_3052,
            NpcID.GOBLIN_3053,
            NpcID.GOBLIN_3054,
            NpcID.GOBLIN_3073,
            NpcID.GOBLIN_3074,
            NpcID.GOBLIN_3075,
            NpcID.GOBLIN_3076,
            NpcID.GOBLIN_4902,
            NpcID.GOBLIN_4903,
            NpcID.GOBLIN_4904,
            NpcID.GOBLIN_4905,
            NpcID.GOBLIN_4906,
            NpcID.GOBLIN_5152,
            NpcID.GOBLIN_5153,
            NpcID.GOBLIN_5154,
            NpcID.GOBLIN_5192,
            NpcID.GOBLIN_5193,
            NpcID.GOBLIN_5195,
            NpcID.GOBLIN_5196,
            NpcID.GOBLIN_5197,
            NpcID.GOBLIN_5198,
            NpcID.GOBLIN_5199,
            NpcID.GOBLIN_5200,
            NpcID.GOBLIN_5201,
            NpcID.GOBLIN_5202,
            NpcID.GOBLIN_5203,
            NpcID.GOBLIN_5204,
            NpcID.GOBLIN_5205,
            NpcID.GOBLIN_5206,
            NpcID.GOBLIN_5207,
            NpcID.GOBLIN_5208,
            NpcID.GOBLIN_5376,
            NpcID.GOBLIN_5377,
            NpcID.GOBLIN_5508,
            NpcID.GOBLIN_5509,
            NpcID.GOBLIN_10566,
            NpcID.GOBLIN_10567
    ));
}
