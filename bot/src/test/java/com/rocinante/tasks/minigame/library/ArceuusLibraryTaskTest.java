package com.rocinante.tasks.minigame.library;

import com.rocinante.core.GameStateService;
import com.rocinante.input.InventoryClickHelper;
import com.rocinante.input.RobotKeyboardController;
import com.rocinante.input.RobotMouseController;
import com.rocinante.state.InventoryState;
import com.rocinante.state.NpcSnapshot;
import com.rocinante.state.PlayerState;
import com.rocinante.state.WorldState;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.tasks.minigame.MinigamePhase;
import com.rocinante.timing.HumanTimer;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ArceuusLibraryTask.
 * Tests minigame phases, book finding, and XP calculations.
 */
public class ArceuusLibraryTaskTest {

    // Item IDs
    private static final int BOOK_OF_ARCANE_KNOWLEDGE_ID = 13513;

    @Mock
    private Client client;

    @Mock
    private GameStateService gameStateService;

    @Mock
    private RobotMouseController mouseController;

    @Mock
    private RobotKeyboardController keyboardController;

    @Mock
    private HumanTimer humanTimer;

    @Mock
    private InventoryClickHelper inventoryClickHelper;

    private TaskContext taskContext;
    private PlayerState playerInLibrary;
    private InventoryState emptyInventory;
    private InventoryState inventoryWithBook;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        // Set up TaskContext with all necessary components
        taskContext = new TaskContext(
                client,
                gameStateService,
                mouseController,
                keyboardController,
                humanTimer,
                null, // targetSelector
                null, // combatManager
                null, // gearSwitcher
                null, // foodManager
                inventoryClickHelper,
                null, // groundItemClickHelper
                null, // widgetClickHelper
                null, // menuHelper
                null, // unlockTracker
                null, // agilityCourseRepository
                null, // playerProfile
                null, // puzzleSolverRegistry
                null, // cameraController
                null, // mouseCameraCoupler
                null, // actionSequencer
                null, // inefficiencyInjector
                null, // logoutHandler
                null, // breakScheduler
                null, // randomization
                null, // pathFinder
                null, // webWalker
                null, // obstacleHandler
                null  // planeTransitionHandler
        );

        // Player in Library region
        WorldPoint libraryPos = ArceuusLibraryConfig.LIBRARY_CENTER;
        playerInLibrary = PlayerState.builder()
                .worldPosition(libraryPos)
                .currentHitpoints(50)
                .maxHitpoints(50)
                .animationId(-1)
                .inCombat(false)
                .build();

        // Empty inventory
        emptyInventory = new InventoryState(new Item[28]);

        // Inventory with Book of Arcane Knowledge
        Item[] itemsWithBook = new Item[28];
        Item book = mock(Item.class);
        when(book.getId()).thenReturn(BOOK_OF_ARCANE_KNOWLEDGE_ID);
        when(book.getQuantity()).thenReturn(1);
        itemsWithBook[0] = book;
        inventoryWithBook = new InventoryState(itemsWithBook);

        // Default mocks
        when(gameStateService.isLoggedIn()).thenReturn(true);
        when(gameStateService.getPlayerState()).thenReturn(playerInLibrary);
        when(gameStateService.getWorldState()).thenReturn(WorldState.EMPTY);
        when(gameStateService.getInventoryState()).thenReturn(emptyInventory);

        // Mock skill levels
        when(client.getRealSkillLevel(Skill.MAGIC)).thenReturn(55);
        when(client.getRealSkillLevel(Skill.RUNECRAFT)).thenReturn(30);
        when(client.getSkillExperience(Skill.MAGIC)).thenReturn(200000);
        when(client.getSkillExperience(Skill.RUNECRAFT)).thenReturn(14000);

        // Mock inventory click helper
        when(inventoryClickHelper.executeClick(anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));
    }

    // ========================================================================
    // Configuration Tests
    // ========================================================================

    @Test
    public void testConfig_LibraryRegion() {
        assertEquals(6459, ArceuusLibraryConfig.LIBRARY_REGION);
    }

    @Test
    public void testConfig_XpMultipliers() {
        assertEquals(15.0, ArceuusLibraryConfig.MAGIC_XP_MULTIPLIER, 0.01);
        assertEquals(5.0, ArceuusLibraryConfig.RUNECRAFT_XP_MULTIPLIER, 0.01);
    }

    @Test
    public void testConfig_BooksPerHour() {
        assertEquals(45, ArceuusLibraryConfig.BOOKS_PER_HOUR);
    }

    @Test
    public void testConfig_MagicFactory() {
        ArceuusLibraryConfig config = ArceuusLibraryConfig.forMagic(75);

        assertEquals("arceuus_library", config.getMinigameId());
        assertEquals(75, config.getTargetLevel());
        assertEquals(Skill.MAGIC, config.getTargetSkill());
        assertTrue(config.isTrainingMagic());
        assertFalse(config.isTrainingRunecraft());
    }

    @Test
    public void testConfig_RunecraftFactory() {
        ArceuusLibraryConfig config = ArceuusLibraryConfig.forRunecraft(77);

        assertEquals("arceuus_library", config.getMinigameId());
        assertEquals(77, config.getTargetLevel());
        assertEquals(Skill.RUNECRAFT, config.getTargetSkill());
        assertFalse(config.isTrainingMagic());
        assertTrue(config.isTrainingRunecraft());
    }

    @Test
    public void testConfig_ForXpFactory() {
        ArceuusLibraryConfig config = ArceuusLibraryConfig.forXp(Skill.MAGIC, 100000);

        assertEquals("arceuus_library", config.getMinigameId());
        assertEquals(100000, config.getTargetXp());
        assertEquals(Skill.MAGIC, config.getTargetSkill());
    }

    @Test
    public void testConfig_ForBooksFactory() {
        ArceuusLibraryConfig config = ArceuusLibraryConfig.forBooks(Skill.RUNECRAFT, 50);

        assertEquals("arceuus_library", config.getMinigameId());
        assertEquals(50, config.getTargetRounds());
        assertEquals(Skill.RUNECRAFT, config.getTargetSkill());
    }

    @Test
    public void testConfig_XpMultiplierBySkill() {
        ArceuusLibraryConfig magicConfig = ArceuusLibraryConfig.forMagic(99);
        ArceuusLibraryConfig rcConfig = ArceuusLibraryConfig.forRunecraft(99);

        assertEquals(15.0, magicConfig.getXpMultiplier(), 0.01);
        assertEquals(5.0, rcConfig.getXpMultiplier(), 0.01);
    }

    @Test
    public void testConfig_XpPerBook() {
        ArceuusLibraryConfig magicConfig = ArceuusLibraryConfig.forMagic(99);
        ArceuusLibraryConfig rcConfig = ArceuusLibraryConfig.forRunecraft(99);

        // At level 50
        assertEquals(750.0, magicConfig.getXpPerBook(50), 0.01); // 50 * 15
        assertEquals(250.0, rcConfig.getXpPerBook(50), 0.01);    // 50 * 5
    }

    @Test
    public void testConfig_XpPerHour() {
        ArceuusLibraryConfig magicConfig = ArceuusLibraryConfig.forMagic(99);

        // At level 50, 45 books/hr
        double expected = 50 * 15 * 45; // 33750 xp/hr
        assertEquals(expected, magicConfig.getXpPerHour(50), 0.01);
    }

    // ========================================================================
    // Book Enum Tests
    // ========================================================================

    @Test
    public void testLibraryBook_ItemIdLookup() {
        LibraryBook radasCensus = LibraryBook.byItemId(13524);
        assertNotNull(radasCensus);
        assertEquals(LibraryBook.RADAS_CENSUS, radasCensus);
    }

    @Test
    public void testLibraryBook_NameLookup() {
        LibraryBook book = LibraryBook.byName("Rada's Census");
        assertNotNull(book);
        assertEquals(LibraryBook.RADAS_CENSUS, book);
    }

    @Test
    public void testLibraryBook_AllBooksHaveIds() {
        for (LibraryBook book : LibraryBook.values()) {
            assertTrue(book.getItemId() > 0);
            assertNotNull(book.getShortName());
            assertNotNull(book.getFullName());
        }
    }

    @Test
    public void testLibraryBook_SpecialEffectBooks() {
        assertTrue(LibraryBook.TRANSPORTATION_INCANTATIONS.hasSpecialEffect());
        assertTrue(LibraryBook.SOUL_JOURNEY.hasSpecialEffect());
        assertFalse(LibraryBook.RADAS_CENSUS.hasSpecialEffect());
    }

    @Test
    public void testLibraryBook_QuestBook() {
        assertTrue(LibraryBook.VARLAMORE_ENVOY.isQuestBook());
        assertFalse(LibraryBook.RADAS_CENSUS.isQuestBook());
    }

    // ========================================================================
    // Customer Enum Tests
    // ========================================================================

    @Test
    public void testLibraryCustomer_AllNpcIds() {
        int[] npcIds = LibraryCustomer.getAllNpcIds();
        assertEquals(3, npcIds.length);
    }

    @Test
    public void testLibraryCustomer_ByNpcId() {
        LibraryCustomer sam = LibraryCustomer.byNpcId(7047);
        assertNotNull(sam);
        assertEquals(LibraryCustomer.SAM, sam);
    }

    @Test
    public void testLibraryCustomer_IsLibraryCustomer() {
        assertTrue(LibraryCustomer.isLibraryCustomer(7047));
        assertTrue(LibraryCustomer.isLibraryCustomer(7048));
        assertTrue(LibraryCustomer.isLibraryCustomer(7049));
        assertFalse(LibraryCustomer.isLibraryCustomer(1234));
    }

    @Test
    public void testLibraryCustomer_AllHaveLocations() {
        for (LibraryCustomer customer : LibraryCustomer.values()) {
            assertNotNull(customer.getLocation());
            assertTrue(customer.getNpcId() > 0);
            assertNotNull(customer.getName());
        }
    }

    // ========================================================================
    // State Tests
    // ========================================================================

    @Test
    public void testLibraryState_InitialState() {
        LibraryState state = new LibraryState();

        assertFalse(state.hasActiveRequest());
        assertNull(state.getRequestedBook());
        assertNull(state.getCurrentCustomer());
        assertFalse(state.isLayoutSolved());
        assertEquals(0, state.getBooksDelivered());
        assertEquals(0, state.getArcaneKnowledgeCount());
    }

    @Test
    public void testLibraryState_SetRequest() {
        LibraryState state = new LibraryState();
        
        state.setRequest(LibraryBook.RADAS_CENSUS, LibraryCustomer.SAM, 7047);

        assertTrue(state.hasActiveRequest());
        assertEquals(LibraryBook.RADAS_CENSUS, state.getRequestedBook());
        assertEquals(LibraryCustomer.SAM, state.getCurrentCustomer());
        assertEquals(7047, state.getCustomerModelId());
    }

    @Test
    public void testLibraryState_ClearRequest() {
        LibraryState state = new LibraryState();
        state.setRequest(LibraryBook.RADAS_CENSUS, LibraryCustomer.SAM, 7047);

        state.clearRequest();

        assertFalse(state.hasActiveRequest());
        assertNull(state.getRequestedBook());
        assertNull(state.getCurrentCustomer());
    }

    @Test
    public void testLibraryState_BookTracking() {
        LibraryState state = new LibraryState();

        state.addBook(LibraryBook.RADAS_CENSUS);
        assertTrue(state.hasBook(LibraryBook.RADAS_CENSUS));
        assertFalse(state.hasBook(LibraryBook.KILLING_OF_A_KING));

        state.removeBook(LibraryBook.RADAS_CENSUS);
        assertFalse(state.hasBook(LibraryBook.RADAS_CENSUS));
    }

    @Test
    public void testLibraryState_HasRequestedBook() {
        LibraryState state = new LibraryState();
        state.setRequest(LibraryBook.RADAS_CENSUS, LibraryCustomer.SAM, 7047);

        assertFalse(state.hasRequestedBook()); // Don't have the book yet

        state.addBook(LibraryBook.RADAS_CENSUS);
        assertTrue(state.hasRequestedBook());
    }

    @Test
    public void testLibraryState_BookLocations() {
        LibraryState state = new LibraryState();
        WorldPoint location = new WorldPoint(1630, 3810, 0);

        state.setConfirmedLocation(LibraryBook.RADAS_CENSUS, location);

        assertTrue(state.getBookLocation(LibraryBook.RADAS_CENSUS).isPresent());
        assertEquals(location, state.getBookLocation(LibraryBook.RADAS_CENSUS).get());
    }

    @Test
    public void testLibraryState_DeliveryStatistics() {
        LibraryState state = new LibraryState();

        state.recordDelivery(750.0);
        state.recordDelivery(750.0);

        assertEquals(2, state.getBooksDelivered());
        assertEquals(1500.0, state.getXpGained(), 0.01);
    }

    @Test
    public void testLibraryState_Summary() {
        LibraryState state = new LibraryState();
        state.setRequest(LibraryBook.RADAS_CENSUS, LibraryCustomer.SAM, 7047);
        
        String summary = state.getSummary();
        
        assertTrue(summary.contains("Rada's Census"));
        assertTrue(summary.contains("delivered="));
        assertTrue(summary.contains("solved="));
    }

    // ========================================================================
    // Task canExecute Tests
    // ========================================================================

    @Test
    public void testCanExecute_ReturnsFalse_WhenNotLoggedIn() {
        when(gameStateService.isLoggedIn()).thenReturn(false);

        ArceuusLibraryTask task = createMagicTask();
        assertFalse(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_ReturnsTrue_WhenLoggedIn() {
        ArceuusLibraryTask task = createMagicTask();
        assertTrue(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_ReturnsFalse_WhenTargetLevelReached() {
        when(client.getRealSkillLevel(Skill.MAGIC)).thenReturn(75);

        ArceuusLibraryConfig config = ArceuusLibraryConfig.forMagic(75);
        ArceuusLibraryTask task = new ArceuusLibraryTask(config);

        assertFalse(task.canExecute(taskContext));
    }

    // ========================================================================
    // Task Phase Tests
    // ========================================================================

    @Test
    public void testInitialPhase() {
        ArceuusLibraryTask task = createMagicTask();
        assertEquals(MinigamePhase.TRAVEL, task.getPhase());
    }

    @Test
    public void testExecute_StartsRunning() {
        ArceuusLibraryTask task = createMagicTask();

        task.execute(taskContext);

        assertEquals(TaskState.RUNNING, task.getState());
    }

    // ========================================================================
    // Description Tests
    // ========================================================================

    @Test
    public void testDescription_ContainsSkillAndActivity() {
        ArceuusLibraryTask task = createMagicTask();
        String desc = task.getDescription();

        assertTrue(desc.contains("ArceuusLibrary"));
        assertTrue(desc.contains("skill="));
        assertTrue(desc.contains("delivered="));
        assertTrue(desc.contains("activity="));
    }

    // ========================================================================
    // TrainingMethod Integration Tests
    // ========================================================================

    @Test
    public void testTrainingMethod_XpMultiplierCalculation() {
        // Verify that the xpMultiplier field works as expected
        com.rocinante.progression.TrainingMethod method = 
                com.rocinante.progression.TrainingMethod.builder()
                        .id("test_method")
                        .name("Test Method")
                        .skill(Skill.MAGIC)
                        .methodType(com.rocinante.progression.MethodType.MINIGAME)
                        .minLevel(1)
                        .xpMultiplier(15)
                        .actionsPerHour(45)
                        .build();

        assertTrue(method.hasLevelBasedXp());
        assertEquals(750.0, method.getXpPerAction(50), 0.01);  // 50 * 15
        assertEquals(33750.0, method.getXpPerHour(50), 0.01);  // 50 * 15 * 45
    }

    @Test
    public void testTrainingMethod_StaticXpCalculation() {
        // Verify static XP methods still work
        com.rocinante.progression.TrainingMethod method = 
                com.rocinante.progression.TrainingMethod.builder()
                        .id("test_static")
                        .name("Test Static")
                        .skill(Skill.FIREMAKING)
                        .methodType(com.rocinante.progression.MethodType.MINIGAME)
                        .minLevel(50)
                        .xpPerAction(161.5)
                        .actionsPerHour(250)
                        .build();

        assertFalse(method.hasLevelBasedXp());
        assertEquals(161.5, method.getXpPerAction(50), 0.01);
        assertEquals(40375.0, method.getXpPerHour(), 0.01);
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private ArceuusLibraryTask createMagicTask() {
        ArceuusLibraryConfig config = ArceuusLibraryConfig.forMagic(75);
        return new ArceuusLibraryTask(config);
    }

    private ArceuusLibraryTask createRunecraftTask() {
        ArceuusLibraryConfig config = ArceuusLibraryConfig.forRunecraft(77);
        return new ArceuusLibraryTask(config);
    }
}

