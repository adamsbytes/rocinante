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

// Original function pointers
static FILE* (*real_fopen)(const char*, const char*) = NULL;
static ssize_t (*real_read)(int, void*, size_t) = NULL;
static int (*real_open)(const char*, int, ...) = NULL;
static char* (*real_getenv)(const char*) = NULL;

// Forward declarations
static const char* get_dynamic_uptime(void);
static const char* get_dynamic_mac(void);

// Library name (must match the compiled .so filename)
static const char* LIB_NAME = "libpthreads_ext.so";

// Spoofed file contents - system info
static const char* PROC_CPUINFO = 
    "processor\t: 0\n"
    "vendor_id\t: AuthenticAMD\n"
    "cpu family\t: 23\n"
    "model\t\t: 144\n"
    "model name\t: AMD Custom APU 0405\n"
    "stepping\t: 1\n"
    "microcode\t: 0xa404101\n"
    "cpu MHz\t\t: 2800.000\n"
    "cache size\t: 1024 KB\n"
    "physical id\t: 0\n"
    "siblings\t: 8\n"
    "core id\t\t: 0\n"
    "cpu cores\t: 4\n"
    "apicid\t\t: 0\n"
    "fpu\t\t: yes\n"
    "fpu_exception\t: yes\n"
    "cpuid level\t: 16\n"
    "wp\t\t: yes\n"
    "flags\t\t: fpu vme de pse tsc msr pae mce cx8 apic sep mtrr pge mca cmov pat pse36 clflush mmx fxsr sse sse2 ht syscall nx mmxext fxsr_opt pdpe1gb rdtscp lm constant_tsc rep_good nopl nonstop_tsc cpuid extd_apicid aperfmperf rapl pni pclmulqdq monitor ssse3 fma cx16 sse4_1 sse4_2 movbe popcnt aes xsave avx f16c rdrand lahf_lm cmp_legacy svm extapic cr8_legacy abm sse4a misalignsse 3dnowprefetch osvw ibs skinit wdt tce topoext perfctr_core perfctr_nb bpext perfctr_llc mwaitx cpb cat_l3 cdp_l3 hw_pstate ssbd mba ibrs ibpb stibp vmmcall fsgsbase bmi1 avx2 smep bmi2 cqm rdt_a rdseed adx smap clflushopt clwb sha_ni xsaveopt xsavec xgetbv1 xsaves cqm_llc cqm_occup_llc cqm_mbm_total cqm_mbm_local clzero irperf xsaveerptr rdpru wbnoinvd cppc arat npt lbrv svm_lock nrip_save tsc_scale vmcb_clean flushbyasid decodeassists pausefilter pfthreshold avic v_vmsave_vmload vgif v_spec_ctrl umip rdpid overflow_recov succor smca sev sev_es\n"
    "bugs\t\t: sysret_ss_attrs spectre_v1 spectre_v2 spec_store_bypass\n"
    "bogomips\t: 5600.00\n"
    "TLB size\t: 2560 4K pages\n"
    "clflush size\t: 64\n"
    "cache_alignment\t: 64\n"
    "address sizes\t: 48 bits physical, 48 bits virtual\n"
    "power management: ts ttp tm hwpstate cpb eff_freq_ro [13] [14]\n"
    "\n"
    "processor\t: 1\n"
    "vendor_id\t: AuthenticAMD\n"
    "cpu family\t: 23\n"
    "model\t\t: 144\n"
    "model name\t: AMD Custom APU 0405\n"
    "stepping\t: 1\n"
    "microcode\t: 0xa404101\n"
    "cpu MHz\t\t: 2800.000\n"
    "cache size\t: 1024 KB\n"
    "physical id\t: 0\n"
    "siblings\t: 8\n"
    "core id\t\t: 1\n"
    "cpu cores\t: 4\n"
    "apicid\t\t: 1\n"
    "fpu\t\t: yes\n"
    "fpu_exception\t: yes\n"
    "cpuid level\t: 16\n"
    "wp\t\t: yes\n"
    "flags\t\t: fpu vme de pse tsc msr pae mce cx8 apic sep mtrr pge mca cmov pat pse36 clflush mmx fxsr sse sse2 ht syscall nx mmxext fxsr_opt pdpe1gb rdtscp lm constant_tsc rep_good nopl nonstop_tsc cpuid extd_apicid aperfmperf rapl pni pclmulqdq monitor ssse3 fma cx16 sse4_1 sse4_2 movbe popcnt aes xsave avx f16c rdrand lahf_lm cmp_legacy svm extapic cr8_legacy abm sse4a misalignsse 3dnowprefetch osvw ibs skinit wdt tce topoext perfctr_core perfctr_nb bpext perfctr_llc mwaitx cpb cat_l3 cdp_l3 hw_pstate ssbd mba ibrs ibpb stibp vmmcall fsgsbase bmi1 avx2 smep bmi2 cqm rdt_a rdseed adx smap clflushopt clwb sha_ni xsaveopt xsavec xgetbv1 xsaves cqm_llc cqm_occup_llc cqm_mbm_total cqm_mbm_local clzero irperf xsaveerptr rdpru wbnoinvd cppc arat npt lbrv svm_lock nrip_save tsc_scale vmcb_clean flushbyasid decodeassists pausefilter pfthreshold avic v_vmsave_vmload vgif v_spec_ctrl umip rdpid overflow_recov succor smca sev sev_es\n"
    "bugs\t\t: sysret_ss_attrs spectre_v1 spectre_v2 spec_store_bypass\n"
    "bogomips\t: 5600.00\n"
    "TLB size\t: 2560 4K pages\n"
    "clflush size\t: 64\n"
    "cache_alignment\t: 64\n"
    "address sizes\t: 48 bits physical, 48 bits virtual\n"
    "power management: ts ttp tm hwpstate cpb eff_freq_ro [13] [14]\n"
    "\n"
    "processor\t: 2\n"
    "vendor_id\t: AuthenticAMD\n"
    "cpu family\t: 23\n"
    "model\t\t: 144\n"
    "model name\t: AMD Custom APU 0405\n"
    "stepping\t: 1\n"
    "microcode\t: 0xa404101\n"
    "cpu MHz\t\t: 2800.000\n"
    "cache size\t: 1024 KB\n"
    "physical id\t: 0\n"
    "siblings\t: 8\n"
    "core id\t\t: 2\n"
    "cpu cores\t: 4\n"
    "apicid\t\t: 2\n"
    "fpu\t\t: yes\n"
    "fpu_exception\t: yes\n"
    "cpuid level\t: 16\n"
    "wp\t\t: yes\n"
    "flags\t\t: fpu vme de pse tsc msr pae mce cx8 apic sep mtrr pge mca cmov pat pse36 clflush mmx fxsr sse sse2 ht syscall nx mmxext fxsr_opt pdpe1gb rdtscp lm constant_tsc rep_good nopl nonstop_tsc cpuid extd_apicid aperfmperf rapl pni pclmulqdq monitor ssse3 fma cx16 sse4_1 sse4_2 movbe popcnt aes xsave avx f16c rdrand lahf_lm cmp_legacy svm extapic cr8_legacy abm sse4a misalignsse 3dnowprefetch osvw ibs skinit wdt tce topoext perfctr_core perfctr_nb bpext perfctr_llc mwaitx cpb cat_l3 cdp_l3 hw_pstate ssbd mba ibrs ibpb stibp vmmcall fsgsbase bmi1 avx2 smep bmi2 cqm rdt_a rdseed adx smap clflushopt clwb sha_ni xsaveopt xsavec xgetbv1 xsaves cqm_llc cqm_occup_llc cqm_mbm_total cqm_mbm_local clzero irperf xsaveerptr rdpru wbnoinvd cppc arat npt lbrv svm_lock nrip_save tsc_scale vmcb_clean flushbyasid decodeassists pausefilter pfthreshold avic v_vmsave_vmload vgif v_spec_ctrl umip rdpid overflow_recov succor smca sev sev_es\n"
    "bugs\t\t: sysret_ss_attrs spectre_v1 spectre_v2 spec_store_bypass\n"
    "bogomips\t: 5600.00\n"
    "TLB size\t: 2560 4K pages\n"
    "clflush size\t: 64\n"
    "cache_alignment\t: 64\n"
    "address sizes\t: 48 bits physical, 48 bits virtual\n"
    "power management: ts ttp tm hwpstate cpb eff_freq_ro [13] [14]\n"
    "\n"
    "processor\t: 3\n"
    "vendor_id\t: AuthenticAMD\n"
    "cpu family\t: 23\n"
    "model\t\t: 144\n"
    "model name\t: AMD Custom APU 0405\n"
    "stepping\t: 1\n"
    "microcode\t: 0xa404101\n"
    "cpu MHz\t\t: 2800.000\n"
    "cache size\t: 1024 KB\n"
    "physical id\t: 0\n"
    "siblings\t: 8\n"
    "core id\t\t: 3\n"
    "cpu cores\t: 4\n"
    "apicid\t\t: 3\n"
    "fpu\t\t: yes\n"
    "fpu_exception\t: yes\n"
    "cpuid level\t: 16\n"
    "wp\t\t: yes\n"
    "flags\t\t: fpu vme de pse tsc msr pae mce cx8 apic sep mtrr pge mca cmov pat pse36 clflush mmx fxsr sse sse2 ht syscall nx mmxext fxsr_opt pdpe1gb rdtscp lm constant_tsc rep_good nopl nonstop_tsc cpuid extd_apicid aperfmperf rapl pni pclmulqdq monitor ssse3 fma cx16 sse4_1 sse4_2 movbe popcnt aes xsave avx f16c rdrand lahf_lm cmp_legacy svm extapic cr8_legacy abm sse4a misalignsse 3dnowprefetch osvw ibs skinit wdt tce topoext perfctr_core perfctr_nb bpext perfctr_llc mwaitx cpb cat_l3 cdp_l3 hw_pstate ssbd mba ibrs ibpb stibp vmmcall fsgsbase bmi1 avx2 smep bmi2 cqm rdt_a rdseed adx smap clflushopt clwb sha_ni xsaveopt xsavec xgetbv1 xsaves cqm_llc cqm_occup_llc cqm_mbm_total cqm_mbm_local clzero irperf xsaveerptr rdpru wbnoinvd cppc arat npt lbrv svm_lock nrip_save tsc_scale vmcb_clean flushbyasid decodeassists pausefilter pfthreshold avic v_vmsave_vmload vgif v_spec_ctrl umip rdpid overflow_recov succor smca sev sev_es\n"
    "bugs\t\t: sysret_ss_attrs spectre_v1 spectre_v2 spec_store_bypass\n"
    "bogomips\t: 5600.00\n"
    "TLB size\t: 2560 4K pages\n"
    "clflush size\t: 64\n"
    "cache_alignment\t: 64\n"
    "address sizes\t: 48 bits physical, 48 bits virtual\n"
    "power management: ts ttp tm hwpstate cpb eff_freq_ro [13] [14]\n";

static const char* PROC_VERSION = 
    "Linux version 6.1.52-valve16-1-neptune-61 (deck@jupiter) "
    "(gcc (GCC) 12.2.0, GNU ld (GNU Binutils) 2.39) "
    "#1 SMP PREEMPT_DYNAMIC Wed Dec 18 04:20:00 UTC 2024\n";

static const char* PROC_MEMINFO = 
    "MemTotal:       16252928 kB\n"
    "MemFree:         8126464 kB\n"
    "MemAvailable:   12189696 kB\n"
    "Buffers:          524288 kB\n"
    "Cached:          4063232 kB\n"
    "SwapCached:            0 kB\n"
    "Active:          4063232 kB\n"
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
    "DirectMap1G:     8388608 kB\n";

// DMI/SMBIOS values
static const char* DMI_PRODUCT_NAME = "Jupiter\n";
static const char* DMI_SYS_VENDOR = "Valve\n";
static const char* DMI_PRODUCT_VERSION = "1\n";
static const char* DMI_BOARD_NAME = "Jupiter\n";
static const char* DMI_BOARD_VENDOR = "Valve\n";
static const char* DMI_BIOS_VENDOR = "Valve\n";
static const char* DMI_BIOS_VERSION = "F7A0131\n";

// Cgroup (non-container)
static const char* PROC_CGROUP = "0::/\n";

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
static const char* BAT1_CHARGE_FULL = "40690000\n";  // 40.69Wh
static const char* BAT1_CHARGE_FULL_DESIGN = "40040000\n";  // 40.04Wh design
static const char* BAT1_CHARGE_NOW = "40690000\n";  // 100% = full
static const char* BAT1_MANUFACTURER = "Valve\n";
static const char* BAT1_MODEL_NAME = "Jupiter\n";
static const char* BAT1_SERIAL = "00000001\n";
static const char* BAT1_TECHNOLOGY = "Li-poly\n";
static const char* BAT1_TYPE = "Battery\n";
static const char* BAT1_CYCLE_COUNT = "142\n";  // Reasonable for used device

// Steam Deck AC adapter (ACAD) - plugged in (docked)
static const char* ACAD_ONLINE = "1\n";  // Plugged in while docked
static const char* ACAD_TYPE = "Mains\n";

// Steam Deck backlight (amdgpu_bl1)
static const char* BL_BRIGHTNESS = "80\n";  // 80% brightness
static const char* BL_MAX_BRIGHTNESS = "100\n";
static const char* BL_ACTUAL_BRIGHTNESS = "80\n";
static const char* BL_TYPE = "raw\n";

// Steam Deck hwmon sensors (k10temp for AMD APU)
static const char* HWMON_NAME = "k10temp\n";
static const char* HWMON_TEMP1_INPUT = "62000\n";  // 62°C - warm from gaming
static const char* HWMON_TEMP1_MAX = "105000\n";  // 105°C max
static const char* HWMON_TEMP1_CRIT = "110000\n";  // 110°C critical
static const char* HWMON_TEMP1_LABEL = "Tctl\n";

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
    if (str_eq(path, "/proc/cpuinfo")) return PROC_CPUINFO;
    if (str_eq(path, "/proc/version")) return PROC_VERSION;
    if (str_eq(path, "/proc/meminfo")) return PROC_MEMINFO;
    if (str_eq(path, "/proc/uptime")) return get_dynamic_uptime();
    if (str_eq(path, "/proc/self/cgroup")) return PROC_CGROUP;
    if (str_eq(path, "/proc/1/cgroup")) return PROC_CGROUP;
    if (str_eq(path, "/proc/bus/input/devices")) return INPUT_DEVICES;
    
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
        str_eq(path, "/sys/class/dmi/id/bios_version")) return DMI_BIOS_VERSION;
    
    // Steam Deck battery (BAT1)
    if (str_contains(path, "/power_supply/BAT1/") || str_contains(path, "/power_supply/BAT0/")) {
        if (str_ends(path, "/status")) return BAT1_STATUS;
        if (str_ends(path, "/present")) return BAT1_PRESENT;
        if (str_ends(path, "/voltage_now")) return BAT1_VOLTAGE_NOW;
        if (str_ends(path, "/current_now")) return BAT1_CURRENT_NOW;
        if (str_ends(path, "/capacity")) return BAT1_CAPACITY;
        if (str_ends(path, "/capacity_level")) return BAT1_CAPACITY_LEVEL;
        if (str_ends(path, "/charge_full")) return BAT1_CHARGE_FULL;
        if (str_ends(path, "/charge_full_design")) return BAT1_CHARGE_FULL_DESIGN;
        if (str_ends(path, "/charge_now")) return BAT1_CHARGE_NOW;
        if (str_ends(path, "/manufacturer")) return BAT1_MANUFACTURER;
        if (str_ends(path, "/model_name")) return BAT1_MODEL_NAME;
        if (str_ends(path, "/serial_number")) return BAT1_SERIAL;
        if (str_ends(path, "/technology")) return BAT1_TECHNOLOGY;
        if (str_ends(path, "/type")) return BAT1_TYPE;
        if (str_ends(path, "/cycle_count")) return BAT1_CYCLE_COUNT;
    }
    
    // Steam Deck AC adapter
    if (str_contains(path, "/power_supply/ACAD/") || str_contains(path, "/power_supply/AC/")) {
        if (str_ends(path, "/online")) return ACAD_ONLINE;
        if (str_ends(path, "/type")) return ACAD_TYPE;
    }
    
    // Steam Deck backlight (amdgpu_bl1 or amdgpu_bl0)
    if (str_contains(path, "/backlight/amdgpu_bl")) {
        if (str_ends(path, "/brightness")) return BL_BRIGHTNESS;
        if (str_ends(path, "/max_brightness")) return BL_MAX_BRIGHTNESS;
        if (str_ends(path, "/actual_brightness")) return BL_ACTUAL_BRIGHTNESS;
        if (str_ends(path, "/type")) return BL_TYPE;
    }
    
    // Steam Deck hwmon (k10temp)
    if (str_contains(path, "/hwmon/hwmon") && str_contains(path, "k10temp")) {
        if (str_ends(path, "/name")) return HWMON_NAME;
        if (str_ends(path, "/temp1_input")) return HWMON_TEMP1_INPUT;
        if (str_ends(path, "/temp1_max")) return HWMON_TEMP1_MAX;
        if (str_ends(path, "/temp1_crit")) return HWMON_TEMP1_CRIT;
        if (str_ends(path, "/temp1_label")) return HWMON_TEMP1_LABEL;
    }
    // Also match just /hwmon/hwmonX/name for k10temp
    if (str_contains(path, "/hwmon/hwmon") && str_ends(path, "/name")) {
        return HWMON_NAME;
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
    
    // Read machine-id for unique per-container seed
    unsigned int seed = 0;
    FILE* f = real_fopen ? real_fopen("/etc/machine-id", "r") : fopen("/etc/machine-id", "r");
    if (f) {
        char machine_id[64];
        if (fgets(machine_id, sizeof(machine_id), f)) {
            // Hash the machine-id
            for (int i = 0; machine_id[i] && machine_id[i] != '\n'; i++) {
                seed = seed * 31 + (unsigned char)machine_id[i];
            }
        }
        fclose(f);
    }
    
    // Fallback: use PID and time if no machine-id
    if (seed == 0) {
        seed = (unsigned int)(getpid() ^ time(NULL));
    }
    
    srand(seed);
    
    // Select OUI based on seed
    const char* oui = REALTEK_OUIS[rand() % 5];
    
    // Generate unique suffix (last 3 octets)
    snprintf(net_mac_buffer, sizeof(net_mac_buffer), 
             "%s:%02x:%02x:%02x\n",
             oui,
             rand() % 256,
             rand() % 256,
             rand() % 256);
    
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
        // Random base between 15min (900s) and 5hr (18000s)
        srand((unsigned int)(now ^ getpid()));
        uptime_base_seconds = 900 + (rand() % 17100);
    }
    
    // Calculate current uptime: base + elapsed since init
    time_t elapsed = now - uptime_init_time;
    double uptime = (double)(uptime_base_seconds + elapsed);
    
    // Idle time: roughly 30-50% of uptime (user is actively gaming)
    double idle_ratio = 0.30 + ((double)(rand() % 20) / 100.0);
    double idle = uptime * idle_ratio;
    
    // Format: "uptime.xx idle.xx\n"
    snprintf(uptime_buffer, sizeof(uptime_buffer), "%.2f %.2f\n", uptime, idle);
    return uptime_buffer;
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

static int find_tracked(int fd) {
    for (int i = 0; i < tracked_count; i++) {
        if (tracked_fds[i].fd == fd) return i;
    }
    return -1;
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
        if (filtered && tracked_count < MAX_TRACKED_FDS) {
            int null_fd = real_open("/dev/null", O_RDONLY);
            if (null_fd >= 0) {
                size_t flen = 0;
                const char* p = filtered;
                while (*p) {
                    size_t var_len = strlen(p);
                    flen += var_len + 1;
                    p += var_len + 1;
                }
                
                tracked_fds[tracked_count].fd = null_fd;
                tracked_fds[tracked_count].content = filtered;
                tracked_fds[tracked_count].length = flen;
                tracked_fds[tracked_count].offset = 0;
                tracked_fds[tracked_count].is_environ = 1;
                tracked_fds[tracked_count].environ_buf = filtered;
                tracked_count++;
                return null_fd;
            }
        }
        if (filtered) free(filtered);
        return -1;
    }
    
    // Handle cmdline - return fake gamescope launch
    if (is_cmdline_path(path) && !(flags & O_WRONLY)) {
        if (tracked_count < MAX_TRACKED_FDS) {
            int null_fd = real_open("/dev/null", O_RDONLY);
            if (null_fd >= 0) {
                tracked_fds[tracked_count].fd = null_fd;
                tracked_fds[tracked_count].content = PROC_CMDLINE;
                tracked_fds[tracked_count].length = PROC_CMDLINE_LEN;
                tracked_fds[tracked_count].offset = 0;
                tracked_fds[tracked_count].is_environ = 0;
                tracked_fds[tracked_count].environ_buf = NULL;
                tracked_count++;
                return null_fd;
            }
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
        
        if (filtered && tracked_count < MAX_TRACKED_FDS) {
            int null_fd = real_open("/dev/null", O_RDONLY);
            if (null_fd >= 0) {
                tracked_fds[tracked_count].fd = null_fd;
                tracked_fds[tracked_count].content = filtered;
                tracked_fds[tracked_count].length = strlen(filtered);
                tracked_fds[tracked_count].offset = 0;
                tracked_fds[tracked_count].is_environ = 1;  // Mark for cleanup
                tracked_fds[tracked_count].environ_buf = filtered;
                tracked_count++;
                return null_fd;
            }
        }
        if (filtered) free(filtered);
        return -1;
    }
    
    // Check for mapped content
    const char* mapped = get_mapped_content(path);
    if (mapped && !(flags & O_WRONLY)) {
        int null_fd = real_open("/dev/null", O_RDONLY);
        if (null_fd >= 0 && tracked_count < MAX_TRACKED_FDS) {
            tracked_fds[tracked_count].fd = null_fd;
            tracked_fds[tracked_count].content = mapped;
            tracked_fds[tracked_count].length = strlen(mapped);
            tracked_fds[tracked_count].offset = 0;
            tracked_fds[tracked_count].is_environ = 0;
            tracked_fds[tracked_count].environ_buf = NULL;
            tracked_count++;
        }
        return null_fd;
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
    
    int idx = find_tracked(fd);
    if (idx >= 0) {
        size_t remaining = tracked_fds[idx].length - tracked_fds[idx].offset;
        if (remaining == 0) return 0;
        
        size_t to_read = (count < remaining) ? count : remaining;
        memcpy(buf, tracked_fds[idx].content + tracked_fds[idx].offset, to_read);
        tracked_fds[idx].offset += to_read;
        return to_read;
    }
    
    return real_read(fd, buf, count);
}

int close(int fd) {
    static int (*real_close)(int) = NULL;
    if (!real_close) real_close = dlsym(RTLD_NEXT, "close");
    
    int idx = find_tracked(fd);
    if (idx >= 0) {
        // Free environ buffer if allocated
        if (tracked_fds[idx].environ_buf) {
            free(tracked_fds[idx].environ_buf);
        }
        // Remove from tracking
        tracked_fds[idx] = tracked_fds[--tracked_count];
    }
    
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
