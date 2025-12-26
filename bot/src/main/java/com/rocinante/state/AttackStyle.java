package com.rocinante.state;

/**
 * Combat attack styles in OSRS.
 *
 * Per REQUIREMENTS.md Section 10.1.3, used for prayer protection switching.
 */
public enum AttackStyle {

    /**
     * Melee attacks (protected by Protect from Melee).
     */
    MELEE(0, "Protect from Melee"),

    /**
     * Ranged attacks (protected by Protect from Missiles).
     */
    RANGED(1, "Protect from Missiles"),

    /**
     * Magic attacks (protected by Protect from Magic).
     */
    MAGIC(2, "Protect from Magic"),

    /**
     * Unknown or typeless attacks.
     */
    UNKNOWN(-1, null);

    private final int id;
    private final String protectionPrayer;

    AttackStyle(int id, String protectionPrayer) {
        this.id = id;
        this.protectionPrayer = protectionPrayer;
    }

    /**
     * Get the numeric ID for this attack style.
     *
     * @return style ID (0=melee, 1=ranged, 2=magic, -1=unknown)
     */
    public int getId() {
        return id;
    }

    /**
     * Get the protection prayer for this attack style.
     *
     * @return prayer name, or null for unknown
     */
    public String getProtectionPrayer() {
        return protectionPrayer;
    }

    /**
     * Get AttackStyle from numeric ID.
     *
     * @param id the style ID
     * @return corresponding AttackStyle
     */
    public static AttackStyle fromId(int id) {
        switch (id) {
            case 0:
                return MELEE;
            case 1:
                return RANGED;
            case 2:
                return MAGIC;
            default:
                return UNKNOWN;
        }
    }
}

