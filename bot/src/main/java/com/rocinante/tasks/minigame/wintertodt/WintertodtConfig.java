package com.rocinante.tasks.minigame.wintertodt;

import com.rocinante.tasks.minigame.MinigameConfig;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;
import net.runelite.api.ItemID;

import java.util.List;
import java.util.Set;

/**
 * Configuration for Wintertodt minigame training.
 *
 * <p>Extends the base {@link MinigameConfig} with Wintertodt-specific options:
 * <ul>
 *   <li>Training strategy (simple vs fletch)</li>
 *   <li>Brazier preference</li>
 *   <li>HP management thresholds</li>
 *   <li>Point targets</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * WintertodtConfig config = WintertodtConfig.builder()
 *     .targetLevel(75)
 *     .strategy(WintertodtStrategy.FLETCH)
 *     .preferredBrazier(BrazierLocation.SOUTHWEST)
 *     .eatThreshold(0.6)
 *     .build();
 * }</pre>
 *
 * @see WintertodtTask
 */
@Data
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class WintertodtConfig extends MinigameConfig {

    // ========================================================================
    // Wintertodt Constants
    // ========================================================================

    /**
     * Wintertodt region ID.
     */
    public static final int WINTERTODT_REGION = 6462;

    /**
     * Minimum firemaking level required.
     */
    public static final int REQUIRED_FM_LEVEL = 50;

    /**
     * Minimum points for reward crate (500 to receive rewards).
     */
    public static final int MIN_POINTS_FOR_REWARD = 500;

    /**
     * Points per bruma root fed.
     */
    public static final int POINTS_PER_ROOT = 10;

    /**
     * Points per kindling fed.
     */
    public static final int POINTS_PER_KINDLING = 25;

    /**
     * Required number of warm clothing pieces for damage reduction.
     * 4 pieces gives maximum damage reduction.
     */
    public static final int REQUIRED_WARM_ITEMS = 4;

    /**
     * Complete set of warm clothing item IDs that count toward damage reduction.
     * Wearing 4 pieces significantly reduces damage taken.
     * Source: https://oldschool.runescape.wiki/w/Wintertodt/Warm_clothing (184 items)
     */
    public static final Set<Integer> WARM_CLOTHING = Set.of(
            // === HEAD SLOT ===
            ItemID.SANTA_MASK,              // 12887
            ItemID.ANTISANTA_MASK,          // 12892
            ItemID.BUNNYMAN_MASK,           // 23448
            ItemID.LARUPIA_HAT,             // 10045
            ItemID.GRAAHK_HEADDRESS,        // 10051
            ItemID.KYATT_HAT,               // 10039
            ItemID.CHICKEN_HEAD,            // 11021
            ItemID.EVIL_CHICKEN_HEAD,       // 20439
            ItemID.PYROMANCER_HOOD,         // 20708
            ItemID.SANTA_HAT,               // 1050
            ItemID.BLACK_SANTA_HAT,         // 13343
            ItemID.INVERTED_SANTA_HAT,      // 13344
            ItemID.FESTIVE_ELF_HAT,         // 26312
            ItemID.FESTIVE_GAMES_CROWN,     // 27588
            ItemID.BEARHEAD,                // 4502
            ItemID.FIRE_TIARA,              // 5537
            ItemID.ELEMENTAL_TIARA,         // 26804
            ItemID.LUMBERJACK_HAT,          // 10941
            ItemID.FORESTRY_HAT,            // 28173
            ItemID.SNOW_GOGGLES__HAT,       // 27568
            ItemID.SNOWGLOBE_HELMET,        // 28788
            ItemID.FIREMAKING_HOOD,         // 9806
            ItemID.FIRE_MAX_HOOD,           // 13330
            ItemID.INFERNAL_MAX_HOOD,       // 21282
            ItemID.BOMBER_CAP,              // 9945
            ItemID.CAP_AND_GOGGLES,         // 9946
            ItemID.BOBBLE_HAT,              // 6856
            ItemID.EARMUFFS,                // 4166
            ItemID.WOLF_MASK,               // 23407
            ItemID.WOOLLY_HAT,              // 6862
            ItemID.JESTER_HAT,              // 6858
            ItemID.TRIJESTER_HAT,           // 6860
            // Slayer helmets (all variants)
            ItemID.SLAYER_HELMET,           // 11864
            ItemID.SLAYER_HELMET_I,         // 11865
            ItemID.ARAXYTE_SLAYER_HELMET,   // 29816
            ItemID.ARAXYTE_SLAYER_HELMET_I, // 29818
            ItemID.BLACK_SLAYER_HELMET,     // 19639
            ItemID.BLACK_SLAYER_HELMET_I,   // 19641
            ItemID.GREEN_SLAYER_HELMET,     // 19643
            ItemID.GREEN_SLAYER_HELMET_I,   // 19645
            ItemID.HYDRA_SLAYER_HELMET,     // 23073
            ItemID.HYDRA_SLAYER_HELMET_I,   // 23075
            ItemID.PURPLE_SLAYER_HELMET,    // 21264
            ItemID.PURPLE_SLAYER_HELMET_I,  // 21266
            ItemID.RED_SLAYER_HELMET,       // 19647
            ItemID.RED_SLAYER_HELMET_I,     // 19649
            ItemID.TURQUOISE_SLAYER_HELMET, // 21888
            ItemID.TURQUOISE_SLAYER_HELMET_I, // 21890
            ItemID.TWISTED_SLAYER_HELMET,   // 24370
            ItemID.TWISTED_SLAYER_HELMET_I, // 24444
            ItemID.TZKAL_SLAYER_HELMET,     // 25910
            ItemID.TZKAL_SLAYER_HELMET_I,   // 25912
            ItemID.TZTOK_SLAYER_HELMET,     // 25898
            ItemID.TZTOK_SLAYER_HELMET_I,   // 25900
            ItemID.VAMPYRIC_SLAYER_HELMET,  // 25904
            ItemID.VAMPYRIC_SLAYER_HELMET_I, // 25906
            ItemID.HAT_OF_THE_EYE,          // 26850
            ItemID.WISE_OLD_MANS_SANTA_HAT, // 21859
            ItemID.HELM_OF_RAEDWALD,        // 19687

            // === NECK SLOT ===
            ItemID.JESTER_SCARF,            // 6859
            ItemID.TRIJESTER_SCARF,         // 6861
            ItemID.WOOLLY_SCARF,            // 6863
            ItemID.BOBBLE_SCARF,            // 6857
            ItemID.GNOME_SCARF,             // 9470
            ItemID.RAINBOW_SCARF,           // 21314
            ItemID.FESTIVE_SCARF,           // 30489

            // === HANDS SLOT ===
            ItemID.SANTA_GLOVES,            // 12890
            ItemID.ANTISANTA_GLOVES,        // 12895
            ItemID.BUNNY_PAWS,              // 13665
            ItemID.CLUE_HUNTER_GLOVES,      // 19691
            ItemID.GLOVES_OF_SILENCE,       // 10075
            ItemID.FREMENNIK_GLOVES,        // 3799
            ItemID.WARM_GLOVES,             // 20712
            2902,  // Grey gloves
            2912,  // Red gloves
            2922,  // Yellow gloves
            2932,  // Teal gloves
            2942,  // Purple gloves

            // === CAPE SLOT ===
            ItemID.FIREMAKING_CAPE,         // 9804
            ItemID.FIREMAKING_CAPET,        // 9805
            ItemID.MAX_CAPE,                // 13280
            ItemID.FIRE_CAPE,               // 6570
            ItemID.FIRE_MAX_CAPE,           // 13329
            ItemID.INFERNAL_CAPE,           // 21295
            ItemID.INFERNAL_MAX_CAPE,       // 21284
            ItemID.OBSIDIAN_CAPE,           // 6568
            ItemID.ACCUMULATOR_MAX_CAPE,    // 13337
            ItemID.ARDOUGNE_MAX_CAPE,       // 20760
            ItemID.ASSEMBLER_MAX_CAPE,      // 21898
            ItemID.MYTHICAL_MAX_CAPE,       // 24855
            ItemID.IMBUED_GUTHIX_MAX_CAPE,  // 21784
            ItemID.IMBUED_SARADOMIN_MAX_CAPE, // 21776
            ItemID.IMBUED_ZAMORAK_MAX_CAPE, // 21780
            ItemID.GUTHIX_MAX_CAPE,         // 13335
            ItemID.SARADOMIN_MAX_CAPE,      // 13331
            ItemID.ZAMORAK_MAX_CAPE,        // 13333
            ItemID.WOLF_CLOAK,              // 23410
            ItemID.RAINBOW_CAPE,            // 29489
            ItemID.CLUE_HUNTER_CLOAK,       // 19697
            ItemID.SPOTTED_CAPE,            // 10069
            ItemID.SPOTTIER_CAPE,           // 10071

            // === WEAPON SLOT ===
            1387,  // Staff of fire (STAFF_OF_FIRE constant may not exist)
            ItemID.FIRE_BATTLESTAFF,        // 1393
            ItemID.LAVA_BATTLESTAFF,        // 3053
            ItemID.STEAM_BATTLESTAFF,       // 11787
            ItemID.SMOKE_BATTLESTAFF,       // 11998
            ItemID.MYSTIC_FIRE_STAFF,       // 1401
            ItemID.MYSTIC_LAVA_STAFF,       // 3054
            ItemID.MYSTIC_STEAM_STAFF,      // 11789
            ItemID.MYSTIC_SMOKE_STAFF,      // 12000
            ItemID.TWINFLAME_STAFF,         // 30634
            ItemID.INFERNAL_AXE,            // 13241
            ItemID.INFERNAL_PICKAXE,        // 13243
            ItemID.INFERNAL_HARPOON,        // 21031
            ItemID.VOLCANIC_ABYSSAL_WHIP,   // 12773
            ItemID.ALE_OF_THE_GODS,         // 20056
            ItemID.BRUMA_TORCH,             // 20720
            ItemID.DRAGON_CANDLE_DAGGER,    // 27810
            ItemID.TOME_OF_FIRE,            // 20714
            ItemID.ABYSSAL_LANTERN,         // 26822
            ItemID.LIT_BUG_LANTERN,         // 7053
            ItemID.BURNING_AMULET5,         // 21166 (burning amulet)
            ItemID.TRAILBLAZER_RELOADED_TORCH, // 28748
            ItemID.BLAZING_BLOWPIPE,        // 28688
            ItemID.SCORCHING_BOW,           // 29591
            ItemID.BURNING_CLAWS,           // 29577
            ItemID.EMBERLIGHT,              // 29589
            ItemID.DEVILS_ELEMENT,          // 30371

            // === SHIELD/OFF-HAND SLOT ===
            ItemID.BRUMA_TORCH_OFFHAND,     // 29777

            // === BODY SLOT ===
            ItemID.SANTA_JACKET,            // 12888
            ItemID.ANTISANTA_JACKET,        // 12893
            ItemID.BUNNY_TOP,               // 13663
            ItemID.CLUE_HUNTER_GARB,        // 19689
            ItemID.POLAR_CAMO_TOP,          // 10065
            ItemID.WOOD_CAMO_TOP,           // 10053
            ItemID.JUNGLE_CAMO_TOP,         // 10057
            ItemID.DESERT_CAMO_TOP,         // 10061
            ItemID.LARUPIA_TOP,             // 10043
            ItemID.GRAAHK_TOP,              // 10049
            ItemID.KYATT_TOP,               // 10037
            ItemID.BOMBER_JACKET,           // 9944
            ItemID.YAKHIDE_ARMOUR,          // 10822
            ItemID.PYROMANCER_GARB,         // 20704
            ItemID.CHICKEN_WINGS,           // 11020
            ItemID.EVIL_CHICKEN_WINGS,      // 20436
            ItemID.UGLY_HALLOWEEN_JUMPER_ORANGE, // 26256
            ItemID.CHRISTMAS_JUMPER,        // 27566
            ItemID.OLDSCHOOL_JUMPER,        // 27822
            ItemID.RAINBOW_JUMPER,          // 28116
            ItemID.ICY_JUMPER,              // 28786

            // === LEGS SLOT ===
            ItemID.SANTA_PANTALOONS,        // 12889
            ItemID.ANTISANTA_PANTALOONS,    // 12894
            ItemID.BUNNY_LEGS,              // 13664
            ItemID.CLUE_HUNTER_TROUSERS,    // 19693
            ItemID.POLAR_CAMO_LEGS,         // 10067
            ItemID.WOOD_CAMO_LEGS,          // 10055
            ItemID.JUNGLE_CAMO_LEGS,        // 10059
            ItemID.DESERT_CAMO_LEGS,        // 10063
            ItemID.LARUPIA_LEGS,            // 10041
            ItemID.GRAAHK_LEGS,             // 10047
            ItemID.KYATT_LEGS,              // 10035
            ItemID.YAKHIDE_ARMOUR_10824,    // 10824
            ItemID.CHICKEN_LEGS,            // 11022
            ItemID.EVIL_CHICKEN_LEGS,       // 20442
            ItemID.PYROMANCER_ROBE,         // 20706

            // === RING SLOT ===
            ItemID.RING_OF_THE_ELEMENTS,    // 26815

            // === FEET SLOT ===
            ItemID.SANTA_BOOTS,             // 12891
            ItemID.ANTISANTA_BOOTS,         // 12896
            ItemID.BUNNY_FEET,              // 13182
            ItemID.CLUE_HUNTER_BOOTS,       // 19695
            ItemID.PYROMANCER_BOOTS,        // 20710
            ItemID.CHICKEN_FEET,            // 11019
            ItemID.EVIL_CHICKEN_FEET,       // 20433
            ItemID.FESTIVE_ELF_SLIPPERS,    // 26310
            ItemID.MOLE_SLIPPERS,           // 23285
            ItemID.BEAR_FEET,               // 23291
            ItemID.DEMON_FEET,              // 23294
            ItemID.FROG_SLIPPERS,           // 23288
            ItemID.BOB_THE_CAT_SLIPPERS,    // 27806
            ItemID.JAD_SLIPPERS,            // 27808
            ItemID.FORESTRY_BOOTS,          // 28175

            // === AMMO/OTHER SLOT ===
            ItemID.BUNNY_EARS,              // 1037
            ItemID.STRUNG_RABBIT_FOOT,      // 10132

            // === ARMOUR SETS (Trailblazer) ===
            ItemID.TRAILBLAZER_RELOADED_RELIC_HUNTER_T1_ARMOUR_SET, // 28777
            ItemID.TRAILBLAZER_RELOADED_RELIC_HUNTER_T2_ARMOUR_SET, // 28780
            ItemID.TRAILBLAZER_RELOADED_RELIC_HUNTER_T3_ARMOUR_SET  // 28783
    );

    // ========================================================================
    // Strategy Configuration
    // ========================================================================

    /**
     * Training strategy to use.
     */
    @Builder.Default
    WintertodtStrategy strategy = WintertodtStrategy.SIMPLE;

    /**
     * Preferred brazier location.
     * If null, uses nearest brazier.
     */
    BrazierLocation preferredBrazier;

    // ========================================================================
    // HP Management
    // ========================================================================

    /**
     * HP percentage at which to eat food.
     * Lower HP = more damage from Wintertodt attacks.
     * Recommended: 0.5-0.7 for safety.
     */
    @Builder.Default
    double eatThreshold = 0.6;

    /**
     * HP percentage at which to immediately flee to safety.
     * Emergency threshold for preventing death.
     */
    @Builder.Default
    double panicThreshold = 0.25;

    /**
     * Preferred food item ID.
     * Cakes are commonly used due to 4 HP healing per bite.
     * -1 for auto-select.
     */
    @Builder.Default
    int preferredFoodId = -1;

    /**
     * Number of food items to bring per trip.
     */
    @Builder.Default
    int foodCount = 8;

    // ========================================================================
    // Point Configuration
    // ========================================================================

    /**
     * Minimum points to obtain before stopping activity during round.
     * 500 minimum for rewards, but more points = more XP.
     */
    @Builder.Default
    int targetPointsPerRound = 500;

    /**
     * Maximum points to obtain before idling.
     * Prevents over-feeding when points are already secured.
     */
    @Builder.Default
    int maxPointsPerRound = 800;

    // ========================================================================
    // Behavior Configuration
    // ========================================================================

    /**
     * Whether to fix broken braziers.
     */
    @Builder.Default
    boolean fixBraziers = true;

    /**
     * Whether to light unlit braziers.
     */
    @Builder.Default
    boolean lightBraziers = true;

    /**
     * Whether to help with other braziers if nearby one is already lit.
     */
    @Builder.Default
    boolean helpOtherBraziers = false;

    /**
     * Whether to continue playing if we miss the start of a round.
     * If false, will wait for next round.
     */
    @Builder.Default
    boolean joinMidRound = true;

    /**
     * Whether to enforce minimum warm clothing requirement.
     * If true, task will fail if player doesn't have 4 warm items equipped.
     */
    @Builder.Default
    boolean enforceWarmClothing = true;

    /**
     * Minimum number of warm clothing pieces required.
     * 4 is recommended for maximum damage reduction.
     */
    @Builder.Default
    int requiredWarmItems = REQUIRED_WARM_ITEMS;

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a simple configuration for Wintertodt training.
     *
     * @param targetLevel the firemaking level to reach
     * @return configured WintertodtConfig
     */
    public static WintertodtConfig simple(int targetLevel) {
        return WintertodtConfig.builder()
                .minigameId("wintertodt")
                .minigameName("Wintertodt")
                .regionIds(List.of(WINTERTODT_REGION))
                .targetLevel(targetLevel)
                .strategy(WintertodtStrategy.SIMPLE)
                .build();
    }

    /**
     * Create a fletch configuration for higher XP rates.
     *
     * @param targetLevel the firemaking level to reach
     * @return configured WintertodtConfig
     */
    public static WintertodtConfig fletch(int targetLevel) {
        return WintertodtConfig.builder()
                .minigameId("wintertodt")
                .minigameName("Wintertodt")
                .regionIds(List.of(WINTERTODT_REGION))
                .targetLevel(targetLevel)
                .strategy(WintertodtStrategy.FLETCH)
                .build();
    }

    /**
     * Create a configuration for maximum points per game.
     *
     * @param targetRounds number of rounds to complete
     * @return configured WintertodtConfig
     */
    public static WintertodtConfig maxPoints(int targetRounds) {
        return WintertodtConfig.builder()
                .minigameId("wintertodt")
                .minigameName("Wintertodt")
                .regionIds(List.of(WINTERTODT_REGION))
                .targetRounds(targetRounds)
                .strategy(WintertodtStrategy.FLETCH)
                .targetPointsPerRound(750)
                .maxPointsPerRound(1500)
                .build();
    }

}

