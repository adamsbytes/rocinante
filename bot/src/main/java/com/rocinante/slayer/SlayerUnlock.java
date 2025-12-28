package com.rocinante.slayer;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarbitID;

/**
 * Enum of slayer unlocks and their associated varbits.
 *
 * Per REQUIREMENTS.md Section 11.4, tracks unlocks purchased from slayer masters:
 * <ul>
 *   <li>Slayer helm crafting ability</li>
 *   <li>Slayer ring crafting ability</li>
 *   <li>Broader fletching (broad arrows/bolts)</li>
 *   <li>Superior slayer creatures</li>
 *   <li>Boss tasks</li>
 *   <li>Task extensions (longer tasks for specific creatures)</li>
 *   <li>Creature unlocks (lizardmen, red dragons, etc.)</li>
 * </ul>
 *
 * Varbit IDs sourced from RuneLite's {@link VarbitID} constants.
 */
@Getter
@RequiredArgsConstructor
public enum SlayerUnlock {

    // ========================================================================
    // Core Unlocks
    // ========================================================================

    /**
     * Malevolent Masquerade - Ability to craft slayer helmets.
     */
    SLAYER_HELM(VarbitID.SLAYER_HELM_UNLOCKED, "Slayer Helmet", 400),

    /**
     * Ring Bling - Ability to craft slayer rings.
     */
    SLAYER_RING(VarbitID.SLAYER_RING_UNLOCKED, "Slayer Ring", 300),

    /**
     * Broader Fletching - Ability to fletch broad arrows and bolts.
     */
    BROADER_FLETCHING(VarbitID.SLAYER_AMMO_UNLOCKED, "Broader Fletching", 300),

    /**
     * Bigger and Badder - Superior slayer creature spawns.
     */
    SUPERIOR_CREATURES(VarbitID.SLAYER_UNLOCK_SUPERIORMOBS, "Superior Creatures", 150),

    /**
     * Like a Boss - Ability to receive boss slayer tasks.
     */
    BOSS_TASKS(VarbitID.SLAYER_UNLOCK_BOSSES, "Boss Tasks", 200),

    // ========================================================================
    // Creature Unlocks
    // ========================================================================

    /**
     * Seeing Red - Red dragon tasks.
     */
    RED_DRAGONS(VarbitID.SLAYER_UNLOCK_REDDRAGONS, "Red Dragons", 50),

    /**
     * Watch the birdie - Aviansie tasks.
     */
    AVIANSIES(VarbitID.SLAYER_UNLOCK_AVIANSIES, "Aviansies", 80),

    /**
     * Hot Stuff - TzHaar tasks.
     */
    TZHAAR(VarbitID.SLAYER_UNLOCK_TZHAAR, "TzHaar", 100),

    /**
     * Mith me - Mithril dragon tasks.
     */
    MITHRIL_DRAGONS(VarbitID.SLAYER_UNLOCK_MITHRILDRAGONS, "Mithril Dragons", 80),

    /**
     * Reptile Got Ripped - Lizardmen tasks.
     */
    LIZARDMEN(VarbitID.SLAYER_UNLOCK_LIZARDMEN, "Lizardmen", 75),

    /**
     * Blisshards - Basilisk tasks.
     */
    BASILISKS(VarbitID.SLAYER_UNLOCK_BASILISK, "Basilisks", 80),

    /**
     * Actual Vampyre Slayer - Vampyre tasks.
     */
    VAMPYRES(VarbitID.SLAYER_UNLOCK_VAMPYRES, "Vampyres", 80),

    /**
     * Warped Reality - Warped creature tasks.
     */
    WARPED_CREATURES(VarbitID.SLAYER_UNLOCK_WARPED_CREATURES, "Warped Creatures", 0),

    // ========================================================================
    // Task Extensions (Longer Tasks)
    // ========================================================================

    /**
     * Ankou Very Much - Extended ankou tasks.
     */
    LONGER_ANKOU(VarbitID.SLAYER_LONGER_ANKOU, "Longer Ankou", 60),

    /**
     * Suqah - Extended suqah tasks.
     */
    LONGER_SUQAH(VarbitID.SLAYER_LONGER_SUQAH, "Longer Suqah", 60),

    /**
     * Need More Darkness - Extended black dragon tasks.
     */
    LONGER_BLACK_DRAGONS(VarbitID.SLAYER_LONGER_BLACKDRAGONS, "Longer Black Dragons", 50),

    /**
     * Pedal to the Metal - Extended metal dragon tasks.
     */
    LONGER_METAL_DRAGONS(VarbitID.SLAYER_LONGER_METALDRAGONS, "Longer Metal Dragons", 50),

    /**
     * Augment my Abbies - Extended abyssal demon tasks.
     */
    LONGER_ABYSSAL_DEMONS(VarbitID.SLAYER_LONGER_ABYSSALDEMONS, "Longer Abyssal Demons", 100),

    /**
     * Greater Challenge - Extended black demon tasks.
     */
    LONGER_BLACK_DEMONS(VarbitID.SLAYER_LONGER_BLACKDEMONS, "Longer Black Demons", 50),

    /**
     * Greater Demons - Extended greater demon tasks.
     */
    LONGER_GREATER_DEMONS(VarbitID.SLAYER_LONGER_GREATERDEMONS, "Longer Greater Demons", 60),

    /**
     * Bloodier Velds - Extended bloodveld tasks.
     */
    LONGER_BLOODVELD(VarbitID.SLAYER_LONGER_BLOODVELD, "Longer Bloodveld", 75),

    /**
     * Smell Ya Later - Extended aberrant spectre tasks.
     */
    LONGER_ABERRANT_SPECTRES(VarbitID.SLAYER_LONGER_ABERRANTSPECTRES, "Longer Aberrant Spectres", 60),

    /**
     * Bird is the Word - Extended aviansie tasks.
     */
    LONGER_AVIANSIES(VarbitID.SLAYER_LONGER_AVIANSIES, "Longer Aviansies", 100),

    /**
     * I hope you mith me - Extended mithril dragon tasks.
     */
    LONGER_MITHRIL_DRAGONS(VarbitID.SLAYER_LONGER_MITHRILDRAGONS, "Longer Mithril Dragons", 100),

    /**
     * Horrorific - Extended cave horror tasks.
     */
    LONGER_CAVE_HORRORS(VarbitID.SLAYER_LONGER_CAVEHORRORS, "Longer Cave Horrors", 60),

    /**
     * To Dust You Shall Return - Extended dust devil tasks.
     */
    LONGER_DUST_DEVILS(VarbitID.SLAYER_LONGER_DUSTDEVILS, "Longer Dust Devils", 100),

    /**
     * Wyver-n Or Lose - Extended skeletal wyvern tasks.
     */
    LONGER_SKELETAL_WYVERNS(VarbitID.SLAYER_LONGER_SKELETALWYVERNS, "Longer Skeletal Wyverns", 100),

    /**
     * Get Smashed - Extended gargoyle tasks.
     */
    LONGER_GARGOYLES(VarbitID.SLAYER_LONGER_GARGOYLES, "Longer Gargoyles", 100),

    /**
     * Nechs Appeal - Extended nechryael tasks.
     */
    LONGER_NECHRYAEL(VarbitID.SLAYER_LONGER_NECHRYAEL, "Longer Nechryael", 100),

    /**
     * Krack On - Extended cave kraken tasks.
     */
    LONGER_CAVE_KRAKEN(VarbitID.SLAYER_LONGER_CAVEKRAKEN, "Longer Cave Kraken", 100),

    /**
     * Spiritual Fervour - Extended GWD spiritual creature tasks.
     */
    LONGER_SPIRITUAL_GWD(VarbitID.SLAYER_LONGER_SPIRITUALGWD, "Longer Spiritual GWD", 60),

    /**
     * More at Stake - Extended vampyre tasks.
     */
    LONGER_VAMPYRES(VarbitID.SLAYER_LONGER_VAMPYRES, "Longer Vampyres", 50),

    /**
     * I Willow your Blows - Extended dark beast tasks.
     */
    LONGER_DARK_BEASTS(VarbitID.SLAYER_LONGER_DARKBEASTS, "Longer Dark Beasts", 100),

    /**
     * King Black Bonnet - Extended fossil island wyvern tasks.
     */
    LONGER_FOSSIL_WYVERNS(VarbitID.SLAYER_LONGER_FOSSILWYVERNS, "Longer Fossil Wyverns", 100),

    /**
     * Ada'm It Up - Extended adamant dragon tasks.
     */
    LONGER_ADAMANT_DRAGONS(VarbitID.SLAYER_LONGER_ADAMANTDRAGONS, "Longer Adamant Dragons", 80),

    /**
     * Rune to the Top - Extended rune dragon tasks.
     */
    LONGER_RUNE_DRAGONS(VarbitID.SLAYER_LONGER_RUNEDRAGONS, "Longer Rune Dragons", 100),

    /**
     * Bug Swatter - Extended scabarite tasks.
     */
    LONGER_SCABARITES(VarbitID.SLAYER_LONGER_SCABARITES, "Longer Scabarites", 60),

    /**
     * Blissful Ignorance - Extended basilisk tasks.
     */
    LONGER_BASILISKS(VarbitID.SLAYER_LONGER_BASILISK, "Longer Basilisks", 100),

    /**
     * Revenants - Extended revenant tasks.
     */
    LONGER_REVENANTS(VarbitID.SLAYER_LONGER_REVENANTS, "Longer Revenants", 0),

    /**
     * Araxyte Extension - Extended araxyte tasks.
     */
    LONGER_ARAXYTES(VarbitID.SLAYER_LONGER_ARAXYTES, "Longer Araxytes", 100),

    /**
     * Custodian Extension - Extended custodian stalker tasks.
     */
    LONGER_CUSTODIANS(VarbitID.SLAYER_LONGER_CUSTODIANS, "Longer Custodians", 60),

    // ========================================================================
    // Slayer Helm Recolors
    // ========================================================================

    /**
     * Black slayer helm.
     */
    HELM_BLACK(VarbitID.SLAYER_UNLOCK_HELM_BLACK, "Black Slayer Helm", 1000),

    /**
     * Green slayer helm.
     */
    HELM_GREEN(VarbitID.SLAYER_UNLOCK_HELM_GREEN, "Green Slayer Helm", 1000),

    /**
     * Red slayer helm.
     */
    HELM_RED(VarbitID.SLAYER_UNLOCK_HELM_RED, "Red Slayer Helm", 1000),

    /**
     * Purple slayer helm.
     */
    HELM_PURPLE(VarbitID.SLAYER_UNLOCK_HELM_PURPLE, "Purple Slayer Helm", 1000),

    /**
     * Turquoise slayer helm.
     */
    HELM_TURQUOISE(VarbitID.SLAYER_UNLOCK_HELM_TURQUOISE, "Turquoise Slayer Helm", 1000),

    /**
     * Hydra slayer helm.
     */
    HELM_HYDRA(VarbitID.SLAYER_UNLOCK_HELM_HYDRA, "Hydra Slayer Helm", 1000),

    /**
     * Twisted slayer helm.
     */
    HELM_TWISTED(VarbitID.SLAYER_UNLOCK_HELM_TWISTED, "Twisted Slayer Helm", 1000),

    /**
     * Araxyte slayer helm.
     */
    HELM_ARAXYTE(VarbitID.SLAYER_UNLOCK_HELM_ARAXYTE, "Araxyte Slayer Helm", 1000),

    // ========================================================================
    // Auto-kill Toggles
    // ========================================================================

    /**
     * Stop the Rock - Auto-kill gargoyles.
     */
    AUTOKILL_GARGOYLES(VarbitID.SLAYER_AUTOKILL_GARGOYLES, "Auto-kill Gargoyles", 0),

    /**
     * Gargoyle Smasher - Auto-kill rockslugs.
     */
    AUTOKILL_ROCKSLUGS(VarbitID.SLAYER_AUTOKILL_ROCKSLUGS, "Auto-kill Rockslugs", 0),

    /**
     * Reptile Freezer - Auto-kill desert lizards.
     */
    AUTOKILL_DESERT_LIZARDS(VarbitID.SLAYER_AUTOKILL_DESERTLIZARDS, "Auto-kill Desert Lizards", 0),

    /**
     * Shroom Sprayer - Auto-kill zygomites.
     */
    AUTOKILL_ZYGOMITES(VarbitID.SLAYER_AUTOKILL_ZYGOMITES, "Auto-kill Zygomites", 0),

    // ========================================================================
    // Other Unlocks
    // ========================================================================

    /**
     * Double Trouble - Grotesque guardians kill tracking.
     */
    GROTESQUE_KILLS(VarbitID.SLAYER_UNLOCK_GROTESQUEKILLS, "Grotesque Kills Tracking", 500),

    /**
     * Noted mithril bars from mithril dragons.
     */
    NOTED_MITHRIL_BARS(VarbitID.SLAYER_UNLOCK_NOTEDMITHRILBARS, "Noted Mithril Bars", 0),

    /**
     * Slayer storage upgrade.
     */
    SLAYER_STORAGE(VarbitID.SLAYER_UNLOCK_STORAGE, "Slayer Storage", 0),

    /**
     * Wilderness slayer extra tasks.
     */
    WILDERNESS_EXTRA_TASKS(VarbitID.SLAYER_UNLOCK_WILDY_EXTRATASKS, "Wilderness Extra Tasks", 0);

    /**
     * The varbit ID for this unlock.
     */
    private final int varbitId;

    /**
     * Human-readable name of this unlock.
     */
    private final String displayName;

    /**
     * Point cost to purchase this unlock.
     */
    private final int pointCost;

    /**
     * Check if this unlock is enabled for the given client.
     *
     * @param client the RuneLite client
     * @return true if the unlock is active
     */
    public boolean isUnlocked(Client client) {
        return client.getVarbitValue(varbitId) == 1;
    }

    /**
     * Check if this is a task extension unlock.
     *
     * @return true if this extends task amounts
     */
    public boolean isExtension() {
        return name().startsWith("LONGER_");
    }

    /**
     * Check if this is a creature unlock.
     *
     * @return true if this unlocks a creature type for tasks
     */
    public boolean isCreatureUnlock() {
        return this == RED_DRAGONS || this == AVIANSIES || this == TZHAAR ||
               this == MITHRIL_DRAGONS || this == LIZARDMEN || this == BASILISKS ||
               this == VAMPYRES || this == WARPED_CREATURES;
    }

    /**
     * Check if this is a slayer helm recolor.
     *
     * @return true if this is a helm recolor
     */
    public boolean isHelmRecolor() {
        return name().startsWith("HELM_");
    }

    /**
     * Check if this is an auto-kill toggle.
     *
     * @return true if this is an auto-kill feature
     */
    public boolean isAutokill() {
        return name().startsWith("AUTOKILL_");
    }
}
