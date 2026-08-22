// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common.ext

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import io.reactivex.BackpressureStrategy
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

/** Subscribes only while this LiveData has an active observer, and cancels when it doesn't
 *  (standard [LiveData.onActive]/[LiveData.onInactive] lifecycle hooks) — rather than
 *  subscribing once, immediately, and never unsubscribing regardless of whether anything is
 *  still observing it. That used to mean an infinite source (e.g. an `Observable.interval`
 *  ticker) just kept running forever at the process level once created, since nothing held a
 *  handle to cancel it — every `ViewModel.onCleared()` was a no-op against it. All call sites
 *  already tolerate a not-yet-emitted value (either via lifecycle-scoped `.observe()` or an
 *  explicit `?:` default on a direct `.value` read), so subscribing lazily on first observation
 *  rather than eagerly is a safe behavior change, not just an internal implementation detail. */
fun <T> Publisher<T>.toLiveData(): LiveData<T> {
	val publisher = this
	return object : MutableLiveData<T>() {
		private var subscription: Subscription? = null

		override fun onActive() {
			super.onActive()
			publisher.subscribe(object : Subscriber<T> {
				override fun onSubscribe(s: Subscription) {
					subscription = s
					s.request(Long.MAX_VALUE)
				}
				override fun onNext(t: T) {
					postValue(t)
				}
				override fun onError(t: Throwable) {}
				override fun onComplete() {}
			})
		}

		override fun onInactive() {
			super.onInactive()
			subscription?.cancel()
			subscription = null
		}
	}
}

fun <T> Observable<T>.toLiveData() = this.toFlowable(BackpressureStrategy.LATEST).toLiveData()
fun <T> Single<T>.toLiveData() = this.toFlowable().toLiveData()