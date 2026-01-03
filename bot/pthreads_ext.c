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
#include <math.h>
#include <sys/ioctl.h>
#include <net/if.h>
#include <linux/sockios.h>
#include <stdint.h>
#include <sys/syscall.h>
#include <sys/utsname.h>

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
static const char* get_spoofed_proc_stat(void);
static const char* get_kernel_cmdline(void);
static void init_functions(void);
static const char* normalize_path(const char* path);
static const char* get_dynamic_input_devices(void);
static unsigned int get_machine_id_hash(void);
static int get_version_index(void);
static unsigned int get_dynamic_mount_id(void);
static int get_current_cycles(void);

// Library name (must match the compiled .so filename)
static const char* LIB_NAME = "libpthreads_ext.so";

// CPU flags (shared across all processors)
static const char* CPU_FLAGS = "fpu vme de pse tsc msr pae mce cx8 apic sep mtrr pge mca cmov pat pse36 clflush mmx fxsr sse sse2 ht syscall nx mmxext fxsr_opt pdpe1gb rdtscp lm constant_tsc rep_good nopl nonstop_tsc cpuid extd_apicid aperfmperf rapl pni pclmulqdq monitor ssse3 fma cx16 sse4_1 sse4_2 movbe popcnt aes xsave avx f16c rdrand lahf_lm cmp_legacy svm extapic cr8_legacy abm sse4a misalignsse 3dnowprefetch osvw ibs skinit wdt tce topoext perfctr_core perfctr_nb bpext perfctr_llc mwaitx cpb cat_l3 cdp_l3 hw_pstate ssbd mba ibrs ibpb stibp vmmcall fsgsbase bmi1 avx2 smep bmi2 cqm rdt_a rdseed adx smap clflushopt clwb sha_ni xsaveopt xsavec xgetbv1 xsaves cqm_llc cqm_occup_llc cqm_mbm_total cqm_mbm_local clzero irperf xsaveerptr rdpru wbnoinvd cppc arat npt lbrv svm_lock nrip_save tsc_scale vmcb_clean flushbyasid decodeassists pausefilter pfthreshold avic v_vmsave_vmload vgif v_spec_ctrl umip rdpid overflow_recov succor smca sev sev_es";

// Dynamic cpuinfo buffer (regenerated each read for thermal throttling)
static char cpuinfo_buffer[16384];

// Per-boot bogomips calibration offsets (like real kernel calibration variance)
static int bogomips_calibration[8];  // Per-core offset, set once per boot
static int bogomips_calibrated = 0;

// Dynamic meminfo buffer (regenerated each read for memory leak simulation)
static char meminfo_buffer[2048];

// Session-based memory state (set once per boot, simulates memory leak over session)
static long mem_session_base = 0;      // Base free memory for this session
static time_t mem_session_start = 0;   // When this session started
static int mem_session_initialized = 0;

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

// Dynamic /proc/self/stat buffer (for PPID spoofing)
static char proc_stat_spoof_buffer[1024];

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
static unsigned char net_mac_bytes[6];  // Raw bytes for ioctl
static int mac_initialized = 0;

// /proc/self/cmdline - simple Java launch (no fake gamescope wrapper)
// Null-separated args, realistic for a user launching RuneLite
static const char* PROC_CMDLINE = "/usr/lib/jvm/java-17-openjdk/bin/java\0-jar\0RuneLite.jar\0";
static const size_t PROC_CMDLINE_LEN = 54;  // Length including nulls

// Fake /proc/self/exe target (what readlink should return)
static const char* FAKE_SELF_EXE = "/usr/lib/jvm/java-17-openjdk/bin/java";

// Fake /proc/self/cwd - hide container working directory
static const char* FAKE_SELF_CWD = "/home/deck";

// Fake /proc/self/root - hide overlay/container root
static const char* FAKE_SELF_ROOT = "/";

// Steam Deck input devices - appended to real input devices
// Dynamic buffer for combined real + virtual devices
static char input_devices_buffer[16384];
static int input_devices_initialized = 0;

// Steam Deck controller entries to append
static const char* STEAM_DECK_CONTROLLER = 
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
    "\n";

// Additional Steam Deck system input devices
static const char* STEAM_DECK_SYSTEM_INPUTS = 
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

// Normalize a path: remove ., .., collapse // sequences
// Returns pointer to static buffer (not thread-safe for path normalization, but our usage is fine)
static const char* normalize_path(const char* path) {
    static char normalized[4096];
    if (!path) return NULL;
    
    // Fast path: if no special sequences, return as-is
    if (!strstr(path, "//") && !strstr(path, "/./") && !strstr(path, "/../") &&
        !str_ends(path, "/.") && !str_ends(path, "/..")) {
        return path;
    }
    
    char* dst = normalized;
    const char* src = path;
    char* dst_end = normalized + sizeof(normalized) - 1;
    
    while (*src && dst < dst_end) {
        if (src[0] == '/') {
            // Collapse multiple slashes
            while (src[1] == '/') src++;
            
            // Handle /./ (current directory - skip)
            if (src[1] == '.' && (src[2] == '/' || src[2] == '\0')) {
                src += 2;
                if (*src == '/') src++;
                continue;
            }
            
            // Handle /../ (parent directory)
            if (src[1] == '.' && src[2] == '.' && (src[3] == '/' || src[3] == '\0')) {
                // Go back to previous /
                if (dst > normalized) {
                    dst--;
                    while (dst > normalized && *dst != '/') dst--;
                }
                src += 3;
                if (*src == '/') src++;
                continue;
            }
        }
        *dst++ = *src++;
    }
    
    // Ensure null termination
    *dst = '\0';
    
    // Remove trailing slash (except for root)
    if (dst > normalized + 1 && *(dst - 1) == '/') {
        *(dst - 1) = '\0';
    }
    
    // Empty result means root
    if (normalized[0] == '\0') {
        normalized[0] = '/';
        normalized[1] = '\0';
    }
    
    return normalized;
}

// Check if an environment variable should be hidden
static int should_hide_env(const char* name) {
    for (int i = 0; HIDDEN_ENV_VARS[i]; i++) {
        if (str_eq(name, HIDDEN_ENV_VARS[i])) return 1;
    }
    return 0;
}

// Check if path should be blocked (return ENOENT) - container indicators or
// files that don't exist on Steam Deck
static int should_block_path(const char* path) {
    if (!path) return 0;
    
    // Docker/container indicators
    if (str_eq(path, "/.dockerenv")) return 1;
    if (str_eq(path, "/.dockerinit")) return 1;
    if (str_contains(path, "/docker")) return 1;
    if (str_contains(path, "/overlay")) return 1;
    
    // ACPI tables that don't exist on Steam Deck
    // MSDM = Microsoft Software Licensing Tables (Windows license key storage)
    // Steam Deck is Linux-only, no MSDM table
    if (str_contains(path, "/sys/firmware/acpi/tables/MSDM")) return 1;
    
    // Other Windows-specific ACPI tables
    if (str_contains(path, "/sys/firmware/acpi/tables/WPBT")) return 1;  // Windows Platform Binary Table
    if (str_contains(path, "/sys/firmware/acpi/tables/WSMT")) return 1;  // Windows SMM Security Mitigations Table
    
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

// Check if path is /proc/self/stat or /proc/<pid>/stat (NOT status)
static int is_stat_path(const char* path) {
    if (!path) return 0;
    if (str_eq(path, "/proc/self/stat")) return 1;
    if (str_starts(path, "/proc/") && str_ends(path, "/stat")) {
        // Make sure it's not /status
        if (str_ends(path, "/status")) return 0;
        const char* p = path + 6;
        while (*p && *p != '/') {
            if (*p < '0' || *p > '9') return 0;
            p++;
        }
        return str_eq(p, "/stat");
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
    
    // Normalize path to handle ., .., and // sequences
    path = normalize_path(path);
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
    if (str_eq(path, "/proc/bus/input/devices")) return get_dynamic_input_devices();
    
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

// Forward declarations for is_spoofed_path (defined later)
static int is_environ_path(const char* path);
static int is_cmdline_path(const char* path);

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

// Check if a path would be spoofed (for access/stat consistency)
// Returns 1 if path is spoofed, 0 otherwise
static int is_spoofed_path(const char* path) {
    if (!path) return 0;
    
    // Normalize path first
    path = normalize_path(path);
    if (!path) return 0;
    
    // Check if get_mapped_content would return something
    if (get_mapped_content(path)) return 1;
    
    // Check dynamic paths that need special handling
    if (is_environ_path(path)) return 1;
    if (is_cmdline_path(path)) return 1;
    if (is_status_path(path)) return 1;
    if (is_stat_path(path)) return 1;
    
    // Check filtered paths (maps, mounts, mountinfo)
    if (needs_line_filtering(path)) return 1;
    
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
    
    // Realtek OUIs commonly found in laptops/handhelds (as raw bytes)
    static const unsigned char REALTEK_OUIS[][3] = {
        {0x48, 0xe7, 0xda},
        {0x2c, 0xf0, 0x5d},
        {0x00, 0xe0, 0x4c},
        {0x74, 0xd8, 0x3e},
        {0x18, 0xc0, 0x4d}
    };
    
    unsigned int hash = get_machine_id_hash();
    
    // Select OUI based on hash
    int oui_idx = hash % 5;
    
    // Populate raw bytes
    net_mac_bytes[0] = REALTEK_OUIS[oui_idx][0];
    net_mac_bytes[1] = REALTEK_OUIS[oui_idx][1];
    net_mac_bytes[2] = REALTEK_OUIS[oui_idx][2];
    net_mac_bytes[3] = (hash >> 8) & 0xFF;
    net_mac_bytes[4] = (hash >> 16) & 0xFF;
    net_mac_bytes[5] = (hash >> 24) & 0xFF;
    
    // Generate string format for /sys reads
    snprintf(net_mac_buffer, sizeof(net_mac_buffer), 
             "%02x:%02x:%02x:%02x:%02x:%02x\n",
             net_mac_bytes[0], net_mac_bytes[1], net_mac_bytes[2],
             net_mac_bytes[3], net_mac_bytes[4], net_mac_bytes[5]);
    
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
    // Mount IDs: expanded range 20-250 with weighted distribution
    // Real systems cluster around certain values:
    // - Root/boot filesystems: 20-50 (most common)
    // - Home/data partitions: 40-100
    // - Additional mounts: can go higher
    // Sticky per machine-id for consistency across reads
    
    unsigned int hash = get_machine_id_hash();
    
    // Weighted distribution: 60% in 20-60, 30% in 60-120, 10% in 120-250
    unsigned int bucket = (hash * 17) % 100;
    unsigned int base_value;
    
    if (bucket < 60) {
        // 60% chance: common low range (20-60)
        base_value = 20 + ((hash * 13) % 41);
    } else if (bucket < 90) {
        // 30% chance: medium range (60-120)
        base_value = 60 + ((hash * 19) % 61);
    } else {
        // 10% chance: high range (120-250)
        base_value = 120 + ((hash * 23) % 131);
    }
    
    return base_value;
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

// Generate dynamic /proc/bus/input/devices
// Reads real input devices and appends Steam Deck controller if not present
static const char* get_dynamic_input_devices(void) {
    if (input_devices_initialized) {
        return input_devices_buffer;
    }
    
    init_functions();
    
    char* p = input_devices_buffer;
    size_t remaining = sizeof(input_devices_buffer);
    int has_steam_deck_controller = 0;
    
    // Read real /proc/bus/input/devices
    FILE* f = real_fopen("/proc/bus/input/devices", "r");
    if (f) {
        char line[512];
        while (fgets(line, sizeof(line), f) && remaining > 1) {
            // Check if Steam Deck controller already present
            if (strstr(line, "Steam Deck Controller")) {
                has_steam_deck_controller = 1;
            }
            
            // Copy line to buffer
            size_t len = strlen(line);
            if (len < remaining) {
                memcpy(p, line, len);
                p += len;
                remaining -= len;
            }
        }
        fclose(f);
    }
    
    // Ensure buffer ends with newline for clean append
    if (p > input_devices_buffer && *(p-1) != '\n' && remaining > 1) {
        *p++ = '\n';
        remaining--;
    }
    
    // Append Steam Deck controller if not already present
    if (!has_steam_deck_controller) {
        size_t ctrl_len = strlen(STEAM_DECK_CONTROLLER);
        if (ctrl_len < remaining) {
            memcpy(p, STEAM_DECK_CONTROLLER, ctrl_len);
            p += ctrl_len;
            remaining -= ctrl_len;
        }
    }
    
    // Always append Steam Deck system inputs (lid switch, power button)
    // These are generic enough to always include
    size_t sys_len = strlen(STEAM_DECK_SYSTEM_INPUTS);
    if (sys_len < remaining) {
        memcpy(p, STEAM_DECK_SYSTEM_INPUTS, sys_len);
        p += sys_len;
    }
    
    *p = '\0';
    input_devices_initialized = 1;
    return input_devices_buffer;
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

// Spoof /proc/self/stat - change PPID (field 4) to 1 (init/systemd)
// Format: pid (comm) state ppid pgrp session tty_nr ...
// The comm field can contain spaces and parens, so we find the last ')' first
static const char* get_spoofed_proc_stat(void) {
    init_functions();
    
    FILE* f = real_fopen("/proc/self/stat", "r");
    if (!f) {
        return "1 (java) S 1 1 1 0 -1 0 0 0 0 0 0 0 0 0 0 0 1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0\n";
    }
    
    char real_stat[1024];
    if (!fgets(real_stat, sizeof(real_stat), f)) {
        fclose(f);
        return "1 (java) S 1 1 1 0 -1 0 0 0 0 0 0 0 0 0 0 0 1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0\n";
    }
    fclose(f);
    
    // Find the last ')' - everything after is space-separated fields
    char* last_paren = strrchr(real_stat, ')');
    if (!last_paren) {
        // Malformed, copy to static buffer and return as-is
        strncpy(proc_stat_spoof_buffer, real_stat, sizeof(proc_stat_spoof_buffer) - 1);
        proc_stat_spoof_buffer[sizeof(proc_stat_spoof_buffer) - 1] = '\0';
        return proc_stat_spoof_buffer;
    }
    
    // Copy up to and including ") X " (state field)
    char* p = proc_stat_spoof_buffer;
    size_t prefix_len = last_paren - real_stat + 1;  // Include the ')'
    memcpy(p, real_stat, prefix_len);
    p += prefix_len;
    
    // Skip the real state and PPID: ") X ppid "
    char* src = last_paren + 1;
    while (*src == ' ') src++;  // Skip space after )
    
    // Copy state character
    if (*src) {
        *p++ = ' ';
        *p++ = *src++;  // State (S, R, etc.)
    }
    
    // Skip the real PPID
    while (*src == ' ') src++;
    while (*src && *src != ' ') src++;  // Skip PPID digits
    
    // Insert spoofed PPID = 1
    *p++ = ' ';
    *p++ = '1';
    
    // Copy the rest of the line
    strcpy(p, src);
    
    return proc_stat_spoof_buffer;
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

// Helper: Generate pseudo-Gaussian value from hash using Box-Muller approximation
// Returns value with mean=0, stddev=1 (caller scales)
static double hash_to_gaussian(unsigned int h1, unsigned int h2) {
    // Convert to uniform [0,1) range
    double u1 = (h1 % 10000) / 10000.0 + 0.0001;  // Avoid 0
    double u2 = (h2 % 10000) / 10000.0;
    
    // Box-Muller transform (approximate)
    double z = sqrt(-2.0 * log(u1)) * cos(2.0 * 3.14159265 * u2);
    
    // Clamp to reasonable range (-3 to +3 stddev)
    if (z < -3.0) z = -3.0;
    if (z > 3.0) z = 3.0;
    
    return z;
}

// Get current thermal throttle factor based on temperature
// Returns MHz reduction (0 to ~400 MHz)
static int get_thermal_throttle(void) {
    // Read current temp (reuse temp logic)
    time_t now = time(NULL);
    unsigned int hash = get_machine_id_hash();
    
    if (temp_init_time == 0) {
        return 0;  // Not warmed up yet, no throttle
    }
    
    time_t elapsed = now - temp_init_time;
    int current_temp_mc;  // millidegrees Celsius
    
    if (elapsed < 600) {
        double progress = (double)elapsed / 600.0;
        progress = progress * progress * (3.0 - 2.0 * progress);
        current_temp_mc = temp_base + (int)((temp_max - temp_base) * progress);
    } else {
        int fluctuation = ((int)(now * hash) % 6000) - 3000;
        current_temp_mc = temp_max + fluctuation;
    }
    
    // Throttle curve:
    // Below 70°C (70000 mc): no throttle
    // 70-80°C: linear throttle 0-300 MHz
    // Above 80°C: 300-400 MHz throttle
    if (current_temp_mc < 70000) {
        return 0;
    } else if (current_temp_mc < 80000) {
        // Linear interpolation: 70000->0, 80000->300
        return (current_temp_mc - 70000) * 300 / 10000;
    } else {
        // Above 80°C: additional throttle up to 400 MHz total
        int extra = (current_temp_mc - 80000) * 100 / 10000;
        if (extra > 100) extra = 100;
        return 300 + extra;
    }
}

// Generate dynamic /proc/cpuinfo with 8 processors and varying MHz/bogomips
// NOT cached - recalculated each read to reflect thermal throttling
static const char* get_dynamic_cpuinfo(void) {
    unsigned int hash = get_machine_id_hash();
    time_t now = time(NULL);
    
    // Initialize per-boot bogomips calibration (simulates kernel calibration variance)
    if (!bogomips_calibrated) {
        // Session seed: different each container start
        unsigned int session_seed = (unsigned int)(now ^ (getpid() * 37));
        for (int i = 0; i < 8; i++) {
            // Each core gets a calibration offset of -30 to +30
            // This varies per boot like real kernel calibration
            bogomips_calibration[i] = ((session_seed >> (i * 4)) % 61) - 30;
        }
        bogomips_calibrated = 1;
    }
    
    // Base CPU MHz: Gaussian distribution centered at 2500 MHz, stddev 150 MHz
    // This creates a bell curve like real Steam Deck population
    double gaussian = hash_to_gaussian(hash, hash >> 16);
    int base_mhz = 2500 + (int)(gaussian * 150);
    
    // Clamp to Steam Deck range (thermal limit at 3200)
    if (base_mhz < 1600) base_mhz = 1600;  // Min P-state
    if (base_mhz > 3200) base_mhz = 3200;  // Thermal limit
    
    // Apply thermal throttling
    int throttle = get_thermal_throttle();
    base_mhz -= throttle;
    if (base_mhz < 1600) base_mhz = 1600;
    
    char* p = cpuinfo_buffer;
    size_t remaining = sizeof(cpuinfo_buffer);
    
    // Generate 8 processors (4 cores x 2 threads with SMT)
    for (int i = 0; i < 8; i++) {
        int core_id = i / 2;  // Cores 0-3, each with 2 threads
        int apicid = i;
        
        // Per-core variation (±30 MHz) - smaller than before for realism
        int core_variation = ((hash >> (i * 3)) % 61) - 30;
        int mhz = base_mhz + core_variation;
        if (mhz < 1600) mhz = 1600;
        if (mhz > 3200) mhz = 3200;
        
        // Bogomips varies per core: ~2x MHz with calibration and variance
        // - Base: 2x MHz (standard relationship)
        // - Per-boot calibration offset (session-random, like kernel calibration)
        // - Per-read variance: ±5-25 (truly random, not hash-derived)
        int read_variance = (rand() % 21) + 5;  // 5-25
        if (rand() % 2) read_variance = -read_variance;  // Random sign
        double bogomips = mhz * 2.0 + bogomips_calibration[i] + read_variance;
        
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
    
    return cpuinfo_buffer;
}

// Generate dynamic /proc/meminfo with session-based variance and memory leak simulation
// NOT cached - recalculated each read
static const char* get_dynamic_meminfo(void) {
    unsigned int hash = get_machine_id_hash();
    time_t now = time(NULL);
    
    // Initialize session state on first call (resets on container restart)
    if (!mem_session_initialized) {
        mem_session_start = now;
        
        // Session-based variance: base ± 500MB to 2GB (in kB)
        // Base is 7GB free, variance is ±500MB to ±2GB
        unsigned int session_seed = (unsigned int)(now ^ (getpid() * 41));
        long variance_kb = 500000 + (session_seed % 1500000);  // 500MB to 2GB in kB
        if (session_seed & 0x80000000) variance_kb = -variance_kb;  // Random sign
        
        mem_session_base = 7000000 + variance_kb;  // 5GB to 9GB starting free
        if (mem_session_base < 3000000) mem_session_base = 3000000;  // Min 3GB
        if (mem_session_base > 10000000) mem_session_base = 10000000;  // Max 10GB
        
        mem_session_initialized = 1;
    }
    
    // Total: 16GB (fixed for Steam Deck)
    long mem_total = 16252928;
    
    // Memory leak simulation: gradual decrease over session
    // Lose ~100MB per hour (realistic for games with minor leaks)
    time_t session_hours = (now - mem_session_start) / 3600;
    long leak_kb = session_hours * 100000;  // 100MB per hour
    if (leak_kb > 3000000) leak_kb = 3000000;  // Cap at 3GB total leak
    
    // Per-read fluctuation: ±50MB (simulates allocation/deallocation)
    long fluctuation = (rand() % 100000) - 50000;  // ±50MB
    
    // Calculate current free memory
    long mem_free = mem_session_base - leak_kb + fluctuation;
    if (mem_free < 1000000) mem_free = 1000000;  // Min 1GB free
    if (mem_free > 12000000) mem_free = 12000000;  // Max 12GB free
    
    // Available: free + cached/buffers (slightly more than free)
    long cached = 2000000 + ((hash >> 12) % 2000000);
    long mem_available = mem_free + cached / 2;
    if (mem_available > mem_total - 2000000) mem_available = mem_total - 2000000;
    
    // Active: used memory
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
// Tracking for fmemopen file descriptors (for fstat spoofing)
// ============================================================================
#define MAX_FMEM_FDS 32
static struct {
    int fd;
    int is_proc;  // Whether this is a /proc file (size should be 0)
} fmem_fds[MAX_FMEM_FDS];
static int fmem_count = 0;
static pthread_mutex_t fmem_mutex = PTHREAD_MUTEX_INITIALIZER;

static void track_fmemopen_fd(FILE* fp, int is_proc) {
    if (!fp) return;
    int fd = fileno(fp);
    if (fd < 0) return;
    
    pthread_mutex_lock(&fmem_mutex);
    if (fmem_count < MAX_FMEM_FDS) {
        fmem_fds[fmem_count].fd = fd;
        fmem_fds[fmem_count].is_proc = is_proc;
        fmem_count++;
    }
    pthread_mutex_unlock(&fmem_mutex);
}

static int is_fmemopen_fd(int fd, int* is_proc) {
    pthread_mutex_lock(&fmem_mutex);
    for (int i = 0; i < fmem_count; i++) {
        if (fmem_fds[i].fd == fd) {
            if (is_proc) *is_proc = fmem_fds[i].is_proc;
            pthread_mutex_unlock(&fmem_mutex);
            return 1;
        }
    }
    pthread_mutex_unlock(&fmem_mutex);
    return 0;
}

static void untrack_fmemopen_fd(int fd) {
    pthread_mutex_lock(&fmem_mutex);
    for (int i = 0; i < fmem_count; i++) {
        if (fmem_fds[i].fd == fd) {
            // Shift remaining entries
            for (int j = i; j < fmem_count - 1; j++) {
                fmem_fds[j] = fmem_fds[j + 1];
            }
            fmem_count--;
            break;
        }
    }
    pthread_mutex_unlock(&fmem_mutex);
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
        track_fmemopen_fd(mem_file, 1);  // Track as /proc file
        // Note: filtered memory will leak, but this is rare operation
        return mem_file;
    }
    
    // Handle /proc/self/cmdline - return Java launch cmdline
    if (is_cmdline_path(path)) {
        // cmdline has embedded nulls, can't use strlen - use fixed length
        FILE* mem_file = fmemopen((void*)PROC_CMDLINE, PROC_CMDLINE_LEN, "r");
        track_fmemopen_fd(mem_file, 1);
        return mem_file;
    }
    
    // Handle /proc/self/status - filter namespace-revealing fields
    if (is_status_path(path)) {
        const char* status = get_dynamic_proc_status();
        FILE* mem_file = fmemopen((void*)status, strlen(status), "r");
        track_fmemopen_fd(mem_file, 1);
        return mem_file;
    }
    
    // Handle /proc/self/stat - spoof PPID to 1 (looks like orphaned/daemon process)
    if (is_stat_path(path)) {
        const char* stat = get_spoofed_proc_stat();
        FILE* mem_file = fmemopen((void*)stat, strlen(stat), "r");
        track_fmemopen_fd(mem_file, 1);
        return mem_file;
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
        track_fmemopen_fd(mem_file, 1);  // Track as /proc file
        // Note: filtered memory will leak, but this is rare operation
        return mem_file;
    }
    
    // Check for mapped content (could be /proc, /sys, or /etc)
    const char* mapped = get_mapped_content(path);
    if (mapped) {
        int is_proc = str_starts(path, "/proc/") || str_starts(path, "/sys/");
        FILE* mem_file = fmemopen((void*)mapped, strlen(mapped), "r");
        track_fmemopen_fd(mem_file, is_proc);
        return mem_file;
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
    
    // Handle /proc/self/stat - spoof PPID to 1
    if (is_stat_path(path) && !(flags & O_WRONLY)) {
        const char* stat = get_spoofed_proc_stat();
        int null_fd = real_open("/dev/null", O_RDONLY);
        if (null_fd >= 0) {
            if (add_tracked_fd(null_fd, stat, strlen(stat), 0, NULL)) {
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
    
    // Clean up fmemopen tracking
    untrack_fmemopen_fd(fd);
    
    // Clean up open() tracking
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

int fclose(FILE* fp) {
    static int (*real_fclose)(FILE*) = NULL;
    if (!real_fclose) real_fclose = dlsym(RTLD_NEXT, "fclose");
    
    if (fp) {
        int fd = fileno(fp);
        if (fd >= 0) {
            untrack_fmemopen_fd(fd);
        }
    }
    
    return real_fclose(fp);
}

// ============================================================================
// Intercepted: readlink() - for /proc/self/exe spoofing
// ============================================================================

// Check if this is a /proc/*/exe path we should spoof
static int is_proc_path_ending(const char* path, const char* suffix) {
    if (!path) return 0;
    // Check /proc/self/<suffix>
    char self_path[64];
    snprintf(self_path, sizeof(self_path), "/proc/self/%s", suffix);
    if (str_eq(path, self_path)) return 1;
    
    // Check /proc/<pid>/<suffix>
    if (str_starts(path, "/proc/") && str_ends(path, suffix)) {
        const char* p = path + 6;
        while (*p && *p != '/') {
            if (*p < '0' || *p > '9') return 0;
            p++;
        }
        char expected[16];
        snprintf(expected, sizeof(expected), "/%s", suffix);
        return str_eq(p, expected);
    }
    return 0;
}

// Helper to copy string to readlink buffer
static ssize_t readlink_spoof(const char* fake, char* buf, size_t bufsiz) {
    size_t len = strlen(fake);
    if (len > bufsiz) len = bufsiz;
    memcpy(buf, fake, len);
    return len;
}

ssize_t readlink(const char* path, char* buf, size_t bufsiz) {
    static ssize_t (*real_readlink)(const char*, char*, size_t) = NULL;
    if (!real_readlink) real_readlink = dlsym(RTLD_NEXT, "readlink");
    
    // Normalize path first
    path = normalize_path(path);
    
    // Spoof /proc/self/exe to look like java executable
    if (is_proc_path_ending(path, "exe")) {
        return readlink_spoof(FAKE_SELF_EXE, buf, bufsiz);
    }
    
    // Spoof /proc/self/cwd to hide container working directory
    if (is_proc_path_ending(path, "cwd")) {
        return readlink_spoof(FAKE_SELF_CWD, buf, bufsiz);
    }
    
    // Spoof /proc/self/root to hide overlay filesystem
    if (is_proc_path_ending(path, "root")) {
        return readlink_spoof(FAKE_SELF_ROOT, buf, bufsiz);
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

// ============================================================================
// Intercepted: ioctl() - for SIOCGIFHWADDR MAC address spoofing
// ============================================================================

int ioctl(int fd, unsigned long request, ...) {
    static int (*real_ioctl)(int, unsigned long, ...) = NULL;
    if (!real_ioctl) real_ioctl = dlsym(RTLD_NEXT, "ioctl");
    
    va_list args;
    va_start(args, request);
    void* argp = va_arg(args, void*);
    va_end(args);
    
    int result = real_ioctl(fd, request, argp);
    
    // Handle SIOCGIFHWADDR - get hardware (MAC) address
    if (request == SIOCGIFHWADDR && result == 0 && argp) {
        struct ifreq* ifr = (struct ifreq*)argp;
        
        // Ensure MAC is initialized
        get_dynamic_mac();
        
        // Replace the MAC address with our spoofed one
        // sa_data contains the MAC starting at offset 0
        memcpy(ifr->ifr_hwaddr.sa_data, net_mac_bytes, 6);
    }
    
    return result;
}

// ============================================================================
// Intercepted: access() / faccessat() - file existence checks
// ============================================================================

int access(const char* path, int mode) {
    static int (*real_access)(const char*, int) = NULL;
    if (!real_access) real_access = dlsym(RTLD_NEXT, "access");
    
    if (!path) return real_access(path, mode);
    
    // Normalize and check if spoofed
    const char* normalized = normalize_path(path);
    if (normalized && is_spoofed_path(normalized)) {
        // Spoofed paths always "exist" and are readable
        // We don't spoof write/execute permissions
        if (mode == F_OK || mode == R_OK || (mode & R_OK)) {
            return 0; // Success
        }
    }
    
    // Hide blocked paths - pretend they don't exist
    if (should_block_path(path)) {
        errno = ENOENT;
        return -1;
    }
    
    return real_access(path, mode);
}

int faccessat(int dirfd, const char* path, int mode, int flags) {
    static int (*real_faccessat)(int, const char*, int, int) = NULL;
    if (!real_faccessat) real_faccessat = dlsym(RTLD_NEXT, "faccessat");
    
    if (!path) return real_faccessat(dirfd, path, mode, flags);
    
    // For absolute paths or AT_FDCWD, we can check spoofing
    if (path[0] == '/' || dirfd == AT_FDCWD) {
        const char* normalized = normalize_path(path);
        if (normalized && is_spoofed_path(normalized)) {
            if (mode == F_OK || mode == R_OK || (mode & R_OK)) {
                return 0;
            }
        }
        
        // Hide blocked paths
        if (should_block_path(path)) {
            errno = ENOENT;
            return -1;
        }
    }
    
    return real_faccessat(dirfd, path, mode, flags);
}

// ============================================================================
// Intercepted: stat() / lstat() / __xstat() / __lxstat64()
// ============================================================================

// Helper to fill stat buffer with plausible values for spoofed files
static void fill_spoofed_stat(struct stat* buf, const char* path) {
    memset(buf, 0, sizeof(*buf));
    
    buf->st_dev = 0x802;  // Typical device ID
    buf->st_ino = 1000 + (get_machine_id_hash() % 100000);  // Pseudo-random inode
    buf->st_nlink = 1;
    buf->st_uid = 1000;  // deck user
    buf->st_gid = 998;   // wheel group
    buf->st_rdev = 0;
    buf->st_blksize = 4096;
    
    // Different modes for different paths
    if (str_starts(path, "/proc/") || str_starts(path, "/sys/")) {
        buf->st_mode = S_IFREG | 0444;  // Regular file, read-only
        buf->st_size = 4096;  // Pseudo-files report various sizes
    } else if (str_starts(path, "/etc/")) {
        buf->st_mode = S_IFREG | 0644;  // Regular file
        buf->st_size = 32;
    } else {
        buf->st_mode = S_IFREG | 0644;
        buf->st_size = 256;
    }
    
    buf->st_blocks = (buf->st_size + 511) / 512;
    
    // Set times to something reasonable (system boot time + offset)
    time_t now = time(NULL);
    time_t boot = now - (get_machine_id_hash() % 86400) - 3600;
    buf->st_atime = now;
    buf->st_mtime = boot;
    buf->st_ctime = boot;
}

int stat(const char* path, struct stat* buf) {
    static int (*real_stat)(const char*, struct stat*) = NULL;
    if (!real_stat) real_stat = dlsym(RTLD_NEXT, "stat");
    
    if (!path || !buf) return real_stat(path, buf);
    
    const char* normalized = normalize_path(path);
    
    // Hide container indicators
    if (should_block_path(path)) {
        errno = ENOENT;
        return -1;
    }
    
    // For spoofed paths, return fake stat
    if (normalized && is_spoofed_path(normalized)) {
        // Try real stat first (for timing/mode accuracy)
        if (real_stat(path, buf) == 0) {
            return 0;  // Real file exists, use real stat
        }
        // File doesn't exist but we spoof it - create fake stat
        fill_spoofed_stat(buf, normalized);
        return 0;
    }
    
    return real_stat(path, buf);
}

int lstat(const char* path, struct stat* buf) {
    static int (*real_lstat)(const char*, struct stat*) = NULL;
    if (!real_lstat) real_lstat = dlsym(RTLD_NEXT, "lstat");
    
    if (!path || !buf) return real_lstat(path, buf);
    
    const char* normalized = normalize_path(path);
    
    // Hide container indicators
    if (should_block_path(path)) {
        errno = ENOENT;
        return -1;
    }
    
    // For spoofed paths, return fake stat
    if (normalized && is_spoofed_path(normalized)) {
        if (real_lstat(path, buf) == 0) {
            return 0;
        }
        fill_spoofed_stat(buf, normalized);
        return 0;
    }
    
    return real_lstat(path, buf);
}

// glibc uses __xstat/__lxstat with version parameter
int __xstat(int ver, const char* path, struct stat* buf) {
    static int (*real_xstat)(int, const char*, struct stat*) = NULL;
    if (!real_xstat) real_xstat = dlsym(RTLD_NEXT, "__xstat");
    
    if (!path || !buf) return real_xstat(ver, path, buf);
    
    const char* normalized = normalize_path(path);
    
    if (should_block_path(path)) {
        errno = ENOENT;
        return -1;
    }
    
    if (normalized && is_spoofed_path(normalized)) {
        if (real_xstat(ver, path, buf) == 0) {
            return 0;
        }
        fill_spoofed_stat(buf, normalized);
        return 0;
    }
    
    return real_xstat(ver, path, buf);
}

int __lxstat(int ver, const char* path, struct stat* buf) {
    static int (*real_lxstat)(int, const char*, struct stat*) = NULL;
    if (!real_lxstat) real_lxstat = dlsym(RTLD_NEXT, "__lxstat");
    
    if (!path || !buf) return real_lxstat(ver, path, buf);
    
    const char* normalized = normalize_path(path);
    
    if (should_block_path(path)) {
        errno = ENOENT;
        return -1;
    }
    
    if (normalized && is_spoofed_path(normalized)) {
        if (real_lxstat(ver, path, buf) == 0) {
            return 0;
        }
        fill_spoofed_stat(buf, normalized);
        return 0;
    }
    
    return real_lxstat(ver, path, buf);
}

// 64-bit variants
int __xstat64(int ver, const char* path, struct stat64* buf) {
    static int (*real_xstat64)(int, const char*, struct stat64*) = NULL;
    if (!real_xstat64) real_xstat64 = dlsym(RTLD_NEXT, "__xstat64");
    
    if (!path || !buf) return real_xstat64(ver, path, buf);
    
    const char* normalized = normalize_path(path);
    
    if (should_block_path(path)) {
        errno = ENOENT;
        return -1;
    }
    
    if (normalized && is_spoofed_path(normalized)) {
        if (real_xstat64(ver, path, buf) == 0) {
            return 0;
        }
        // Fill with spoofed data (cast to regular stat, sizes match for our usage)
        fill_spoofed_stat((struct stat*)buf, normalized);
        return 0;
    }
    
    return real_xstat64(ver, path, buf);
}

int __lxstat64(int ver, const char* path, struct stat64* buf) {
    static int (*real_lxstat64)(int, const char*, struct stat64*) = NULL;
    if (!real_lxstat64) real_lxstat64 = dlsym(RTLD_NEXT, "__lxstat64");
    
    if (!path || !buf) return real_lxstat64(ver, path, buf);
    
    const char* normalized = normalize_path(path);
    
    if (should_block_path(path)) {
        errno = ENOENT;
        return -1;
    }
    
    if (normalized && is_spoofed_path(normalized)) {
        if (real_lxstat64(ver, path, buf) == 0) {
            return 0;
        }
        fill_spoofed_stat((struct stat*)buf, normalized);
        return 0;
    }
    
    return real_lxstat64(ver, path, buf);
}

// ============================================================================
// Intercepted: fstat() / __fxstat() - for fmemopen fd spoofing
// ============================================================================

// Helper to fill stat buffer with /proc-like values for fmemopen fds
static void fill_proc_fstat(struct stat* buf) {
    memset(buf, 0, sizeof(*buf));
    
    // /proc filesystem characteristics
    buf->st_dev = 0x4;          // proc filesystem device ID (typically 4)
    buf->st_ino = 1000 + (get_machine_id_hash() % 50000);  // Pseudo-random inode
    buf->st_mode = S_IFREG | 0444;  // Regular file, read-only
    buf->st_nlink = 1;
    buf->st_uid = 0;            // root owns /proc files
    buf->st_gid = 0;
    buf->st_rdev = 0;
    buf->st_size = 0;           // CRITICAL: /proc files report size 0
    buf->st_blksize = 1024;     // /proc uses 1024 block size
    buf->st_blocks = 0;         // No blocks for virtual files
    
    time_t now = time(NULL);
    buf->st_atime = now;
    buf->st_mtime = now;
    buf->st_ctime = now;
}

int fstat(int fd, struct stat* buf) {
    static int (*real_fstat)(int, struct stat*) = NULL;
    if (!real_fstat) real_fstat = dlsym(RTLD_NEXT, "fstat");
    
    if (!buf) return real_fstat(fd, buf);
    
    // Check if this is one of our fmemopen fds
    int is_proc = 0;
    if (is_fmemopen_fd(fd, &is_proc)) {
        if (is_proc) {
            fill_proc_fstat(buf);
            return 0;
        }
        // For non-proc spoofed files (e.g., /etc), use real fstat
        // but it's still fmemopen so won't look weird
    }
    
    // Check if this is one of our tracked open() fds
    pthread_mutex_lock(&tracked_mutex);
    int idx = find_tracked_unlocked(fd);
    if (idx >= 0) {
        pthread_mutex_unlock(&tracked_mutex);
        fill_proc_fstat(buf);
        return 0;
    }
    pthread_mutex_unlock(&tracked_mutex);
    
    return real_fstat(fd, buf);
}

// glibc version
int __fxstat(int ver, int fd, struct stat* buf) {
    static int (*real_fxstat)(int, int, struct stat*) = NULL;
    if (!real_fxstat) real_fxstat = dlsym(RTLD_NEXT, "__fxstat");
    
    if (!buf) return real_fxstat(ver, fd, buf);
    
    int is_proc = 0;
    if (is_fmemopen_fd(fd, &is_proc)) {
        if (is_proc) {
            fill_proc_fstat(buf);
            return 0;
        }
    }
    
    pthread_mutex_lock(&tracked_mutex);
    int idx = find_tracked_unlocked(fd);
    if (idx >= 0) {
        pthread_mutex_unlock(&tracked_mutex);
        fill_proc_fstat(buf);
        return 0;
    }
    pthread_mutex_unlock(&tracked_mutex);
    
    return real_fxstat(ver, fd, buf);
}

int __fxstat64(int ver, int fd, struct stat64* buf) {
    static int (*real_fxstat64)(int, int, struct stat64*) = NULL;
    if (!real_fxstat64) real_fxstat64 = dlsym(RTLD_NEXT, "__fxstat64");
    
    if (!buf) return real_fxstat64(ver, fd, buf);
    
    int is_proc = 0;
    if (is_fmemopen_fd(fd, &is_proc)) {
        if (is_proc) {
            fill_proc_fstat((struct stat*)buf);
            return 0;
        }
    }
    
    pthread_mutex_lock(&tracked_mutex);
    int idx = find_tracked_unlocked(fd);
    if (idx >= 0) {
        pthread_mutex_unlock(&tracked_mutex);
        fill_proc_fstat((struct stat*)buf);
        return 0;
    }
    pthread_mutex_unlock(&tracked_mutex);
    
    return real_fxstat64(ver, fd, buf);
}

// ============================================================================
// Intercepted: getdents() / getdents64() - directory listing filtering
// ============================================================================

// Linux directory entry structure
struct linux_dirent {
    unsigned long  d_ino;
    unsigned long  d_off;
    unsigned short d_reclen;
    char           d_name[];
};

struct linux_dirent64 {
    uint64_t       d_ino;
    int64_t        d_off;
    unsigned short d_reclen;
    unsigned char  d_type;
    char           d_name[];
};

// Check if a directory entry should be hidden
static int should_hide_dirent(const char* name) {
    if (!name) return 0;
    
    // Hide Docker/container indicators
    if (str_eq(name, ".dockerenv")) return 1;
    if (str_eq(name, ".dockerinit")) return 1;
    if (str_starts(name, "docker")) return 1;
    
    // Hide our LD_PRELOAD library if somehow visible
    if (str_contains(name, "pthreads_ext")) return 1;
    if (str_contains(name, "libpthreads_ext")) return 1;
    
    return 0;
}

// Process names to hide from /proc listings
static const char* HIDDEN_PROCESSES[] = {
    "Xvfb",
    "x11vnc",
    "Xephyr",
    "fluxbox",
    "openbox",
    "picom",
    "xcompmgr",
    "pulseaudio",
    "pipewire",
    "dbus-daemon",
    "upowerd",
    "at-spi-bus-launcher",
    "at-spi2-registryd",
    NULL
};

// Check if a PID should be hidden based on its process name
static int should_hide_pid(const char* pid_str) {
    if (!pid_str) return 0;
    
    // Only check numeric entries (PIDs)
    for (const char* p = pid_str; *p; p++) {
        if (*p < '0' || *p > '9') return 0;
    }
    
    // Read the process comm (name)
    char comm_path[64];
    snprintf(comm_path, sizeof(comm_path), "/proc/%s/comm", pid_str);
    
    // Use direct syscall to avoid recursion through our hooks
    int fd = syscall(SYS_open, comm_path, O_RDONLY);
    if (fd < 0) return 0;
    
    char comm[256];
    ssize_t len = syscall(SYS_read, fd, comm, sizeof(comm) - 1);
    syscall(SYS_close, fd);
    
    if (len <= 0) return 0;
    comm[len] = '\0';
    
    // Remove trailing newline
    if (len > 0 && comm[len - 1] == '\n') {
        comm[len - 1] = '\0';
    }
    
    // Check against hidden process list
    for (int i = 0; HIDDEN_PROCESSES[i]; i++) {
        if (str_eq(comm, HIDDEN_PROCESSES[i])) {
            return 1;
        }
    }
    
    return 0;
}

// Get the path a directory fd refers to (for /proc detection)
static int is_proc_dir_fd(int fd) {
    char fd_path[64];
    char resolved[256];
    
    snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", fd);
    
    // Use syscall to avoid recursion
    ssize_t len = syscall(SYS_readlink, fd_path, resolved, sizeof(resolved) - 1);
    if (len <= 0) return 0;
    resolved[len] = '\0';
    
    // Check if this fd points to /proc (but not /proc/self or /proc/<our_pid>)
    if (str_eq(resolved, "/proc")) return 1;
    
    return 0;
}

// ============================================================================
// Intercepted: uname() - kernel version spoofing
// ============================================================================

int uname(struct utsname* buf) {
    static int (*real_uname)(struct utsname*) = NULL;
    if (!real_uname) real_uname = dlsym(RTLD_NEXT, "uname");
    
    int ret = real_uname(buf);
    if (ret != 0 || !buf) return ret;
    
    // Spoof to Steam Deck values
    strncpy(buf->sysname, "Linux", sizeof(buf->sysname) - 1);
    buf->sysname[sizeof(buf->sysname) - 1] = '\0';
    
    // Use machine-id derived kernel version for per-bot variation
    // KERNEL_VERSIONS[i][0] = release string, [1] = gcc version, [2] = build date
    unsigned int hash = get_machine_id_hash();
    int version_idx = hash % NUM_KERNEL_VERSIONS;
    
    strncpy(buf->release, KERNEL_VERSIONS[version_idx][0], sizeof(buf->release) - 1);
    buf->release[sizeof(buf->release) - 1] = '\0';
    
    // Build version string matching kernel format
    snprintf(buf->version, sizeof(buf->version),
             "#1 SMP PREEMPT_DYNAMIC %s", KERNEL_VERSIONS[version_idx][2]);
    
    strncpy(buf->machine, "x86_64", sizeof(buf->machine) - 1);
    buf->machine[sizeof(buf->machine) - 1] = '\0';
    
    // nodename is hostname - should already be "steamdeck" from container
    strncpy(buf->nodename, "steamdeck", sizeof(buf->nodename) - 1);
    buf->nodename[sizeof(buf->nodename) - 1] = '\0';
    
    return ret;
}

ssize_t getdents(int fd, void* dirp, size_t count) {
    ssize_t nread = syscall(SYS_getdents, fd, dirp, count);
    if (nread <= 0) return nread;
    
    // Check if we're listing /proc (for PID filtering)
    int listing_proc = is_proc_dir_fd(fd);
    
    // Filter entries
    char* buf = (char*)dirp;
    char* filtered = buf;
    ssize_t filtered_len = 0;
    ssize_t pos = 0;
    
    while (pos < nread) {
        struct linux_dirent* d = (struct linux_dirent*)(buf + pos);
        
        int hide = should_hide_dirent(d->d_name);
        
        // If listing /proc, also check if this PID should be hidden
        if (!hide && listing_proc) {
            hide = should_hide_pid(d->d_name);
        }
        
        if (!hide) {
            if (filtered != buf + pos) {
                memmove(filtered, d, d->d_reclen);
            }
            filtered += d->d_reclen;
            filtered_len += d->d_reclen;
        }
        
        pos += d->d_reclen;
    }
    
    return filtered_len;
}

ssize_t getdents64(int fd, void* dirp, size_t count) {
    ssize_t nread = syscall(SYS_getdents64, fd, dirp, count);
    if (nread <= 0) return nread;
    
    // Check if we're listing /proc (for PID filtering)
    int listing_proc = is_proc_dir_fd(fd);
    
    // Filter entries
    char* buf = (char*)dirp;
    char* filtered = buf;
    ssize_t filtered_len = 0;
    ssize_t pos = 0;
    
    while (pos < nread) {
        struct linux_dirent64* d = (struct linux_dirent64*)(buf + pos);
        
        int hide = should_hide_dirent(d->d_name);
        
        // If listing /proc, also check if this PID should be hidden
        if (!hide && listing_proc) {
            hide = should_hide_pid(d->d_name);
        }
        
        if (!hide) {
            if (filtered != buf + pos) {
                memmove(filtered, d, d->d_reclen);
            }
            filtered += d->d_reclen;
            filtered_len += d->d_reclen;
        }
        
        pos += d->d_reclen;
    }
    
    return filtered_len;
}
