/* Linux host fixture：编译当前 dsha-pty.c；JNI 表仅模拟 Java 回调边界，不加载 Android。 */
#include "dsha-pty.h"
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define CHECK(value) do { if (!(value)) { \
    fprintf(stderr, "PTY_HOST_ASSERT line=%d expression=%s errno=%d\n", __LINE__, #value, errno); \
    exit(97); \
} } while (0)

static const char *mode;
static pid_t owned;
static int marker_fd, ack_fd, callbacks, birth_checked, pending_exception;
static char copied_stat[2048];
static int class_token, method_token;

static int is(const char *value) { return strcmp(mode, value) == 0; }

static long long millis(void) {
    struct timespec value;
    CHECK(clock_gettime(CLOCK_MONOTONIC, &value) == 0);
    return value.tv_sec * 1000LL + value.tv_nsec / 1000000;
}

static int ready(int fd, int timeout) {
    struct pollfd item = { .fd = fd, .events = POLLIN };
    int result;
    do { result = poll(&item, 1, timeout); } while (result < 0 && errno == EINTR);
    CHECK(result >= 0);
    return result > 0;
}

/* 独立测试 oracle 只比较 /proc 的出生字段；它不是 NativeProcess 的 Java 接受判据。 */
static unsigned long long birth(const char *text, pid_t expected) {
    int pid;
    CHECK(sscanf(text, "%d (", &pid) == 1 && pid == expected);
    const char *tail = strrchr(text, ')');
    CHECK(tail && tail[1] == ' ');
    char copy[2048];
    CHECK(strlen(tail + 2) < sizeof(copy));
    strcpy(copy, tail + 2);
    char *save = NULL, *field = strtok_r(copy, " ", &save);
    unsigned long long started = 0;
    for (int index = 0; index <= 19; index++) {
        CHECK(field != NULL);
        if (index == 1) CHECK(atoi(field) == getpid());
        if (index == 2 || index == 3) CHECK(atoi(field) == expected);
        if (index == 19) {
            char *end = NULL;
            errno = 0;
            started = strtoull(field, &end, 10);
            CHECK(errno == 0 && end != field && (*end == 0 || *end == '\n') && started > 0);
        }
        field = strtok_r(NULL, " ", &save);
    }
    return started;
}

static void check_birth(const char *reported) {
    char path[64], actual[2048];
    snprintf(path, sizeof(path), "/proc/%d/stat", owned);
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    CHECK(fd >= 0);
    ssize_t count = read(fd, actual, sizeof(actual) - 1);
    CHECK(count > 0);
    close(fd);
    actual[count] = 0;
    CHECK(birth(reported, owned) == birth(actual, owned));
    CHECK(!ready(marker_fd, 0));
    birth_checked++;
}

static jclass JNICALL find_class(JNIEnv *env, const char *name) {
    (void) env;
    CHECK(strcmp(name, "com/deepseekharness/app/runtime/NativeProcess") == 0);
    if (is("class-error")) { pending_exception = 1; return NULL; }
    return (jclass) &class_token;
}

static jmethodID JNICALL find_method(JNIEnv *env, jclass type, const char *name, const char *signature) {
    (void) env;
    CHECK(type == (jclass) &class_token);
    CHECK(strcmp(name, "recordPtyIdentity") == 0);
    CHECK(strcmp(signature, "(ILjava/lang/String;)Z") == 0);
    if (is("method-error")) { pending_exception = 1; return NULL; }
    return (jmethodID) &method_token;
}

static jstring JNICALL new_string(JNIEnv *env, const char *text) {
    (void) env;
    if (is("string-error")) { pending_exception = 1; return NULL; }
    CHECK(strlen(text) < sizeof(copied_stat));
    strcpy(copied_stat, text);
    return (jstring) copied_stat;
}

static jboolean JNICALL record(JNIEnv *env, jclass type, jmethodID method, ...) {
    (void) env;
    CHECK(type == (jclass) &class_token && method == (jmethodID) &method_token);
    va_list args;
    va_start(args, method);
    jint pid = va_arg(args, jint);
    jstring value = va_arg(args, jstring);
    va_end(args);
    CHECK(pid == owned && value == (jstring) copied_stat);
    check_birth(copied_stat);
    callbacks++;
    if (is("reject")) return JNI_FALSE;
    if (is("exception")) pending_exception = 1;
    if (is("ack-write-error")) CHECK(close(ack_fd) == 0);
    return JNI_TRUE;
}

static jboolean JNICALL exception_check(JNIEnv *env) {
    (void) env;
    return pending_exception ? JNI_TRUE : JNI_FALSE;
}

static void JNICALL delete_reference(JNIEnv *env, jobject value) {
    (void) env;
    CHECK(value == (jobject) &class_token || value == (jobject) copied_stat);
}

static const struct JNINativeInterface_ functions = {
    .FindClass = find_class,
    .GetStaticMethodID = find_method,
    .NewStringUTF = new_string,
    .CallStaticBooleanMethod = record,
    .ExceptionCheck = exception_check,
    .DeleteLocalRef = delete_reference,
};

/* 仅限 fixture 的 containment：驱动超时杀掉宿主时，这些未 reap 的测试子进程随父退出。 */
static void contain(pid_t parent) {
    CHECK(prctl(PR_SET_PDEATHSIG, SIGKILL) == 0);
    if (getppid() != parent) _exit(96);
}

static int reap(pid_t pid) {
    int status;
    pid_t result;
    do { result = waitpid(pid, &status, 0); } while (result < 0 && errno == EINTR);
    CHECK(result == pid);
    return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
}

int main(int argc, char **argv) {
    CHECK(argc == 2);
    mode = argv[1];
    CHECK(is("success") || is("reject") || is("exception") || is("class-error")
        || is("method-error") || is("string-error") || is("ack-write-error")
        || is("ack-reject") || is("parent-disconnect") || is("parent-silent")
        || is("child-silent") || is("truncated") || is("oversized"));
    int pair[2], marker[2];
    CHECK(dsha_pty_prepare(pair) == 0);
    CHECK(pipe2(marker, O_CLOEXEC) == 0);
    marker_fd = marker[0];
    ack_fd = pair[0];
    pid_t parent = getpid();
    pid_t sibling = fork();
    CHECK(sibling >= 0);
    if (sibling == 0) {
        contain(parent);
        close(pair[0]); close(pair[1]); close(marker[0]); close(marker[1]);
        for (;;) pause();
    }
    owned = fork();
    CHECK(owned >= 0);
    if (owned == 0) {
        contain(parent);
        close(marker[0]);
        CHECK(dup2(marker[1], STDOUT_FILENO) == STDOUT_FILENO);
        close(marker[1]);
        CHECK(setsid() > 0);
        if (is("child-silent")) {
            close(pair[0]);
            for (;;) pause();
        }
        if (is("truncated") || is("oversized")) {
            close(pair[0]);
            char invalid[4096];
            memset(invalid, 'x', sizeof(invalid));
            size_t length = is("truncated") ? 32 : sizeof(invalid);
            CHECK(send(pair[1], invalid, length, MSG_NOSIGNAL) == (ssize_t) length);
            for (;;) pause();
        }
        dsha_pty_child(pair);
        execl("/bin/sh", "sh", "-c", "printf 'DSHA_PTY_HOST_GUEST\\n'", (char *) NULL);
        _exit(127);
    }
    close(marker[1]);
    // 真正调用父握手前，子端不能越过 dsha_pty_child 进入 exec。
    CHECK(!ready(marker_fd, 100));
    long long started = millis();
    int accepted = 0, child_reaped = 0, guest_started = 0;
    if (is("ack-reject") || is("parent-disconnect") || is("parent-silent")) {
        close(pair[1]);
        CHECK(ready(pair[0], 5000));
        char reported[2048];
        ssize_t count = recv(pair[0], reported, sizeof(reported) - 1, 0);
        CHECK(count > 0);
        reported[count] = 0;
        check_birth(reported);
        if (is("ack-reject")) {
            char rejected = 0;
            CHECK(send(pair[0], &rejected, 1, MSG_NOSIGNAL) == 1);
        }
        if (!is("parent-silent")) close(pair[0]);
        CHECK(reap(owned) == 125);
        child_reaped = 1;
        if (is("parent-silent")) {
            CHECK(millis() - started >= 2000);
            close(pair[0]);
        }
    } else {
        JNIEnv env = &functions;
        accepted = dsha_pty_parent(&env, pair, owned);
        if (is("success")) {
            CHECK(accepted == 1 && callbacks == 1 && birth_checked == 1);
            CHECK(reap(owned) == 0);
            child_reaped = 1;
            CHECK(ready(marker_fd, 2000));
            char output[128];
            ssize_t count = read(marker_fd, output, sizeof(output) - 1);
            CHECK(count > 0);
            output[count] = 0;
            CHECK(strcmp(output, "DSHA_PTY_HOST_GUEST\n") == 0);
            guest_started = 1;
        } else {
            CHECK(accepted == 0);
            int status;
            errno = 0;
            CHECK(waitpid(owned, &status, WNOHANG) == -1 && errno == ECHILD);
            child_reaped = 1;
            if (is("child-silent")) CHECK(millis() - started >= 2000);
        }
    }
    if (!guest_started) {
        CHECK(ready(marker_fd, 2000));
        char byte;
        CHECK(read(marker_fd, &byte, 1) == 0);
    }
    close(marker_fd);
    int sibling_status;
    CHECK(waitpid(sibling, &sibling_status, WNOHANG) == 0);
    CHECK(kill(sibling, SIGTERM) == 0);
    CHECK(reap(sibling) == 128 + SIGTERM);
    printf("{\"case\":\"%s\",\"accepted\":%s,\"callbacks\":%d,\"birthChecked\":%d,"
        "\"guestStarted\":%s,\"childReaped\":%s,\"siblingUnaffected\":true,"
        "\"scope\":\"linux-host-current-pty-c-fake-jni\"}\n",
        mode, accepted ? "true" : "false", callbacks, birth_checked,
        guest_started ? "true" : "false", child_reaped ? "true" : "false");
    return 0;
}
