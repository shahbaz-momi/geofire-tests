package com.asdev.geofire

import android.app.Service
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationProvider
import android.os.Bundle
import android.support.v4.content.PermissionChecker
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.View
import com.asdev.geofire.models.User
import com.asdev.geofire.models.UserProtected
import com.firebase.geofire.GeoFire
import com.firebase.geofire.GeoLocation
import com.firebase.geofire.GeoQueryStatic
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import io.ashdavies.rx.rxfirebase.RxFirebaseDatabase
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.rxkotlin.subscribeBy
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.ReplaySubject
import kotlinx.android.synthetic.main.activity_main.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: RxFirebaseDatabase
    private lateinit var locationManager: LocationManager
    private lateinit var geofire: GeoFire
    private lateinit var geofireNode: DatabaseReference

    /**
     * The [GoogleMap] fragment associated with this activity.
     */
    private var maps: GoogleMap? = null

    /**
     * The user bound with this session.
     */
    private var user: User? = null
    /**
     * The user protected data for the user bound with this session.
     */
    private var userProtected: UserProtected? = null

    /**
     * Running RxKotlin subscriptions to be safely disposed when the
     * activity is stopped.
     */
    private var subscriptions = CompositeDisposable()

    /**
     * Whether or not we have been granted the location permission.
     */
    private var hasLocationPermission = PermissionChecker.PERMISSION_DENIED

    /**
     * Called when the initial stages of init have completed, and postInit() is yet to be
     * called. Used in initStage2() to send the initFinish event.
     */
    private val initFinishSubject = BehaviorSubject.create<Boolean>()

    /**
     * Used to mark a forceful UI reload.
     */
    private var doReload = false

    /**
     * Used to denote whether the reload/init has completed.
     */
    private var hasInited = false

    /**
     * Whether or not to follow live location requests. You should call
     * [updateLocationState] after modifying this value.
     */
    private var followLiveLocation = false

    /**
     * A subject which is activated when the google map is actually gotten.
     */
    private val mapRequestSubject = ReplaySubject.create<GoogleMap>()

    /**
     * The marker on the [GoogleMap] that represents the location of the user.
     */
    private var userLocationMarker: Marker? = null

    /**
     * The current best location.
     */
    private var currentBestLocation: Location? = null

    /**
     * The listener for location events for this activity.
     */
    private val locationListener = object : LocationListener {

        override fun onLocationChanged(location: Location?) {
            Log.d(TAG, "Got location: $location")

            if(location == null)
                return

            // update the current best to that
            if(isBetterLocation(location, currentBestLocation)) {
                currentBestLocation = location
            }

            runOnUiThread {
                currentBestLocation?.let {
                    userLocationMarker?.position = it.toLatLng()
                }

                userLocationMarker?.setIcon(BitmapDescriptorFactory.fromResource(R.drawable.ic_location_live))
            }
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            if(status == LocationProvider.OUT_OF_SERVICE) {
                // continue follow location
                followLiveLocation = true
                // unregister the location listener
                updateLocationState(false)
                // change provider
                if(provider == LIVE_LOCATION_PRIMARY_PROVIDER) {
                    // change to alt
                    locationProvider = LIVE_LOCATION_ALT_PROVIDER
                    // update location state using new provider
                    updateLocationState(true)
                } else {
                    // no more providers, abandon
                    followLiveLocation = false
                    // force a ui update
                    updateLocationState(false)
                }
            }
        }

        override fun onProviderEnabled(provider: String?) {
        }

        override fun onProviderDisabled(provider: String?) {
        }

    }

    private var currentQuery: GeoQueryStatic? = null

    /**
     * A timeout disposable for the [currentQuery], called after [QUERY_TIMEOUT] ms to cancel
     * the current query as it has timed out.
     */
    private var queryTimeoutDisposable: Disposable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        locationManager = getSystemService(Service.LOCATION_SERVICE) as LocationManager

        // init the maps
        val mapFragment = supportFragmentManager
                .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        // init the app again
        FirebaseApp.initializeApp(this)

        auth = FirebaseAuth.getInstance()
        db = RxFirebaseDatabase.getInstance()
        geofireNode = FirebaseDatabase.getInstance().reference.child(DB_NODE_GEOFIRE)
        geofireNode.keepSynced(false) // disable downloading for this node // TODO: this seems to be ignored
        geofire = GeoFire(geofireNode)

        user = intent.getSerializableExtra(EXTRA_KEY_USER) as? User

        // on resume will call reload
    }

    override fun onResume() {
        super.onResume()

        // reload if it hasn't already (don't call markForReload())
        reload()
        // update the location state
        updateLocationState(true)
    }

    override fun onPause() {
        super.onPause()

        // pause location updates if running
        updateLocationState(false)

        // TODO: cancel query
        currentQuery?.cancel()
    }

    override fun onMapReady(map: GoogleMap?) {
        if(map == null) {
            // TODO: map failed
            return
        }

        maps = map

        // disable certain functions
        map.uiSettings.isTiltGesturesEnabled = false
        map.uiSettings.isCompassEnabled = false
        map.uiSettings.isIndoorLevelPickerEnabled = false
        map.uiSettings.isMapToolbarEnabled = false
        map.uiSettings.isRotateGesturesEnabled = false
        map.setMaxZoomPreference(MAP_MAX_ZOOM)
        map.mapType = GoogleMap.MAP_TYPE_NORMAL

        // add the map idle listener to obtain new place lists
        map.setOnCameraIdleListener(this::onMapIdle)

        mapRequestSubject.onNext(map)
        // only one initialization, so we can safely do an on complete
        mapRequestSubject.onComplete()
    }

    private fun showError(msg: String) {
        runOnUiThread {
            layout_error.visibility = View.VISIBLE
            text_error_view.text = msg
        }
    }

    private fun hideError() {
        runOnUiThread {
            layout_error.visibility = View.GONE
        }
    }

    private fun showLoading() {
        runOnUiThread {
            is_loading_view.visibility = View.VISIBLE
            // cancel any errors
            hideError()
        }
    }

    private fun hideLoading() {
        runOnUiThread {
            is_loading_view.visibility = View.GONE
        }
    }

    /**
     * Forces a reload to occur. You should follow this with a
     * [reload] call.
     */
    fun markForReload() {
        doReload = true
    }

    /**
     * Calls a complete reload of the UI if needed. To force a reload, use markForReload().
     *
     * Flow:
     * 1. clearUI()       | MainThread
     * 2. init()          | MainThread | Has calls that run on io scheduler
     * 3. initStage2()    | MainThread
     * 4. initBaseless()  | MainThread | Only if needed
     * 5. postInit()      | MainThread | Waits for the main init() chain to finish and the places loading thread to finish
     */
    fun reload() {
        // trash old subscriptions
        subscriptions.dispose()

        // reset the state of subscriptions
        subscriptions = CompositeDisposable()

        Log.d(TAG, "Reload requested, will execute?: $doReload. Has inited: $hasInited")
        if(!doReload && hasInited) {
            return
        }

        doReload = false
        hasInited = false

        // call post init once the init finish has happened
        subscriptions.add(initFinishSubject.observeOn(AndroidSchedulers.mainThread()).subscribe {
            if(it) {
                Log.d(TAG, "Reload: calling postInit()")
                postInit()
            }
        })

        Log.d(TAG, "Reload: calling clearUi()")
        clearUi()

        // do the basic init on the computation thread
        Log.d(TAG, "Reload: calling init()")
        init()
    }

    /**
     * Clears the current ui state.
     */
    private fun clearUi() {
        // show the loading view
        showLoading()

        resetMap()
    }

    /**
     * Resets the position of the map to the last known location,
     * and binds a location listener.
     */
    private fun resetMap() {
        // request our location and zoom to it
        // zoom to our current location
        hasLocationPermission = PermissionChecker.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)

        if(hasLocationPermission == PermissionChecker.PERMISSION_GRANTED) {
            // already have location permission
            // get our location
            val lastLoc = locationManager.getLastKnownLocation(DEAD_LOCATION_PROVIDER)

            // update the current best to that
            if(isBetterLocation(lastLoc, currentBestLocation)) {
                currentBestLocation = lastLoc
            }

            if(lastLoc != null) {
                val update = CameraUpdateFactory.newLatLngZoom(lastLoc.toLatLng(), 11f)

                // do a map call do animate the camera
                safeMapCall {
                    animateCamera(update)
                    // add a dead location marker for now
                    if(userLocationMarker == null) {
                    userLocationMarker = addMarker(MarkerOptions()
                            .position(lastLoc.toLatLng())
                            .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_location_dead))
                            .title(getString(R.string.label_user_location))
                            .zIndex(99f))
                    } else {
                        userLocationMarker?.let {
                            it.position = lastLoc.toLatLng()
                            it.setIcon(BitmapDescriptorFactory.fromResource(R.drawable.ic_location_dead))
                            it.title = getString(R.string.label_user_location)
                            it.zIndex = 99f
                        }
                    }
                }
            }

            // force location updates
            followLiveLocation = true
            // request updates after
            updateLocationState(true)
        } else {
            // request it
            requestLocationPermission()
        }
    }

    /**
     * Safely performs the given map calls upon this activities local map fragment.
     * @param block The block of code to safely execute.
     */
    private inline fun safeMapCall(crossinline block: GoogleMap.() -> Unit) {
        if(maps != null) {
            maps?.block()
        } else {
            // add a request for later
            subscriptions.add(
                    mapRequestSubject.subscribe {
                        it.block()
                    }
            )
        }
    }

    /**
     * Called to refresh the places/games on the visible map.
     */
    fun onMapIdle() {
        button_search_area.visibility = View.VISIBLE

        // check if has search before
        if(currentQuery == null) {
            // do a search
            refreshMapPlaces()
        }
    }

    /**
     * Called when the map has relocated and settled. Will refetch a list of locations within the center
     * and the bounds of the current view port of the map.
     */
    private fun refreshMapPlaces() {
        button_search_area.visibility = View.GONE
        safeMapCall {
            val cameraPos = cameraPosition

            // use the target to find the center
            val center = cameraPos.target
            // use the visible region to calculate the radius
            val r = projection.visibleRegion

            val width = floatArrayOf(0f)
            Location.distanceBetween(
                    (r.farRight.latitude + r.nearRight.latitude) / 2.0,
                    (r.farRight.longitude + r.nearRight.longitude) / 2.0,
                    (r.farLeft.latitude + r.nearLeft.latitude) / 2.0,
                    (r.farLeft.longitude + r.nearLeft.longitude) / 2.0,
                    width)

            val radius = width[0]

            val query = GeoQueryStatic(geofireNode,
                    center = center.toGeoLocation(),
                    radius = minOf(radius.toDouble(), MAX_QUERY_DISTANCE),
                    strictLocationEnforcing = true)

            // cancel any previous
            currentQuery?.cancel()

            // execute the query
            onQueryPrepare()
            // execute upon our selves
            query.execute(onNext = this@MainActivity::onQueryResult,
                    onComplete = this@MainActivity::onQueryComplete,
                    onError = this@MainActivity::onQueryError)

            subscriptions.add(query)
            currentQuery = query

            // show loading
            showLoading()
            // cancel any previous ones
            queryTimeoutDisposable?.dispose()
            queryTimeoutDisposable = Schedulers.computation().scheduleDirect({ onQueryTimeout() }, QUERY_TIMEOUT, TimeUnit.MILLISECONDS)
            // add to disposables
            subscriptions.add(queryTimeoutDisposable)
        }
    }

    private val markers = HashMap<String, Marker>()
    private fun onQueryPrepare() {
    }

    private fun onQueryResult(key: String, location: GeoLocation) {
        if(markers.containsKey(key)) {
            // change the location
            markers[key]?.position = location.toLatLng()
            return
        }

        // add this to the map
        safeMapCall {
            markers[key] = addMarker(
                    MarkerOptions()
                            .position(location.toLatLng())
                            .title(key)
            )
        }
    }

    private fun onQueryComplete() {
        // cancel the timeout
        queryTimeoutDisposable?.dispose()
        // hide loading
        hideLoading()
    }

    private fun onQueryError(throwable: Throwable) {
        Log.e(TAG, "Got error during query", throwable)
    }

    /**
     * Called when the scheduled query timeout has been reached.
     */
    private fun onQueryTimeout() {
        Log.d(TAG, "Query timed out")

        // dispose this
        // cancel the query
        queryTimeoutDisposable?.dispose()

        currentQuery?.cancel()

        runOnUiThread {
            // cancel the loading view
            hideLoading()
            // show an error
            showError(getString(R.string.text_error_request_timeout))
        }
    }

    /**
     * Updates the live location listening state.
     * @param willBeActive whether or not the activity will continue to be active (in the foreground).
     */
    private fun updateLocationState(willBeActive: Boolean) {
        // TODO: update UI
        Log.d(TAG, "Update location state: followLiveLocation = $followLiveLocation, willBeActive = $willBeActive")

        if(followLiveLocation && willBeActive) {
            // bind the listener if not already
            locationManager.requestLocationUpdates(locationProvider, LIVE_LOCATION_TIME, LIVE_LOCATION_DISTANCE, locationListener)
        } else {
            // release the listener
            locationManager.removeUpdates(locationListener)
        }
    }

    /** Determines whether one Location reading is better than the current Location fix
     * @param location The new Location that you want to evaluate
     * *
     * @param currentBestLocation The current Location fix, to which you want to compare the new one
     */
    private fun isBetterLocation(location: Location, currentBestLocation: Location?): Boolean {
        if (currentBestLocation == null) {
            // A new location is always better than no location
            return true
        }

        // Check whether the new location fix is newer or older
        val timeDelta = location.time - currentBestLocation.time
        val isSignificantlyNewer = timeDelta > TWO_MINUTES
        val isSignificantlyOlder = timeDelta < -TWO_MINUTES
        val isNewer = timeDelta > 0

        // If it's been more than two minutes since the current location, use the new location
        // because the user has likely moved
        if (isSignificantlyNewer) {
            return true
            // If the new location is more than two minutes older, it must be worse
        } else if (isSignificantlyOlder) {
            return false
        }

        // Check whether the new location fix is more or less accurate
        val accuracyDelta = location.accuracy - currentBestLocation.accuracy
        val isLessAccurate = accuracyDelta > 0f
        val isMoreAccurate = accuracyDelta < 0f
        val isSignificantlyLessAccurate = accuracyDelta > 200f

        // Check if the old and new location are from the same provider
        val isFromSameProvider = location.provider == currentBestLocation.provider

        // Determine location quality using a combination of timeliness and accuracy
        if (isMoreAccurate) {
            return true
        } else if (isNewer && !isLessAccurate) {
            return true
        } else if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider) {
            return true
        }
        return false
    }

    /**
     * Retrieves the user protected data granted that the user data has already been retrieved.
     * Then calls the next init step, initStage2().
     */
    private fun init() {
        val uLocal = user

        // make sure we have a user
        if(uLocal != null) {
            // retrieve the user protected data from either online or disk
            subscriptions.add(
                    UserProtected.fetchUserProtectedSubscription(uLocal.uid, db).subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribeBy(
                            onSuccess = {
                                Log.d(TAG, "Got user protected data: $it")

                                if (it.value == null) {
                                    // no data stored, create a blank one
                                    userProtected = UserProtected().apply { uid = uLocal.uid }
                                    // put the blank one
                                    subscriptions.add(userProtected?.commit(db))
                                } else {
                                    // there is existing data
                                    userProtected = it.getValue(UserProtected::class.java)
                                }

                                Log.d(TAG, "Reload: calling initStage2() from successful acquisition")
                                initStage2()
                            },
                            onError = {
                                Log.d(TAG, "Unable to fetch user protected data!")
                                it.printStackTrace()

                                Log.d(TAG, "Reload: calling initStage2() from failed acquisition")
                                initStage2()
                            }
                    )
            )
        } else {
            // plain stage 2 init
            Log.d(TAG, "Reload: calling initStage2() with no user")
            initStage2()
        }
    }

    /**
     * Checks whether this app is running baseless (without a fully qualified user). If it is,
     * calls initBaseless(). After, it marks the completion of the init() chain.
     */
    private fun initStage2() {
        val profile = auth.currentUser

        text_greeting.text = "Hello, ${profile?.displayName} \nProfile: $user\nAuth user: $profile\nUser protected: $userProtected"

        // if no standing user base, call init baseless.
        if(profile == null || user == null || userProtected == null) {
            Log.d(TAG, "Reload: calling initBaseless()")
            initBaseless()
        }

        Log.d(TAG, "Reload: marking init complete")
        initFinishSubject.onNext(true) // mark the init stage as finished
    }

    /**
     * The last step in the init chain that is only called when this app is running
     * baseless (without a fully qualified user). It will try and resolve this critical issue.
     */
    private fun initBaseless() {
        // TODO: implement baseless sign in. Maybe force sign in?
    }

    /**
     * Called after both the init() chain and the places loading chain finish loading, essentially
     * marking the end of this app init.
     */
    private fun postInit() {
        hideLoading()

        // mark init finished LEGIT
        hasInited = true
    }

    /**
     * Disposes and resets any remaining subscriptions, and does a general app cleanup.
     */
    override fun onStop() {
        super.onStop()

        // release any subscriptions
        Log.d(TAG, "Disposing MainActivity subscriptions")
        subscriptions.dispose()

        // reset the subscriptions for next use
        subscriptions = CompositeDisposable()
    }

    /**
     * Begins a user sign out.
     */
    fun actionSignOut(v: View? = null) {
        auth.signOut()
        finish()
    }

    /**
     * Begins a map area search.
     */
    fun actionSearchArea(v: View? = null) {
        refreshMapPlaces()
        button_search_area.visibility = View.GONE
    }

    /**
     * Requests the fine location permission.
     */
    private fun requestLocationPermission() {
        // Location permission has not been granted yet, request it.
        PermissionUtils.requestPermission(this, RC_PERMISSION_LOCATION,
                android.Manifest.permission.ACCESS_FINE_LOCATION, false)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>,
                                            grantResults: IntArray) {
        if (requestCode == RC_PERMISSION_LOCATION) {
            // Enable the My Location button if the permission has been granted.
            if (PermissionUtils.isPermissionGranted(permissions, grantResults,
                    android.Manifest.permission.ACCESS_FINE_LOCATION)) {
                hasLocationPermission = PermissionChecker.PERMISSION_GRANTED
                // reload now that we have the permission
                markForReload()
                reload()
            } else {
                hasLocationPermission = PermissionChecker.PERMISSION_DENIED
            }
        }
    }
}