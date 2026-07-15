package tachiyomi.core.common.util.lang

import rx.Observable

expect suspend fun <T> Observable<T>.awaitSingle(): T
