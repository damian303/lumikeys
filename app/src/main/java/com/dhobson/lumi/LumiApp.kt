package com.dhobson.lumi

import android.app.Application

class LumiApp : Application() {
    /** Created on first use (from the main thread, by the first activity). */
    val session: Session by lazy(LazyThreadSafetyMode.NONE) { Session(this) }
}
