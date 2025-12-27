package com.rocinante.data.model;

import lombok.Builder;

/**
 * Weapon information data from OSRS Wiki.
 *
 * <p>Per REQUIREMENTS.md Section 10.6.1, weapon data should be queried from
 * the wiki rather than hardcoded. This includes:
 * <ul>
 *   <li>Attack speed (ticks between attacks)</li>
 *   <li>Special attack cost (if applicable)</li>
 *   <li>Combat stats and bonuses</li>
 * </ul>
 *
 * <p>Used to:
 * <ul>
 *   <li>Track weapon attack speed dynamically per equipped weapon</li>
 *   <li>Query special attack costs for SpecialAttackManager</li>
 *   <li>Avoid hardcoded weapon data that may become outdated</li>
 * </ul>
 */
@Builder
public record WeaponInfo(
        /**
         * Item ID of the weapon.
         */
        int itemId,

        /**
         * Weapon name as it appears on wiki.
         */
        String itemName,

        /**
         * Attack speed in game ticks (600ms per tick).
         * Common values: 3 (fast), 4 (average), 5 (slow), 6 (slowest).
         * -1 if unknown.
         */
        int attackSpeed,

        /**
         * Combat style categories this weapon can use.
         * (e.g., "Slash", "Stab", "Crush" for melee)
         */
        String combatStyle,

        /**
         * Weapon category (e.g., "Sword", "Scimitar", "2h sword", "Bow").
         */
        String weaponCategory,

        /**
         * Whether this weapon has a special attack.
         */
        boolean hasSpecialAttack,

        /**
         * Special attack energy cost (0-100).
         * -1 if no special attack or unknown.
         */
        int specialAttackCost,

        /**
         * Description of the special attack effect.
         */
        String specialAttackDescription,

        /**
         * Whether this is a two-handed weapon.
         */
        boolean twoHanded,

        /**
         * Attack bonus for stab style.
         */
        int stabBonus,

        /**
         * Attack bonus for slash style.
         */
        int slashBonus,

        /**
         * Attack bonus for crush style.
         */
        int crushBonus,

        /**
         * Attack bonus for magic.
         */
        int magicBonus,

        /**
         * Attack bonus for ranged.
         */
        int rangedBonus,

        /**
         * Strength bonus for melee.
         */
        int strengthBonus,

        /**
         * Ranged strength bonus.
         */
        int rangedStrengthBonus,

        /**
         * Magic damage bonus (percentage).
         */
        int magicDamageBonus,

        /**
         * Prayer bonus.
         */
        int prayerBonus,

        /**
         * Wiki page URL for reference.
         */
        String wikiUrl,

        /**
         * Timestamp when this data was fetched.
         */
        String fetchedAt
) {
    /**
     * Common attack speed constants.
     */
    public static final int SPEED_FASTEST = 2;  // Dart, knife
    public static final int SPEED_FAST = 3;     // Scimitar, whip
    public static final int SPEED_AVERAGE = 4;  // Longsword, battleaxe
    public static final int SPEED_SLOW = 5;     // 2h sword, godsword
    public static final int SPEED_SLOWEST = 6;  // Maul, heavy weapons
    public static final int SPEED_UNKNOWN = -1;

    /**
     * Get the attack interval in milliseconds.
     *
     * @return attack interval in ms, or -1 if unknown
     */
    public int attackIntervalMs() {
        if (attackSpeed <= 0) {
            return -1;
        }
        return attackSpeed * 600;
    }

    /**
     * Get the attacks per minute for this weapon.
     *
     * @return attacks per minute, or -1 if unknown
     */
    public double attacksPerMinute() {
        if (attackSpeed <= 0) {
            return -1;
        }
        return 60.0 / (attackSpeed * 0.6);
    }

    /**
     * Check if this weapon is melee.
     *
     * @return true if melee weapon
     */
    public boolean isMelee() {
        if (combatStyle == null) {
            return false;
        }
        String lower = combatStyle.toLowerCase();
        return lower.contains("slash") || lower.contains("stab") || lower.contains("crush");
    }

    /**
     * Check if this weapon is ranged.
     *
     * @return true if ranged weapon
     */
    public boolean isRanged() {
        if (combatStyle == null) {
            return false;
        }
        String lower = combatStyle.toLowerCase();
        return lower.contains("ranged") || lower.contains("accurate") ||
               (weaponCategory != null && weaponCategory.toLowerCase().contains("bow"));
    }

    /**
     * Check if this weapon is magic.
     *
     * @return true if magic weapon
     */
    public boolean isMagic() {
        if (combatStyle == null) {
            return false;
        }
        return combatStyle.toLowerCase().contains("magic") || magicBonus > 0;
    }

    /**
     * Get the primary attack style based on highest bonus.
     *
     * @return "Stab", "Slash", "Crush", "Ranged", "Magic", or "Unknown"
     */
    public String getPrimaryStyle() {
        int maxMelee = Math.max(Math.max(stabBonus, slashBonus), crushBonus);
        
        if (rangedBonus > maxMelee && rangedBonus > magicBonus) {
            return "Ranged";
        }
        if (magicBonus > maxMelee && magicBonus > rangedBonus) {
            return "Magic";
        }
        if (maxMelee <= 0) {
            return "Unknown";
        }
        if (stabBonus == maxMelee) {
            return "Stab";
        }
        if (slashBonus == maxMelee) {
            return "Slash";
        }
        return "Crush";
    }

    /**
     * Check if this weapon can perform special attacks.
     *
     * @return true if has special attack
     */
    public boolean canSpecial() {
        return hasSpecialAttack && specialAttackCost > 0 && specialAttackCost <= 100;
    }

    /**
     * Calculate how many special attacks can be performed from 100% energy.
     *
     * @return max spec count, or 0 if no special
     */
    public int maxSpecCount() {
        if (!canSpecial()) {
            return 0;
        }
        return 100 / specialAttackCost;
    }

    /**
     * Check if this is valid weapon info.
     *
     * @return true if valid
     */
    public boolean isValid() {
        return itemName != null && !itemName.isEmpty() && itemId > 0;
    }

    /**
     * Check if attack speed is known.
     *
     * @return true if attack speed is known
     */
    public boolean hasKnownAttackSpeed() {
        return attackSpeed > 0;
    }

    /**
     * Empty weapon info singleton.
     */
    public static final WeaponInfo EMPTY = WeaponInfo.builder()
            .itemId(-1)
            .itemName("")
            .attackSpeed(SPEED_UNKNOWN)
            .combatStyle("")
            .weaponCategory("")
            .hasSpecialAttack(false)
            .specialAttackCost(-1)
            .specialAttackDescription("")
            .twoHanded(false)
            .stabBonus(0)
            .slashBonus(0)
            .crushBonus(0)
            .magicBonus(0)
            .rangedBonus(0)
            .strengthBonus(0)
            .rangedStrengthBonus(0)
            .magicDamageBonus(0)
            .prayerBonus(0)
            .wikiUrl("")
            .fetchedAt("")
            .build();

    /**
     * Create a weapon info with just attack speed and spec cost.
     * Used for minimal fallback data.
     *
     * @param itemId   the item ID
     * @param itemName the item name
     * @param speed    attack speed in ticks
     * @param specCost special attack cost (0 if none)
     * @return minimal weapon info
     */
    public static WeaponInfo minimal(int itemId, String itemName, int speed, int specCost) {
        return WeaponInfo.builder()
                .itemId(itemId)
                .itemName(itemName)
                .attackSpeed(speed)
                .combatStyle("")
                .weaponCategory("")
                .hasSpecialAttack(specCost > 0)
                .specialAttackCost(specCost > 0 ? specCost : -1)
                .specialAttackDescription("")
                .twoHanded(false)
                .stabBonus(0)
                .slashBonus(0)
                .crushBonus(0)
                .magicBonus(0)
                .rangedBonus(0)
                .strengthBonus(0)
                .rangedStrengthBonus(0)
                .magicDamageBonus(0)
                .prayerBonus(0)
                .wikiUrl("")
                .fetchedAt("")
                .build();
    }
}

