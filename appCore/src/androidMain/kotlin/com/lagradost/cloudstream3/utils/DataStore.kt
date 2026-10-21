package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.SharedPreferences

const val PREFERENCES_NAME = "rebuild_preference"

/** Where the CloudStream app keeps values extensions save; the same preferences file name. */
object DataStore {
    fun Context.getSharedPrefs(): SharedPreferences = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun Context.getFolderName(folder: String, path: String): String = "$folder/$path"
}
