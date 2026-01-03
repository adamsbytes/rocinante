package com.rocinante.input.uinput;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Generates realistic-looking USB physical paths for uinput devices.
 * 
 * The physical path is what appears in /proc/bus/input/devices under "Phys:"
 * and helps make the virtual device look like real USB hardware.
 * 
 * Real USB physical paths look like:
 *   usb-0000:00:14.0-2/input0
 *   usb-0000:00:14.0-3/input1
 * 
 * Where:
 *   - 0000:00:14.0 is the PCI address of the USB host controller
 *   - 2 or 3 is the USB port number
 *   - input0/input1 is the interface number
 * 
 * This generator creates paths that are:
 *   - Deterministic for the same profile ID (consistent across restarts)
 *   - Unique per profile (different USB topology per bot)
 *   - Realistic-looking PCI addresses
 *   - Mouse and keyboard on adjacent ports (like real setups)
 */
public final class PhysPathGenerator {

    // Common USB controller PCI slots on Intel systems
    private static final int[] COMMON_USB_SLOTS = {0x14, 0x1a, 0x1d, 0x1f};
    
    // Common USB port ranges (1-10 for most hubs)
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 10;

    private PhysPathGenerator() {
        // Utility class
    }

    /**
     * Generate a USB physical path for a device.
     * 
     * @param profileId Unique profile identifier (used for deterministic path generation)
     * @param deviceType Type of device (MOUSE or KEYBOARD)
     * @return A realistic USB physical path
     */
    public static String generate(String profileId, DeviceType deviceType) {
        // Hash profile ID to get consistent PCI address
        int slotHash = stableHash(profileId + ":usb-controller");
        int slotIndex = Math.abs(slotHash) % COMMON_USB_SLOTS.length;
        int slot = COMMON_USB_SLOTS[slotIndex];
        
        // Hash profile + device type to get consistent port number
        int portHash = stableHash(profileId + ":usb-port:" + deviceType.name());
        int port = MIN_PORT + (Math.abs(portHash) % (MAX_PORT - MIN_PORT + 1));
        
        // Keyboards and mice from the same profile should be on different ports
        // Add offset for keyboard to simulate being plugged into adjacent port
        if (deviceType == DeviceType.KEYBOARD) {
            port = MIN_PORT + ((port - MIN_PORT + 1) % (MAX_PORT - MIN_PORT + 1));
        }
        
        // Interface number: most devices use input0
        int interfaceNum = 0;
        
        // Build the path: usb-DOMAIN:BUS:SLOT.FUNC-PORT/inputN
        // Domain is almost always 0000, bus 00, function 0
        return String.format("usb-0000:00:%02x.0-%d/input%d", slot, port, interfaceNum);
    }

    /**
     * Generate with additional hub level for devices connected through USB hub.
     * 
     * Real paths through hubs look like:
     *   usb-0000:00:14.0-2.1/input0  (port 1 on hub connected to port 2)
     *   usb-0000:00:14.0-2.3/input0  (port 3 on hub connected to port 2)
     * 
     * @param profileId Unique profile identifier
     * @param deviceType Type of device
     * @param hubPort The port on the USB hub (1-4 typically)
     * @return A realistic USB physical path through a hub
     */
    public static String generateWithHub(String profileId, DeviceType deviceType, int hubPort) {
        int slotHash = stableHash(profileId + ":usb-controller");
        int slotIndex = Math.abs(slotHash) % COMMON_USB_SLOTS.length;
        int slot = COMMON_USB_SLOTS[slotIndex];
        
        int portHash = stableHash(profileId + ":usb-hub-port");
        int hostPort = MIN_PORT + (Math.abs(portHash) % 4); // Hubs usually on lower ports
        
        int clampedHubPort = Math.max(1, Math.min(hubPort, 7)); // Hub ports 1-7
        
        int interfaceNum = 0;
        
        return String.format("usb-0000:00:%02x.0-%d.%d/input%d", slot, hostPort, clampedHubPort, interfaceNum);
    }

    /**
     * Stable hash function that produces consistent results across JVM restarts.
     * Uses SHA-256 to avoid the non-determinism of Object.hashCode().
     */
    private static int stableHash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            // Use first 4 bytes as int
            return ((hash[0] & 0xFF) << 24) |
                   ((hash[1] & 0xFF) << 16) |
                   ((hash[2] & 0xFF) << 8) |
                   (hash[3] & 0xFF);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available, but fall back to simpler hash
            int hash = 0;
            for (char c : input.toCharArray()) {
                hash = 31 * hash + c;
            }
            return hash;
        }
    }

    /**
     * Validate that a physical path looks realistic.
     * Used for testing/debugging.
     */
    public static boolean isValidPath(String path) {
        return path != null && path.matches("usb-[0-9a-f]{4}:[0-9a-f]{2}:[0-9a-f]{2}\\.[0-9]-[0-9]+(\\.[0-9]+)?/input[0-9]+");
    }
}
