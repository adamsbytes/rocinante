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

    // ========================================================================
    // Slayer Creatures
    // ========================================================================

    /**
     * Hellhound NPCs.
     */
    public static final List<Integer> HELLHOUNDS = Collections.unmodifiableList(Arrays.asList(
            NpcID.HELLHOUND,
            NpcID.HELLHOUND_105,
            NpcID.HELLHOUND_135,
            NpcID.HELLHOUND_3133,
            NpcID.HELLHOUND_7256,
            NpcID.HELLHOUND_7877
    ));

    /**
     * Abyssal demon NPCs.
     */
    public static final List<Integer> ABYSSAL_DEMONS = Collections.unmodifiableList(Arrays.asList(
            NpcID.ABYSSAL_DEMON,
            NpcID.ABYSSAL_DEMON_416,
            NpcID.ABYSSAL_DEMON_7241,
            NpcID.ABYSSAL_DEMON_11239,
            NpcID.ABYSSAL_DEMON_14174,
            NpcID.ABYSSAL_DEMON_14175
    ));

    /**
     * Bloodveld NPCs.
     */
    public static final List<Integer> BLOODVELDS = Collections.unmodifiableList(Arrays.asList(
            NpcID.BLOODVELD,
            NpcID.BLOODVELD_485,
            NpcID.BLOODVELD_486,
            NpcID.BLOODVELD_487,
            NpcID.MUTATED_BLOODVELD,
            NpcID.MUTATED_BLOODVELD_9610,
            NpcID.MUTATED_BLOODVELD_9611
    ));

    /**
     * Gargoyle NPCs.
     */
    public static final List<Integer> GARGOYLES = Collections.unmodifiableList(Arrays.asList(
            NpcID.GARGOYLE,
            NpcID.GARGOYLE_413,
            NpcID.GARGOYLE_1543
    ));

    /**
     * Nechryael NPCs.
     */
    public static final List<Integer> NECHRYAELS = Collections.unmodifiableList(Arrays.asList(
            NpcID.NECHRYAEL,
            NpcID.NECHRYAEL_11,
            NpcID.GREATER_NECHRYAEL,
            NpcID.GREATER_NECHRYAEL_11240
    ));

    /**
     * Dust devil NPCs.
     */
    public static final List<Integer> DUST_DEVILS = Collections.unmodifiableList(Arrays.asList(
            NpcID.DUST_DEVIL,
            NpcID.DUST_DEVIL_7249,
            NpcID.DUST_DEVIL_11238
    ));

    /**
     * Smoke devil NPCs (not including Thermonuclear boss).
     */
    public static final List<Integer> SMOKE_DEVILS = Collections.unmodifiableList(Arrays.asList(
            NpcID.SMOKE_DEVIL,
            NpcID.SMOKE_DEVIL_8482,
            NpcID.SMOKE_DEVIL_8483
    ));

    /**
     * Greater demon NPCs.
     */
    public static final List<Integer> GREATER_DEMONS = Collections.unmodifiableList(Arrays.asList(
            NpcID.GREATER_DEMON,
            NpcID.GREATER_DEMON_2026,
            NpcID.GREATER_DEMON_2027,
            NpcID.GREATER_DEMON_2028,
            NpcID.GREATER_DEMON_2029,
            NpcID.GREATER_DEMON_2030,
            NpcID.GREATER_DEMON_2031,
            NpcID.GREATER_DEMON_2032,
            NpcID.GREATER_DEMON_7244,
            NpcID.GREATER_DEMON_7245,
            NpcID.GREATER_DEMON_7246,
            NpcID.GREATER_DEMON_7871,
            NpcID.GREATER_DEMON_7872,
            NpcID.GREATER_DEMON_7873
    ));

    /**
     * Black demon NPCs.
     */
    public static final List<Integer> BLACK_DEMONS = Collections.unmodifiableList(Arrays.asList(
            NpcID.BLACK_DEMON,
            NpcID.BLACK_DEMON_1432,
            NpcID.BLACK_DEMON_2048,
            NpcID.BLACK_DEMON_2049,
            NpcID.BLACK_DEMON_2050,
            NpcID.BLACK_DEMON_2051,
            NpcID.BLACK_DEMON_2052,
            NpcID.BLACK_DEMON_7242,
            NpcID.BLACK_DEMON_7243,
            NpcID.BLACK_DEMON_7874,
            NpcID.BLACK_DEMON_7875,
            NpcID.BLACK_DEMON_7876
    ));

    /**
     * Fire giant NPCs.
     */
    public static final List<Integer> FIRE_GIANTS = Collections.unmodifiableList(Arrays.asList(
            NpcID.FIRE_GIANT,
            NpcID.FIRE_GIANT_2076,
            NpcID.FIRE_GIANT_2077,
            NpcID.FIRE_GIANT_2078,
            NpcID.FIRE_GIANT_2079,
            NpcID.FIRE_GIANT_2080,
            NpcID.FIRE_GIANT_2081,
            NpcID.FIRE_GIANT_2082,
            NpcID.FIRE_GIANT_2083,
            NpcID.FIRE_GIANT_2084,
            NpcID.FIRE_GIANT_7251,
            NpcID.FIRE_GIANT_7252
    ));

    /**
     * Moss giant NPCs.
     */
    public static final List<Integer> MOSS_GIANTS = Collections.unmodifiableList(Arrays.asList(
            NpcID.MOSS_GIANT,
            NpcID.MOSS_GIANT_2091,
            NpcID.MOSS_GIANT_2092,
            NpcID.MOSS_GIANT_2093,
            NpcID.MOSS_GIANT_3851,
            NpcID.MOSS_GIANT_3852,
            NpcID.MOSS_GIANT_7262,
            NpcID.MOSS_GIANT_8736,
            NpcID.MOSS_GIANT_12844,
            NpcID.MOSS_GIANT_12845,
            NpcID.MOSS_GIANT_12846,
            NpcID.MOSS_GIANT_12847
    ));

    /**
     * Hill giant NPCs.
     */
    public static final List<Integer> HILL_GIANTS = Collections.unmodifiableList(Arrays.asList(
            NpcID.HILL_GIANT,
            NpcID.HILL_GIANT_2099,
            NpcID.HILL_GIANT_2100,
            NpcID.HILL_GIANT_2101,
            NpcID.HILL_GIANT_2102,
            NpcID.HILL_GIANT_2103,
            NpcID.HILL_GIANT_7261,
            NpcID.HILL_GIANT_10374,
            NpcID.HILL_GIANT_10375,
            NpcID.HILL_GIANT_10376
    ));

    /**
     * Ice giant NPCs.
     */
    public static final List<Integer> ICE_GIANTS = Collections.unmodifiableList(Arrays.asList(
            NpcID.ICE_GIANT,
            NpcID.ICE_GIANT_2086,
            NpcID.ICE_GIANT_2087,
            NpcID.ICE_GIANT_2088,
            NpcID.ICE_GIANT_2089,
            NpcID.ICE_GIANT_7878,
            NpcID.ICE_GIANT_7879,
            NpcID.ICE_GIANT_7880
    ));

    /**
     * Cave horror NPCs.
     */
    public static final List<Integer> CAVE_HORRORS = Collections.unmodifiableList(Arrays.asList(
            NpcID.CAVE_HORROR,
            NpcID.CAVE_HORROR_1048,
            NpcID.CAVE_HORROR_1049,
            NpcID.CAVE_HORROR_1050,
            NpcID.CAVE_HORROR_1051
    ));

    /**
     * Dark beast NPCs.
     */
    public static final List<Integer> DARK_BEASTS = Collections.unmodifiableList(Arrays.asList(
            NpcID.DARK_BEAST,
            NpcID.DARK_BEAST_7250
    ));

    /**
     * Kurask NPCs.
     */
    public static final List<Integer> KURASKS = Collections.unmodifiableList(Arrays.asList(
            NpcID.KURASK,
            NpcID.KURASK_411,
            NpcID.KURASK_14172,
            NpcID.KURASK_14173
    ));

    /**
     * Turoth NPCs.
     */
    public static final List<Integer> TUROTHS = Collections.unmodifiableList(Arrays.asList(
            NpcID.TUROTH,
            NpcID.TUROTH_427,
            NpcID.TUROTH_428,
            NpcID.TUROTH_429,
            NpcID.TUROTH_430,
            NpcID.TUROTH_431,
            NpcID.TUROTH_432
    ));

    /**
     * Aberrant spectre NPCs.
     */
    public static final List<Integer> ABERRANT_SPECTRES = Collections.unmodifiableList(Arrays.asList(
            NpcID.ABERRANT_SPECTRE,
            NpcID.ABERRANT_SPECTRE_3,
            NpcID.ABERRANT_SPECTRE_4,
            NpcID.ABERRANT_SPECTRE_5,
            NpcID.ABERRANT_SPECTRE_6,
            NpcID.ABERRANT_SPECTRE_7,
            NpcID.DEVIANT_SPECTRE
    ));

    /**
     * Wyrm NPCs.
     */
    public static final List<Integer> WYRMS = Collections.unmodifiableList(Arrays.asList(
            NpcID.WYRM,
            NpcID.WYRM_8611
    ));

    /**
     * Drake NPCs.
     */
    public static final List<Integer> DRAKES = Collections.unmodifiableList(Arrays.asList(
            NpcID.DRAKE,
            NpcID.DRAKE_8612,
            NpcID.DRAKE_8613
    ));

    /**
     * Hydra NPCs (regular, not Alchemical boss).
     */
    public static final List<Integer> HYDRAS = Collections.unmodifiableList(Arrays.asList(
            NpcID.HYDRA
    ));

    /**
     * Dagannoth NPCs (not including kings).
     */
    public static final List<Integer> DAGANNOTHS = Collections.unmodifiableList(Arrays.asList(
            NpcID.DAGANNOTH,
            NpcID.DAGANNOTH_970,
            NpcID.DAGANNOTH_971,
            NpcID.DAGANNOTH_972,
            NpcID.DAGANNOTH_973,
            NpcID.DAGANNOTH_974,
            NpcID.DAGANNOTH_975,
            NpcID.DAGANNOTH_976,
            NpcID.DAGANNOTH_977,
            NpcID.DAGANNOTH_978,
            NpcID.DAGANNOTH_979,
            NpcID.DAGANNOTH_2259,
            NpcID.DAGANNOTH_3185,
            NpcID.DAGANNOTH_5942,
            NpcID.DAGANNOTH_5943,
            NpcID.DAGANNOTH_7259,
            NpcID.DAGANNOTH_7260
    ));

    /**
     * Banshee NPCs.
     */
    public static final List<Integer> BANSHEES = Collections.unmodifiableList(Arrays.asList(
            NpcID.BANSHEE,
            NpcID.TWISTED_BANSHEE
    ));

    /**
     * Cave crawler NPCs.
     */
    public static final List<Integer> CAVE_CRAWLERS = Collections.unmodifiableList(Arrays.asList(
            NpcID.CAVE_CRAWLER,
            NpcID.CAVE_CRAWLER_407,
            NpcID.CAVE_CRAWLER_408,
            NpcID.CAVE_CRAWLER_409
    ));

    /**
     * Cockatrice NPCs.
     */
    public static final List<Integer> COCKATRICES = Collections.unmodifiableList(Arrays.asList(
            NpcID.COCKATRICE,
            NpcID.COCKATRICE_420
    ));

    /**
     * Basilisk NPCs (not including knights).
     */
    public static final List<Integer> BASILISKS = Collections.unmodifiableList(Arrays.asList(
            NpcID.BASILISK,
            NpcID.BASILISK_418,
            NpcID.BASILISK_YOUNGLING,
            NpcID.BASILISK_9283,
            NpcID.BASILISK_9284,
            NpcID.BASILISK_9285,
            NpcID.BASILISK_9286
    ));

    /**
     * Rockslug NPCs.
     */
    public static final List<Integer> ROCKSLUGS = Collections.unmodifiableList(Arrays.asList(
            NpcID.ROCKSLUG,
            NpcID.ROCKSLUG_422
    ));

    /**
     * Ankou NPCs.
     */
    public static final List<Integer> ANKOUS = Collections.unmodifiableList(Arrays.asList(
            NpcID.ANKOU,
            NpcID.ANKOU_2515,
            NpcID.ANKOU_2516,
            NpcID.ANKOU_2517,
            NpcID.ANKOU_2518,
            NpcID.ANKOU_2519,
            NpcID.ANKOU_6608,
            NpcID.ANKOU_7257,
            NpcID.ANKOU_7864
    ));

    /**
     * Kalphite NPCs (workers, soldiers, guardians).
     */
    public static final List<Integer> KALPHITES = Collections.unmodifiableList(Arrays.asList(
            NpcID.KALPHITE_WORKER,
            NpcID.KALPHITE_WORKER_956,
            NpcID.KALPHITE_SOLDIER_957,
            NpcID.KALPHITE_SOLDIER_958,
            NpcID.KALPHITE_GUARDIAN,
            NpcID.KALPHITE_GUARDIAN_960,
            NpcID.KALPHITE_WORKER_961,
            NpcID.KALPHITE_GUARDIAN_962,
            NpcID.KALPHITE_LARVA
    ));

    // ========================================================================
    // Dragons
    // ========================================================================

    /**
     * Black dragon NPCs (not including KBD or babies).
     */
    public static final List<Integer> BLACK_DRAGONS = Collections.unmodifiableList(Arrays.asList(
            NpcID.BLACK_DRAGON,
            NpcID.BLACK_DRAGON_253,
            NpcID.BLACK_DRAGON_254,
            NpcID.BLACK_DRAGON_255,
            NpcID.BLACK_DRAGON_256,
            NpcID.BLACK_DRAGON_257,
            NpcID.BLACK_DRAGON_258,
            NpcID.BLACK_DRAGON_259,
            NpcID.BLACK_DRAGON_7861,
            NpcID.BLACK_DRAGON_7862,
            NpcID.BLACK_DRAGON_7863,
            NpcID.BLACK_DRAGON_8084,
            NpcID.BLACK_DRAGON_8085
    ));

    /**
     * Blue dragon NPCs (not including babies).
     */
    public static final List<Integer> BLUE_DRAGONS = Collections.unmodifiableList(Arrays.asList(
            NpcID.BLUE_DRAGON,
            NpcID.BLUE_DRAGON_266,
            NpcID.BLUE_DRAGON_267,
            NpcID.BLUE_DRAGON_268,
            NpcID.BLUE_DRAGON_269,
            NpcID.BLUE_DRAGON_4385,
            NpcID.BLUE_DRAGON_5878,
            NpcID.BLUE_DRAGON_5879,
            NpcID.BLUE_DRAGON_5880,
            NpcID.BLUE_DRAGON_5881,
            NpcID.BLUE_DRAGON_5882,
            NpcID.BLUE_DRAGON_8074,
            NpcID.BLUE_DRAGON_8077,
            NpcID.BLUE_DRAGON_8083,
            NpcID.BLUE_DRAGON_14103,
            NpcID.BLUE_DRAGON_14104
    ));

    /**
     * Green dragon NPCs (not including babies).
     */
    public static final List<Integer> GREEN_DRAGONS = Collections.unmodifiableList(Arrays.asList(
            NpcID.GREEN_DRAGON,
            NpcID.GREEN_DRAGON_261,
            NpcID.GREEN_DRAGON_262,
            NpcID.GREEN_DRAGON_263,
            NpcID.GREEN_DRAGON_264,
            NpcID.GREEN_DRAGON_7868,
            NpcID.GREEN_DRAGON_7869,
            NpcID.GREEN_DRAGON_7870,
            NpcID.GREEN_DRAGON_8073,
            NpcID.GREEN_DRAGON_8076,
            NpcID.GREEN_DRAGON_8082
    ));

    /**
     * Red dragon NPCs (not including babies).
     */
    public static final List<Integer> RED_DRAGONS = Collections.unmodifiableList(Arrays.asList(
            NpcID.RED_DRAGON,
            NpcID.RED_DRAGON_248,
            NpcID.RED_DRAGON_249,
            NpcID.RED_DRAGON_250,
            NpcID.RED_DRAGON_251,
            NpcID.RED_DRAGON_8075,
            NpcID.RED_DRAGON_8078,
            NpcID.RED_DRAGON_8079
    ));

    /**
     * Bronze dragon NPCs.
     */
    public static final List<Integer> BRONZE_DRAGONS = Collections.unmodifiableList(Arrays.asList(
            NpcID.BRONZE_DRAGON,
            NpcID.BRONZE_DRAGON_271,
            NpcID.BRONZE_DRAGON_7253
    ));

    /**
     * Iron dragon NPCs.
     */
    public static final List<Integer> IRON_DRAGONS = Collections.unmodifiableList(Arrays.asList(
            NpcID.IRON_DRAGON,
            NpcID.IRON_DRAGON_273,
            NpcID.IRON_DRAGON_7254,
            NpcID.IRON_DRAGON_8080
    ));

    /**
     * Steel dragon NPCs.
     */
    public static final List<Integer> STEEL_DRAGONS = Collections.unmodifiableList(Arrays.asList(
            NpcID.STEEL_DRAGON,
            NpcID.STEEL_DRAGON_274,
            NpcID.STEEL_DRAGON_275,
            NpcID.STEEL_DRAGON_7255,
            NpcID.STEEL_DRAGON_8086
    ));

    /**
     * Mithril dragon NPCs.
     */
    public static final List<Integer> MITHRIL_DRAGONS = Collections.unmodifiableList(Arrays.asList(
            NpcID.MITHRIL_DRAGON,
            NpcID.MITHRIL_DRAGON_8088,
            NpcID.MITHRIL_DRAGON_8089
    ));

    /**
     * Adamant dragon NPCs.
     */
    public static final List<Integer> ADAMANT_DRAGONS = Collections.unmodifiableList(Arrays.asList(
            NpcID.ADAMANT_DRAGON,
            NpcID.ADAMANT_DRAGON_8090
    ));

    /**
     * Rune dragon NPCs.
     */
    public static final List<Integer> RUNE_DRAGONS = Collections.unmodifiableList(Arrays.asList(
            NpcID.RUNE_DRAGON,
            NpcID.RUNE_DRAGON_8031,
            NpcID.RUNE_DRAGON_8091
    ));

    /**
     * Brutal black dragon NPCs.
     */
    public static final List<Integer> BRUTAL_BLACK_DRAGONS = Collections.unmodifiableList(Arrays.asList(
            NpcID.BRUTAL_BLACK_DRAGON,
            NpcID.BRUTAL_BLACK_DRAGON_8092,
            NpcID.BRUTAL_BLACK_DRAGON_8093
    ));

    /**
     * Brutal blue dragon NPCs.
     */
    public static final List<Integer> BRUTAL_BLUE_DRAGONS = Collections.unmodifiableList(Arrays.asList(
            NpcID.BRUTAL_BLUE_DRAGON
    ));

    /**
     * Brutal red dragon NPCs.
     */
    public static final List<Integer> BRUTAL_RED_DRAGONS = Collections.unmodifiableList(Arrays.asList(
            NpcID.BRUTAL_RED_DRAGON,
            NpcID.BRUTAL_RED_DRAGON_8087
    ));

    /**
     * Brutal green dragon NPCs.
     */
    public static final List<Integer> BRUTAL_GREEN_DRAGONS = Collections.unmodifiableList(Arrays.asList(
            NpcID.BRUTAL_GREEN_DRAGON,
            NpcID.BRUTAL_GREEN_DRAGON_8081
    ));

    // ========================================================================
    // Superior Slayer Creatures
    // ========================================================================

    /**
     * Greater abyssal demon (superior).
     */
    public static final List<Integer> SUPERIOR_ABYSSAL_DEMONS = Collections.unmodifiableList(Arrays.asList(
            NpcID.GREATER_ABYSSAL_DEMON
    ));

    /**
     * Insatiable bloodveld (superior).
     */
    public static final List<Integer> SUPERIOR_BLOODVELDS = Collections.unmodifiableList(Arrays.asList(
            NpcID.INSATIABLE_BLOODVELD,
            NpcID.INSATIABLE_MUTATED_BLOODVELD
    ));

    /**
     * Marble gargoyle (superior).
     */
    public static final List<Integer> SUPERIOR_GARGOYLES = Collections.unmodifiableList(Arrays.asList(
            NpcID.MARBLE_GARGOYLE,
            NpcID.MARBLE_GARGOYLE_7408
    ));

    /**
     * Nechryarch (superior).
     */
    public static final List<Integer> SUPERIOR_NECHRYAELS = Collections.unmodifiableList(Arrays.asList(
            NpcID.NECHRYARCH
    ));

    /**
     * Choke devil (superior).
     */
    public static final List<Integer> SUPERIOR_DUST_DEVILS = Collections.unmodifiableList(Arrays.asList(
            NpcID.CHOKE_DEVIL
    ));

    /**
     * Nuclear smoke devil (superior).
     */
    public static final List<Integer> SUPERIOR_SMOKE_DEVILS = Collections.unmodifiableList(Arrays.asList(
            NpcID.NUCLEAR_SMOKE_DEVIL
    ));

    /**
     * Cave abomination (superior).
     */
    public static final List<Integer> SUPERIOR_CAVE_HORRORS = Collections.unmodifiableList(Arrays.asList(
            NpcID.CAVE_ABOMINATION
    ));

    /**
     * Night beast (superior).
     */
    public static final List<Integer> SUPERIOR_DARK_BEASTS = Collections.unmodifiableList(Arrays.asList(
            NpcID.NIGHT_BEAST
    ));

    /**
     * King kurask (superior).
     */
    public static final List<Integer> SUPERIOR_KURASKS = Collections.unmodifiableList(Arrays.asList(
            NpcID.KING_KURASK
    ));

    /**
     * Spiked turoth (superior).
     */
    public static final List<Integer> SUPERIOR_TUROTHS = Collections.unmodifiableList(Arrays.asList(
            NpcID.SPIKED_TUROTH
    ));

    /**
     * Abhorrent spectre and Repugnant spectre (superiors).
     */
    public static final List<Integer> SUPERIOR_ABERRANT_SPECTRES = Collections.unmodifiableList(Arrays.asList(
            NpcID.ABHORRENT_SPECTRE,
            NpcID.REPUGNANT_SPECTRE
    ));

    /**
     * Shadow wyrm (superior).
     */
    public static final List<Integer> SUPERIOR_WYRMS = Collections.unmodifiableList(Arrays.asList(
            NpcID.SHADOW_WYRM,
            NpcID.SHADOW_WYRM_10399
    ));

    /**
     * Guardian drake (superior).
     */
    public static final List<Integer> SUPERIOR_DRAKES = Collections.unmodifiableList(Arrays.asList(
            NpcID.GUARDIAN_DRAKE,
            NpcID.GUARDIAN_DRAKE_10401
    ));

    /**
     * Colossal hydra (superior).
     */
    public static final List<Integer> SUPERIOR_HYDRAS = Collections.unmodifiableList(Arrays.asList(
            NpcID.COLOSSAL_HYDRA
    ));

    /**
     * Screaming banshee (superior).
     */
    public static final List<Integer> SUPERIOR_BANSHEES = Collections.unmodifiableList(Arrays.asList(
            NpcID.SCREAMING_BANSHEE,
            NpcID.SCREAMING_TWISTED_BANSHEE
    ));

    /**
     * Chasm crawler (superior).
     */
    public static final List<Integer> SUPERIOR_CAVE_CRAWLERS = Collections.unmodifiableList(Arrays.asList(
            NpcID.CHASM_CRAWLER
    ));

    /**
     * Cockathrice (superior).
     */
    public static final List<Integer> SUPERIOR_COCKATRICES = Collections.unmodifiableList(Arrays.asList(
            NpcID.COCKATHRICE
    ));

    /**
     * Monstrous basilisk (superior).
     */
    public static final List<Integer> SUPERIOR_BASILISKS = Collections.unmodifiableList(Arrays.asList(
            NpcID.MONSTROUS_BASILISK,
            NpcID.MONSTROUS_BASILISK_9287,
            NpcID.MONSTROUS_BASILISK_9288
    ));

    /**
     * Giant rockslug (superior).
     */
    public static final List<Integer> SUPERIOR_ROCKSLUGS = Collections.unmodifiableList(Arrays.asList(
            NpcID.GIANT_ROCKSLUG
    ));

    // ========================================================================
    // Thieving Targets - Pickpocketable NPCs
    // ========================================================================

    /**
     * Man NPCs that can be pickpocketed (level 1 Thieving).
     * Found throughout Gielinor in cities and towns.
     */
    public static final List<Integer> MEN = Collections.unmodifiableList(Arrays.asList(
            NpcID.MAN,
            NpcID.MAN_1118,
            NpcID.MAN_3014,
            NpcID.MAN_3106,
            NpcID.MAN_3107,
            NpcID.MAN_3108,
            NpcID.MAN_3109,
            NpcID.MAN_3110,
            NpcID.MAN_3261,
            NpcID.MAN_3264,
            NpcID.MAN_3265,
            NpcID.MAN_3298,
            NpcID.MAN_3652,
            NpcID.MAN_4268,
            NpcID.MAN_4269,
            NpcID.MAN_4270,
            NpcID.MAN_4271,
            NpcID.MAN_4272,
            NpcID.MAN_6776,
            NpcID.MAN_6815,
            NpcID.MAN_6818,
            NpcID.MAN_6987,
            NpcID.MAN_6988,
            NpcID.MAN_6989,
            NpcID.MAN_7281,
            NpcID.MAN_7919,
            NpcID.MAN_7920,
            NpcID.MAN_8858,
            NpcID.MAN_8859,
            NpcID.MAN_8860,
            NpcID.MAN_8861,
            NpcID.MAN_8862,
            NpcID.MAN_10672,
            NpcID.MAN_10673,
            NpcID.MAN_10945,
            NpcID.MAN_11032
    ));

    /**
     * Woman NPCs that can be pickpocketed (level 1 Thieving).
     * Found throughout Gielinor in cities and towns.
     */
    public static final List<Integer> WOMEN = Collections.unmodifiableList(Arrays.asList(
            NpcID.WOMAN,
            NpcID.WOMAN_1130,
            NpcID.WOMAN_1131,
            NpcID.WOMAN_1139,
            NpcID.WOMAN_1140,
            NpcID.WOMAN_1141,
            NpcID.WOMAN_1142,
            NpcID.WOMAN_3015,
            NpcID.WOMAN_3111,
            NpcID.WOMAN_3112,
            NpcID.WOMAN_3113,
            NpcID.WOMAN_3268,
            NpcID.WOMAN_3299,
            NpcID.WOMAN_4958,
            NpcID.WOMAN_6990,
            NpcID.WOMAN_6991,
            NpcID.WOMAN_6992,
            NpcID.WOMAN_7921,
            NpcID.WOMAN_7922,
            NpcID.WOMAN_8863,
            NpcID.WOMAN_8864,
            NpcID.WOMAN_10674,
            NpcID.WOMAN_10728,
            NpcID.WOMAN_11053
    ));

    /**
     * Combined Men and Women for generic citizen pickpocketing.
     * Level 1 Thieving, 8 XP per successful pickpocket.
     */
    public static final List<Integer> CITIZENS = Collections.unmodifiableList(Arrays.asList(
            // Men
            NpcID.MAN,
            NpcID.MAN_1118,
            NpcID.MAN_3014,
            NpcID.MAN_3106,
            NpcID.MAN_3107,
            NpcID.MAN_3108,
            NpcID.MAN_3109,
            NpcID.MAN_3110,
            NpcID.MAN_3261,
            NpcID.MAN_3264,
            NpcID.MAN_3265,
            NpcID.MAN_3298,
            NpcID.MAN_3652,
            NpcID.MAN_4268,
            NpcID.MAN_4269,
            NpcID.MAN_4270,
            NpcID.MAN_4271,
            NpcID.MAN_4272,
            NpcID.MAN_6776,
            NpcID.MAN_6815,
            NpcID.MAN_6818,
            NpcID.MAN_6987,
            NpcID.MAN_6988,
            NpcID.MAN_6989,
            NpcID.MAN_7281,
            NpcID.MAN_7919,
            NpcID.MAN_7920,
            NpcID.MAN_8858,
            NpcID.MAN_8859,
            NpcID.MAN_8860,
            NpcID.MAN_8861,
            NpcID.MAN_8862,
            NpcID.MAN_10672,
            NpcID.MAN_10673,
            NpcID.MAN_10945,
            NpcID.MAN_11032,
            // Women
            NpcID.WOMAN,
            NpcID.WOMAN_1130,
            NpcID.WOMAN_1131,
            NpcID.WOMAN_1139,
            NpcID.WOMAN_1140,
            NpcID.WOMAN_1141,
            NpcID.WOMAN_1142,
            NpcID.WOMAN_3015,
            NpcID.WOMAN_3111,
            NpcID.WOMAN_3112,
            NpcID.WOMAN_3113,
            NpcID.WOMAN_3268,
            NpcID.WOMAN_3299,
            NpcID.WOMAN_4958,
            NpcID.WOMAN_6990,
            NpcID.WOMAN_6991,
            NpcID.WOMAN_6992,
            NpcID.WOMAN_7921,
            NpcID.WOMAN_7922,
            NpcID.WOMAN_8863,
            NpcID.WOMAN_8864,
            NpcID.WOMAN_10674,
            NpcID.WOMAN_10728,
            NpcID.WOMAN_11053
    ));

    /**
     * Farmer NPCs that can be pickpocketed (level 10 Thieving).
     * Note: These are generic farmers, not Master Farmers.
     * 14.5 XP per successful pickpocket.
     */
    public static final List<Integer> FARMERS_PICKPOCKET = Collections.unmodifiableList(Arrays.asList(
            NpcID.FARMER,
            NpcID.FARMER_3243,
            NpcID.FARMER_3244,
            NpcID.FARMER_3245,
            NpcID.FARMER_3250,
            NpcID.FARMER_3251,
            NpcID.FARMER_3672,
            NpcID.FARMER_6947,
            NpcID.FARMER_6948,
            NpcID.FARMER_6949,
            NpcID.FARMER_6950,
            NpcID.FARMER_6951,
            NpcID.FARMER_6952,
            NpcID.FARMER_6959,
            NpcID.FARMER_6960,
            NpcID.FARMER_6961,
            NpcID.FARMER_11045
    ));

    /**
     * Guard NPCs that can be pickpocketed (level 40 Thieving).
     * 46.8 XP per successful pickpocket.
     * Note: Excludes special guards (Khazard, Tyras, etc.)
     */
    public static final List<Integer> GUARDS_PICKPOCKET = Collections.unmodifiableList(Arrays.asList(
            NpcID.GUARD,
            NpcID.GUARD_398,
            NpcID.GUARD_399,
            NpcID.GUARD_400,
            NpcID.GUARD_995,
            NpcID.GUARD_998,
            NpcID.GUARD_999,
            NpcID.GUARD_1000,
            NpcID.GUARD_1001,
            NpcID.GUARD_1002,
            NpcID.GUARD_1003,
            NpcID.GUARD_1004,
            NpcID.GUARD_1005,
            NpcID.GUARD_1006,
            NpcID.GUARD_1007,
            NpcID.GUARD_1008,
            NpcID.GUARD_1009,
            NpcID.GUARD_1099,
            NpcID.GUARD_1100,
            NpcID.GUARD_1111,
            NpcID.GUARD_1112,
            NpcID.GUARD_1113,
            NpcID.GUARD_1147,
            NpcID.GUARD_1371,
            NpcID.GUARD_1372,
            NpcID.GUARD_1546,
            NpcID.GUARD_1547,
            NpcID.GUARD_1548,
            NpcID.GUARD_1549,
            NpcID.GUARD_1550,
            NpcID.GUARD_1551,
            NpcID.GUARD_1552,
            NpcID.GUARD_1947,
            NpcID.GUARD_1948,
            NpcID.GUARD_1949,
            NpcID.GUARD_1950,
            NpcID.GUARD_2316,
            NpcID.GUARD_2317
    ));

    /**
     * Master Farmer NPCs (level 38 Thieving).
     * 43 XP per successful pickpocket.
     * Primary source of seeds for Farming.
     */
    public static final List<Integer> MASTER_FARMERS = Collections.unmodifiableList(Arrays.asList(
            NpcID.MASTER_FARMER,
            NpcID.MASTER_FARMER_5731,
            NpcID.MASTER_FARMER_11940,
            NpcID.MASTER_FARMER_11941,
            NpcID.MASTER_FARMER_13236,
            NpcID.MASTER_FARMER_13237,
            NpcID.MASTER_FARMER_13238,
            NpcID.MASTER_FARMER_13239,
            NpcID.MASTER_FARMER_13240,
            NpcID.MASTER_FARMER_13241,
            NpcID.MASTER_FARMER_13242,
            NpcID.MASTER_FARMER_13243,
            NpcID.MASTER_FARMER_14755,
            NpcID.MASTER_FARMER_14756,
            NpcID.MASTER_FARMER_14757,
            NpcID.MASTER_FARMER_14758
    ));

    /**
     * Knight of Ardougne NPCs (level 55 Thieving).
     * 84.3 XP per successful pickpocket.
     * Popular training method at Ardougne Market Square.
     */
    public static final List<Integer> KNIGHTS_OF_ARDOUGNE = Collections.unmodifiableList(Arrays.asList(
            NpcID.KNIGHT_OF_ARDOUGNE,
            NpcID.KNIGHT_OF_ARDOUGNE_3300,
            NpcID.KNIGHT_OF_ARDOUGNE_8799,
            NpcID.KNIGHT_OF_ARDOUGNE_8800,
            NpcID.KNIGHT_OF_ARDOUGNE_8801,
            NpcID.KNIGHT_OF_ARDOUGNE_8851,
            NpcID.KNIGHT_OF_ARDOUGNE_8852,
            NpcID.KNIGHT_OF_ARDOUGNE_8854,
            NpcID.KNIGHT_OF_ARDOUGNE_8855,
            NpcID.KNIGHT_OF_ARDOUGNE_11902,
            NpcID.KNIGHT_OF_ARDOUGNE_11936
    ));

    /**
     * H.A.M. Member NPCs (level 15 Thieving).
     * 18.5 XP per successful pickpocket.
     * Found in H.A.M. Hideout, good for easy clue scrolls.
     */
    public static final List<Integer> HAM_MEMBERS = Collections.unmodifiableList(Arrays.asList(
            NpcID.HAM_MEMBER,
            NpcID.HAM_MEMBER_2541,
            NpcID.HAM_MEMBER_2542,
            NpcID.HAM_MEMBER_2543
    ));

    /**
     * Rogue NPCs (level 32 Thieving).
     * 35.5 XP per successful pickpocket.
     */
    public static final List<Integer> ROGUES_PICKPOCKET = Collections.unmodifiableList(Arrays.asList(
            NpcID.ROGUE
    ));

    /**
     * Paladin NPCs (level 70 Thieving).
     * 151.75 XP per successful pickpocket.
     */
    public static final List<Integer> PALADINS = Collections.unmodifiableList(Arrays.asList(
            NpcID.PALADIN
    ));

    /**
     * Hero NPCs (level 80 Thieving).
     * 275.25 XP per successful pickpocket.
     */
    public static final List<Integer> HEROES_PICKPOCKET = Collections.unmodifiableList(Arrays.asList(
            NpcID.HERO
    ));

    /**
     * TzHaar-Hur NPCs (level 90 Thieving).
     * 103.4 XP per successful pickpocket.
     */
    public static final List<Integer> TZHAAR_PICKPOCKET = Collections.unmodifiableList(Arrays.asList(
            NpcID.TZHAARHUR,
            NpcID.TZHAARHUR_2162,
            NpcID.TZHAARHUR_2163,
            NpcID.TZHAARHUR_2164,
            NpcID.TZHAARHUR_2165,
            NpcID.TZHAARHUR_2166
    ));

    // ========================================================================
    // Player Gravestones (death recovery)
    // ========================================================================

    /**
     * Player gravestone NPCs that spawn when a player dies.
     * Yes, gravestones are NPCs, not objects!
     * Action: "Loot" to open the retrieval interface.
     */
    public static final List<Integer> PLAYER_GRAVESTONES = Collections.unmodifiableList(Arrays.asList(
            NpcID.GRAVE,        // 9856 - Default gravestone
            NpcID.GRAVE_9857,   // 9857 - Angel gravestone
            9858,               // Variant (no RuneLite constant)
            9859                // Variant (no RuneLite constant)
    ));
}
