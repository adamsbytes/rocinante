package com.rocinante.behavior;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.rocinante.behavior.tasks.*;
import com.rocinante.input.CameraController;
import com.rocinante.input.MouseCameraCoupler;
import com.rocinante.tasks.Task;
import com.rocinante.timing.HumanTimer;
import com.rocinante.util.PerlinNoise;
import com.rocinante.util.Randomization;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;

import java.awt.AWTException;
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
                                                     PerlinNoise perlinNoise) {
        try {
            return new CameraController(client, randomization, perlinNoise);
        } catch (AWTException e) {
            log.error("Failed to create CameraController", e);
            throw new RuntimeException("Failed to create CameraController", e);
        }
    }

    /**
     * Provides the MouseCameraCoupler for coordinated mouse+camera movements.
     */
    @Provides
    @Singleton
    public MouseCameraCoupler provideMouseCameraCoupler(Client client, CameraController cameraController,
                                                         Randomization randomization) {
        return new MouseCameraCoupler(client, cameraController, randomization);
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
     * Provides the LogoutHandler for humanized logout behavior.
     */
    @Provides
    @Singleton
    public LogoutHandler provideLogoutHandler(Client client, Randomization randomization, 
                                               HumanTimer humanTimer) {
        return new LogoutHandler(client, randomization, humanTimer);
    }
}

