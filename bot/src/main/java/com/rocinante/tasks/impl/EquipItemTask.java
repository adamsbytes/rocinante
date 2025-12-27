package com.rocinante.tasks.impl;

import com.rocinante.combat.AttackStyle;
import com.rocinante.combat.GearSet;
import com.rocinante.combat.GearSwitcher;
import com.rocinante.combat.WeaponCategories;
import com.rocinante.state.EquipmentState;
import com.rocinante.state.InventoryState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Task for equipping items from inventory.
 *
 * <p>Supports three modes:
 * <ul>
 *   <li>Single item: Equip a specific item by ID</li>
 *   <li>Gear set: Equip a predefined gear set</li>
 *   <li>Attack style: Auto-detect and equip appropriate gear for a combat style</li>
 * </ul>
 *
 * <p>Uses {@link GearSwitcher} for humanized equipment switching.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Equip a specific item
 * EquipItemTask equipBow = new EquipItemTask(ItemID.MAGIC_SHORTBOW);
 *
 * // Equip a gear set
 * GearSet rangedGear = GearSet.builder()
 *     .name("Ranged")
 *     .weapon(ItemID.MAGIC_SHORTBOW)
 *     .ammo(ItemID.RUNE_ARROW)
 *     .build();
 * EquipItemTask equipRanged = new EquipItemTask(rangedGear);
 *
 * // Auto-equip for attack style
 * EquipItemTask equipForMagic = new EquipItemTask(AttackStyle.MAGIC);
 * }</pre>
 */
@Slf4j
public class EquipItemTask extends AbstractTask {

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * Single item ID to equip (-1 if using gear set or attack style).
     */
    @Getter
    private final int itemId;

    /**
     * Gear set to equip (null if using single item or attack style).
     */
    @Getter
    private final GearSet gearSet;

    /**
     * Attack style for auto-detection (null if using single item or gear set).
     */
    @Getter
    private final AttackStyle attackStyle;

    /**
     * Task description for logging.
     */
    @Getter
    private String description;

    // ========================================================================
    // State
    // ========================================================================

    /**
     * Whether the equip operation has been started.
     */
    private final AtomicBoolean started = new AtomicBoolean(false);

    /**
     * Future tracking the ongoing equip operation.
     */
    private final AtomicReference<CompletableFuture<Boolean>> pendingOperation = new AtomicReference<>();

    /**
     * The gear set being used (may be auto-detected).
     */
    private GearSet activeGearSet;

    // ========================================================================
    // Constructors
    // ========================================================================

    /**
     * Create a task to equip a single item by ID.
     *
     * @param itemId the item ID to equip
     */
    public EquipItemTask(int itemId) {
        this.itemId = itemId;
        this.gearSet = null;
        this.attackStyle = null;
        this.description = "Equip item " + itemId;
        this.timeout = Duration.ofSeconds(10);
    }

    /**
     * Create a task to equip a gear set.
     *
     * @param gearSet the gear set to equip
     */
    public EquipItemTask(GearSet gearSet) {
        this.itemId = -1;
        this.gearSet = gearSet;
        this.attackStyle = null;
        this.description = "Equip gear set: " + (gearSet != null ? gearSet.getName() : "null");
        this.timeout = Duration.ofSeconds(15); // Longer for multi-item switches
    }

    /**
     * Create a task to auto-equip for an attack style.
     *
     * @param attackStyle the attack style to equip for
     */
    public EquipItemTask(AttackStyle attackStyle) {
        this.itemId = -1;
        this.gearSet = null;
        this.attackStyle = attackStyle;
        this.description = "Equip for " + attackStyle.name() + " combat";
        this.timeout = Duration.ofSeconds(15);
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set a custom description.
     *
     * @param description the description
     * @return this for chaining
     */
    public EquipItemTask withDescription(String description) {
        this.description = description;
        return this;
    }

    // ========================================================================
    // Task Implementation
    // ========================================================================

    @Override
    public boolean canExecute(TaskContext ctx) {
        // Need GearSwitcher in context
        if (ctx.getGearSwitcher() == null) {
            log.error("GearSwitcher not available in TaskContext");
            return false;
        }

        InventoryState inventory = ctx.getGameStateService().getInventoryState();
        EquipmentState equipment = ctx.getGameStateService().getEquipmentState();

        // If equipping single item, check it exists in inventory or is already equipped
        if (itemId > 0) {
            if (!inventory.hasItem(itemId) && !equipment.hasEquipped(itemId)) {
                log.debug("Item {} not in inventory or equipment", itemId);
                return false;
            }
        }

        // If equipping gear set, verify items available
        if (gearSet != null && !gearSet.isEmpty()) {
            if (!gearSet.isAvailable(inventory, equipment)) {
                log.debug("Gear set '{}' items not available", gearSet.getName());
                return false;
            }
        }

        // For attack style, we'll try to auto-detect - verify later
        return true;
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        // Check if already complete
        if (isEquipmentDone(ctx)) {
            log.debug("Equipment already in desired state");
            complete();
            return;
        }

        // Start the equip operation if not already started
        if (started.compareAndSet(false, true)) {
            GearSwitcher switcher = ctx.getGearSwitcher();
            CompletableFuture<Boolean> future;

            if (itemId > 0) {
                // Single item equip
                log.debug("Starting single item equip: {}", itemId);
                future = switcher.equipItem(itemId);
            } else if (gearSet != null) {
                // Gear set equip
                log.debug("Starting gear set equip: {}", gearSet.getName());
                activeGearSet = gearSet;
                future = switcher.switchTo(gearSet);
            } else if (attackStyle != null) {
                // Auto-detect for attack style
                log.debug("Starting auto-equip for style: {}", attackStyle);
                
                // Auto-detect the gear set
                InventoryState inventory = ctx.getGameStateService().getInventoryState();
                EquipmentState equipment = ctx.getGameStateService().getEquipmentState();
                activeGearSet = GearSet.autoDetect(attackStyle, inventory, equipment);
                
                if (activeGearSet.isEmpty()) {
                    log.warn("No gear found in inventory for attack style: {}", attackStyle);
                    // Check if already equipped for this style
                    if (checkStyleEquipped(equipment, attackStyle)) {
                        complete();
                        return;
                    }
                    fail("No gear available for attack style: " + attackStyle);
                    return;
                }
                
                future = switcher.switchTo(activeGearSet);
            } else {
                // No valid configuration
                fail("No item, gear set, or attack style specified");
                return;
            }

            pendingOperation.set(future);

            // Handle completion
            future.whenComplete((success, error) -> {
                if (error != null) {
                    log.error("Equip operation failed", error);
                    fail("Equip failed: " + error.getMessage());
                } else if (!success) {
                    // Don't fail immediately - let isEquipmentDone check
                    log.warn("Equip operation returned false");
                }
                // Success will be detected on next tick via isEquipmentDone
            });
        }

        // Check if operation completed
        CompletableFuture<Boolean> future = pendingOperation.get();
        if (future != null && future.isDone()) {
            if (isEquipmentDone(ctx)) {
                complete();
            } else if (future.isCompletedExceptionally()) {
                // Already handled in whenComplete
            }
            // If not done, keep waiting or let timeout handle it
        }
    }

    /**
     * Check if the equipment is in the desired state.
     */
    private boolean isEquipmentDone(TaskContext ctx) {
        EquipmentState equipment = ctx.getGameStateService().getEquipmentState();

        if (itemId > 0) {
            // Single item - check if equipped
            return equipment.hasEquipped(itemId);
        } else if (gearSet != null && !gearSet.isEmpty()) {
            // Gear set - check if all items equipped
            return gearSet.isEquipped(equipment);
        } else if (activeGearSet != null && !activeGearSet.isEmpty()) {
            // Auto-detected gear set
            return activeGearSet.isEquipped(equipment);
        } else if (attackStyle != null) {
            // For attack style without a gear set, check if style is equipped
            return checkStyleEquipped(equipment, attackStyle);
        }

        // No requirement specified - consider done
        return true;
    }

    /**
     * Check if appropriate gear for an attack style is equipped.
     */
    private boolean checkStyleEquipped(EquipmentState equipment, AttackStyle style) {
        int weaponId = equipment.getWeaponId();

        switch (style) {
            case RANGED:
                // Check for ranged weapons (bows, crossbows, thrown)
                return isRangedWeapon(weaponId);
            case MAGIC:
                // Check for magic weapons (staffs, wands)
                return isMagicWeapon(weaponId);
            case MELEE:
            default:
                // Melee is the default - as long as not ranged/magic, consider it done
                // Also consider done if no weapon equipped (unarmed melee)
                return weaponId == -1 || (!isRangedWeapon(weaponId) && !isMagicWeapon(weaponId));
        }
    }

    /**
     * Check if weapon ID is a known ranged weapon.
     *
     * @param weaponId the weapon ID to check
     * @return true if it's a ranged weapon
     */
    private boolean isRangedWeapon(int weaponId) {
        return WeaponCategories.isRangedWeapon(weaponId);
    }

    /**
     * Check if weapon ID is a known magic weapon.
     *
     * @param weaponId the weapon ID to check
     * @return true if it's a magic weapon
     */
    private boolean isMagicWeapon(int weaponId) {
        return WeaponCategories.isMagicWeapon(weaponId);
    }

    @Override
    public String getDescription() {
        return description;
    }
}
