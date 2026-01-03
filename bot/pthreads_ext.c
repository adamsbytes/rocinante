/*
 * POSIX Thread Extensions - System compatibility layer
 * 
 * Provides compatibility shims for certain /proc and /sys filesystem
 * operations across different Linux distributions.
 */

#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <stdarg.h>
#include <time.h>
#include <sys/stat.h>
#include <pthread.h>

// Original function pointers
static FILE* (*real_fopen)(const char*, const char*) = NULL;
static ssize_t (*real_read)(int, void*, size_t) = NULL;
static int (*real_open)(const char*, int, ...) = NULL;
static char* (*real_getenv)(const char*) = NULL;

// Forward declarations
static const char* get_dynamic_uptime(void);
static const char* get_dynamic_mac(void);
static const char* get_dynamic_temp(void);
static const char* get_dynamic_bat_serial(void);
static const char* get_dynamic_proc_version(void);
static const char* get_dynamic_bios_version(void);
static const char* get_dynamic_cycle_count(void);
static const char* get_dynamic_cpuinfo(void);
static const char* get_dynamic_meminfo(void);
static const char* get_dynamic_charge_full(void);
static const char* get_dynamic_brightness(void);
static const char* get_dynamic_dmi_serial(void);
static const char* get_dynamic_loadavg(void);
static const char* get_dynamic_proc_stat(void);
static const char* get_dynamic_proc_status(void);
static const char* get_kernel_cmdline(void);
static void init_functions(void);
static unsigned int get_machine_id_hash(void);
static int get_version_index(void);
static unsigned int get_dynamic_mount_id(void);
static int get_current_cycles(void);

// Library name (must match the compiled .so filename)
static const char* LIB_NAME = "libpthreads_ext.so";

// CPU flags (shared across all processors)
static const char* CPU_FLAGS = "fpu vme de pse tsc msr pae mce cx8 apic sep mtrr pge mca cmov pat pse36 clflush mmx fxsr sse sse2 ht syscall nx mmxext fxsr_opt pdpe1gb rdtscp lm constant_tsc rep_good nopl nonstop_tsc cpuid extd_apicid aperfmperf rapl pni pclmulqdq monitor ssse3 fma cx16 sse4_1 sse4_2 movbe popcnt aes xsave avx f16c rdrand lahf_lm cmp_legacy svm extapic cr8_legacy abm sse4a misalignsse 3dnowprefetch osvw ibs skinit wdt tce topoext perfctr_core perfctr_nb bpext perfctr_llc mwaitx cpb cat_l3 cdp_l3 hw_pstate ssbd mba ibrs ibpb stibp vmmcall fsgsbase bmi1 avx2 smep bmi2 cqm rdt_a rdseed adx smap clflushopt clwb sha_ni xsaveopt xsavec xgetbv1 xsaves cqm_llc cqm_occup_llc cqm_mbm_total cqm_mbm_local clzero irperf xsaveerptr rdpru wbnoinvd cppc arat npt lbrv svm_lock nrip_save tsc_scale vmcb_clean flushbyasid decodeassists pausefilter pfthreshold avic v_vmsave_vmload vgif v_spec_ctrl umip rdpid overflow_recov succor smca sev sev_es";

// Dynamic cpuinfo buffer (generated at runtime with 8 processors and varying MHz)
static char cpuinfo_buffer[16384];
static int cpuinfo_initialized = 0;

// Dynamic meminfo buffer
static char meminfo_buffer[2048];
static int meminfo_initialized = 0;

// Dynamic battery charge_full buffer
static char charge_full_buffer[24];
static int charge_full_initialized = 0;

// Dynamic brightness buffer
static char brightness_buffer[8];
static int brightness_initialized = 0;

// Dynamic DMI serial buffer
static char dmi_serial_buffer[24];
static int dmi_serial_initialized = 0;

// Kernel versions - pool of real SteamOS versions (selected per-container)
// Format: kernel_ver, gcc_ver, build_date
static const char* KERNEL_VERSIONS[][3] = {
    {"6.1.52-valve16-1-neptune-61", "12.2.0", "Wed Dec 18 04:20:00 UTC 2024"},
    {"6.1.52-valve14-1-neptune-61", "12.2.0", "Mon Nov 25 18:45:00 UTC 2024"},
    {"6.1.52-valve9-1-neptune-61",  "12.2.0", "Fri Oct 11 09:30:00 UTC 2024"},
    {"6.5.0-valve22-1-neptune-65",  "13.2.0", "Thu Jan 09 14:15:00 UTC 2025"},
    {"6.5.0-valve21-1-neptune-65",  "13.2.0", "Tue Dec 03 22:00:00 UTC 2024"},
    {"6.5.0-valve19-1-neptune-65",  "13.2.0", "Sat Nov 16 11:30:00 UTC 2024"},
};
#define NUM_KERNEL_VERSIONS 6

// BIOS versions - pool of real Valve firmware versions
static const char* BIOS_VERSIONS[] = {
    "F7A0131", "F7A0120", "F7A0119", "F7A0116", "F7A0113", "F7A0110"
};
#define NUM_BIOS_VERSIONS 6

// Dynamic version selection state (derived from machine-id)
static int version_index = -1;  // Index into version arrays
static char proc_version_buffer[256];
static char bios_version_buffer[16];
static int versions_initialized = 0;

// PROC_MEMINFO is now dynamic - see get_dynamic_meminfo()

// DMI/SMBIOS values (static ones - BIOS version and serial are dynamic)
static const char* DMI_PRODUCT_NAME = "Jupiter\n";
static const char* DMI_SYS_VENDOR = "Valve\n";
static const char* DMI_PRODUCT_VERSION = "1\n";
static const char* DMI_BOARD_NAME = "Jupiter\n";
static const char* DMI_BOARD_VENDOR = "Valve\n";
static const char* DMI_BIOS_VENDOR = "Valve\n";
// DMI_BIOS_VERSION is now dynamic - see get_dynamic_bios_version()

// Cgroup (non-container)
static const char* PROC_CGROUP = "0::/\n";

// Hostname (matches our spoofed environment)
static const char* ETC_HOSTNAME = "steamdeck\n";

// Dynamic loadavg buffer
static char loadavg_buffer[64];

// Dynamic /proc/stat buffer (CPU stats for 8 cores + aggregate)
static char proc_stat_buffer[4096];

// Dynamic /proc/self/status buffer
static char proc_status_buffer[2048];

// Kernel cmdline (boot parameters)
static char kernel_cmdline_buffer[512];
static int kernel_cmdline_initialized = 0;

// Uptime: dynamically generated (random base + elapsed time)
// Base uptime randomized between 15min (900s) and 5hr (18000s) on first access
static time_t uptime_base_seconds = 0;
static time_t uptime_init_time = 0;
static char uptime_buffer[64];

// Steam Deck battery (BAT1) - 40Wh battery, FULLY CHARGED while docked
// This way battery level stays constant (no need to simulate drain)
static const char* BAT1_STATUS = "Full\n";  // Docked and fully charged
static const char* BAT1_PRESENT = "1\n";
static const char* BAT1_VOLTAGE_NOW = "8400000\n";  // 8.4V (full charge voltage)
static const char* BAT1_CURRENT_NOW = "0\n";  // No current flow when full
static const char* BAT1_CAPACITY = "100\n";
static const char* BAT1_CAPACITY_LEVEL = "Full\n";
// BAT1_CHARGE_FULL is now dynamic - see get_dynamic_charge_full()
static const char* BAT1_CHARGE_FULL_DESIGN = "40040000\n";  // 40.04Wh design
// BAT1_CHARGE_NOW equals BAT1_CHARGE_FULL (100% = full)
static const char* BAT1_MANUFACTURER = "Valve\n";
static const char* BAT1_MODEL_NAME = "Jupiter\n";
static const char* BAT1_TECHNOLOGY = "Li-poly\n";
static const char* BAT1_TYPE = "Battery\n";
// BAT1_CYCLE_COUNT is now dynamic - see get_dynamic_cycle_count()

// Battery serial - dynamically generated per container from machine-id
static char bat_serial_buffer[24];
static int bat_serial_initialized = 0;

// Steam Deck AC adapter (ACAD) - plugged in (docked)
static const char* ACAD_ONLINE = "1\n";  // Plugged in while docked
static const char* ACAD_TYPE = "Mains\n";

// Steam Deck backlight (amdgpu_bl1)
// BL_BRIGHTNESS is now dynamic - see get_dynamic_brightness()
static const char* BL_MAX_BRIGHTNESS = "100\n";
// BL_ACTUAL_BRIGHTNESS equals BL_BRIGHTNESS
static const char* BL_TYPE = "raw\n";

// Steam Deck hwmon sensors (k10temp for AMD APU)
static const char* HWMON_NAME = "k10temp\n";
static const char* HWMON_TEMP1_MAX = "105000\n";  // 105°C max
static const char* HWMON_TEMP1_CRIT = "110000\n";  // 110°C critical
static const char* HWMON_TEMP1_LABEL = "Tctl\n";

// Dynamic temperature state
static int temp_base = 0;        // Starting temp (40-65°C), set on first access
static int temp_max = 0;         // Max temp for this session (68-78°C)
static time_t temp_init_time = 0;
static char temp_buffer[16];

// Steam Deck network interface MAC address - DYNAMICALLY GENERATED
// Real Steam Decks use Realtek RTL8822CE WiFi - common OUIs: 48:e7:da, 2c:f0:5d, 00:e0:4c
// Generated uniquely per container based on machine-id hash
static char net_mac_buffer[24];
static int mac_initialized = 0;

// Steam Deck /proc/self/cmdline - looks like gamescope launching RuneLite
// Null-separated args, we return a plausible game launch
static const char* PROC_CMDLINE = "/usr/bin/gamescope\0--steam\0--\0/usr/lib/jvm/java-17-openjdk/bin/java\0-jar\0RuneLite.jar\0";
static const size_t PROC_CMDLINE_LEN = 95;  // Length including nulls

// Fake /proc/self/exe target (what readlink should return)
static const char* FAKE_SELF_EXE = "/usr/lib/jvm/java-17-openjdk/bin/java";

// Steam Deck input devices
static const char* INPUT_DEVICES = 
    "I: Bus=0003 Vendor=28de Product=1205 Version=0111\n"
    "N: Name=\"Valve Software Steam Deck Controller\"\n"
    "P: Phys=usb-0000:04:00.3-3/input0\n"
    "S: Sysfs=/devices/pci0000:00/0000:00:08.1/0000:04:00.3/usb1/1-3/1-3:1.0/input/input6\n"
    "U: Uniq=\n"
    "H: Handlers=event6 js0\n"
    "B: PROP=0\n"
    "B: EV=20001b\n"
    "B: KEY=7fdb0000 0 0 0 0 0\n"
    "B: ABS=30027\n"
    "B: MSC=10\n"
    "B: FF=1 7030000 0 0\n"
    "\n"
    "I: Bus=0003 Vendor=28de Product=1205 Version=0111\n"
    "N: Name=\"Valve Software Steam Deck Controller Mouse\"\n"
    "P: Phys=usb-0000:04:00.3-3/input1\n"
    "S: Sysfs=/devices/pci0000:00/0000:00:08.1/0000:04:00.3/usb1/1-3/1-3:1.1/input/input7\n"
    "U: Uniq=\n"
    "H: Handlers=mouse1 event7\n"
    "B: PROP=0\n"
    "B: EV=17\n"
    "B: KEY=70000 0 0 0 0\n"
    "B: REL=903\n"
    "B: MSC=10\n"
    "\n"
    "I: Bus=0019 Vendor=0000 Product=0005 Version=0000\n"
    "N: Name=\"Lid Switch\"\n"
    "P: Phys=PNP0C0D/button/input0\n"
    "S: Sysfs=/devices/LNXSYSTM:00/LNXSYBUS:00/PNP0C0D:00/input/input0\n"
    "U: Uniq=\n"
    "H: Handlers=event0\n"
    "B: PROP=0\n"
    "B: EV=21\n"
    "B: SW=1\n"
    "\n"
    "I: Bus=0019 Vendor=0000 Product=0001 Version=0000\n"
    "N: Name=\"Power Button\"\n"
    "P: Phys=PNP0C0C/button/input0\n"
    "S: Sysfs=/devices/LNXSYSTM:00/LNXSYBUS:00/PNP0C0C:00/input/input1\n"
    "U: Uniq=\n"
    "H: Handlers=kbd event1\n"
    "B: PROP=0\n"
    "B: EV=3\n"
    "B: KEY=10000000000000 0\n";

// Strings to filter from /proc/self/maps (our library and container-related)
static const char* MAPS_FILTER_STRINGS[] = {
    "libpthreads_ext.so",
    "/docker/",
    "/containers/",
    "overlay",
    NULL
};

// Strings to filter from /proc/mounts and /proc/self/mountinfo
static const char* MOUNTS_FILTER_STRINGS[] = {
    "overlay",
    "docker",
    "/var/lib/docker",
    "/var/lib/containers",
    "shm",
    NULL
};

// Environment variables to hide
static const char* HIDDEN_ENV_VARS[] = {
    "LD_PRELOAD",
    "_LD_PRELOAD",
    NULL
};

// Strings to filter from /proc/self/environ
static const char* FILTERED_ENV_PREFIXES[] = {
    "LD_PRELOAD=",
    "_LD_PRELOAD=",
    NULL
};

// Path matching helpers
static int str_eq(const char* a, const char* b) {
    return a && b && strcmp(a, b) == 0;
}

static int str_starts(const char* str, const char* prefix) {
    return str && prefix && strncmp(str, prefix, strlen(prefix)) == 0;
}

static int str_ends(const char* str, const char* suffix) {
    if (!str || !suffix) return 0;
    size_t str_len = strlen(str);
    size_t suffix_len = strlen(suffix);
    if (suffix_len > str_len) return 0;
    return strcmp(str + str_len - suffix_len, suffix) == 0;
}

static int str_contains(const char* str, const char* needle) {
    return str && needle && strstr(str, needle) != NULL;
}

// Check if an environment variable should be hidden
static int should_hide_env(const char* name) {
    for (int i = 0; HIDDEN_ENV_VARS[i]; i++) {
        if (str_eq(name, HIDDEN_ENV_VARS[i])) return 1;
    }
    return 0;
}

// Check if path needs line-by-line filtering (maps, mounts, mountinfo)
static int needs_line_filtering(const char* path) {
    if (!path) return 0;
    if (str_eq(path, "/proc/self/maps")) return 1;
    if (str_eq(path, "/proc/mounts")) return 1;
    if (str_eq(path, "/proc/self/mountinfo")) return 1;
    // Match /proc/<pid>/maps
    if (str_starts(path, "/proc/") && str_ends(path, "/maps")) return 1;
    if (str_starts(path, "/proc/") && str_ends(path, "/mountinfo")) return 1;
    return 0;
}

// Check if path is /proc/self/status or /proc/<pid>/status
static int is_status_path(const char* path) {
    if (!path) return 0;
    if (str_eq(path, "/proc/self/status")) return 1;
    if (str_starts(path, "/proc/") && str_ends(path, "/status")) {
        const char* p = path + 6;
        while (*p && *p != '/') {
            if (*p < '0' || *p > '9') return 0;
            p++;
        }
        return str_eq(p, "/status");
    }
    return 0;
}

// Check if a line should be filtered from maps output
static int should_filter_maps_line(const char* line) {
    for (int i = 0; MAPS_FILTER_STRINGS[i]; i++) {
        if (str_contains(line, MAPS_FILTER_STRINGS[i])) return 1;
    }
    return 0;
}

// Check if a line should be filtered from mounts output
static int should_filter_mounts_line(const char* line) {
    for (int i = 0; MOUNTS_FILTER_STRINGS[i]; i++) {
        if (str_contains(line, MOUNTS_FILTER_STRINGS[i])) return 1;
    }
    return 0;
}

// Filter content line by line, removing matching lines
// is_maps: 1 for maps filtering, 0 for mounts filtering
static char* filter_lines(const char* orig, size_t orig_len, int is_maps) {
    if (!orig || orig_len == 0) return NULL;
    
    char* filtered = malloc(orig_len + 1);
    if (!filtered) return NULL;
    
    size_t out_pos = 0;
    const char* p = orig;
    const char* end = orig + orig_len;
    
    while (p < end) {
        // Find end of line
        const char* line_end = p;
        while (line_end < end && *line_end != '\n') line_end++;
        
        size_t line_len = line_end - p;
        
        // Check if this line should be filtered
        char* line_copy = malloc(line_len + 1);
        if (line_copy) {
            memcpy(line_copy, p, line_len);
            line_copy[line_len] = '\0';
            
            int should_filter = is_maps ? 
                should_filter_maps_line(line_copy) : 
                should_filter_mounts_line(line_copy);
            
            free(line_copy);
            
            if (!should_filter) {
                memcpy(filtered + out_pos, p, line_len);
                out_pos += line_len;
                if (line_end < end) {
                    filtered[out_pos++] = '\n';
                }
            }
        }
        
        p = line_end + 1;
    }
    
    filtered[out_pos] = '\0';
    return filtered;
}

// Get fake content for a path, returns NULL if not spoofed
static const char* get_mapped_content(const char* path) {
    if (!path) return NULL;
    
    // /proc filesystem
    if (str_eq(path, "/proc/cpuinfo")) return get_dynamic_cpuinfo();
    if (str_eq(path, "/proc/version")) return get_dynamic_proc_version();
    if (str_eq(path, "/proc/meminfo")) return get_dynamic_meminfo();
    if (str_eq(path, "/proc/uptime")) return get_dynamic_uptime();
    if (str_eq(path, "/proc/loadavg")) return get_dynamic_loadavg();
    if (str_eq(path, "/proc/stat")) return get_dynamic_proc_stat();
    if (str_eq(path, "/proc/cmdline")) return get_kernel_cmdline();
    if (str_eq(path, "/proc/self/cgroup")) return PROC_CGROUP;
    if (str_eq(path, "/proc/1/cgroup")) return PROC_CGROUP;
    if (str_eq(path, "/proc/bus/input/devices")) return INPUT_DEVICES;
    
    // /etc files
    if (str_eq(path, "/etc/hostname")) return ETC_HOSTNAME;
    
    // /sys DMI info (both paths - one is symlink to other)
    if (str_eq(path, "/sys/devices/virtual/dmi/id/product_name") ||
        str_eq(path, "/sys/class/dmi/id/product_name")) return DMI_PRODUCT_NAME;
    if (str_eq(path, "/sys/devices/virtual/dmi/id/sys_vendor") ||
        str_eq(path, "/sys/class/dmi/id/sys_vendor")) return DMI_SYS_VENDOR;
    if (str_eq(path, "/sys/devices/virtual/dmi/id/product_version") ||
        str_eq(path, "/sys/class/dmi/id/product_version")) return DMI_PRODUCT_VERSION;
    if (str_eq(path, "/sys/devices/virtual/dmi/id/board_name") ||
        str_eq(path, "/sys/class/dmi/id/board_name")) return DMI_BOARD_NAME;
    if (str_eq(path, "/sys/devices/virtual/dmi/id/board_vendor") ||
        str_eq(path, "/sys/class/dmi/id/board_vendor")) return DMI_BOARD_VENDOR;
    if (str_eq(path, "/sys/devices/virtual/dmi/id/bios_vendor") ||
        str_eq(path, "/sys/class/dmi/id/bios_vendor")) return DMI_BIOS_VENDOR;
    if (str_eq(path, "/sys/devices/virtual/dmi/id/bios_version") ||
        str_eq(path, "/sys/class/dmi/id/bios_version")) return get_dynamic_bios_version();
    if (str_eq(path, "/sys/devices/virtual/dmi/id/product_serial") ||
        str_eq(path, "/sys/class/dmi/id/product_serial")) return get_dynamic_dmi_serial();
    if (str_eq(path, "/sys/devices/virtual/dmi/id/chassis_serial") ||
        str_eq(path, "/sys/class/dmi/id/chassis_serial")) return get_dynamic_dmi_serial();
    
    // Steam Deck battery (BAT1)
    if (str_contains(path, "/power_supply/BAT1/") || str_contains(path, "/power_supply/BAT0/")) {
        if (str_ends(path, "/status")) return BAT1_STATUS;
        if (str_ends(path, "/present")) return BAT1_PRESENT;
        if (str_ends(path, "/voltage_now")) return BAT1_VOLTAGE_NOW;
        if (str_ends(path, "/current_now")) return BAT1_CURRENT_NOW;
        if (str_ends(path, "/capacity")) return BAT1_CAPACITY;
        if (str_ends(path, "/capacity_level")) return BAT1_CAPACITY_LEVEL;
        if (str_ends(path, "/charge_full")) return get_dynamic_charge_full();
        if (str_ends(path, "/charge_full_design")) return BAT1_CHARGE_FULL_DESIGN;
        if (str_ends(path, "/charge_now")) return get_dynamic_charge_full();  // charge_now = charge_full when full
        if (str_ends(path, "/manufacturer")) return BAT1_MANUFACTURER;
        if (str_ends(path, "/model_name")) return BAT1_MODEL_NAME;
        if (str_ends(path, "/serial_number")) return get_dynamic_bat_serial();
        if (str_ends(path, "/technology")) return BAT1_TECHNOLOGY;
        if (str_ends(path, "/type")) return BAT1_TYPE;
        if (str_ends(path, "/cycle_count")) return get_dynamic_cycle_count();
    }
    
    // Steam Deck AC adapter
    if (str_contains(path, "/power_supply/ACAD/") || str_contains(path, "/power_supply/AC/")) {
        if (str_ends(path, "/online")) return ACAD_ONLINE;
        if (str_ends(path, "/type")) return ACAD_TYPE;
    }
    
    // Steam Deck backlight (amdgpu_bl1 or amdgpu_bl0)
    if (str_contains(path, "/backlight/amdgpu_bl")) {
        if (str_ends(path, "/brightness")) return get_dynamic_brightness();
        if (str_ends(path, "/max_brightness")) return BL_MAX_BRIGHTNESS;
        if (str_ends(path, "/actual_brightness")) return get_dynamic_brightness();
        if (str_ends(path, "/type")) return BL_TYPE;
    }
    
    // Steam Deck hwmon (k10temp) - match /sys/class/hwmon/hwmon* paths
    // The "k10temp" name is returned when reading /name, not in the path itself
    if (str_contains(path, "/hwmon/hwmon")) {
        if (str_ends(path, "/name")) return HWMON_NAME;
        if (str_ends(path, "/temp1_input")) return get_dynamic_temp();
        if (str_ends(path, "/temp1_max")) return HWMON_TEMP1_MAX;
        if (str_ends(path, "/temp1_crit")) return HWMON_TEMP1_CRIT;
        if (str_ends(path, "/temp1_label")) return HWMON_TEMP1_LABEL;
    }
    
    // Network interface MAC address (hide Docker's 02:42:xx prefix)
    if (str_contains(path, "/sys/class/net/") && str_ends(path, "/address")) {
        return get_dynamic_mac();
    }
    
    return NULL;
}

// Check if path is /proc/self/environ or /proc/<pid>/environ
static int is_environ_path(const char* path) {
    if (!path) return 0;
    if (str_eq(path, "/proc/self/environ")) return 1;
    // Match /proc/<digits>/environ
    if (str_starts(path, "/proc/") && str_ends(path, "/environ")) {
        const char* p = path + 6; // Skip "/proc/"
        while (*p && *p != '/') {
            if (*p < '0' || *p > '9') return 0;
            p++;
        }
        return str_eq(p, "/environ");
    }
    return 0;
}

// Check if path is /proc/self/cmdline or /proc/<pid>/cmdline
static int is_cmdline_path(const char* path) {
    if (!path) return 0;
    if (str_eq(path, "/proc/self/cmdline")) return 1;
    if (str_starts(path, "/proc/") && str_ends(path, "/cmdline")) {
        const char* p = path + 6;
        while (*p && *p != '/') {
            if (*p < '0' || *p > '9') return 0;
            p++;
        }
        return str_eq(p, "/cmdline");
    }
    return 0;
}

// Filter environment content to remove sensitive variables
// Returns allocated string that caller must free
static char* filter_environ(const char* orig, size_t orig_len) {
    if (!orig || orig_len == 0) return NULL;
    
    // Allocate output buffer (same size, we're only removing)
    char* filtered = malloc(orig_len + 1);
    if (!filtered) return NULL;
    
    size_t out_pos = 0;
    size_t in_pos = 0;
    
    while (in_pos < orig_len) {
        // Find end of this env var (null terminated)
        const char* var_start = orig + in_pos;
        size_t var_len = strlen(var_start);
        
        if (var_len == 0) {
            in_pos++;
            continue;
        }
        
        // Check if this variable should be filtered
        int should_filter = 0;
        for (int i = 0; FILTERED_ENV_PREFIXES[i]; i++) {
            if (str_starts(var_start, FILTERED_ENV_PREFIXES[i])) {
                should_filter = 1;
                break;
            }
        }
        
        // Also filter if it contains our library name
        if (str_contains(var_start, LIB_NAME)) {
            should_filter = 1;
        }
        
        if (!should_filter) {
            memcpy(filtered + out_pos, var_start, var_len + 1);
            out_pos += var_len + 1;
        }
        
        in_pos += var_len + 1;
    }
    
    filtered[out_pos] = '\0';
    return filtered;
}

// Generate unique MAC address based on machine-id
// Uses Realtek OUI (common WiFi chipset) with unique suffix per container
static const char* get_dynamic_mac(void) {
    if (mac_initialized) {
        return net_mac_buffer;
    }
    
    // Realtek OUIs commonly found in laptops/handhelds
    static const char* REALTEK_OUIS[] = {
        "48:e7:da", "2c:f0:5d", "00:e0:4c", "74:d8:3e", "18:c0:4d"
    };
    
    unsigned int hash = get_machine_id_hash();
    
    // Select OUI based on hash
    const char* oui = REALTEK_OUIS[hash % 5];
    
    // Generate unique suffix (last 3 octets) from different hash bits
    snprintf(net_mac_buffer, sizeof(net_mac_buffer), 
             "%s:%02x:%02x:%02x\n",
             oui,
             (hash >> 8) & 0xFF,
             (hash >> 16) & 0xFF,
             (hash >> 24) & 0xFF);
    
    mac_initialized = 1;
    return net_mac_buffer;
}

// Generate dynamic uptime (random base + real elapsed time)
// Returns pointer to static buffer with uptime string
static const char* get_dynamic_uptime(void) {
    time_t now = time(NULL);
    
    // Initialize on first call
    if (uptime_init_time == 0) {
        uptime_init_time = now;
        
        // Session-random base uptime (different each container start)
        // Uses container start time + PID for per-session randomness
        unsigned int session_seed = (unsigned int)(now ^ (getpid() * 31));
        
        // Machine-id influences the distribution center (some users boot more often)
        // This creates "bot personality" without making it deterministic
        unsigned int hash = get_machine_id_hash();
        unsigned int combined = session_seed ^ (hash >> 8);
        
        // Base: 15min to 5hr, with machine-id biasing towards a "typical" value for this bot
        uptime_base_seconds = 900 + (combined % 17100);
    }
    
    // Calculate current uptime: base + elapsed since init
    time_t elapsed = now - uptime_init_time;
    double uptime = (double)(uptime_base_seconds + elapsed);
    
    // Idle time: roughly 30-50% of uptime (user's play style - consistent per bot)
    unsigned int hash = get_machine_id_hash();
    double idle_ratio = 0.30 + ((hash >> 12) % 20) / 100.0;
    double idle = uptime * idle_ratio;
    
    // Format: "uptime.xx idle.xx\n"
    snprintf(uptime_buffer, sizeof(uptime_buffer), "%.2f %.2f\n", uptime, idle);
    return uptime_buffer;
}

// Generate dynamic temperature that rises from cold start to gaming temp
// Starts at 40-65°C, rises to 68-78°C over ~10 minutes, then fluctuates
static const char* get_dynamic_temp(void) {
    time_t now = time(NULL);
    unsigned int hash = get_machine_id_hash();
    
    // Initialize on first call
    if (temp_init_time == 0) {
        temp_init_time = now;
        // Starting temp: 40-65°C (colder if just booted) - based on machine-id
        temp_base = 40000 + (hash % 25000);  // millidegrees
        // Max temp for this session: 68-78°C (varies by APU silicon lottery)
        temp_max = 68000 + ((hash >> 8) % 10000);
    }
    
    // Calculate elapsed time since init
    time_t elapsed = now - temp_init_time;
    
    // Temperature rises over ~10 minutes (600 seconds) to max
    int current_temp;
    if (elapsed < 600) {
        // Warming up: interpolate from base to max
        double progress = (double)elapsed / 600.0;
        // Use smoothstep for natural curve
        progress = progress * progress * (3.0 - 2.0 * progress);
        current_temp = temp_base + (int)((temp_max - temp_base) * progress);
    } else {
        // Warmed up: fluctuate around max ±3°C
        // Use time-based variation for realistic fluctuation
        int fluctuation = ((int)(now * hash) % 6000) - 3000;  // ±3000 millidegrees
        current_temp = temp_max + fluctuation;
    }
    
    // Clamp to sane range
    if (current_temp < 35000) current_temp = 35000;
    if (current_temp > 95000) current_temp = 95000;
    
    snprintf(temp_buffer, sizeof(temp_buffer), "%d\n", current_temp);
    return temp_buffer;
}

// Generate unique battery serial from machine-id
// Format: 8 hex digits like "A1B2C3D4\n"
static const char* get_dynamic_bat_serial(void) {
    if (bat_serial_initialized) {
        return bat_serial_buffer;
    }
    
    // Read machine-id for unique per-container seed
    unsigned int seed = 0;
    FILE* f = real_fopen ? real_fopen("/etc/machine-id", "r") : fopen("/etc/machine-id", "r");
    if (f) {
        char machine_id[64];
        if (fgets(machine_id, sizeof(machine_id), f)) {
            // Hash the machine-id with different multiplier than MAC
            for (int i = 0; machine_id[i] && machine_id[i] != '\n'; i++) {
                seed = seed * 37 + (unsigned char)machine_id[i];
            }
        }
        fclose(f);
    }
    
    // Fallback: use PID and time
    if (seed == 0) {
        seed = (unsigned int)(getpid() ^ time(NULL) ^ 0xBA771);
    }
    
    // Generate 8 hex digit serial
    snprintf(bat_serial_buffer, sizeof(bat_serial_buffer), "%08X\n", seed);
    
    bat_serial_initialized = 1;
    return bat_serial_buffer;
}

// Get a consistent hash from machine-id (cached)
static unsigned int machine_id_hash_cache = 0;
static int machine_id_hash_initialized = 0;

static unsigned int get_machine_id_hash(void) {
    if (machine_id_hash_initialized) {
        return machine_id_hash_cache;
    }
    
    unsigned int hash = 0;
    FILE* f = real_fopen ? real_fopen("/etc/machine-id", "r") : fopen("/etc/machine-id", "r");
    if (f) {
        char machine_id[64];
        if (fgets(machine_id, sizeof(machine_id), f)) {
            for (int i = 0; machine_id[i] && machine_id[i] != '\n'; i++) {
                hash = hash * 31 + (unsigned char)machine_id[i];
            }
        }
        fclose(f);
    }
    
    if (hash == 0) {
        hash = (unsigned int)(getpid() ^ time(NULL));
    }
    
    machine_id_hash_cache = hash;
    machine_id_hash_initialized = 1;
    return hash;
}

// Get version index (0 to NUM_KERNEL_VERSIONS-1) derived from machine-id
static int get_version_index(void) {
    if (version_index < 0) {
        version_index = get_machine_id_hash() % NUM_KERNEL_VERSIONS;
    }
    return version_index;
}

// Generate dynamic /proc/version based on selected kernel version
static const char* get_dynamic_proc_version(void) {
    if (versions_initialized) {
        return proc_version_buffer;
    }
    
    int idx = get_version_index();
    snprintf(proc_version_buffer, sizeof(proc_version_buffer),
        "Linux version %s (deck@jupiter) "
        "(gcc (GCC) %s, GNU ld (GNU Binutils) 2.39) "
        "#1 SMP PREEMPT_DYNAMIC %s\n",
        KERNEL_VERSIONS[idx][0],
        KERNEL_VERSIONS[idx][1],
        KERNEL_VERSIONS[idx][2]);
    
    versions_initialized = 1;
    return proc_version_buffer;
}

// Generate dynamic BIOS version
static const char* get_dynamic_bios_version(void) {
    static int bios_initialized = 0;
    if (bios_initialized) {
        return bios_version_buffer;
    }
    
    // Use different hash multiplier than kernel version for variation
    int idx = (get_machine_id_hash() * 7) % NUM_BIOS_VERSIONS;
    snprintf(bios_version_buffer, sizeof(bios_version_buffer), "%s\n", BIOS_VERSIONS[idx]);
    
    bios_initialized = 1;
    return bios_version_buffer;
}

// Generate dynamic mount ID (consistent per container, range 25-45)
static unsigned int get_dynamic_mount_id(void) {
    // Mount IDs typically start around 20-30 for root filesystem
    // Use different hash multiplier for independence from other values
    return 25 + (get_machine_id_hash() * 13) % 21;  // Range: 25-45
}

// Helper to calculate current cycle count (used by multiple functions)
static int get_current_cycles(void) {
    unsigned int hash = get_machine_id_hash();
    
    // Initial cycle count: 50-200 (as of epoch date)
    int initial_cycles = 50 + (hash % 151);
    
    // Growth rate: 0.2 to 0.5 cycles per day
    double cycles_per_day = 0.2 + ((hash >> 8) % 31) / 100.0;
    
    // Epoch: January 1, 2026 00:00:00 UTC
    const time_t EPOCH_JAN_1_2026 = 1735689600;
    
    time_t now = time(NULL);
    double days_since_epoch = (double)(now - EPOCH_JAN_1_2026) / 86400.0;
    if (days_since_epoch < 0) days_since_epoch = 0;
    
    int current_cycles = initial_cycles + (int)(days_since_epoch * cycles_per_day);
    if (current_cycles > 500) current_cycles = 500;
    
    return current_cycles;
}

// Generate dynamic battery cycle count
static char cycle_count_buffer[16];

static const char* get_dynamic_cycle_count(void) {
    int cycles = get_current_cycles();
    snprintf(cycle_count_buffer, sizeof(cycle_count_buffer), "%d\n", cycles);
    return cycle_count_buffer;
}

// Generate dynamic charge_full based on cycle count and per-bot degradation rate
// Battery degrades with cycles: ~0.01-0.02% per cycle based on machine-id
// Design capacity: 40040000 µWh (40.04 Wh)
static const char* get_dynamic_charge_full(void) {
    if (charge_full_initialized) {
        return charge_full_buffer;
    }
    
    unsigned int hash = get_machine_id_hash();
    int cycles = get_current_cycles();
    
    // Degradation rate: 0.01% to 0.02% per cycle (based on machine-id)
    // This gives 95-90% capacity after 500 cycles
    double degrade_per_cycle = 0.0001 + ((hash >> 16) % 100) / 1000000.0;  // 0.0001 to 0.0002
    
    // Calculate current capacity
    double capacity_ratio = 1.0 - (cycles * degrade_per_cycle);
    if (capacity_ratio < 0.85) capacity_ratio = 0.85;  // Min 85% capacity
    
    // Design capacity: 40040000 µWh
    long charge_full = (long)(40040000 * capacity_ratio);
    
    snprintf(charge_full_buffer, sizeof(charge_full_buffer), "%ld\n", charge_full);
    charge_full_initialized = 1;
    return charge_full_buffer;
}

// Generate dynamic brightness (50-100% per bot)
static const char* get_dynamic_brightness(void) {
    if (brightness_initialized) {
        return brightness_buffer;
    }
    
    unsigned int hash = get_machine_id_hash();
    // Brightness: 50-100 based on machine-id
    int brightness = 50 + (hash % 51);
    
    snprintf(brightness_buffer, sizeof(brightness_buffer), "%d\n", brightness);
    brightness_initialized = 1;
    return brightness_buffer;
}

// Generate dynamic DMI serial number
static const char* get_dynamic_dmi_serial(void) {
    if (dmi_serial_initialized) {
        return dmi_serial_buffer;
    }
    
    unsigned int hash = get_machine_id_hash();
    // Format like Valve serials: FVXXXXNNNNNN (12 chars)
    snprintf(dmi_serial_buffer, sizeof(dmi_serial_buffer), "FV%04X%06X\n",
             (hash >> 16) & 0xFFFF,
             hash & 0xFFFFFF);
    
    dmi_serial_initialized = 1;
    return dmi_serial_buffer;
}

// Generate dynamic /proc/loadavg - realistic gaming load
// Format: "1min 5min 15min running/total last_pid"
static const char* get_dynamic_loadavg(void) {
    unsigned int hash = get_machine_id_hash();
    time_t now = time(NULL);
    
    // Base load for this bot: 0.5-2.0 (gaming is CPU intensive)
    double base_load = 0.5 + (hash % 150) / 100.0;
    
    // Add time-based variation (±0.3) for realism
    double variation = ((now * hash) % 60) / 100.0 - 0.3;
    
    // 1min load: most variable
    double load_1 = base_load + variation;
    if (load_1 < 0.1) load_1 = 0.1;
    if (load_1 > 4.0) load_1 = 4.0;
    
    // 5min load: smoother (closer to base)
    double load_5 = base_load + variation * 0.5;
    if (load_5 < 0.1) load_5 = 0.1;
    
    // 15min load: smoothest (very close to base)
    double load_15 = base_load + variation * 0.2;
    if (load_15 < 0.1) load_15 = 0.1;
    
    // Running/total processes: typical for Steam Deck gaming
    int running = 1 + (hash % 3);  // 1-3 running
    int total = 150 + (hash % 100);  // 150-250 total processes
    
    // Last PID: based on uptime simulation (grows over time)
    int last_pid = 1000 + ((now - 1735689600) % 50000);  // Grows from ~1000
    
    snprintf(loadavg_buffer, sizeof(loadavg_buffer), 
             "%.2f %.2f %.2f %d/%d %d\n",
             load_1, load_5, load_15, running, total, last_pid);
    
    return loadavg_buffer;
}

// Generate dynamic /proc/stat - CPU time statistics
// Format: cpu [user] [nice] [system] [idle] [iowait] [irq] [softirq] [steal] [guest] [guest_nice]
// Values should be realistic for the uptime and vary per bot
static const char* get_dynamic_proc_stat(void) {
    unsigned int hash = get_machine_id_hash();
    time_t now = time(NULL);
    
    // Get uptime for time-based calculations
    const time_t EPOCH_JAN_1_2026 = 1735689600;
    double uptime_secs = 900 + (hash % 17100) + (now - EPOCH_JAN_1_2026);
    if (uptime_secs < 900) uptime_secs = 900;
    
    // Jiffies = uptime * HZ (typically 100 on desktop, 250-1000 on server)
    // Steam Deck uses 300 HZ kernel
    double hz = 300.0;
    unsigned long total_jiffies = (unsigned long)(uptime_secs * hz);
    
    // Per-bot usage patterns (some bots are more CPU intensive)
    double cpu_usage = 0.15 + (hash % 30) / 100.0;  // 15-45% usage
    double io_ratio = 0.01 + (hash >> 4) % 3 / 100.0;  // 1-3% iowait
    
    char* p = proc_stat_buffer;
    size_t remaining = sizeof(proc_stat_buffer);
    
    // Aggregate CPU line (sum of all cores)
    unsigned long user_total = 0, nice_total = 0, system_total = 0, idle_total = 0;
    unsigned long iowait_total = 0, irq_total = 0, softirq_total = 0;
    
    // Per-core stats (will accumulate for total)
    for (int i = 0; i < 8; i++) {
        unsigned long core_jiffies = total_jiffies / 8;
        
        // Distribute usage across cores with variation
        double core_usage = cpu_usage + ((hash >> (i * 2)) % 10 - 5) / 100.0;
        if (core_usage < 0.05) core_usage = 0.05;
        if (core_usage > 0.80) core_usage = 0.80;
        
        unsigned long user = (unsigned long)(core_jiffies * core_usage * 0.7);
        unsigned long nice = (unsigned long)(core_jiffies * core_usage * 0.02);
        unsigned long system = (unsigned long)(core_jiffies * core_usage * 0.25);
        unsigned long iowait = (unsigned long)(core_jiffies * io_ratio);
        unsigned long irq = (unsigned long)(core_jiffies * 0.001);
        unsigned long softirq = (unsigned long)(core_jiffies * 0.002);
        unsigned long idle = core_jiffies - user - nice - system - iowait - irq - softirq;
        
        user_total += user;
        nice_total += nice;
        system_total += system;
        idle_total += idle;
        iowait_total += iowait;
        irq_total += irq;
        softirq_total += softirq;
    }
    
    // Write aggregate line
    int written = snprintf(p, remaining,
        "cpu  %lu %lu %lu %lu %lu %lu %lu 0 0 0\n",
        user_total, nice_total, system_total, idle_total,
        iowait_total, irq_total, softirq_total);
    if (written > 0 && (size_t)written < remaining) {
        p += written;
        remaining -= written;
    }
    
    // Write per-core lines
    for (int i = 0; i < 8; i++) {
        unsigned long core_jiffies = total_jiffies / 8;
        double core_usage = cpu_usage + ((hash >> (i * 2)) % 10 - 5) / 100.0;
        if (core_usage < 0.05) core_usage = 0.05;
        if (core_usage > 0.80) core_usage = 0.80;
        
        unsigned long user = (unsigned long)(core_jiffies * core_usage * 0.7);
        unsigned long nice = (unsigned long)(core_jiffies * core_usage * 0.02);
        unsigned long system = (unsigned long)(core_jiffies * core_usage * 0.25);
        unsigned long iowait = (unsigned long)(core_jiffies * io_ratio);
        unsigned long irq = (unsigned long)(core_jiffies * 0.001);
        unsigned long softirq = (unsigned long)(core_jiffies * 0.002);
        unsigned long idle = core_jiffies - user - nice - system - iowait - irq - softirq;
        
        written = snprintf(p, remaining,
            "cpu%d %lu %lu %lu %lu %lu %lu %lu 0 0 0\n",
            i, user, nice, system, idle, iowait, irq, softirq);
        if (written > 0 && (size_t)written < remaining) {
            p += written;
            remaining -= written;
        }
    }
    
    // Add other standard /proc/stat fields
    unsigned long intr_count = total_jiffies * 50 + (hash % 10000);  // Interrupt count
    unsigned long ctx_switches = total_jiffies * 200 + (hash % 100000);  // Context switches
    unsigned long boot_time = EPOCH_JAN_1_2026 - (hash % 17100) - 900;  // Boot timestamp
    unsigned long processes = 5000 + (hash % 5000) + (unsigned long)((now - EPOCH_JAN_1_2026) / 60);
    unsigned long procs_running = 1 + (hash % 3);
    unsigned long procs_blocked = (hash % 2);
    
    written = snprintf(p, remaining,
        "intr %lu 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0\n"
        "ctxt %lu\n"
        "btime %lu\n"
        "processes %lu\n"
        "procs_running %lu\n"
        "procs_blocked %lu\n"
        "softirq %lu 0 %lu %lu 0 0 0 %lu 0 0 %lu\n",
        intr_count, ctx_switches, boot_time, processes,
        procs_running, procs_blocked,
        softirq_total * 8,  // Total softirq
        softirq_total * 2,  // TIMER
        softirq_total,      // NET_RX
        softirq_total * 3,  // SCHED
        softirq_total * 2); // RCU
    
    return proc_stat_buffer;
}

// Generate filtered /proc/self/status - removes namespace-revealing fields
// Reads real status and filters/modifies NSpid, NStgid, NSpgid, NSsid lines
static const char* get_dynamic_proc_status(void) {
    init_functions();
    
    // Read real /proc/self/status
    FILE* f = real_fopen("/proc/self/status", "r");
    if (!f) {
        // Fallback: return minimal status
        snprintf(proc_status_buffer, sizeof(proc_status_buffer),
            "Name:\tjava\nState:\tS (sleeping)\n");
        return proc_status_buffer;
    }
    
    char* p = proc_status_buffer;
    size_t remaining = sizeof(proc_status_buffer);
    char line[256];
    
    // Track the Pid so we can use it for NS* fields
    int real_pid = 0;
    
    while (fgets(line, sizeof(line), f) && remaining > 1) {
        // Extract Pid for later use
        if (str_starts(line, "Pid:")) {
            sscanf(line, "Pid:\t%d", &real_pid);
        }
        
        // Filter namespace fields - replace with regular PID to hide namespacing
        if (str_starts(line, "NSpid:") || str_starts(line, "NStgid:") ||
            str_starts(line, "NSpgid:") || str_starts(line, "NSsid:")) {
            // Replace with single value (same as non-namespaced)
            const char* field = str_starts(line, "NSpid:") ? "NSpid:" :
                               str_starts(line, "NStgid:") ? "NStgid:" :
                               str_starts(line, "NSpgid:") ? "NSpgid:" : "NSsid:";
            int written = snprintf(p, remaining, "%s\t%d\n", field, real_pid);
            if (written > 0 && (size_t)written < remaining) {
                p += written;
                remaining -= written;
            }
            continue;
        }
        
        // Copy other lines unchanged
        size_t len = strlen(line);
        if (len < remaining) {
            memcpy(p, line, len);
            p += len;
            remaining -= len;
        }
    }
    
    fclose(f);
    *p = '\0';
    return proc_status_buffer;
}

// Generate kernel cmdline (boot parameters) - looks like SteamOS boot
static const char* get_kernel_cmdline(void) {
    if (kernel_cmdline_initialized) {
        return kernel_cmdline_buffer;
    }
    
    unsigned int hash = get_machine_id_hash();
    int version_idx = get_version_index();
    
    // Root partition UUID varies per bot (derived from machine-id)
    // Format: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
    snprintf(kernel_cmdline_buffer, sizeof(kernel_cmdline_buffer),
        "BOOT_IMAGE=/boot/vmlinuz-%s "
        "root=UUID=%08x-%04x-%04x-%04x-%012llx "
        "rootflags=subvol=@ "
        "quiet splash "
        "amd_pstate=active "
        "amd_iommu=off "
        "nvidia-drm.modeset=1 "
        "loglevel=3 "
        "rd.udev.log_priority=3 "
        "sysrq_always_enabled=1\n",
        KERNEL_VERSIONS[version_idx][0],
        hash,
        (hash >> 8) & 0xFFFF,
        0x4000 | ((hash >> 12) & 0x0FFF),  // Version 4 UUID
        0x8000 | ((hash >> 16) & 0x3FFF),  // Variant 1
        ((unsigned long long)hash << 16) | (hash & 0xFFFF));
    
    kernel_cmdline_initialized = 1;
    return kernel_cmdline_buffer;
}

// Generate dynamic /proc/cpuinfo with 8 processors and varying MHz/bogomips
static const char* get_dynamic_cpuinfo(void) {
    if (cpuinfo_initialized) {
        return cpuinfo_buffer;
    }
    
    unsigned int hash = get_machine_id_hash();
    
    // Base CPU MHz varies by bot: 2400-3200 MHz (gaming load)
    int base_mhz = 2400 + (hash % 800);
    
    char* p = cpuinfo_buffer;
    size_t remaining = sizeof(cpuinfo_buffer);
    
    // Generate 8 processors (4 cores x 2 threads with SMT)
    for (int i = 0; i < 8; i++) {
        int core_id = i / 2;  // Cores 0-3, each with 2 threads
        int apicid = i;
        
        // Slight MHz variation per core (±50 MHz)
        int mhz = base_mhz + ((hash >> (i * 3)) % 100) - 50;
        
        // Bogomips varies per core: ~2x MHz with slight variation
        // Real systems show small differences due to calibration timing
        double bogomips = mhz * 2.0 + ((hash >> (i * 2 + 1)) % 20) - 10;
        
        int written = snprintf(p, remaining,
            "processor\t: %d\n"
            "vendor_id\t: AuthenticAMD\n"
            "cpu family\t: 23\n"
            "model\t\t: 144\n"
            "model name\t: AMD Custom APU 0405\n"
            "stepping\t: 1\n"
            "microcode\t: 0xa404101\n"
            "cpu MHz\t\t: %d.000\n"
            "cache size\t: 1024 KB\n"
            "physical id\t: 0\n"
            "siblings\t: 8\n"
            "core id\t\t: %d\n"
            "cpu cores\t: 4\n"
            "apicid\t\t: %d\n"
            "fpu\t\t: yes\n"
            "fpu_exception\t: yes\n"
            "cpuid level\t: 16\n"
            "wp\t\t: yes\n"
            "flags\t\t: %s\n"
            "bugs\t\t: sysret_ss_attrs spectre_v1 spectre_v2 spec_store_bypass\n"
            "bogomips\t: %.2f\n"
            "TLB size\t: 2560 4K pages\n"
            "clflush size\t: 64\n"
            "cache_alignment\t: 64\n"
            "address sizes\t: 48 bits physical, 48 bits virtual\n"
            "power management: ts ttp tm hwpstate cpb eff_freq_ro [13] [14]\n"
            "\n",
            i, mhz, core_id, apicid, CPU_FLAGS, bogomips);
        
        if (written > 0 && (size_t)written < remaining) {
            p += written;
            remaining -= written;
        }
    }
    
    cpuinfo_initialized = 1;
    return cpuinfo_buffer;
}

// Generate dynamic /proc/meminfo with varying free/available memory
static const char* get_dynamic_meminfo(void) {
    if (meminfo_initialized) {
        return meminfo_buffer;
    }
    
    unsigned int hash = get_machine_id_hash();
    
    // Total: 16GB (fixed for Steam Deck)
    long mem_total = 16252928;
    
    // Free: 4-10 GB based on bot (gaming uses memory)
    long mem_free = 4000000 + (hash % 6000000);
    
    // Available: free + cached/buffers
    long mem_available = mem_free + 3000000 + ((hash >> 8) % 2000000);
    if (mem_available > mem_total - 2000000) mem_available = mem_total - 2000000;
    
    // Cached: 2-4 GB
    long cached = 2000000 + ((hash >> 12) % 2000000);
    
    // Active: roughly mem_total - mem_free - some overhead
    long active = mem_total - mem_free - cached - 500000;
    if (active < 1000000) active = 1000000;
    
    snprintf(meminfo_buffer, sizeof(meminfo_buffer),
        "MemTotal:       %ld kB\n"
        "MemFree:        %ld kB\n"
        "MemAvailable:   %ld kB\n"
        "Buffers:          524288 kB\n"
        "Cached:         %ld kB\n"
        "SwapCached:            0 kB\n"
        "Active:         %ld kB\n"
        "Inactive:        2031616 kB\n"
        "Active(anon):    2031616 kB\n"
        "Inactive(anon):        0 kB\n"
        "Active(file):    2031616 kB\n"
        "Inactive(file):  2031616 kB\n"
        "Unevictable:           0 kB\n"
        "Mlocked:               0 kB\n"
        "SwapTotal:       8126464 kB\n"
        "SwapFree:        8126464 kB\n"
        "Dirty:                 0 kB\n"
        "Writeback:             0 kB\n"
        "AnonPages:       2031616 kB\n"
        "Mapped:           507904 kB\n"
        "Shmem:             16384 kB\n"
        "KReclaimable:     507904 kB\n"
        "Slab:             507904 kB\n"
        "SReclaimable:     406323 kB\n"
        "SUnreclaim:       101581 kB\n"
        "KernelStack:       16384 kB\n"
        "PageTables:        32768 kB\n"
        "NFS_Unstable:          0 kB\n"
        "Bounce:                0 kB\n"
        "WritebackTmp:          0 kB\n"
        "CommitLimit:    16252928 kB\n"
        "Committed_AS:    8126464 kB\n"
        "VmallocTotal:   34359738367 kB\n"
        "VmallocUsed:       65536 kB\n"
        "VmallocChunk:          0 kB\n"
        "Percpu:            16384 kB\n"
        "HardwareCorrupted:     0 kB\n"
        "AnonHugePages:         0 kB\n"
        "ShmemHugePages:        0 kB\n"
        "ShmemPmdMapped:        0 kB\n"
        "FileHugePages:         0 kB\n"
        "FilePmdMapped:         0 kB\n"
        "HugePages_Total:       0\n"
        "HugePages_Free:        0\n"
        "HugePages_Rsvd:        0\n"
        "HugePages_Surp:        0\n"
        "Hugepagesize:       2048 kB\n"
        "Hugetlb:               0 kB\n"
        "DirectMap4k:      262144 kB\n"
        "DirectMap2M:     8126464 kB\n"
        "DirectMap1G:     8388608 kB\n",
        mem_total, mem_free, mem_available, cached, active);
    
    meminfo_initialized = 1;
    return meminfo_buffer;
}

// Initialize real function pointers
static void init_functions(void) {
    if (!real_fopen) real_fopen = dlsym(RTLD_NEXT, "fopen");
    if (!real_read) real_read = dlsym(RTLD_NEXT, "read");
    if (!real_open) real_open = dlsym(RTLD_NEXT, "open");
    if (!real_getenv) real_getenv = dlsym(RTLD_NEXT, "getenv");
}

// ============================================================================
// Intercepted: getenv()
// ============================================================================
char* getenv(const char* name) {
    init_functions();
    
    // Hide LD_PRELOAD and related
    if (should_hide_env(name)) {
        return NULL;
    }
    
    return real_getenv(name);
}

// Also intercept secure_getenv (glibc)
char* secure_getenv(const char* name) {
    return getenv(name);
}

// ============================================================================
// Intercepted: fopen() / fopen64()
// ============================================================================
FILE* fopen(const char* path, const char* mode) {
    init_functions();
    
    // Handle /proc/self/environ specially - need to filter content
    if (is_environ_path(path)) {
        FILE* real_file = real_fopen(path, mode);
        if (!real_file) return NULL;
        
        // Read all content
        fseek(real_file, 0, SEEK_END);
        long size = ftell(real_file);
        fseek(real_file, 0, SEEK_SET);
        
        char* content = malloc(size + 1);
        if (!content) {
            fclose(real_file);
            return NULL;
        }
        
        size_t read_size = fread(content, 1, size, real_file);
        fclose(real_file);
        
        // Filter the content
        char* filtered = filter_environ(content, read_size);
        free(content);
        
        if (!filtered) return NULL;
        
        // Return as in-memory file
        size_t filtered_len = 0;
        const char* p = filtered;
        while (*p) {
            size_t var_len = strlen(p);
            filtered_len += var_len + 1;
            p += var_len + 1;
        }
        
        FILE* mem_file = fmemopen(filtered, filtered_len, "r");
        // Note: filtered memory will leak, but this is rare operation
        return mem_file;
    }
    
    // Handle /proc/self/cmdline - return fake gamescope launch cmdline
    if (is_cmdline_path(path)) {
        // cmdline has embedded nulls, can't use strlen - use fixed length
        return fmemopen((void*)PROC_CMDLINE, PROC_CMDLINE_LEN, "r");
    }
    
    // Handle /proc/self/status - filter namespace-revealing fields
    if (is_status_path(path)) {
        const char* status = get_dynamic_proc_status();
        return fmemopen((void*)status, strlen(status), "r");
    }
    
    // Handle maps/mounts filtering
    if (needs_line_filtering(path)) {
        FILE* real_file = real_fopen(path, mode);
        if (!real_file) return NULL;
        
        // Read all content
        fseek(real_file, 0, SEEK_END);
        long size = ftell(real_file);
        fseek(real_file, 0, SEEK_SET);
        
        char* content = malloc(size + 1);
        if (!content) {
            fclose(real_file);
            return NULL;
        }
        
        size_t read_size = fread(content, 1, size, real_file);
        content[read_size] = '\0';
        fclose(real_file);
        
        // Determine if this is maps or mounts
        int is_maps = str_contains(path, "/maps");
        char* filtered = filter_lines(content, read_size, is_maps);
        free(content);
        
        if (!filtered) return NULL;
        
        FILE* mem_file = fmemopen(filtered, strlen(filtered), "r");
        // Note: filtered memory will leak, but this is rare operation
        return mem_file;
    }
    
    // Check for mapped content
    const char* mapped = get_mapped_content(path);
    if (mapped) {
        return fmemopen((void*)mapped, strlen(mapped), "r");
    }
    
    return real_fopen(path, mode);
}

FILE* fopen64(const char* path, const char* mode) {
    return fopen(path, mode);
}

// ============================================================================
// Intercepted: open() / open64() / read() / close()
// ============================================================================

#define MAX_TRACKED_FDS 64
static struct {
    int fd;
    const char* content;
    size_t length;
    size_t offset;
    int is_environ;
    char* environ_buf; // For environ, we allocate filtered content
} tracked_fds[MAX_TRACKED_FDS];
static int tracked_count = 0;
static pthread_mutex_t tracked_mutex = PTHREAD_MUTEX_INITIALIZER;

static int find_tracked_unlocked(int fd) {
    for (int i = 0; i < tracked_count; i++) {
        if (tracked_fds[i].fd == fd) return i;
    }
    return -1;
}

// Thread-safe helper to add a tracked fd
// Returns 1 on success, 0 if array is full
static int add_tracked_fd(int fd, const char* content, size_t length, int is_environ, char* environ_buf) {
    pthread_mutex_lock(&tracked_mutex);
    if (tracked_count >= MAX_TRACKED_FDS) {
        pthread_mutex_unlock(&tracked_mutex);
        return 0;
    }
    tracked_fds[tracked_count].fd = fd;
    tracked_fds[tracked_count].content = content;
    tracked_fds[tracked_count].length = length;
    tracked_fds[tracked_count].offset = 0;
    tracked_fds[tracked_count].is_environ = is_environ;
    tracked_fds[tracked_count].environ_buf = environ_buf;
    tracked_count++;
    pthread_mutex_unlock(&tracked_mutex);
    return 1;
}

int open(const char* path, int flags, ...) {
    init_functions();
    
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode = va_arg(args, mode_t);
        va_end(args);
    }
    
    // Handle environ specially
    if (is_environ_path(path) && !(flags & O_WRONLY)) {
        int real_fd = (flags & O_CREAT) ? real_open(path, flags, mode) : real_open(path, flags);
        if (real_fd < 0) return real_fd;
        
        // Read content
        char buf[65536];
        ssize_t total = 0;
        ssize_t n;
        while ((n = real_read(real_fd, buf + total, sizeof(buf) - total - 1)) > 0) {
            total += n;
            if (total >= (ssize_t)(sizeof(buf) - 1)) break;
        }
        
        // Close real fd, we'll use /dev/null as placeholder
        static int (*real_close)(int) = NULL;
        if (!real_close) real_close = dlsym(RTLD_NEXT, "close");
        real_close(real_fd);
        
        // Filter and track
        char* filtered = filter_environ(buf, total);
        if (filtered) {
            int null_fd = real_open("/dev/null", O_RDONLY);
            if (null_fd >= 0) {
                size_t flen = 0;
                const char* p = filtered;
                while (*p) {
                    size_t var_len = strlen(p);
                    flen += var_len + 1;
                    p += var_len + 1;
                }
                
                if (add_tracked_fd(null_fd, filtered, flen, 1, filtered)) {
                    return null_fd;
                }
                // Failed to add - close fd and cleanup
                static int (*local_close)(int) = NULL;
                if (!local_close) local_close = dlsym(RTLD_NEXT, "close");
                local_close(null_fd);
            }
            free(filtered);
        }
        return -1;
    }
    
    // Handle cmdline - return fake gamescope launch
    if (is_cmdline_path(path) && !(flags & O_WRONLY)) {
        int null_fd = real_open("/dev/null", O_RDONLY);
        if (null_fd >= 0) {
            if (add_tracked_fd(null_fd, PROC_CMDLINE, PROC_CMDLINE_LEN, 0, NULL)) {
                return null_fd;
            }
            static int (*local_close)(int) = NULL;
            if (!local_close) local_close = dlsym(RTLD_NEXT, "close");
            local_close(null_fd);
        }
        return -1;
    }
    
    // Handle /proc/self/status - filter namespace-revealing fields
    if (is_status_path(path) && !(flags & O_WRONLY)) {
        const char* status = get_dynamic_proc_status();
        int null_fd = real_open("/dev/null", O_RDONLY);
        if (null_fd >= 0) {
            if (add_tracked_fd(null_fd, status, strlen(status), 0, NULL)) {
                return null_fd;
            }
            static int (*local_close)(int) = NULL;
            if (!local_close) local_close = dlsym(RTLD_NEXT, "close");
            local_close(null_fd);
        }
        return -1;
    }
    
    // Handle maps/mounts filtering
    if (needs_line_filtering(path) && !(flags & O_WRONLY)) {
        int real_fd = (flags & O_CREAT) ? real_open(path, flags, mode) : real_open(path, flags);
        if (real_fd < 0) return real_fd;
        
        // Read content
        char buf[262144];  // 256KB for maps
        ssize_t total = 0;
        ssize_t n;
        while ((n = real_read(real_fd, buf + total, sizeof(buf) - total - 1)) > 0) {
            total += n;
            if (total >= (ssize_t)(sizeof(buf) - 1)) break;
        }
        buf[total] = '\0';
        
        static int (*real_close)(int) = NULL;
        if (!real_close) real_close = dlsym(RTLD_NEXT, "close");
        real_close(real_fd);
        
        // Filter content
        int is_maps = str_contains(path, "/maps");
        char* filtered = filter_lines(buf, total, is_maps);
        
        if (filtered) {
            int null_fd = real_open("/dev/null", O_RDONLY);
            if (null_fd >= 0) {
                if (add_tracked_fd(null_fd, filtered, strlen(filtered), 1, filtered)) {
                    return null_fd;
                }
                static int (*local_close)(int) = NULL;
                if (!local_close) local_close = dlsym(RTLD_NEXT, "close");
                local_close(null_fd);
            }
            free(filtered);
        }
        return -1;
    }
    
    // Check for mapped content
    const char* mapped = get_mapped_content(path);
    if (mapped && !(flags & O_WRONLY)) {
        int null_fd = real_open("/dev/null", O_RDONLY);
        if (null_fd >= 0) {
            if (add_tracked_fd(null_fd, mapped, strlen(mapped), 0, NULL)) {
                return null_fd;
            }
            // Failed to add, close fd
            static int (*local_close)(int) = NULL;
            if (!local_close) local_close = dlsym(RTLD_NEXT, "close");
            local_close(null_fd);
            return -1;
        }
    }
    
    return (flags & O_CREAT) ? real_open(path, flags, mode) : real_open(path, flags);
}

int open64(const char* path, int flags, ...) {
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode_t mode = va_arg(args, mode_t);
        va_end(args);
        return open(path, flags, mode);
    }
    return open(path, flags);
}

ssize_t read(int fd, void* buf, size_t count) {
    init_functions();
    
    pthread_mutex_lock(&tracked_mutex);
    int idx = find_tracked_unlocked(fd);
    if (idx >= 0) {
        size_t remaining = tracked_fds[idx].length - tracked_fds[idx].offset;
        if (remaining == 0) {
            pthread_mutex_unlock(&tracked_mutex);
            return 0;
        }
        
        size_t to_read = (count < remaining) ? count : remaining;
        memcpy(buf, tracked_fds[idx].content + tracked_fds[idx].offset, to_read);
        tracked_fds[idx].offset += to_read;
        pthread_mutex_unlock(&tracked_mutex);
        return to_read;
    }
    pthread_mutex_unlock(&tracked_mutex);
    
    return real_read(fd, buf, count);
}

int close(int fd) {
    static int (*real_close)(int) = NULL;
    if (!real_close) real_close = dlsym(RTLD_NEXT, "close");
    
    pthread_mutex_lock(&tracked_mutex);
    int idx = find_tracked_unlocked(fd);
    if (idx >= 0) {
        // Free environ buffer if allocated
        if (tracked_fds[idx].environ_buf) {
            free(tracked_fds[idx].environ_buf);
        }
        // Remove from tracking
        tracked_fds[idx] = tracked_fds[--tracked_count];
    }
    pthread_mutex_unlock(&tracked_mutex);
    
    return real_close(fd);
}

// ============================================================================
// Intercepted: readlink() - for /proc/self/exe spoofing
// ============================================================================

// Check if this is a /proc/*/exe path we should spoof
static int is_exe_path(const char* path) {
    if (!path) return 0;
    if (str_eq(path, "/proc/self/exe")) return 1;
    if (str_starts(path, "/proc/") && str_ends(path, "/exe")) {
        const char* p = path + 6;
        while (*p && *p != '/') {
            if (*p < '0' || *p > '9') return 0;
            p++;
        }
        return str_eq(p, "/exe");
    }
    return 0;
}

ssize_t readlink(const char* path, char* buf, size_t bufsiz) {
    static ssize_t (*real_readlink)(const char*, char*, size_t) = NULL;
    if (!real_readlink) real_readlink = dlsym(RTLD_NEXT, "readlink");
    
    // Spoof /proc/self/exe to look like java executable
    if (is_exe_path(path)) {
        size_t len = strlen(FAKE_SELF_EXE);
        if (len > bufsiz) len = bufsiz;
        memcpy(buf, FAKE_SELF_EXE, len);
        return len;
    }
    
    return real_readlink(path, buf, bufsiz);
}

// ============================================================================
// Intercepted: openat() / openat64() - *at variant of open()
// ============================================================================

int openat(int dirfd, const char* path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode = va_arg(args, mode_t);
        va_end(args);
    }
    
    // If dirfd is AT_FDCWD or path is absolute, delegate to our open() hook
    if (dirfd == AT_FDCWD || path[0] == '/') {
        return (flags & O_CREAT) ? open(path, flags, mode) : open(path, flags);
    }
    
    // For relative paths with actual dirfd, call real openat
    static int (*real_openat)(int, const char*, int, ...) = NULL;
    if (!real_openat) real_openat = dlsym(RTLD_NEXT, "openat");
    return (flags & O_CREAT) ? real_openat(dirfd, path, flags, mode) : real_openat(dirfd, path, flags);
}

int openat64(int dirfd, const char* path, int flags, ...) {
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode_t mode = va_arg(args, mode_t);
        va_end(args);
        return openat(dirfd, path, flags, mode);
    }
    return openat(dirfd, path, flags);
}

// ============================================================================
// Intercepted: readlinkat() - *at variant of readlink()
// ============================================================================

ssize_t readlinkat(int dirfd, const char* path, char* buf, size_t bufsiz) {
    // If dirfd is AT_FDCWD or path is absolute, delegate to our readlink() hook
    if (dirfd == AT_FDCWD || path[0] == '/') {
        return readlink(path, buf, bufsiz);
    }
    
    // For relative paths with actual dirfd, call real readlinkat
    static ssize_t (*real_readlinkat)(int, const char*, char*, size_t) = NULL;
    if (!real_readlinkat) real_readlinkat = dlsym(RTLD_NEXT, "readlinkat");
    return real_readlinkat(dirfd, path, buf, bufsiz);
}

// ============================================================================
// Intercepted: fstatat() / fstatat64() - *at variant of stat()
// Currently passes through but intercepts for future spoofing if needed
// ============================================================================

int fstatat(int dirfd, const char* path, struct stat* statbuf, int flags) {
    static int (*real_fstatat)(int, const char*, struct stat*, int) = NULL;
    if (!real_fstatat) real_fstatat = dlsym(RTLD_NEXT, "fstatat");
    
    // For now, pass through - but we intercept to prevent bypass of future spoofing
    // If we ever need to spoof file metadata, add logic here
    return real_fstatat(dirfd, path, statbuf, flags);
}

int fstatat64(int dirfd, const char* path, struct stat64* statbuf, int flags) {
    static int (*real_fstatat64)(int, const char*, struct stat64*, int) = NULL;
    if (!real_fstatat64) real_fstatat64 = dlsym(RTLD_NEXT, "fstatat64");
    return real_fstatat64(dirfd, path, statbuf, flags);
}

// ============================================================================
// Intercepted: statx() - extended stat with mount ID that can leak container info
// ============================================================================

int statx(int dirfd, const char* path, int flags, unsigned int mask, struct statx* statxbuf) {
    static int (*real_statx)(int, const char*, int, unsigned int, struct statx*) = NULL;
    if (!real_statx) real_statx = dlsym(RTLD_NEXT, "statx");
    
    int result = real_statx(dirfd, path, flags, mask, statxbuf);
    
    if (result == 0) {
        // Spoof mount ID to hide overlay/container mounts
        // Mount ID is derived from machine-id for per-container consistency
        unsigned int mnt_id = get_dynamic_mount_id();
        
        // stx_mnt_id was added in Linux 5.8, check if it was requested
        #ifdef STATX_MNT_ID
        if (mask & STATX_MNT_ID) {
            statxbuf->stx_mnt_id = mnt_id;
        }
        #endif
        
        // Also available unconditionally in stx_mnt_id field since 5.8
        // Some systems always populate it regardless of mask
        #if defined(__GLIBC__) && defined(STATX_MNT_ID)
        statxbuf->stx_mnt_id = mnt_id;
        #endif
    }
    
    return result;
}
