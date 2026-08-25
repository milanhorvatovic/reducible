package io.github.milanhorvatovic.reducible.runtime

import android.os.Looper

internal actual fun isMainThread(): Boolean = Looper.getMainLooper() === Looper.myLooper()
