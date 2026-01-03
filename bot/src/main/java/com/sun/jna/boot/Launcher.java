package com.sun.jna.boot;

import com.rocinante.core.RocinantePlugin;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class Launcher {
    public static void main(String[] args) throws Exception {
        ExternalPluginManager.loadBuiltin(RocinantePlugin.class);
        RuneLite.main(args);
    }
}
