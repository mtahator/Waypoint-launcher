package app.olauncher.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** Injected dispatchers: IO for DB/files, default for parsing, a serial lane for notification capture. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppDispatchers(
    val io: CoroutineDispatcher = Dispatchers.IO,
    val default: CoroutineDispatcher = Dispatchers.Default,
    val capture: CoroutineDispatcher = default.limitedParallelism(1),
)
