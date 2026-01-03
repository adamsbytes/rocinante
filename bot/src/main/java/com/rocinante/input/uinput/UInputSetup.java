package com.rocinante.input.uinput;

import com.sun.jna.Structure;
import com.sun.jna.Structure.FieldOrder;

/**
 * JNA structure representing the Linux uinput_setup struct.
 * Used with UI_DEV_SETUP ioctl to configure the virtual device identity.
 * 
 * struct uinput_setup {
 *     struct input_id id;
 *     char name[UINPUT_MAX_NAME_SIZE];
 *     __u32 ff_effects_max;
 * };
 * 
 * struct input_id {
 *     __u16 bustype;
 *     __u16 vendor;
 *     __u16 product;
 *     __u16 version;
 * };
 */
@FieldOrder({"bustype", "vendor", "product", "version", "name", "ff_effects_max"})
public class UInputSetup extends Structure {

    /**
     * Bus type (BUS_USB, BUS_BLUETOOTH, etc.)
     */
    public short bustype;

    /**
     * Vendor ID (e.g., 0x046d for Logitech)
     */
    public short vendor;

    /**
     * Product ID (e.g., 0xc08b for G502)
     */
    public short product;

    /**
     * Version number (typically 0x0111)
     */
    public short version;

    /**
     * Device name (max 80 characters)
     */
    public byte[] name = new byte[LinuxInputConstants.UINPUT_MAX_NAME_SIZE];

    /**
     * Force feedback effects max (0 for no FF support)
     */
    public int ff_effects_max;

    /**
     * Default constructor.
     */
    public UInputSetup() {
        super();
    }

    /**
     * Set the device name.
     * 
     * @param deviceName the name to set (will be truncated if too long)
     */
    public void setName(String deviceName) {
        byte[] nameBytes = deviceName.getBytes();
        int length = Math.min(nameBytes.length, LinuxInputConstants.UINPUT_MAX_NAME_SIZE - 1);
        System.arraycopy(nameBytes, 0, name, 0, length);
        name[length] = 0; // Null terminate
    }

    /**
     * Configure the device identity.
     * 
     * @param busType bus type (use BUS_USB for USB devices)
     * @param vendorId vendor ID
     * @param productId product ID
     * @param deviceVersion version number
     * @param deviceName device name string
     */
    public void configure(short busType, short vendorId, short productId, 
                         short deviceVersion, String deviceName) {
        this.bustype = busType;
        this.vendor = vendorId;
        this.product = productId;
        this.version = deviceVersion;
        this.ff_effects_max = 0;
        setName(deviceName);
    }
}
