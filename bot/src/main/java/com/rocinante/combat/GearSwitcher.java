package com.rocinante.combat;

import com.rocinante.core.GameStateService;
import com.rocinante.input.InventoryClickHelper;
import com.rocinante.state.EquipmentState;
import com.rocinante.state.InventoryState;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Handles equipment switching with humanized timing.
 *
 * Per REQUIREMENTS.md Section 10.3:
 * <ul>
 *   <li>Switch Sets (10.3.1): Define gear sets with item IDs, partial sets supported</li>
 *   <li>Switch Execution (10.3.2): Humanized delays (80-150ms), weapon last, failure handling</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Switch to a predefined gear set
 * GearSet rangedGear = GearSet.builder()
 *     .name("Ranged")
 *     .weapon(ItemID.MAGIC_SHORTBOW)
 *     .ammo(ItemID.RUNE_ARROW)
 *     .build();
 *
 * gearSwitcher.switchTo(rangedGear).thenRun(() -> {
 *     log.info("Ranged gear equipped!");
 * });
 *
 * // Auto-detect and equip for attack style
 * gearSwitcher.equipForStyle(AttackStyle.RANGED);
 * }</pre>
 */
@Slf4j
@Singleton
public class GearSwitcher {

    // ========================================================================
    // Constants
    // ========================================================================

    /**
     * Minimum delay between equipment clicks (ms).
     * Per Section 10.3.2: humanized delays between each equipment click (80-150ms).
     */
    private static final int MIN_CLICK_DELAY_MS = 80;

    /**
     * Maximum delay between equipment clicks (ms).
     */
    private static final int MAX_CLICK_DELAY_MS = 150;

    // ========================================================================
    // Dependencies
    // ========================================================================

    private final Client client;
    private final GameStateService gameStateService;
    private final InventoryClickHelper inventoryClickHelper;

    // ========================================================================
    // State
    // ========================================================================

    /**
     * Named gear sets for quick switching.
     */
    @Getter
    private final Map<String, GearSet> savedSets = new HashMap<>();

    /**
     * Whether a switch is currently in progress.
     */
    @Getter
    private volatile boolean switching = false;

    /**
     * Minimum click delay (configurable).
     */
    @Getter
    @Setter
    private int minClickDelay = MIN_CLICK_DELAY_MS;

    /**
     * Maximum click delay (configurable).
     */
    @Getter
    @Setter
    private int maxClickDelay = MAX_CLICK_DELAY_MS;

    // ========================================================================
    // Constructor
    // ========================================================================

    @Inject
    public GearSwitcher(Client client, GameStateService gameStateService, 
                        InventoryClickHelper inventoryClickHelper) {
        this.client = client;
        this.gameStateService = gameStateService;
        this.inventoryClickHelper = inventoryClickHelper;
        log.info("GearSwitcher initialized");
    }

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Switch to a gear set.
     *
     * @param gearSet the gear set to equip
     * @return CompletableFuture that completes when switch is done (or fails)
     */
    public CompletableFuture<Boolean> switchTo(GearSet gearSet) {
        if (switching) {
            log.warn("Gear switch already in progress");
            return CompletableFuture.completedFuture(false);
        }

        if (gearSet == null || gearSet.isEmpty()) {
            log.debug("Empty gear set, nothing to switch");
            return CompletableFuture.completedFuture(true);
        }

        EquipmentState equipment = gameStateService.getEquipmentState();
        InventoryState inventory = gameStateService.getInventoryState();

        // Check if already equipped
        if (gearSet.isEquipped(equipment)) {
            log.debug("Gear set '{}' already equipped", gearSet.getName());
            return CompletableFuture.completedFuture(true);
        }

        // Pre-validate items
        if (!gearSet.isAvailable(inventory, equipment)) {
            log.warn("Gear set '{}' not available - missing items", gearSet.getName());
            return CompletableFuture.completedFuture(false);
        }

        // Get items to equip
        Map<Integer, Integer> itemsToEquip = gearSet.getItemsToEquip(equipment);
        if (itemsToEquip.isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }

        log.debug("Switching to gear set '{}' - {} items to equip", 
                gearSet.getName(), itemsToEquip.size());

        // Order items: weapon last to avoid losing attack tick (per Section 10.3.2)
        List<Map.Entry<Integer, Integer>> orderedItems = orderForSwitch(itemsToEquip);

        switching = true;
        return executeSwitch(orderedItems, 0)
                .whenComplete((result, error) -> {
                    switching = false;
                    if (error != null) {
                        log.error("Gear switch failed", error);
                    } else if (result) {
                        log.debug("Gear switch completed successfully");
                    } else {
                        log.warn("Gear switch incomplete");
                    }
                });
    }

    /**
     * Switch to a saved gear set by name.
     *
     * @param setName the name of the saved gear set
     * @return CompletableFuture that completes when switch is done
     */
    public CompletableFuture<Boolean> switchTo(String setName) {
        GearSet gearSet = savedSets.get(setName);
        if (gearSet == null) {
            log.warn("Unknown gear set: {}", setName);
            return CompletableFuture.completedFuture(false);
        }
        return switchTo(gearSet);
    }

    /**
     * Auto-detect and equip gear for an attack style.
     * Scans inventory for appropriate weapons/ammo.
     *
     * @param style the attack style to equip for
     * @return CompletableFuture that completes when switch is done
     */
    public CompletableFuture<Boolean> equipForStyle(AttackStyle style) {
        InventoryState inventory = gameStateService.getInventoryState();
        EquipmentState equipment = gameStateService.getEquipmentState();

        GearSet autoSet = GearSet.autoDetect(style, inventory, equipment);

        if (autoSet.isEmpty()) {
            log.warn("No gear found for attack style: {}", style);
            return CompletableFuture.completedFuture(false);
        }

        log.debug("Auto-detected gear for {}: {}", style, autoSet.getSummary());
        return switchTo(autoSet);
    }

    /**
     * Equip a single item from inventory.
     *
     * @param itemId the item ID to equip
     * @return CompletableFuture that completes when equip is done
     */
    public CompletableFuture<Boolean> equipItem(int itemId) {
        InventoryState inventory = gameStateService.getInventoryState();

        int slot = inventory.getSlotOf(itemId);
        if (slot == -1) {
            log.warn("Item {} not found in inventory", itemId);
            return CompletableFuture.completedFuture(false);
        }

        return inventoryClickHelper.executeClick(slot, "Equipping item " + itemId);
    }

    /**
     * Save a gear set for later use.
     *
     * @param name    the name to save under
     * @param gearSet the gear set to save
     */
    public void saveSet(String name, GearSet gearSet) {
        savedSets.put(name, gearSet);
        log.debug("Saved gear set: {}", name);
    }

    /**
     * Remove a saved gear set.
     *
     * @param name the name of the set to remove
     * @return true if removed
     */
    public boolean removeSet(String name) {
        return savedSets.remove(name) != null;
    }

    /**
     * Check if any equipment needs to be switched for a gear set.
     *
     * @param gearSet the gear set to check
     * @return true if switch is needed
     */
    public boolean needsSwitch(GearSet gearSet) {
        if (gearSet == null || gearSet.isEmpty()) {
            return false;
        }
        return !gearSet.isEquipped(gameStateService.getEquipmentState());
    }

    // ========================================================================
    // Switch Execution (Section 10.3.2)
    // ========================================================================

    /**
     * Order items for optimal switch (weapon last).
     * Per Section 10.3.2: Optimal order - weapon last to avoid losing attack tick.
     */
    private List<Map.Entry<Integer, Integer>> orderForSwitch(Map<Integer, Integer> items) {
        List<Map.Entry<Integer, Integer>> ordered = new ArrayList<>(items.entrySet());

        // Sort: weapon slot (3) goes last
        ordered.sort((a, b) -> {
            boolean aIsWeapon = a.getKey() == EquipmentState.SLOT_WEAPON;
            boolean bIsWeapon = b.getKey() == EquipmentState.SLOT_WEAPON;
            if (aIsWeapon && !bIsWeapon) return 1;
            if (!aIsWeapon && bIsWeapon) return -1;
            return 0;
        });

        return ordered;
    }

    /**
     * Execute the gear switch recursively with delays.
     */
    private CompletableFuture<Boolean> executeSwitch(List<Map.Entry<Integer, Integer>> items, int index) {
        if (index >= items.size()) {
            return CompletableFuture.completedFuture(true);
        }

        Map.Entry<Integer, Integer> item = items.get(index);
        int slotIndex = item.getKey();
        int itemId = item.getValue();

        // Find item in inventory
        InventoryState inventory = gameStateService.getInventoryState();
        int invSlot = inventory.getSlotOf(itemId);

        if (invSlot == -1) {
            // Item might already be equipped (switched by previous action)
            EquipmentState equipment = gameStateService.getEquipmentState();
            if (equipment.getEquippedItem(slotIndex)
                    .map(i -> i.getId() == itemId)
                    .orElse(false)) {
                // Already equipped, continue to next
                return executeSwitch(items, index + 1);
            }
            log.warn("Item {} not found for slot {}", itemId, slotIndex);
            // Continue with remaining items (failure handling per Section 10.3.2)
            return executeSwitch(items, index + 1);
        }

        // Click the inventory slot to equip
        return inventoryClickHelper.executeClick(invSlot, "Equipping item " + itemId)
                .thenCompose(success -> {
                    if (!success) {
                        log.warn("Failed to equip item {} in slot {}", itemId, invSlot);
                    }

                    // Humanized delay before next click
                    int delay = randomDelay();
                    return delayThen(delay, () -> executeSwitch(items, index + 1));
                });
    }

    // ========================================================================
    // Humanization Helpers
    // ========================================================================

    /**
     * Generate a random delay within configured range.
     */
    private int randomDelay() {
        return ThreadLocalRandom.current().nextInt(minClickDelay, maxClickDelay + 1);
    }

    /**
     * Execute an action after a delay.
     */
    private <T> CompletableFuture<T> delayThen(int delayMs, java.util.function.Supplier<CompletableFuture<T>> action) {
        CompletableFuture<T> result = new CompletableFuture<>();

        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                log.debug("Gear switch delay interrupted: {}", e.getMessage());
                Thread.currentThread().interrupt();
            }
        }).thenCompose(v -> action.get())
                .whenComplete((value, error) -> {
                    if (error != null) {
                        result.completeExceptionally(error);
                    } else {
                        result.complete(value);
                    }
                });

        return result;
    }
}

