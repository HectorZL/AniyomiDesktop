package eu.kanade.tachiyomi.util

import rx.Observable
import tachiyomi.core.common.util.lang.awaitSingle as langAwaitSingle

actual suspend fun <T> Observable<T>.awaitSingle(): T {
    return this.langAwaitSingle()
}
