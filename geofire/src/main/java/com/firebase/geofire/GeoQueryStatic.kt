package com.firebase.geofire

import android.util.Log
import com.firebase.geofire.core.GeoHashQuery
import com.firebase.geofire.util.GeoUtils
import com.google.firebase.database.DatabaseReference
import io.ashdavies.rx.rxfirebase.RxFirebaseDatabase
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.rxkotlin.subscribeBy
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.ReplaySubject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Provides a one-time query that uses RxKotlin for sync and provides cancellable interfaces.
 * @param db a database reference to the geofire node.
 * @param center the center of the search.
 * @param radius the radius of the search, in meters.
 * @param limit the maximum number of results. Use -1 for no limit.
 * @param strictLocationEnforcing whether or not to enforce strict location checking.
 */
class GeoQueryStatic(private val db: DatabaseReference, val center: GeoLocation, val radius: Double, val limit: Int = -1, val strictLocationEnforcing: Boolean = true) : Disposable {

    private val queries = GeoHashQuery.queriesAtLocation(center, radius)
    private var isExecuting = false
    private var hasExecuted = false
    private val disposable = CompositeDisposable()

    private fun locationIsInQuery(location: GeoLocation): Boolean {
        return GeoUtils.distance(location, center) <= this.radius
    }

    /**
     * Executes this query.
     * @param onNext Called when the query finds a location. The first parameter is the key, and the second is the location of the key.
     */
    fun execute(onNext: (String, GeoLocation) -> Unit, onComplete: () -> Unit = {}, onError: (Throwable) -> Unit = {}) {
        // make sure we haven't executed or are executing
        if(isExecuting || hasExecuted)
            return

        hasExecuted = false
        isExecuting = true

        // for each query, execute a new firebase subscription
        val itr = queries.iterator()

        // use a replay behaviour to make sure we catch all events
        val completionSubject = ReplaySubject.create<Boolean>()

        while(itr.hasNext()) {
            val query = itr.next()

            // build a query from the given one
            var fbQuery = db.orderByChild("g").startAt(query.startValue).endAt(query.endValue)
            if(limit > 0)
                fbQuery = fbQuery.limitToFirst(limit)

            // convert to RxKotlin that emit on next events
            val d = RxFirebaseDatabase.getInstance(fbQuery)
                    .onSingleValueEvent()
                    .subscribeOn(Schedulers.io()) // run async
                    .observeOn(AndroidSchedulers.mainThread()) // converge on main thread
                    .subscribeBy (
                            onSuccess = {
                                val children = it.children
                                // parse all children
                                for(c in children) {
                                    // convert the data to locations
                                    val location = GeoFire.getLocationValue(c)
                                    // get the key
                                    val key = c.key
                                    if(locationIsInQuery(location) || !strictLocationEnforcing) {
                                        // call on next listener
                                        onNext(key, location)
                                    }
                                }

                                // add a completion entry (increment count)
                                completionSubject.onNext(true)
                            },

                            onError = {
                                // execute on error
                                onError(it)
                                // cancel all
                                cancel()
                            }
                    )

            disposable.add(d)
        }

        val maxCount = disposable.size()
        val count = AtomicInteger(0)
        val finisher = completionSubject
                .subscribeOn(Schedulers.computation()) // run sequentially
                .subscribe {
                    // check if we hit max count
                    if(count.incrementAndGet() == maxCount) {
                        onComplete()
                        // call cancel to dispose all queries
                        cancel()
                    }
                }

        // add the finisher itself as a disposable
        disposable.add(finisher)
    }

    /**
     * Cancels this query.
     */
    fun cancel() {
        disposable.dispose()
    }

    override fun isDisposed() = disposable.isDisposed

    override fun dispose() = cancel()
}