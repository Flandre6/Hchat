typedef unsigned long size_t;
typedef long ssize_t;

int open(const char *path, int flags, ...) { (void) path; (void) flags; return -1; }
ssize_t write(int fd, const void *data, size_t size) { (void) fd; (void) data; (void) size; return -1; }
ssize_t read(int fd, void *data, size_t size) { (void) fd; (void) data; (void) size; return -1; }
int fsync(int fd) { (void) fd; return -1; }
int close(int fd) { (void) fd; return -1; }
int sigaction(int signal_number, const void *action, void *old_action) {
    (void) signal_number; (void) action; (void) old_action; return -1;
}
int sigaltstack(const void *stack, void *old_stack) { (void) stack; (void) old_stack; return -1; }
int sigemptyset(void *set) { (void) set; return -1; }
int sigaddset(void *set, int signal_number) { (void) set; (void) signal_number; return -1; }
int sigprocmask(int how, const void *set, void *old_set) { (void) how; (void) set; (void) old_set; return -1; }
int getpid(void) { return -1; }
long syscall(long number, ...) { (void) number; return -1; }
void _exit(int status) { (void) status; for (;;) {} }
