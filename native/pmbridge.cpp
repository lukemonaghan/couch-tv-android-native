// The thin C++ side of the music visualiser: libprojectM (OpenGL ES 2.0 build, see tools/build-native.sh) behind a few JNI calls.
// Every call except pcm() must run on the GL thread of the Visualiser view (a context must be current).
#include <jni.h>
#include <android/log.h>
#include <GLES2/gl2.h>
#include <projectM-4/projectM.h>
#include <mutex>
#include <string>
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "tvhome-viz", __VA_ARGS__)
static projectm_handle pm = nullptr;
static std::mutex failMutex;
static std::string failedFile;
static void failed(const char* file, const char* msg, void*) {
    LOG("preset failed: %s: %s", file ? file : "?", msg ? msg : "?");
    std::lock_guard<std::mutex> g(failMutex); failedFile = file ? file : "?";
}
extern "C" {
JNIEXPORT jstring JNICALL Java_com_lukemonaghan_tvhome_ProjectM_init(JNIEnv* e, jclass, jstring tex, jint w, jint h, jint fps, jint meshW, jint meshH) {
    const char* r = (const char*) glGetString(GL_RENDERER);
    LOG("GL %s | %s", glGetString(GL_VERSION), r ? r : "?");
    pm = projectm_create();
    if (!pm) return e->NewStringUTF("projectm_create failed");
    const char* t = e->GetStringUTFChars(tex, nullptr);
    const char* paths[1] = { t };
    projectm_set_texture_search_paths(pm, paths, 1);
    e->ReleaseStringUTFChars(tex, t);
    projectm_set_window_size(pm, w, h);
    projectm_set_fps(pm, fps);
    projectm_set_mesh_size(pm, meshW, meshH);
    projectm_set_preset_duration(pm, 86400);       // the app changes presets itself
    projectm_set_soft_cut_duration(pm, 1.5);
    projectm_set_preset_switch_failed_event_callback(pm, failed, nullptr);
    return e->NewStringUTF("ok");
}
JNIEXPORT void JNICALL Java_com_lukemonaghan_tvhome_ProjectM_resize(JNIEnv*, jclass, jint w, jint h) { if (pm) projectm_set_window_size(pm, w, h); }
JNIEXPORT void JNICALL Java_com_lukemonaghan_tvhome_ProjectM_load(JNIEnv* e, jclass, jstring f, jboolean smooth) {
    if (!pm) return;
    const char* s = e->GetStringUTFChars(f, nullptr);
    projectm_load_preset_file(pm, s, smooth);
    e->ReleaseStringUTFChars(f, s);
}
JNIEXPORT void JNICALL Java_com_lukemonaghan_tvhome_ProjectM_pcm(JNIEnv* e, jclass, jfloatArray a, jint n) {
    if (!pm) return;
    jfloat* d = e->GetFloatArrayElements(a, nullptr);
    projectm_pcm_add_float(pm, d, n, PROJECTM_MONO);
    e->ReleaseFloatArrayElements(a, d, JNI_ABORT);
}
JNIEXPORT jint JNICALL Java_com_lukemonaghan_tvhome_ProjectM_frame(JNIEnv*, jclass) {
    if (!pm) return -1;
    projectm_opengl_render_frame(pm);
    return (jint) glGetError();
}
JNIEXPORT jstring JNICALL Java_com_lukemonaghan_tvhome_ProjectM_takeFailed(JNIEnv* e, jclass) {
    std::lock_guard<std::mutex> g(failMutex);
    if (failedFile.empty()) return nullptr;
    jstring s = e->NewStringUTF(failedFile.c_str()); failedFile.clear(); return s;
}
JNIEXPORT void JNICALL Java_com_lukemonaghan_tvhome_ProjectM_destroy(JNIEnv*, jclass) { if (pm) { projectm_destroy(pm); pm = nullptr; } }
}
