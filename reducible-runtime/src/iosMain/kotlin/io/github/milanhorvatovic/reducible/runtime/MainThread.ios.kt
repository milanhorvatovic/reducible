package io.github.milanhorvatovic.reducible.runtime

import platform.Foundation.NSThread

internal actual fun isMainThread(): Boolean = NSThread.isMainThread()
