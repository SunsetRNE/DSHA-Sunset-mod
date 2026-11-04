#include <errno.h>
#ifndef DSHA_PLUGIN_LOCK_TEST
#include <jni.h>
#endif
#include <sys/file.h>
#include <sys/stat.h>

/* 只借用 Java 持有的 FD；不打开路径、不重复 fd、不等待、不保存状态。 */
static int dsha_plugin_try_exclusive(int descriptor) {
    struct stat status;
    if (descriptor < 0) return -EBADF;
    if (fstat(descriptor, &status) != 0) return -errno;
    if (!S_ISREG(status.st_mode)) return -EINVAL;
    if (flock(descriptor, LOCK_EX | LOCK_NB) != 0) return -errno;
    return 0;
}

#ifndef DSHA_PLUGIN_LOCK_TEST
JNIEXPORT jint JNICALL
Java_com_deepseekharness_app_runtime_NativePluginLock_tryExclusive(
        JNIEnv *env, jclass type, jint descriptor) {
    (void) env;
    (void) type;
    return dsha_plugin_try_exclusive(descriptor);
}
#endif
