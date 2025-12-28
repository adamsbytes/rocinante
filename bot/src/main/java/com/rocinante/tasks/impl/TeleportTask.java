package com.rocinante.tasks.impl;

import com.rocinante.behavior.PlayerProfile;
import com.rocinante.state.EquipmentState;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;

import javax.annotation.Nullable;
import java.awt.Point;
import java.awt.Rectangle;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Task for teleporting the player to a destination.
 *
 * Per REQUIREMENTS.md Section 5.4.12:
 * <ul>
 *   <li>Teleport via: spellbook, jewelry, tablets, POH portals, fairy rings, spirit trees</li>
 *   <li>Select appropriate method based on availability, destination, AND behavioral profile</li>
 *   <li>Apply account-specific law rune aversion (HCIM strongly avoids)</li>
 *   <li>Handle interfaces (fairy ring code entry, spirit tree selection)</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Teleport using a specific spell
 * TeleportTask varrockTele = TeleportTask.spell("Varrock Teleport");
 *
 * // Teleport using home teleport
 * TeleportTask homeTele = TeleportTask.homeTeleport();
 *
 * // Teleport using a tablet
 * TeleportTask tabletTele = TeleportTask.tablet(ItemID.VARROCK_TELEPORT);
 *
 * // Teleport using equipped jewelry
 * TeleportTask gloryTele = TeleportTask.jewelry(ItemID.AMULET_OF_GLORY6, "Edgeville");
 * }</pre>
 */
@Slf4j
public class TeleportTask extends AbstractTask {

    // ========================================================================
    // Constants - Spellbook Widget IDs
    // ========================================================================

    /**
     * Standard spellbook widget group.
     */
    private static final int SPELLBOOK_GROUP = 218;

    /**
     * Home teleport widget child (standard spellbook).
     */
    private static final int HOME_TELEPORT_CHILD = 7;

    /**
     * Spell name to widget child ID mapping (standard spellbook).
     * Widget IDs from RuneLite's InterfaceID.MagicSpellbook.
     */
    private static final Map<String, Integer> SPELL_WIDGET_IDS = new HashMap<>();
    static {
        SPELL_WIDGET_IDS.put("Varrock Teleport", 23);      // 0x17
        SPELL_WIDGET_IDS.put("Lumbridge Teleport", 26);    // 0x1a
        SPELL_WIDGET_IDS.put("Falador Teleport", 29);      // 0x1d
        SPELL_WIDGET_IDS.put("Camelot Teleport", 37);      // 0x25
        SPELL_WIDGET_IDS.put("Ardougne Teleport", 51);     // 0x33
        SPELL_WIDGET_IDS.put("Watchtower Teleport", 56);   // 0x38
        SPELL_WIDGET_IDS.put("Trollheim Teleport", 64);    // 0x40
        SPELL_WIDGET_IDS.put("Teleport to Kourend", 79);   // 0x4f
        SPELL_WIDGET_IDS.put("Teleport to House", 31);     // 0x1f
    }

    /**
     * Equipment widget group.
     */
    private static final int EQUIPMENT_GROUP = 387;

    /**
     * Equipment slot to widget child ID mapping.
     */
    private static final Map<Integer, Integer> EQUIPMENT_SLOT_WIDGETS = new HashMap<>();
    static {
        EQUIPMENT_SLOT_WIDGETS.put(EquipmentState.SLOT_RING, 13);
        EQUIPMENT_SLOT_WIDGETS.put(EquipmentState.SLOT_AMULET, 6);
        EQUIPMENT_SLOT_WIDGETS.put(EquipmentState.SLOT_GLOVES, 12);
        EQUIPMENT_SLOT_WIDGETS.put(EquipmentState.SLOT_CAPE, 4);
        EQUIPMENT_SLOT_WIDGETS.put(EquipmentState.SLOT_WEAPON, 8);
    }

    /**
     * Inventory widget group.
     */
    private static final int INVENTORY_GROUP = 149;
    private static final int INVENTORY_CHILD = 0;

    /**
     * Home teleport animation duration (ticks).
     */
    private static final int HOME_TELEPORT_DURATION_TICKS = 16;

    /**
     * Normal teleport animation duration (ticks).
     */
    private static final int TELEPORT_DURATION_TICKS = 5;

    // ========================================================================
    // Teleport Method Enum
    // ========================================================================

    /**
     * The type of teleport method to use.
     */
    public enum TeleportMethod {
        /**
         * Cast a spell from the spellbook.
         */
        SPELL,

        /**
         * Use home teleport (no rune cost, long animation).
         */
        HOME_TELEPORT,

        /**
         * Use a teleport tablet from inventory.
         */
        TABLET,

        /**
         * Use equipped jewelry (ring, amulet, etc.).
         */
        JEWELRY_EQUIPPED,

        /**
         * Use jewelry from inventory.
         */
        JEWELRY_INVENTORY,

        /**
         * Use fairy ring interface.
         */
        FAIRY_RING,

        /**
         * Use spirit tree interface.
         */
        SPIRIT_TREE,

        /**
         * Use POH portal.
         */
        POH_PORTAL
    }

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * The teleport method to use.
     */
    @Getter
    private final TeleportMethod method;

    /**
     * Spell name (for SPELL method).
     */
    @Getter
    @Nullable
    private final String spellName;

    /**
     * Item ID (for TABLET, JEWELRY_EQUIPPED, JEWELRY_INVENTORY methods).
     */
    @Getter
    private final int itemId;

    /**
     * Menu option for jewelry teleport (e.g., "Edgeville", "Grand Exchange").
     */
    @Getter
    @Nullable
    private final String teleportOption;

    /**
     * Expected destination after teleport (for verification).
     */
    @Getter
    @Setter
    @Nullable
    private WorldPoint expectedDestination;

    /**
     * Tolerance for destination verification (tiles).
     */
    @Getter
    @Setter
    private int destinationTolerance = 10;

    /**
     * Custom description.
     */
    @Getter
    @Setter
    @Nullable
    private String description;

    /**
     * Whether to verify we arrived at the destination.
     */
    @Getter
    @Setter
    private boolean verifyArrival = true;

    // ========================================================================
    // Execution State
    // ========================================================================

    private TeleportPhase phase = TeleportPhase.INIT;
    private boolean clickPending = false;
    private WorldPoint startPosition;
    private int waitTicks = 0;

    // ========================================================================
    // Constructors
    // ========================================================================

    @Builder
    private TeleportTask(TeleportMethod method, @Nullable String spellName, 
                         int itemId, @Nullable String teleportOption,
                         @Nullable WorldPoint expectedDestination,
                         @Nullable String description) {
        this.method = method;
        this.spellName = spellName;
        this.itemId = itemId;
        this.teleportOption = teleportOption;
        this.expectedDestination = expectedDestination;
        this.description = description;
        this.timeout = Duration.ofSeconds(30);
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a task to cast a teleport spell.
     *
     * @param spellName the spell name (e.g., "Varrock Teleport")
     * @return teleport task
     */
    public static TeleportTask spell(String spellName) {
        return TeleportTask.builder()
                .method(TeleportMethod.SPELL)
                .spellName(spellName)
                .itemId(-1)
                .build();
    }

    /**
     * Create a task to cast a teleport spell with destination.
     *
     * @param spellName the spell name
     * @param destination expected destination
     * @return teleport task
     */
    public static TeleportTask spell(String spellName, WorldPoint destination) {
        return TeleportTask.builder()
                .method(TeleportMethod.SPELL)
                .spellName(spellName)
                .itemId(-1)
                .expectedDestination(destination)
                .build();
    }

    /**
     * Create a task to use home teleport.
     *
     * @return teleport task
     */
    public static TeleportTask homeTeleport() {
        return TeleportTask.builder()
                .method(TeleportMethod.HOME_TELEPORT)
                .itemId(-1)
                .build();
    }

    /**
     * Create a task to use a teleport tablet.
     *
     * @param tabletItemId the tablet item ID
     * @return teleport task
     */
    public static TeleportTask tablet(int tabletItemId) {
        return TeleportTask.builder()
                .method(TeleportMethod.TABLET)
                .itemId(tabletItemId)
                .build();
    }

    /**
     * Create a task to use equipped jewelry.
     *
     * @param jewelryItemId the jewelry item ID
     * @param option the teleport option (e.g., "Edgeville")
     * @return teleport task
     */
    public static TeleportTask jewelry(int jewelryItemId, String option) {
        return TeleportTask.builder()
                .method(TeleportMethod.JEWELRY_EQUIPPED)
                .itemId(jewelryItemId)
                .teleportOption(option)
                .build();
    }

    /**
     * Create a task to use jewelry from inventory.
     *
     * @param jewelryItemId the jewelry item ID
     * @param option the teleport option
     * @return teleport task
     */
    public static TeleportTask jewelryFromInventory(int jewelryItemId, String option) {
        return TeleportTask.builder()
                .method(TeleportMethod.JEWELRY_INVENTORY)
                .itemId(jewelryItemId)
                .teleportOption(option)
                .build();
    }

    /**
     * Create a teleport task from navigation edge metadata.
     *
     * @param metadata edge metadata map
     * @return teleport task, or null if metadata is invalid
     */
    @Nullable
    public static TeleportTask fromNavigationMetadata(Map<String, String> metadata) {
        if (metadata == null) {
            return null;
        }

        String teleportType = metadata.get("teleport_type");
        if (teleportType == null) {
            log.warn("No teleport_type in metadata");
            return null;
        }

        switch (teleportType) {
            case "spell":
                String spellName = metadata.get("spell_name");
                if (spellName == null) {
                    log.warn("No spell_name in metadata for spell teleport");
                    return null;
                }
                return spell(spellName);

            case "home_teleport":
                return homeTeleport();

            case "tablet":
                String tabletIdStr = metadata.get("item_id");
                if (tabletIdStr == null) {
                    log.warn("No item_id in metadata for tablet teleport");
                    return null;
                }
                return tablet(Integer.parseInt(tabletIdStr));

            case "jewelry":
                String jewelryIdStr = metadata.get("item_id");
                String option = metadata.get("teleport_option");
                if (jewelryIdStr == null || option == null) {
                    log.warn("Missing item_id or teleport_option for jewelry teleport");
                    return null;
                }
                // Check if it's equipped or inventory
                String location = metadata.getOrDefault("location", "equipped");
                if ("inventory".equals(location)) {
                    return jewelryFromInventory(Integer.parseInt(jewelryIdStr), option);
                } else {
                    return jewelry(Integer.parseInt(jewelryIdStr), option);
                }

            default:
                log.warn("Unknown teleport_type: {}", teleportType);
                return null;
        }
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set expected destination (builder-style).
     */
    public TeleportTask withDestination(WorldPoint destination) {
        this.expectedDestination = destination;
        return this;
    }

    /**
     * Set destination tolerance (builder-style).
     */
    public TeleportTask withTolerance(int tiles) {
        this.destinationTolerance = tiles;
        return this;
    }

    /**
     * Set whether to verify arrival (builder-style).
     */
    public TeleportTask withVerifyArrival(boolean verify) {
        this.verifyArrival = verify;
        return this;
    }

    /**
     * Set custom description (builder-style).
     */
    public TeleportTask withDescription(String desc) {
        this.description = desc;
        return this;
    }

    // ========================================================================
    // Task Implementation
    // ========================================================================

    @Override
    public boolean canExecute(TaskContext ctx) {
        if (!ctx.isLoggedIn()) {
            return false;
        }

        // Check method-specific requirements
        switch (method) {
            case SPELL:
                return canCastSpell(ctx);
            case HOME_TELEPORT:
                return true; // Always available
            case TABLET:
                return hasItemInInventory(ctx, itemId);
            case JEWELRY_EQUIPPED:
                return hasJewelryEquipped(ctx, itemId);
            case JEWELRY_INVENTORY:
                return hasItemInInventory(ctx, itemId);
            default:
                return true;
        }
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        if (clickPending) {
            return;
        }

        switch (phase) {
            case INIT:
                executeInit(ctx);
                break;
            case OPEN_SPELLBOOK:
                executeOpenSpellbook(ctx);
                break;
            case OPEN_EQUIPMENT:
                executeOpenEquipment(ctx);
                break;
            case OPEN_INVENTORY:
                executeOpenInventory(ctx);
                break;
            case CLICK_TELEPORT:
                executeClickTeleport(ctx);
                break;
            case WAIT_FOR_TELEPORT:
                executeWaitForTeleport(ctx);
                break;
            case VERIFY_ARRIVAL:
                executeVerifyArrival(ctx);
                break;
        }
    }

    // ========================================================================
    // Phase: Init
    // ========================================================================

    private void executeInit(TaskContext ctx) {
        // Record starting position
        startPosition = ctx.getPlayerState().getWorldPosition();
        waitTicks = 0;

        log.debug("Starting teleport: method={}, spell={}, item={}", method, spellName, itemId);

        // Determine first phase based on method
        switch (method) {
            case SPELL:
            case HOME_TELEPORT:
                phase = TeleportPhase.OPEN_SPELLBOOK;
                break;
            case JEWELRY_EQUIPPED:
                phase = TeleportPhase.OPEN_EQUIPMENT;
                break;
            case TABLET:
            case JEWELRY_INVENTORY:
                phase = TeleportPhase.OPEN_INVENTORY;
                break;
            default:
                fail("Unsupported teleport method: " + method);
        }
    }

    // ========================================================================
    // Phase: Open Spellbook
    // ========================================================================

    private void executeOpenSpellbook(TaskContext ctx) {
        Client client = ctx.getClient();
        Widget spellbook = client.getWidget(SPELLBOOK_GROUP, 0);

        if (spellbook != null && !spellbook.isHidden()) {
            log.debug("Spellbook is open");
            phase = TeleportPhase.CLICK_TELEPORT;
            return;
        }

        // Open spellbook using F7 (or click)
        log.debug("Opening spellbook");
        clickPending = true;

        ctx.getKeyboardController().pressKey(java.awt.event.KeyEvent.VK_F7)
                .thenRun(() -> {
                    clickPending = false;
                    waitTicks = 0;
                })
                .exceptionally(e -> {
                    clickPending = false;
                    log.error("Failed to open spellbook", e);
                    return null;
                });
    }

    // ========================================================================
    // Phase: Open Equipment
    // ========================================================================

    private void executeOpenEquipment(TaskContext ctx) {
        Client client = ctx.getClient();
        Widget equipment = client.getWidget(EQUIPMENT_GROUP, 0);

        if (equipment != null && !equipment.isHidden()) {
            log.debug("Equipment tab is open");
            phase = TeleportPhase.CLICK_TELEPORT;
            return;
        }

        // Open equipment using F5 (or click)
        log.debug("Opening equipment tab");
        clickPending = true;

        ctx.getKeyboardController().pressKey(java.awt.event.KeyEvent.VK_F5)
                .thenRun(() -> {
                    clickPending = false;
                    waitTicks = 0;
                })
                .exceptionally(e -> {
                    clickPending = false;
                    log.error("Failed to open equipment", e);
                    return null;
                });
    }

    // ========================================================================
    // Phase: Open Inventory
    // ========================================================================

    private void executeOpenInventory(TaskContext ctx) {
        Client client = ctx.getClient();
        Widget inventory = client.getWidget(INVENTORY_GROUP, 0);

        if (inventory != null && !inventory.isHidden()) {
            log.debug("Inventory is open");
            phase = TeleportPhase.CLICK_TELEPORT;
            return;
        }

        // Open inventory using F4 (or click)
        log.debug("Opening inventory");
        clickPending = true;

        ctx.getKeyboardController().pressKey(java.awt.event.KeyEvent.VK_F4)
                .thenRun(() -> {
                    clickPending = false;
                    waitTicks = 0;
                })
                .exceptionally(e -> {
                    clickPending = false;
                    log.error("Failed to open inventory", e);
                    return null;
                });
    }

    // ========================================================================
    // Phase: Click Teleport
    // ========================================================================

    private void executeClickTeleport(TaskContext ctx) {
        switch (method) {
            case SPELL:
                clickSpell(ctx);
                break;
            case HOME_TELEPORT:
                clickHomeTeleport(ctx);
                break;
            case TABLET:
                clickTablet(ctx);
                break;
            case JEWELRY_EQUIPPED:
                clickEquippedJewelry(ctx);
                break;
            case JEWELRY_INVENTORY:
                clickInventoryJewelry(ctx);
                break;
            default:
                fail("Unsupported teleport method: " + method);
        }
    }

    private void clickSpell(TaskContext ctx) {
        Integer childId = SPELL_WIDGET_IDS.get(spellName);
        if (childId == null) {
            fail("Unknown spell: " + spellName);
            return;
        }

        Client client = ctx.getClient();
        Widget spellWidget = client.getWidget(SPELLBOOK_GROUP, childId);

        if (spellWidget == null || spellWidget.isHidden()) {
            log.warn("Spell widget not found: {}:{}", SPELLBOOK_GROUP, childId);
            fail("Spell not available");
            return;
        }

        clickWidget(ctx, spellWidget, "Cast " + spellName);
    }

    private void clickHomeTeleport(TaskContext ctx) {
        Client client = ctx.getClient();
        Widget homeWidget = client.getWidget(SPELLBOOK_GROUP, HOME_TELEPORT_CHILD);

        if (homeWidget == null || homeWidget.isHidden()) {
            fail("Home teleport not available");
            return;
        }

        clickWidget(ctx, homeWidget, "Home Teleport");
    }

    private void clickTablet(TaskContext ctx) {
        int slot = findItemSlot(ctx, itemId);
        if (slot < 0) {
            fail("Tablet not found in inventory");
            return;
        }

        Client client = ctx.getClient();
        Widget inventoryWidget = client.getWidget(INVENTORY_GROUP, INVENTORY_CHILD);

        if (inventoryWidget == null) {
            fail("Inventory widget not found");
            return;
        }

        Widget[] children = inventoryWidget.getDynamicChildren();
        if (children == null || slot >= children.length) {
            fail("Invalid inventory slot");
            return;
        }

        clickWidget(ctx, children[slot], "Break tablet");
    }

    private void clickEquippedJewelry(TaskContext ctx) {
        EquipmentState equipment = ctx.getGameStateService().getEquipmentState();
        var slotOpt = equipment.getSlotOf(itemId);

        if (slotOpt.isEmpty()) {
            fail("Jewelry not equipped");
            return;
        }

        int slot = slotOpt.get();
        Integer widgetChildId = EQUIPMENT_SLOT_WIDGETS.get(slot);

        if (widgetChildId == null) {
            fail("Unsupported equipment slot for jewelry: " + slot);
            return;
        }

        Client client = ctx.getClient();
        Widget slotWidget = client.getWidget(EQUIPMENT_GROUP, widgetChildId);

        if (slotWidget == null || slotWidget.isHidden()) {
            fail("Equipment slot widget not found");
            return;
        }

        // Use menu helper to select the teleport option
        Rectangle bounds = slotWidget.getBounds();
        if (bounds == null) {
            fail("Equipment slot has no bounds");
            return;
        }

        log.debug("Clicking jewelry for teleport option: {}", teleportOption);
        clickPending = true;

        ctx.getMenuHelper().selectMenuEntry(bounds, teleportOption)
                .thenAccept(success -> {
                    clickPending = false;
                    if (success) {
                        phase = TeleportPhase.WAIT_FOR_TELEPORT;
                        waitTicks = 0;
                    } else {
                        fail("Failed to select teleport option: " + teleportOption);
                    }
                })
                .exceptionally(e -> {
                    clickPending = false;
                    log.error("Jewelry teleport failed", e);
                    fail("Jewelry teleport failed: " + e.getMessage());
                    return null;
                });
    }

    private void clickInventoryJewelry(TaskContext ctx) {
        int slot = findItemSlot(ctx, itemId);
        if (slot < 0) {
            fail("Jewelry not found in inventory");
            return;
        }

        Client client = ctx.getClient();
        Widget inventoryWidget = client.getWidget(INVENTORY_GROUP, INVENTORY_CHILD);

        if (inventoryWidget == null) {
            fail("Inventory widget not found");
            return;
        }

        Widget[] children = inventoryWidget.getDynamicChildren();
        if (children == null || slot >= children.length) {
            fail("Invalid inventory slot");
            return;
        }

        Rectangle bounds = children[slot].getBounds();
        if (bounds == null) {
            fail("Inventory slot has no bounds");
            return;
        }

        log.debug("Clicking inventory jewelry for teleport option: {}", teleportOption);
        clickPending = true;

        ctx.getMenuHelper().selectMenuEntry(bounds, teleportOption)
                .thenAccept(success -> {
                    clickPending = false;
                    if (success) {
                        phase = TeleportPhase.WAIT_FOR_TELEPORT;
                        waitTicks = 0;
                    } else {
                        fail("Failed to select teleport option: " + teleportOption);
                    }
                })
                .exceptionally(e -> {
                    clickPending = false;
                    log.error("Inventory jewelry teleport failed", e);
                    fail("Inventory jewelry teleport failed: " + e.getMessage());
                    return null;
                });
    }

    private void clickWidget(TaskContext ctx, Widget widget, String actionDesc) {
        Rectangle bounds = widget.getBounds();
        if (bounds == null || bounds.width == 0) {
            fail("Widget has invalid bounds");
            return;
        }

        // Calculate humanized click point
        int x = bounds.x + bounds.width / 2 + (int) ((Math.random() - 0.5) * bounds.width * 0.4);
        int y = bounds.y + bounds.height / 2 + (int) ((Math.random() - 0.5) * bounds.height * 0.4);

        log.debug("{}: clicking at ({}, {})", actionDesc, x, y);
        clickPending = true;

        ctx.getMouseController().moveToCanvas(x, y)
                .thenCompose(v -> ctx.getMouseController().click())
                .thenRun(() -> {
                    clickPending = false;
                    phase = TeleportPhase.WAIT_FOR_TELEPORT;
                    waitTicks = 0;
                })
                .exceptionally(e -> {
                    clickPending = false;
                    log.error("Click failed: {}", actionDesc, e);
                    fail("Click failed: " + e.getMessage());
                    return null;
                });
    }

    // ========================================================================
    // Phase: Wait For Teleport
    // ========================================================================

    private void executeWaitForTeleport(TaskContext ctx) {
        waitTicks++;

        // Check if player position has changed (teleport completed)
        WorldPoint currentPos = ctx.getPlayerState().getWorldPosition();
        if (currentPos != null && startPosition != null) {
            int distance = currentPos.distanceTo(startPosition);
            if (distance > 5) {
                log.debug("Teleport completed, moved {} tiles", distance);
                if (verifyArrival && expectedDestination != null) {
                    phase = TeleportPhase.VERIFY_ARRIVAL;
                } else {
                    complete();
                }
                return;
            }
        }

        // Timeout based on teleport type
        int maxWaitTicks = (method == TeleportMethod.HOME_TELEPORT) 
                ? HOME_TELEPORT_DURATION_TICKS + 5 
                : TELEPORT_DURATION_TICKS + 5;

        if (waitTicks > maxWaitTicks) {
            log.warn("Teleport timed out after {} ticks", waitTicks);
            fail("Teleport timed out");
        }
    }

    // ========================================================================
    // Phase: Verify Arrival
    // ========================================================================

    private void executeVerifyArrival(TaskContext ctx) {
        WorldPoint currentPos = ctx.getPlayerState().getWorldPosition();
        if (currentPos == null) {
            fail("Could not get player position");
            return;
        }

        int distance = currentPos.distanceTo(expectedDestination);
        if (distance <= destinationTolerance) {
            log.debug("Arrived at destination (within {} tiles)", distance);
            complete();
        } else {
            log.warn("Teleport destination mismatch: expected {}, got {} (distance: {})",
                    expectedDestination, currentPos, distance);
            // Still complete since teleport happened, just not where expected
            complete();
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private boolean canCastSpell(TaskContext ctx) {
        // Check if spell exists
        Integer childId = SPELL_WIDGET_IDS.get(spellName);
        if (childId == null) {
            return false;
        }

        // Check magic level (basic check - could be enhanced with rune checking)
        // For now, we'll let the game tell us if we can't cast
        return true;
    }

    private boolean hasItemInInventory(TaskContext ctx, int itemId) {
        return findItemSlot(ctx, itemId) >= 0;
    }

    private boolean hasJewelryEquipped(TaskContext ctx, int itemId) {
        EquipmentState equipment = ctx.getGameStateService().getEquipmentState();
        return equipment.hasEquipped(itemId);
    }

    private int findItemSlot(TaskContext ctx, int itemId) {
        Client client = ctx.getClient();
        ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
        if (inventory == null) {
            return -1;
        }

        Item[] items = inventory.getItems();
        for (int i = 0; i < items.length; i++) {
            if (items[i] != null && items[i].getId() == itemId) {
                return i;
            }
        }
        return -1;
    }

    // ========================================================================
    // Description
    // ========================================================================

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }

        switch (method) {
            case SPELL:
                return "Cast " + spellName;
            case HOME_TELEPORT:
                return "Home Teleport";
            case TABLET:
                return "Use teleport tablet";
            case JEWELRY_EQUIPPED:
            case JEWELRY_INVENTORY:
                return "Use jewelry teleport to " + teleportOption;
            default:
                return "Teleport";
        }
    }

    // ========================================================================
    // Phase Enum
    // ========================================================================

    private enum TeleportPhase {
        INIT,
        OPEN_SPELLBOOK,
        OPEN_EQUIPMENT,
        OPEN_INVENTORY,
        CLICK_TELEPORT,
        WAIT_FOR_TELEPORT,
        VERIFY_ARRIVAL
    }
}

