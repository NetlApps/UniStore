package ru.updatehub.app

import android.app.Activity

/** The Activity currently visible, used to host the invisible download WebView. */
object CurrentActivity {
    @Volatile
    var value: Activity? = null
}
