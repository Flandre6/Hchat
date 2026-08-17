#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdint.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>

#define SIGNAL_COUNT 6
#define ALT_STACK_SIZE (64 * 1024)
#define REPORT_PATH_SIZE 1024
#define PROC_PATH_SIZE 96
#define PROC_LINE_SIZE 1024
#define MAX_NATIVE_FRAMES 32

static const int k_signals[SIGNAL_COUNT] = {
    SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE, SIGSYS
};

static struct sigaction g_previous[SIGNAL_COUNT];
static unsigned char g_previous_valid[SIGNAL_COUNT];
static unsigned char g_alt_stack[ALT_STACK_SIZE] __attribute__((aligned(16)));
static char g_report_path[REPORT_PATH_SIZE];
static int g_report_fd = -1;
static volatile sig_atomic_t g_handling = 0;
static int g_alt_stack_installed = 0;

static void crash_handler(int signal_number, siginfo_t *info, void *context);

static void zero_bytes(void *target, unsigned long size) {
    unsigned char *bytes = (unsigned char *) target;
    unsigned long index;
    for (index = 0; index < size; ++index) bytes[index] = 0;
}

static void copy_bytes(void *target, const void *source, unsigned long size) {
    unsigned char *out = (unsigned char *) target;
    const unsigned char *in = (const unsigned char *) source;
    unsigned long index;
    for (index = 0; index < size; ++index) out[index] = in[index];
}

static unsigned long text_length(const char *text) {
    unsigned long length = 0;
    if (text == 0) return 0;
    while (text[length] != '\0') ++length;
    return length;
}

static void write_bytes(const char *text, unsigned long length) {
    if (g_report_fd < 0 || text == 0) return;
    while (length > 0) {
        long written = write(g_report_fd, text, length);
        if (written <= 0) return;
        text += written;
        length -= (unsigned long) written;
    }
}

static void write_text(const char *text) {
    write_bytes(text, text_length(text));
}

static void write_hex_value(uintptr_t value) {
    static const char digits[] = "0123456789abcdef";
    char output[2 + sizeof(uintptr_t) * 2];
    unsigned long index;
    output[0] = '0';
    output[1] = 'x';
    for (index = 0; index < sizeof(uintptr_t) * 2; ++index) {
        unsigned long shift = (sizeof(uintptr_t) * 2 - index - 1) * 4;
        output[index + 2] = digits[(value >> shift) & 0x0f];
    }
    write_bytes(output, sizeof(output));
}

#if defined(__aarch64__) || defined(__arm__)
static unsigned long divide_unsigned_by_ten(unsigned long value, unsigned int *remainder) {
    unsigned long quotient = 0;
    unsigned long current_remainder = 0;
    unsigned long bit = sizeof(unsigned long) * 8;
    while (bit > 0) {
        --bit;
        current_remainder = (current_remainder << 1) | ((value >> bit) & 1UL);
        if (current_remainder >= 10) {
            current_remainder -= 10;
            quotient |= 1UL << bit;
        }
    }
    if (remainder != 0) *remainder = (unsigned int) current_remainder;
    return quotient;
}

static void write_unsigned_decimal(unsigned long value) {
    char output[3 * sizeof(unsigned long) + 1];
    unsigned long length = 0;
    do {
        unsigned int remainder = 0;
        value = divide_unsigned_by_ten(value, &remainder);
        output[length++] = (char) ('0' + remainder);
    } while (value > 0 && length < sizeof(output));
    while (length > 0) {
        --length;
        write_bytes(&output[length], 1);
    }
}

static void write_signed_decimal(long value) {
    unsigned long magnitude;
    if (value < 0) {
        write_text("-");
        magnitude = (unsigned long) (-(value + 1)) + 1;
    } else {
        magnitude = (unsigned long) value;
    }
    write_unsigned_decimal(magnitude);
}
#endif

static const char *signal_name(int signal_number) {
    switch (signal_number) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGBUS: return "SIGBUS";
        case SIGILL: return "SIGILL";
        case SIGFPE: return "SIGFPE";
        case SIGSYS: return "SIGSYS";
        default: return "UNKNOWN";
    }
}

static const char *signal_code_name(int signal_number, int code) {
    if (code == SI_USER) return "SI_USER";
    if (code == SI_KERNEL) return "SI_KERNEL";
    if (code == SI_QUEUE) return "SI_QUEUE";
    if (code == SI_TIMER) return "SI_TIMER";
    if (code == SI_MESGQ) return "SI_MESGQ";
    if (code == SI_ASYNCIO) return "SI_ASYNCIO";
    if (code == SI_SIGIO) return "SI_SIGIO";
    if (code == SI_TKILL) return "SI_TKILL";
    if (signal_number == SIGSEGV) {
        switch (code) {
            case SEGV_MAPERR: return "SEGV_MAPERR";
            case SEGV_ACCERR: return "SEGV_ACCERR";
            case SEGV_BNDERR: return "SEGV_BNDERR";
            case SEGV_PKUERR: return "SEGV_PKUERR";
            case SEGV_MTEAERR: return "SEGV_MTEAERR";
            case SEGV_MTESERR: return "SEGV_MTESERR";
            default: return "SEGV_UNKNOWN";
        }
    }
    if (signal_number == SIGBUS) {
        switch (code) {
            case BUS_ADRALN: return "BUS_ADRALN";
            case BUS_ADRERR: return "BUS_ADRERR";
            case BUS_OBJERR: return "BUS_OBJERR";
            case BUS_MCEERR_AR: return "BUS_MCEERR_AR";
            case BUS_MCEERR_AO: return "BUS_MCEERR_AO";
            default: return "BUS_UNKNOWN";
        }
    }
    if (signal_number == SIGILL) {
        switch (code) {
            case ILL_ILLOPC: return "ILL_ILLOPC";
            case ILL_ILLOPN: return "ILL_ILLOPN";
            case ILL_ILLADR: return "ILL_ILLADR";
            case ILL_ILLTRP: return "ILL_ILLTRP";
            case ILL_PRVOPC: return "ILL_PRVOPC";
            case ILL_PRVREG: return "ILL_PRVREG";
            case ILL_COPROC: return "ILL_COPROC";
            case ILL_BADSTK: return "ILL_BADSTK";
            case ILL_BADIADDR: return "ILL_BADIADDR";
            default: return "ILL_UNKNOWN";
        }
    }
    if (signal_number == SIGFPE) {
        switch (code) {
            case FPE_INTDIV: return "FPE_INTDIV";
            case FPE_INTOVF: return "FPE_INTOVF";
            case FPE_FLTDIV: return "FPE_FLTDIV";
            case FPE_FLTOVF: return "FPE_FLTOVF";
            case FPE_FLTUND: return "FPE_FLTUND";
            case FPE_FLTRES: return "FPE_FLTRES";
            case FPE_FLTINV: return "FPE_FLTINV";
            case FPE_FLTSUB: return "FPE_FLTSUB";
            default: return "FPE_UNKNOWN";
        }
    }
    if (signal_number == SIGSYS && code == SYS_SECCOMP) return "SYS_SECCOMP";
    return "UNKNOWN";
}

static int signal_index(int signal_number) {
    int index;
    for (index = 0; index < SIGNAL_COUNT; ++index) {
        if (k_signals[index] == signal_number) return index;
    }
    return -1;
}

static int is_our_handler(const struct sigaction *action) {
    return action != 0 &&
        (action->sa_flags & SA_SIGINFO) != 0 &&
        action->sa_sigaction == crash_handler;
}

static void write_key_hex(const char *key, uintptr_t value) {
    write_text(key);
    write_hex_value(value);
    write_text("\n");
}

static void write_key_signed(const char *key, long value) {
    write_text(key);
    write_signed_decimal(value);
    write_text("\n");
}

static void write_key_unsigned(const char *key, unsigned long value) {
    write_text(key);
    write_unsigned_decimal(value);
    write_text("\n");
}

static unsigned long append_text(char *output, unsigned long capacity, unsigned long length, const char *text) {
    unsigned long index = 0;
    if (output == 0 || text == 0 || capacity == 0) return length;
    while (text[index] != '\0' && length + 1 < capacity) {
        output[length++] = text[index++];
    }
    output[length] = '\0';
    return length;
}

static unsigned long append_unsigned_decimal(
    char *output,
    unsigned long capacity,
    unsigned long length,
    unsigned long value
) {
    char reversed[3 * sizeof(unsigned long) + 1];
    unsigned long digits = 0;
    do {
        unsigned int remainder = 0;
        value = divide_unsigned_by_ten(value, &remainder);
        reversed[digits++] = (char) ('0' + remainder);
    } while (value > 0 && digits < sizeof(reversed));
    while (digits > 0 && length + 1 < capacity) {
        output[length++] = reversed[--digits];
    }
    output[length] = '\0';
    return length;
}

static long read_proc_line(int fd, char *line, unsigned long capacity) {
    unsigned long length = 0;
    int saw_data = 0;
    int overflow = 0;
    char value;
    if (line == 0 || capacity < 2) return -1;
    for (;;) {
        long count = read(fd, &value, 1);
        if (count <= 0) break;
        saw_data = 1;
        if (value == '\n') break;
        if (length + 1 < capacity) {
            line[length++] = value;
        } else {
            overflow = 1;
        }
    }
    line[length] = '\0';
    if (!saw_data && length == 0) return -1;
    return overflow ? -2 : (long) length;
}

static int hex_digit_value(char value) {
    if (value >= '0' && value <= '9') return value - '0';
    if (value >= 'a' && value <= 'f') return value - 'a' + 10;
    if (value >= 'A' && value <= 'F') return value - 'A' + 10;
    return -1;
}

static int parse_hex_token(const char **cursor, uintptr_t *value) {
    const char *current = cursor != 0 ? *cursor : 0;
    uintptr_t parsed = 0;
    int digits = 0;
    if (current == 0 || value == 0) return 0;
    for (;;) {
        int digit = hex_digit_value(*current);
        if (digit < 0) break;
        parsed = (parsed << 4) | (uintptr_t) digit;
        ++current;
        ++digits;
    }
    if (digits == 0) return 0;
    *cursor = current;
    *value = parsed;
    return 1;
}

static const char *skip_spaces(const char *cursor) {
    while (cursor != 0 && (*cursor == ' ' || *cursor == '\t')) ++cursor;
    return cursor;
}

static const char *skip_token(const char *cursor) {
    while (cursor != 0 && *cursor != '\0' && *cursor != ' ' && *cursor != '\t') ++cursor;
    return cursor;
}

static int parse_mapping_line(
    const char *line,
    uintptr_t *start,
    uintptr_t *end,
    uintptr_t *file_offset,
    int *readable,
    int *executable,
    const char **path
) {
    const char *cursor = line;
    if (!parse_hex_token(&cursor, start) || *cursor != '-') return 0;
    ++cursor;
    if (!parse_hex_token(&cursor, end)) return 0;
    cursor = skip_spaces(cursor);
    if (cursor == 0 || *cursor == '\0') return 0;
    *readable = *cursor == 'r';
    *executable = cursor[0] != '\0' && cursor[1] != '\0' && cursor[2] == 'x';
    cursor = skip_token(cursor);
    cursor = skip_spaces(cursor);
    if (!parse_hex_token(&cursor, file_offset)) return 0;
    cursor = skip_spaces(skip_token(skip_spaces(cursor)));
    cursor = skip_spaces(skip_token(cursor));
    *path = skip_spaces(cursor);
    return *start < *end;
}

#if defined(__aarch64__)
static int find_readable_mapping(uintptr_t address, uintptr_t *start, uintptr_t *end) {
    int fd = open("/proc/self/maps", O_RDONLY | O_CLOEXEC);
    char line[PROC_LINE_SIZE];
    if (fd < 0) return 0;
    for (;;) {
        long length = read_proc_line(fd, line, sizeof(line));
        if (length == -1) break;
        if (length == -2) continue;
        uintptr_t map_start = 0;
        uintptr_t map_end = 0;
        uintptr_t file_offset = 0;
        int readable = 0;
        int executable = 0;
        const char *path = 0;
        if (parse_mapping_line(
                line,
                &map_start,
                &map_end,
                &file_offset,
                &readable,
                &executable,
                &path
            ) &&
            readable && address >= map_start && address < map_end) {
            *start = map_start;
            *end = map_end;
            close(fd);
            return 1;
        }
    }
    close(fd);
    return 0;
}
#endif

static void write_address_mapping(const char *label, uintptr_t address) {
    int fd = open("/proc/self/maps", O_RDONLY | O_CLOEXEC);
    char line[PROC_LINE_SIZE];
    int found = 0;
    if (fd >= 0) {
        for (;;) {
            long length = read_proc_line(fd, line, sizeof(line));
            if (length == -1) break;
            if (length == -2) continue;
            uintptr_t map_start = 0;
            uintptr_t map_end = 0;
            uintptr_t file_offset = 0;
            int readable = 0;
            int executable = 0;
            const char *path = 0;
            if (!parse_mapping_line(
                    line,
                    &map_start,
                    &map_end,
                    &file_offset,
                    &readable,
                    &executable,
                    &path
                ) || address < map_start || address >= map_end) {
                continue;
            }
            write_text(label);
            write_text("_module=");
            write_text(path != 0 && *path != '\0' ? path : "[anonymous]");
            write_text("\n");
            write_text(label);
            write_text("_module_offset=");
            write_hex_value(file_offset + (address - map_start));
            write_text("\n");
            write_text(label);
            write_text("_mapping=");
            write_bytes(line, (unsigned long) length);
            write_text("\n");
            found = 1;
            break;
        }
        close(fd);
    }
    if (!found) {
        write_text(label);
        write_text("_module=<unmapped>\n");
    }
}

static void write_executable_mappings(void) {
    int fd = open("/proc/self/maps", O_RDONLY | O_CLOEXEC);
    char line[PROC_LINE_SIZE];
    if (fd < 0) return;
    write_text("executable_mappings_begin\n");
    for (;;) {
        long length = read_proc_line(fd, line, sizeof(line));
        if (length == -1) break;
        if (length == -2) continue;
        uintptr_t map_start = 0;
        uintptr_t map_end = 0;
        uintptr_t file_offset = 0;
        int readable = 0;
        int executable = 0;
        const char *path = 0;
        if (parse_mapping_line(
                line,
                &map_start,
                &map_end,
                &file_offset,
                &readable,
                &executable,
                &path
            ) && executable) {
            write_bytes(line, (unsigned long) length);
            write_text("\n");
        }
    }
    write_text("executable_mappings_end\n");
    close(fd);
}

static void write_current_thread_name(unsigned long tid) {
    char path[PROC_PATH_SIZE];
    char name[128];
    unsigned long path_length = 0;
    path[0] = '\0';
    path_length = append_text(path, sizeof(path), path_length, "/proc/self/task/");
    path_length = append_unsigned_decimal(path, sizeof(path), path_length, tid);
    append_text(path, sizeof(path), path_length, "/comm");
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    long length = read(fd, name, sizeof(name) - 1);
    close(fd);
    if (length <= 0) return;
    while (length > 0 && (name[length - 1] == '\n' || name[length - 1] == '\r' || name[length - 1] == '\0')) {
        --length;
    }
    if (length <= 0) return;
    name[length] = '\0';
    write_text("thread_name=");
    write_bytes(name, (unsigned long) length);
    write_text("\n");
}

static uintptr_t context_program_counter(void *raw_context) {
    if (raw_context == 0) return 0;
    ucontext_t *context = (ucontext_t *) raw_context;
#if defined(__aarch64__)
    return (uintptr_t) context->uc_mcontext.pc;
#elif defined(__arm__)
    return (uintptr_t) context->uc_mcontext.arm_pc;
#else
    return 0;
#endif
}

static uintptr_t context_link_register(void *raw_context) {
    if (raw_context == 0) return 0;
    ucontext_t *context = (ucontext_t *) raw_context;
#if defined(__aarch64__)
    return (uintptr_t) context->uc_mcontext.regs[30];
#elif defined(__arm__)
    return (uintptr_t) context->uc_mcontext.arm_lr;
#else
    return 0;
#endif
}

static void write_native_frame(unsigned long index, uintptr_t address) {
    write_text("native_frame_");
    write_unsigned_decimal(index);
    write_text("=");
    write_hex_value(address);
    write_text("\n");
}

static void write_native_frames(void *raw_context) {
    uintptr_t pc = context_program_counter(raw_context);
    uintptr_t lr = context_link_register(raw_context);
    unsigned long count = 0;
#if defined(__aarch64__)
    write_text("native_backtrace_source=frame_pointer_best_effort\n");
#else
    write_text("native_backtrace_source=pc_lr_only\n");
#endif
    if (pc != 0) write_native_frame(count++, pc);
    if (lr != 0 && lr != pc) write_native_frame(count++, lr);
#if defined(__aarch64__)
    if (raw_context != 0 && count < MAX_NATIVE_FRAMES) {
        ucontext_t *context = (ucontext_t *) raw_context;
        uintptr_t frame = (uintptr_t) context->uc_mcontext.regs[29];
        uintptr_t map_start = 0;
        uintptr_t map_end = 0;
        if (frame != 0 && (frame & 0x0f) == 0 && find_readable_mapping(frame, &map_start, &map_end)) {
            while (count < MAX_NATIVE_FRAMES && frame >= map_start &&
                   frame <= map_end - 2 * sizeof(uintptr_t)) {
                volatile uintptr_t *words = (volatile uintptr_t *) frame;
                uintptr_t next_frame = words[0];
                uintptr_t return_address = words[1];
                if (return_address == 0) break;
                if (return_address != lr) write_native_frame(count++, return_address);
                if (next_frame <= frame || next_frame > map_end - 2 * sizeof(uintptr_t) ||
                    (next_frame & 0x0f) != 0) {
                    break;
                }
                frame = next_frame;
            }
        }
    }
#endif
    write_key_unsigned("native_frame_count=", count);
}

static void write_registers(void *raw_context) {
    if (raw_context == 0) return;
    ucontext_t *context = (ucontext_t *) raw_context;
#if defined(__aarch64__)
    write_text("abi=arm64-v8a\n");
    write_key_hex("pc=", (uintptr_t) context->uc_mcontext.pc);
    write_key_hex("sp=", (uintptr_t) context->uc_mcontext.sp);
    write_key_hex("lr=", (uintptr_t) context->uc_mcontext.regs[30]);
    write_key_hex("pstate=", (uintptr_t) context->uc_mcontext.pstate);
    int index;
    for (index = 0; index < 30; ++index) {
        write_text("x");
        write_unsigned_decimal((unsigned int) index);
        write_text("=");
        write_hex_value((uintptr_t) context->uc_mcontext.regs[index]);
        write_text("\n");
    }
#elif defined(__arm__)
    write_text("abi=armeabi-v7a\n");
    write_key_hex("pc=", (uintptr_t) context->uc_mcontext.arm_pc);
    write_key_hex("sp=", (uintptr_t) context->uc_mcontext.arm_sp);
    write_key_hex("lr=", (uintptr_t) context->uc_mcontext.arm_lr);
    write_key_hex("fp=", (uintptr_t) context->uc_mcontext.arm_fp);
    write_key_hex("r0=", (uintptr_t) context->uc_mcontext.arm_r0);
    write_key_hex("r1=", (uintptr_t) context->uc_mcontext.arm_r1);
    write_key_hex("r2=", (uintptr_t) context->uc_mcontext.arm_r2);
    write_key_hex("r3=", (uintptr_t) context->uc_mcontext.arm_r3);
    write_key_hex("r4=", (uintptr_t) context->uc_mcontext.arm_r4);
    write_key_hex("r5=", (uintptr_t) context->uc_mcontext.arm_r5);
    write_key_hex("r6=", (uintptr_t) context->uc_mcontext.arm_r6);
    write_key_hex("r7=", (uintptr_t) context->uc_mcontext.arm_r7);
    write_key_hex("r8=", (uintptr_t) context->uc_mcontext.arm_r8);
    write_key_hex("r9=", (uintptr_t) context->uc_mcontext.arm_r9);
    write_key_hex("r10=", (uintptr_t) context->uc_mcontext.arm_r10);
#endif
}

static void write_crash_record(int signal_number, siginfo_t *info, void *context) {
    long tid = syscall(__NR_gettid);
    int signal_code = info != 0 ? info->si_code : 0;
    write_text("hchat_native_crash=1\n");
    write_text("signal=");
    write_text(signal_name(signal_number));
    write_text("\n");
    write_key_signed("signal_number=", signal_number);
    write_text("signal_code=");
    write_signed_decimal(signal_code);
    write_text(" (");
    write_text(signal_code_name(signal_number, signal_code));
    write_text(")\n");
    write_key_signed("signal_errno=", info != 0 ? info->si_errno : 0);
    if (signal_number == SIGSEGV || signal_number == SIGBUS ||
        signal_number == SIGILL || signal_number == SIGFPE) {
        write_key_hex("fault_address=", (uintptr_t) (info != 0 ? info->si_addr : 0));
    } else {
        write_text("fault_address=<not-applicable>\n");
    }
    write_key_signed("pid=", getpid());
    write_key_signed("tid=", tid);
    if (tid > 0) write_current_thread_name((unsigned long) tid);
    if (info != 0 && signal_code <= 0) {
        write_key_signed("sender_pid=", info->si_pid);
        write_key_unsigned("sender_uid=", info->si_uid);
    }
    if (info != 0 && signal_number == SIGSYS) {
        write_key_hex("syscall_address=", (uintptr_t) info->si_call_addr);
        write_key_signed("syscall_number=", info->si_syscall);
        write_key_hex("syscall_arch=", (uintptr_t) info->si_arch);
    }
    write_registers(context);
    uintptr_t pc = context_program_counter(context);
    uintptr_t lr = context_link_register(context);
    if (pc != 0) write_address_mapping("pc", pc);
    if (lr != 0) write_address_mapping("lr", lr);
    write_native_frames(context);
    write_executable_mappings();
    fsync(g_report_fd);
}

static void forward_to_previous(int signal_number, siginfo_t *info, void *context) {
    int index = signal_index(signal_number);
    struct sigaction previous;
    zero_bytes(&previous, sizeof(previous));
    previous.sa_handler = SIG_DFL;
    sigemptyset(&previous.sa_mask);
    if (index >= 0 && g_previous_valid[index]) {
        copy_bytes(&previous, &g_previous[index], sizeof(previous));
    }
    sigaction(signal_number, &previous, 0);

    sigset_t original_mask;
    int mask_saved = sigprocmask(SIG_BLOCK, &previous.sa_mask, &original_mask) == 0;
    if ((previous.sa_flags & SA_NODEFER) != 0) {
        sigset_t signal_mask;
        sigemptyset(&signal_mask);
        sigaddset(&signal_mask, signal_number);
        sigprocmask(SIG_UNBLOCK, &signal_mask, 0);
    }
    if ((previous.sa_flags & SA_RESETHAND) != 0) {
        struct sigaction default_action;
        zero_bytes(&default_action, sizeof(default_action));
        default_action.sa_handler = SIG_DFL;
        sigemptyset(&default_action.sa_mask);
        sigaction(signal_number, &default_action, 0);
    }

    if ((previous.sa_flags & SA_SIGINFO) != 0) {
        if (previous.sa_sigaction != 0 &&
            previous.sa_handler != SIG_DFL &&
            previous.sa_handler != SIG_IGN &&
            previous.sa_sigaction != crash_handler) {
            previous.sa_sigaction(signal_number, info, context);
            if (mask_saved) sigprocmask(SIG_SETMASK, &original_mask, 0);
            return;
        }
    } else if (previous.sa_handler != SIG_DFL && previous.sa_handler != SIG_IGN && previous.sa_handler != 0) {
        previous.sa_handler(signal_number);
        if (mask_saved) sigprocmask(SIG_SETMASK, &original_mask, 0);
        return;
    } else if (previous.sa_handler == SIG_IGN) {
        if (mask_saved) sigprocmask(SIG_SETMASK, &original_mask, 0);
        return;
    }

    if (mask_saved) sigprocmask(SIG_SETMASK, &original_mask, 0);

    sigset_t unblocked;
    sigemptyset(&unblocked);
    sigaddset(&unblocked, signal_number);
    sigprocmask(SIG_UNBLOCK, &unblocked, 0);
    syscall(__NR_tgkill, getpid(), syscall(__NR_gettid), signal_number);
    _exit(128 + signal_number);
}

static void crash_handler(int signal_number, siginfo_t *info, void *context) {
    if (!g_handling) {
        g_handling = 1;
        write_crash_record(signal_number, info, context);
    }
    forward_to_previous(signal_number, info, context);
}

static int install_alt_stack(void) {
    if (g_alt_stack_installed) return 1;
    stack_t current;
    zero_bytes(&current, sizeof(current));
    if (sigaltstack(0, &current) != 0) return 0;
    if ((current.ss_flags & SS_DISABLE) == 0) {
        g_alt_stack_installed = 1;
        return 1;
    }
    stack_t stack;
    zero_bytes(&stack, sizeof(stack));
    stack.ss_sp = g_alt_stack;
    stack.ss_size = sizeof(g_alt_stack);
    if (sigaltstack(&stack, 0) != 0) return 0;
    g_alt_stack_installed = 1;
    return 1;
}

static int open_report_file(void) {
    if (g_report_fd >= 0) close(g_report_fd);
    g_report_fd = open(g_report_path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    return g_report_fd >= 0;
}

JNIEXPORT jboolean JNICALL
Java_h_Hchat_crash_NativeCrashBridge_nativeInstall(JNIEnv *env, jclass clazz, jstring report_path) {
    (void) clazz;
    if (env == 0 || report_path == 0) return JNI_FALSE;
    const char *path = (*env)->GetStringUTFChars(env, report_path, 0);
    if (path == 0) return JNI_FALSE;
    unsigned long index = 0;
    while (path[index] != '\0' && index + 1 < REPORT_PATH_SIZE) {
        g_report_path[index] = path[index];
        ++index;
    }
    g_report_path[index] = '\0';
    (*env)->ReleaseStringUTFChars(env, report_path, path);
    if (index == 0) return JNI_FALSE;

    int already_installed = 0;
    int signal_index_value;
    for (signal_index_value = 0; signal_index_value < SIGNAL_COUNT; ++signal_index_value) {
        struct sigaction current;
        zero_bytes(&current, sizeof(current));
        if (sigaction(k_signals[signal_index_value], 0, &current) == 0 && is_our_handler(&current)) {
            ++already_installed;
        }
    }
    if (already_installed == SIGNAL_COUNT) return JNI_TRUE;
    if (!open_report_file() || !install_alt_stack()) return JNI_FALSE;

    struct sigaction action;
    zero_bytes(&action, sizeof(action));
    sigemptyset(&action.sa_mask);
    action.sa_sigaction = crash_handler;
    action.sa_flags = SA_SIGINFO | SA_ONSTACK | SA_RESTART;

    int installed_count = 0;
    for (signal_index_value = 0; signal_index_value < SIGNAL_COUNT; ++signal_index_value) {
        struct sigaction current;
        zero_bytes(&current, sizeof(current));
        if (sigaction(k_signals[signal_index_value], 0, &current) != 0) continue;
        if (!is_our_handler(&current)) {
            copy_bytes(&g_previous[signal_index_value], &current, sizeof(current));
            g_previous_valid[signal_index_value] = 1;
            struct sigaction install_action;
            copy_bytes(&install_action, &action, sizeof(install_action));
#if defined(SA_EXPOSE_TAGBITS)
            install_action.sa_flags |= current.sa_flags & SA_EXPOSE_TAGBITS;
#endif
            if (sigaction(k_signals[signal_index_value], &install_action, 0) != 0) continue;
        }
        ++installed_count;
    }
    g_handling = 0;
    return installed_count == SIGNAL_COUNT ? JNI_TRUE : JNI_FALSE;
}
