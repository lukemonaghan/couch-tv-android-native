package com.lukemonaghan.tvhome

/**
 * projectM, the open-source MilkDrop player (LGPL-2.1, see NOTICE.md), through native/pmbridge.cpp. Built by tools/build-native.sh
 * for OpenGL ES 2.0, which is all some TVs have (the Mali-470 ones). If the libraries are not in the app, [available] is false
 * and the music view keeps its blurred cover. Every call except [pcm] belongs on the GL thread of [Visualiser].
 */
object ProjectM {
    val available: Boolean by lazy {
        runCatching { System.loadLibrary("c++_shared"); System.loadLibrary("projectM-4"); System.loadLibrary("pmbridge"); true }.getOrDefault(false)
    }
    @JvmStatic external fun init(textures: String, w: Int, h: Int, fps: Int, meshW: Int, meshH: Int): String
    @JvmStatic external fun resize(w: Int, h: Int)
    @JvmStatic external fun load(file: String, smooth: Boolean)
    @JvmStatic external fun pcm(samples: FloatArray, count: Int)
    @JvmStatic external fun frame(): Int
    @JvmStatic external fun takeFailed(): String?
    @JvmStatic external fun destroy()
}
