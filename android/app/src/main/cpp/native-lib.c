#include <string.h>

#include <jni.h>
#include <getopt.h>
#include <signal.h>
#include <setjmp.h>
#include <stdlib.h>

#include "error.h"
#include "main.h"

// server_fd is declared in byedpi's proxy.c — the listening socket fd.
// shutdown(server_fd, SHUT_RDWR) causes main() to return, stopping the proxy.
extern int server_fd;
static int g_proxy_running = 0;

struct params default_params = {
        .await_int = 10,
        .ipv6 = 1,
        .resolve = 1,
        .udp = 1,
        .max_open = 512,
        .bfsize = 16384,
        .baddr = {
            .in6 = { .sin6_family = AF_INET6 }
        },
        .laddr = {
            .in = { .sin_family = AF_INET }
        },
        .debug = 0
};

void reset_params(void) {
    clear_params(NULL, NULL);
    params = default_params;
}

JNIEXPORT jint JNICALL
Java_top_chelvp_vpn_vpn_ByeDpiProxy_jniStartProxy(JNIEnv *env, jobject thiz, jobjectArray args) {
    if (g_proxy_running) {
        LOG(LOG_S, "byedpi proxy already running");
        return -1;
    }

    int argc = (*env)->GetArrayLength(env, args);
    char **argv = calloc(argc, sizeof(char *));
    if (!argv) {
        LOG(LOG_S, "failed to allocate argv");
        return -1;
    }

    for (int i = 0; i < argc; i++) {
        jstring arg = (jstring) (*env)->GetObjectArrayElement(env, args, i);
        if (!arg) { argv[i] = NULL; continue; }
        const char *s = (*env)->GetStringUTFChars(env, arg, 0);
        argv[i] = s ? strdup(s) : NULL;
        if (s) (*env)->ReleaseStringUTFChars(env, arg, s);
        (*env)->DeleteLocalRef(env, arg);
    }

    LOG(LOG_S, "starting byedpi proxy with %d args", argc);
    reset_params();
    g_proxy_running = 1;
    optind = 1;

    int result = main(argc, argv);

    LOG(LOG_S, "byedpi proxy exited with code %d", result);
    g_proxy_running = 0;

    for (int i = 0; i < argc; i++) free(argv[i]);
    free(argv);

    return result;
}

JNIEXPORT jint JNICALL
Java_top_chelvp_vpn_vpn_ByeDpiProxy_jniStopProxy(JNIEnv *env, jobject thiz) {
    LOG(LOG_S, "stopping byedpi proxy (fd: %d)", server_fd);
    if (!g_proxy_running) {
        LOG(LOG_S, "byedpi proxy is not running");
        return -1;
    }
    shutdown(server_fd, SHUT_RDWR);
    g_proxy_running = 0;
    return 0;
}

JNIEXPORT jint JNICALL
Java_top_chelvp_vpn_vpn_ByeDpiProxy_jniForceClose(JNIEnv *env, jobject thiz) {
    LOG(LOG_S, "force-closing byedpi socket (fd: %d)", server_fd);
    if (close(server_fd) == -1) {
        LOG(LOG_S, "failed to close byedpi socket");
        return -1;
    }
    g_proxy_running = 0;
    return 0;
}
