package tachiyomi.core.common.util.lang

import rx.Observable
import tachiyomi.core.common.util.lang.awaitSingle as androidAwaitSingle

actual suspend fun <T> Observable<T>.awaitSingle(): T {
    return this.androidAwaitSingle()
}
