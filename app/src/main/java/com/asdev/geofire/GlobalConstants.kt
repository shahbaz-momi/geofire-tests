package com.asdev.geofire

import android.location.Location
import android.location.LocationManager
import com.firebase.geofire.GeoLocation
import com.google.android.gms.maps.model.LatLng

/* Request codes */

const val RC_FIREBASE_AUTH = 39534
const val RC_PERMISSION_LOCATION = 3943

/* Intent extras */
const val EXTRA_KEY_OFFLINE = "is_offline"
const val EXTRA_KEY_USER = "user"

/* Location constants */

const val LIVE_LOCATION_PRIMARY_PROVIDER = LocationManager.NETWORK_PROVIDER
const val LIVE_LOCATION_ALT_PROVIDER = LocationManager.GPS_PROVIDER

/**
 * The current location provider to use.
 */
var locationProvider = LIVE_LOCATION_PRIMARY_PROVIDER

const val LIVE_LOCATION_DISTANCE = 0f // the min distance change for the live location to update
const val LIVE_LOCATION_TIME = 1000L // the min time before location updates

const val DEAD_LOCATION_PROVIDER = LocationManager.GPS_PROVIDER // provider for the last known location

/* Map constants */

const val MAP_MAX_ZOOM = 16.0f // the maximum map zoom
const val MAX_QUERY_DISTANCE = 100000.0 // the maximum query radius, in km

/* Misc constants */

const val TWO_MINUTES = 1000 * 60 * 2
const val TAG = "GeofireTesting" // debug tag
const val MARKER_CACHE_NUM = 100 // the max number of markers to show on screen // TODO: currently ignored
const val QUERY_TIMEOUT = 7000L // the timeout time in ms for a query
const val PERSISTENCE_CACHE_SIZE = 1024L * 1024L * 10L // 10mb of persistence data is allowable

/* Firebase constants */

const val DB_NODE_GAMES = "games"
const val DB_NODE_GAMES_PROTECTED = "games__protected"

const val DB_NODE_USERS = "users"
const val DB_NODE_USERS_PROTECTED = "users__protected"

const val DB_NODE_GEOFIRE = "geofire"

/* Util functions */

fun Location.toLatLng() = LatLng(latitude, longitude)
fun LatLng.toGeoLocation() = GeoLocation(latitude, longitude)
fun GeoLocation.toLatLng() = LatLng(latitude, longitude)