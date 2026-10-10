package me.rerere.rikkahub

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppScope : CoroutineScope by CoroutineScope(
    SupervisorJob() + Dispatchers.Main + CoroutineName("AppScope") +
        CoroutineExceptionHandler { _, error -> Log.e("AppScope", "AppScope exception", error) }
)
