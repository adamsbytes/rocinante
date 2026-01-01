package com.rocinante.behavior.idle;

import com.rocinante.behavior.PlayerProfile;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.util.Randomization;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;

import java.awt.Rectangle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * XP checking behavior - simulates periodic XP/level checks during gameplay.
 * 
 * Per REQUIREMENTS.md Section 3.5.2:
 * 
 * XP Checking Patterns:
 * - Frequency: 0-15 times per hour (skill-dependent: training skill checked 3x more often)
 * - Method: 70% hover skill orb, 25% open skills tab, 5% use XP tracker overlay
 * - Duration: 0.3-1.5 seconds per check
 * 
 * This behavior should be periodically triggered during normal gameplay,
 * weighted by the player's profile preferences.
 */
@Slf4j
public class XpCheckBehavior extends AbstractTask {

    // === Check method probabilities (from PlayerProfile) ===
    // Now configurable per-account via PlayerProfile preferences

    // === View duration range per skill ===
    private static final long MIN_VIEW_DURATION_PER_SKILL_MS = 800;
    private static final long MAX_VIEW_DURATION_PER_SKILL_MS = 1600;
    
    // === Multi-skill check probabilities ===
    private static final double PROB_ONE_SKILL = 0.60;    // 60% check 1 skill
    private static final double PROB_TWO_SKILLS = 0.30;   // 30% check 2 skills
    // Remaining 10% check 3 skills

    // === Phases ===
    private enum Phase {
        INIT,
        SELECTING_METHOD,
        HOVERING_ORB,
        OPENING_TAB,
        VIEWING_TAB,
        HOVERING_XP_TRACKER,
        CLOSING,
        COMPLETED
    }

    // === Check method types ===
    private enum CheckMethod {
        SKILL_ORB,
        SKILLS_TAB,
        XP_TRACKER
    }

    private final PlayerProfile playerProfile;
    private final Randomization randomization;
    
    private Phase phase = Phase.INIT;
    private CheckMethod selectedMethod;
    private List<Skill> skillsToCheck = new ArrayList<>();
    private int currentSkillIndex = 0;
    private long viewDurationPerSkill;
    private long currentSkillViewStart;
    
    private CompletableFuture<?> pendingOperation = null;
    
    public XpCheckBehavior(PlayerProfile playerProfile, Randomization randomization) {
        this.playerProfile = playerProfile;
        this.randomization = randomization;
    }

    /**
     * Simplified constructor for backwards compatibility.
     */
    public XpCheckBehavior(PlayerProfile playerProfile) {
        this.playerProfile = playerProfile;
        this.randomization = new Randomization();
    }

    @Override
    public String getDescription() {
        if (selectedMethod != null) {
            return "Check XP: " + selectedMethod;
        }
        return "Check XP progress";
    }

    @Override
    public boolean canExecute(TaskContext ctx) {
        // Don't check XP during combat or critical activities
        if (ctx.getCombatState() != null && ctx.getCombatState().hasTarget()) {
            return false;
        }
        
        return ctx.isLoggedIn();
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        // Wait for pending operations
        if (pendingOperation != null && !pendingOperation.isDone()) {
            return;
        }
        
        switch (phase) {
            case INIT:
                initCheck(ctx);
                break;
                
            case SELECTING_METHOD:
                selectMethod();
                break;
                
            case HOVERING_ORB:
                executeOrbHover(ctx);
                break;
                
            case OPENING_TAB:
                openStatsTab(ctx);
                break;
                
            case VIEWING_TAB:
                viewStatsTab(ctx);
                break;
                
            case HOVERING_XP_TRACKER:
                hoverXpTracker(ctx);
                break;
                
            case CLOSING:
                executeClose(ctx);
                break;
                
            case COMPLETED:
                transitionTo(TaskState.COMPLETED);
                break;
        }
    }

    private void initCheck(TaskContext ctx) {
        // Determine how many skills to check (60% one, 30% two, 10% three)
        skillsToCheck.clear();
        currentSkillIndex = 0;
        
        double roll = randomization.uniformRandom(0, 1);
        int numSkills;
        if (roll < PROB_ONE_SKILL) {
            numSkills = 1;
        } else if (roll < PROB_ONE_SKILL + PROB_TWO_SKILLS) {
            numSkills = 2;
        } else {
            numSkills = 3;
        }
        
        // Add the primary target skill first
        Skill primarySkill = determineTargetSkill(ctx);
        skillsToCheck.add(primarySkill);
        
        // Add additional random skills if checking multiple
        for (int i = 1; i < numSkills; i++) {
            Skill additionalSkill = getRandomDifferentSkill(skillsToCheck);
            skillsToCheck.add(additionalSkill);
        }
        
        viewDurationPerSkill = randomization.uniformRandomLong(
                MIN_VIEW_DURATION_PER_SKILL_MS, MAX_VIEW_DURATION_PER_SKILL_MS);
        
        log.debug("Initiating XP check (skills: {}, duration per skill: {}ms)", 
                 skillsToCheck, viewDurationPerSkill);
        
        phase = Phase.SELECTING_METHOD;
    }
    
    private Skill getRandomDifferentSkill(List<Skill> exclude) {
        Skill[] allSkills = Skill.values();
        Skill candidate;
        int attempts = 0;
        do {
            candidate = allSkills[randomization.uniformRandomInt(0, allSkills.length - 1)];
            attempts++;
        } while (exclude.contains(candidate) && attempts < 50);
        return candidate;
    }

    private Skill determineTargetSkill(TaskContext ctx) {
        // Per REQUIREMENTS.md: Training skill checked 3x more often
        // We can infer training skill from BotActivityTracker current activity
        // For now, we'll select based on recent XP gains or randomly
        
        // Default to a random skill with bias toward common training skills
        double roll = randomization.uniformRandom(0, 1);
        
        // Common training skills with higher weights
        if (roll < 0.3) {
            // Combat skills
            Skill[] combatSkills = {Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, 
                                    Skill.HITPOINTS, Skill.RANGED, Skill.MAGIC, Skill.PRAYER};
            return combatSkills[randomization.uniformRandomInt(0, combatSkills.length - 1)];
        } else if (roll < 0.6) {
            // Gathering skills
            Skill[] gatheringSkills = {Skill.WOODCUTTING, Skill.MINING, Skill.FISHING, Skill.HUNTER};
            return gatheringSkills[randomization.uniformRandomInt(0, gatheringSkills.length - 1)];
        } else {
            // Any other skill
            Skill[] allSkills = Skill.values();
            return allSkills[randomization.uniformRandomInt(0, allSkills.length - 1)];
        }
    }

    private void selectMethod() {
        double roll = randomization.uniformRandom(0, 1);
        
        // Use per-account preferences from PlayerProfile
        double orbProb = playerProfile.getXpCheckOrbProbability();
        double tabProb = playerProfile.getXpCheckTabProbability();
        // Tracker gets the remainder
        
        if (roll < orbProb) {
            selectedMethod = CheckMethod.SKILL_ORB;
            phase = Phase.HOVERING_ORB;
        } else if (roll < orbProb + tabProb) {
            selectedMethod = CheckMethod.SKILLS_TAB;
            phase = Phase.OPENING_TAB;
        } else {
            selectedMethod = CheckMethod.XP_TRACKER;
            phase = Phase.HOVERING_XP_TRACKER;
        }
        
        log.debug("Selected XP check method: {} (orb={}%, tab={}%, tracker={}%)", 
                  selectedMethod,
                  String.format("%.0f", orbProb * 100), 
                  String.format("%.0f", tabProb * 100), 
                  String.format("%.0f", (1.0 - orbProb - tabProb) * 100));
    }

    private void executeOrbHover(TaskContext ctx) {
        Client client = ctx.getClient();
        
        // Get XP orb widget
        Widget xpOrb = client.getWidget(WidgetInfo.MINIMAP_XP_ORB);
        
        if (xpOrb != null && !xpOrb.isHidden()) {
            Rectangle bounds = xpOrb.getBounds();
            if (bounds != null && bounds.width > 0) {
                int x = bounds.x + randomization.uniformRandomInt(5, Math.max(6, bounds.width - 5));
                int y = bounds.y + randomization.uniformRandomInt(5, Math.max(6, bounds.height - 5));
                
                log.debug("Hovering XP orb at ({}, {}) for {}ms", x, y, viewDurationPerSkill);
                
                pendingOperation = ctx.getMouseController().moveToCanvas(x, y)
                    .thenCompose(v -> ctx.getHumanTimer().sleep(viewDurationPerSkill))
                    .thenRun(() -> phase = Phase.CLOSING);
            } else {
                // Widget not usable, skip to close
                phase = Phase.CLOSING;
            }
        } else {
            // XP orb not visible, try skills tab instead
            log.debug("XP orb not visible, falling back to skills tab");
            selectedMethod = CheckMethod.SKILLS_TAB;
            phase = Phase.OPENING_TAB;
        }
    }

    private void openStatsTab(TaskContext ctx) {
        Client client = ctx.getClient();
        
        // Try fixed viewport first, then resizable
        Widget statsTab = client.getWidget(WidgetInfo.FIXED_VIEWPORT_STATS_TAB);
        if (statsTab == null || statsTab.isHidden()) {
            statsTab = client.getWidget(WidgetInfo.RESIZABLE_VIEWPORT_STATS_TAB);
        }
        
        if (statsTab != null && !statsTab.isHidden()) {
            Rectangle bounds = statsTab.getBounds();
            if (bounds != null && bounds.width > 0) {
                int x = bounds.x + randomization.uniformRandomInt(5, Math.max(6, bounds.width - 5));
                int y = bounds.y + randomization.uniformRandomInt(5, Math.max(6, bounds.height - 5));
                
                log.debug("Clicking stats tab at ({}, {}) to view {} skill(s)", 
                        x, y, skillsToCheck.size());
                
                pendingOperation = ctx.getMouseController().moveToCanvas(x, y)
                    .thenCompose(v -> ctx.getMouseController().click())
                    .thenCompose(v -> ctx.getHumanTimer().sleep(randomization.uniformRandomLong(200, 400)))
                    .thenRun(() -> {
                        currentSkillIndex = 0;
                        phase = Phase.VIEWING_TAB;
                    });
            } else {
                phase = Phase.CLOSING;
            }
        } else {
            log.debug("Stats tab not visible, completing");
            phase = Phase.CLOSING;
        }
    }

    private void viewStatsTab(TaskContext ctx) {
        // Check if we've viewed all skills
        if (currentSkillIndex >= skillsToCheck.size()) {
            phase = Phase.CLOSING;
            return;
        }
        
        Skill currentSkill = skillsToCheck.get(currentSkillIndex);
        
        // Hover over the current skill in the skills container
        Client client = ctx.getClient();
        Widget skillsContainer = client.getWidget(WidgetInfo.SKILLS_CONTAINER);
        
        if (skillsContainer != null && !skillsContainer.isHidden()) {
            Widget[] children = skillsContainer.getStaticChildren();
            if (children != null && children.length > 0) {
                // Get skill widget by index (skills are ordered by ordinal)
                int skillWidgetIdx = getSkillWidgetIndex(currentSkill);
                if (skillWidgetIdx >= 0 && skillWidgetIdx < children.length) {
                    Widget skillWidget = children[skillWidgetIdx];
                    if (skillWidget != null && !skillWidget.isHidden()) {
                        Rectangle bounds = skillWidget.getBounds();
                        if (bounds != null && bounds.width > 0) {
                            int x = bounds.x + randomization.uniformRandomInt(5, Math.max(6, bounds.width - 5));
                            int y = bounds.y + randomization.uniformRandomInt(5, Math.max(6, bounds.height - 5));
                            
                            // Randomize this skill's view duration slightly
                            final long thisSkillDuration = Math.max(500, 
                                    viewDurationPerSkill + randomization.uniformRandomLong(-200, 200));
                            
                            log.debug("Hovering {} skill ({}/{}) at ({}, {}) for {}ms", 
                                    currentSkill, currentSkillIndex + 1, skillsToCheck.size(), 
                                    x, y, thisSkillDuration);
                            
                            currentSkillViewStart = System.currentTimeMillis();
                            
                            // Hover over this skill, then move to next
                            pendingOperation = ctx.getMouseController().moveToCanvas(x, y)
                                .thenCompose(v -> ctx.getHumanTimer().sleep(thisSkillDuration))
                                .thenRun(() -> {
                                    currentSkillIndex++;
                                    // Don't transition phase - let next tick handle it
                                });
                            return;
                        }
                    }
                }
            }
        }
        
        // Fallback: skip this skill and move to next
        currentSkillIndex++;
    }

    private void hoverXpTracker(TaskContext ctx) {
        Client client = ctx.getClient();
        
        // Check if XP tracker widget is visible
        Widget xpTracker = client.getWidget(WidgetInfo.EXPERIENCE_TRACKER_WIDGET);
        
        if (xpTracker != null && !xpTracker.isHidden()) {
            Rectangle bounds = xpTracker.getBounds();
            if (bounds != null && bounds.width > 0) {
                int x = bounds.x + randomization.uniformRandomInt(10, Math.max(11, bounds.width - 10));
                int y = bounds.y + randomization.uniformRandomInt(5, Math.max(6, bounds.height - 5));
                
                log.debug("Hovering XP tracker at ({}, {}) for {}ms", x, y, viewDurationPerSkill);
                
                pendingOperation = ctx.getMouseController().moveToCanvas(x, y)
                    .thenCompose(v -> ctx.getHumanTimer().sleep(viewDurationPerSkill))
                    .thenRun(() -> phase = Phase.CLOSING);
            } else {
                phase = Phase.CLOSING;
            }
        } else {
            // XP tracker not visible, fall back to skill orb
            log.debug("XP tracker not visible, falling back to skill orb");
            selectedMethod = CheckMethod.SKILL_ORB;
            phase = Phase.HOVERING_ORB;
        }
    }

    private void executeClose(TaskContext ctx) {
        // For skills tab, return to inventory tab
        if (selectedMethod == CheckMethod.SKILLS_TAB) {
            Client client = ctx.getClient();
            
            Widget inventoryTab = client.getWidget(WidgetInfo.FIXED_VIEWPORT_INVENTORY_TAB);
            if (inventoryTab == null || inventoryTab.isHidden()) {
                inventoryTab = client.getWidget(WidgetInfo.RESIZABLE_VIEWPORT_INVENTORY_TAB);
            }
            
            if (inventoryTab != null && !inventoryTab.isHidden()) {
                Rectangle bounds = inventoryTab.getBounds();
                if (bounds != null && bounds.width > 0) {
                    int x = bounds.x + randomization.uniformRandomInt(5, Math.max(6, bounds.width - 5));
                    int y = bounds.y + randomization.uniformRandomInt(5, Math.max(6, bounds.height - 5));
                    
                    log.debug("Returning to inventory tab");
                    pendingOperation = ctx.getMouseController().moveToCanvas(x, y)
                        .thenCompose(v -> ctx.getMouseController().click())
                        .thenCompose(v -> ctx.getHumanTimer().sleep(randomization.uniformRandomLong(100, 300)))
                        .thenRun(() -> phase = Phase.COMPLETED);
                    return;
                }
            }
        }
        
        // For orb hover or XP tracker, just move mouse slightly and complete
        pendingOperation = ctx.getMouseController().performIdleBehavior()
            .thenRun(() -> phase = Phase.COMPLETED);
    }

    /**
     * Get the widget index for a skill in the skills container.
     * The skills container children are ordered by the skill's display order.
     */
    private int getSkillWidgetIndex(Skill skill) {
        // Skills are displayed in a specific order in the stats tab
        // This maps skill ordinal to widget index
        switch (skill) {
            case ATTACK: return 0;
            case STRENGTH: return 2;
            case DEFENCE: return 4;
            case RANGED: return 6;
            case PRAYER: return 8;
            case MAGIC: return 10;
            case RUNECRAFT: return 12;
            case CONSTRUCTION: return 14;
            case HITPOINTS: return 1;
            case AGILITY: return 3;
            case HERBLORE: return 5;
            case THIEVING: return 7;
            case CRAFTING: return 9;
            case FLETCHING: return 11;
            case SLAYER: return 13;
            case HUNTER: return 15;
            case MINING: return 16;
            case SMITHING: return 17;
            case FISHING: return 18;
            case COOKING: return 19;
            case FIREMAKING: return 20;
            case WOODCUTTING: return 21;
            case FARMING: return 22;
            default: return 0;
        }
    }
}
