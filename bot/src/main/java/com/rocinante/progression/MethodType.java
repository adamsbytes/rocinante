package com.rocinante.progression;

/**
 * Types of skill training methods.
 *
 * Each type has different execution patterns in SkillTask:
 * <ul>
 *   <li>GATHER - Interact with objects/NPCs, wait for inventory to fill</li>
 *   <li>PROCESS - Use item on item, handle make-all interfaces</li>
 *   <li>COMBAT - Delegate to CombatTask for combat-based training</li>
 *   <li>RUNECRAFT - Special altar-based runecrafting</li>
 *   <li>AGILITY - Course-based obstacle running</li>
 *   <li>THIEVING - Pickpocket NPCs or steal from stalls</li>
 * </ul>
 */
public enum MethodType {

    /**
     * Gathering skills: Mining, Woodcutting, Fishing.
     * Pattern: Interact with object/NPC, wait for animation, repeat until inventory full.
     */
    GATHER,

    /**
     * Processing/production skills: Fletching, Crafting, Herblore, Cooking, Smithing.
     * Pattern: Use item on item, handle make-all interface, wait for completion.
     */
    PROCESS,

    /**
     * Combat-based training: Attack, Strength, Defence, Ranged, Magic, Hitpoints.
     * Pattern: Delegate to CombatTask with appropriate configuration.
     */
    COMBAT,

    /**
     * Runecrafting at altars.
     * Pattern: Bank for essence, travel to altar, craft runes, repeat.
     */
    RUNECRAFT,

    /**
     * Agility course training.
     * Pattern: Navigate obstacle course in sequence, repeat laps.
     */
    AGILITY,

    /**
     * Thieving from NPCs or stalls.
     * Pattern: Pickpocket NPC or steal from stall, handle stun, repeat.
     */
    THIEVING,

    /**
     * Firemaking (line-based log burning).
     * Pattern: Use tinderbox on logs, move west after each fire, reposition at line end.
     */
    FIREMAKING,

    /**
     * Minigame-based training (Wintertodt, Tempoross, Guardians of the Rift, etc.).
     * Pattern: Delegate to specific MinigameTask implementation.
     */
    MINIGAME
}

