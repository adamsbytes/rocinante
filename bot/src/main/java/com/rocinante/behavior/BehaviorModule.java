package com.rocinante.behavior;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.rocinante.behavior.tasks.*;
import com.rocinante.input.CameraController;
import com.rocinante.input.MouseCameraCoupler;
import com.rocinante.input.RobotMouseController;
import com.rocinante.input.RobotKeyboardController;
import com.rocinante.state.IronmanState;
import com.rocinante.tasks.Task;
import com.rocinante.timing.HumanTimer;
import com.rocinante.util.PerlinNoise;
import com.rocinante.util.Randomization;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;

import java.util.function.Function;

/**
 * Guice module for the behavioral anti-detection system.
 * 
 * Binds all behavioral components and wires up the break task factory.
 */
@Slf4j
public class BehaviorModule extends AbstractModule {

    @Override
    protected void configure() {
        // Most bindings are @Singleton annotated on the classes themselves
        // This module provides integration wiring
    }

    /**
     * Creates the break task factory that BreakScheduler uses to create break tasks.
     */
    @Provides
    @Singleton
    public Function<BreakType, Task> provideBreakTaskFactory(
            BreakScheduler breakScheduler,
            FatigueModel fatigueModel,
            PlayerProfile playerProfile,
            Randomization randomization,
            HumanTimer humanTimer) {
        
        return breakType -> {
            switch (breakType) {
                case MICRO_PAUSE:
                    return new MicroPauseTask(breakScheduler, fatigueModel, randomization);
                    
                case SHORT_BREAK:
                    return new ShortBreakTask(breakScheduler, fatigueModel, playerProfile, 
                            randomization, humanTimer);
                    
                case LONG_BREAK:
                    return new LongBreakTask(breakScheduler, fatigueModel, playerProfile,
                            randomization, humanTimer);
                    
                case SESSION_END:
                    // Session end doesn't need a task - it triggers logout
                    log.info("Session end triggered - should logout");
                    return null;
                    
                default:
                    log.warn("Unknown break type: {}", breakType);
                    return null;
            }
        };
    }

    /**
     * Creates a session ritual task factory.
     */
    @Provides
    public SessionRitualTask provideSessionRitualTask(
            PlayerProfile playerProfile,
            Randomization randomization,
            HumanTimer humanTimer) {
        return new SessionRitualTask(playerProfile, randomization, humanTimer);
    }

    /**
     * Creates an idle behavior task factory.
     */
    @Provides
    public IdleBehaviorTask provideIdleBehaviorTask(Randomization randomization) {
        return new IdleBehaviorTask(randomization);
    }

    // ========================================================================
    // Anti-Detection Components
    // ========================================================================

    /**
     * Provides the CameraController for humanized camera manipulation.
     */
    @Provides
    @Singleton
    public CameraController provideCameraController(Client client, Randomization randomization, 
                                                     PerlinNoise perlinNoise,
                                                     RobotMouseController mouseController,
                                                     RobotKeyboardController keyboardController) {
        return new CameraController(client, randomization, perlinNoise, mouseController, keyboardController);
    }

    /**
     * Provides the MouseCameraCoupler for coordinated mouse+camera movements.
     */
    @Provides
    @Singleton
    public MouseCameraCoupler provideMouseCameraCoupler(Client client, CameraController cameraController,
                                                         Randomization randomization, ClientThread clientThread) {
        return new MouseCameraCoupler(client, cameraController, randomization, clientThread);
    }

    /**
     * Provides the ActionSequencer for randomizing action sequences.
     */
    @Provides
    @Singleton
    public ActionSequencer provideActionSequencer(Randomization randomization) {
        return new ActionSequencer(randomization);
    }

    /**
     * Provides the InefficiencyInjector for humanizing bot behavior.
     */
    @Provides
    @Singleton
    public InefficiencyInjector provideInefficiencyInjector(Randomization randomization) {
        return new InefficiencyInjector(randomization);
    }

    /**
     * Provides the PredictiveHoverManager for anticipatory hovering during gathering activities.
     * 
     * <p>The predictive hover system allows the bot to hover over the next target while
     * the current action is completing, mimicking engaged human behavior during:
     * <ul>
     *   <li>Woodcutting - hover next tree while current one falls</li>
     *   <li>Mining - hover next rock while current one depletes</li>
     *   <li>Fishing - hover next spot while current one moves</li>
     * </ul>
     */
    @Provides
    @Singleton
    public PredictiveHoverManager providePredictiveHoverManager(
            PlayerProfile playerProfile,
            FatigueModel fatigueModel,
            AttentionModel attentionModel,
            Randomization randomization,
            com.rocinante.navigation.NavigationService navigationService) {
        return new PredictiveHoverManager(playerProfile, fatigueModel, attentionModel, randomization, navigationService);
    }

    /**
     * Provides the LogoutHandler for humanized logout behavior.
     */
    @Provides
    @Singleton
    public LogoutHandler provideLogoutHandler(Client client, Randomization randomization, 
                                               HumanTimer humanTimer) {
        return new LogoutHandler(client, randomization, humanTimer);
    }

    /**
     * Provides the TradeHandler for handling incoming trade requests.
     */
    @Provides
    @Singleton
    public TradeHandler provideTradeHandler(Client client, IronmanState ironmanState) {
        return new TradeHandler(client, ironmanState);
    }
}

