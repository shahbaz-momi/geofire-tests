package com.asdev.geofire

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.asdev.geofire.models.User
import com.firebase.ui.auth.AuthUI
import com.firebase.ui.auth.ErrorCodes
import com.firebase.ui.auth.IdpResponse
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.FirebaseDatabase
import io.ashdavies.rx.rxfirebase.RxFirebaseDatabase
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.subscribeBy
import io.reactivex.schedulers.Schedulers
import java.util.concurrent.TimeUnit

class LauncherActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)

        // init firebase
        FirebaseApp.initializeApp(this)

        val vanillaDb = FirebaseDatabase.getInstance()
        try {
            vanillaDb.setPersistenceCacheSizeBytes(PERSISTENCE_CACHE_SIZE)
            vanillaDb.setPersistenceEnabled(true) // TODO: enable persistence?
        } catch (e: Exception) { e.printStackTrace() } // we can safely ignore any persistence exceptions

        // check for sign in status
        val auth = FirebaseAuth.getInstance()

        val user = auth.currentUser

        if(user != null) {
            // register user if not already, and launch the main activity
            registerUser()
        } else {
            // launch the sign in AuthUi activity
            launchSignIn()
        }
    }

    private var hasLaunchedMain = false

    /**
     * Launches the main activity with the given user and offline status.
     */
    private fun launchMainActivity(isOffline: Boolean, user: User?) {
        // cancel any subscriptions
        subscriptions.dispose()

        if(hasLaunchedMain) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask()
            }

            finish()
            return
        }

        // mark that it has launched the main
        hasLaunchedMain = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        }

        finish()
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_KEY_OFFLINE, isOffline)
            putExtra(EXTRA_KEY_USER, user)
        })
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /**
     * Launches the firebase authentication activity.
     */
    private fun launchSignIn() {
        // build a list of providers
        val providers = listOf(AuthUI.IdpConfig.Builder(AuthUI.EMAIL_PROVIDER).build(),
                AuthUI.IdpConfig.Builder(AuthUI.GOOGLE_PROVIDER).build())

        // start the activity for result
        startActivityForResult(
                AuthUI.getInstance().createSignInIntentBuilder()
                        .setLogo(R.mipmap.ic_launcher)
                        .setProviders(providers)
                        .setIsSmartLockEnabled(false) // TODO: smart lock for development
                        .build(),
                RC_FIREBASE_AUTH
        )
    }

    /**
     * Launches the profile activity (only for new users) for the given authenticated firebase
     * user.
     */
    private fun launchProfileActivity(authUser: FirebaseUser) {
        // remove any subscriptions
        subscriptions.dispose()

        // check if already launched
        if(hasLaunchedMain) {
            return
        }

        hasLaunchedMain = true

        // create a new user object
        val user = User(authUser.uid, authUser.displayName?: "")

        finish()
        startActivity(Intent(this, ProfileActivity::class.java).apply { putExtra(EXTRA_KEY_USER, user) })
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // subscriptions that have to be released on
    private var subscriptions = CompositeDisposable()

    /**
     * Checks whether this user is registered or not. If it is, launches the main activity.
     * If it isn't, launches the profile activity for registration.
     */
    private fun registerUser() {
        val user = FirebaseAuth.getInstance().currentUser

        if(user != null) {
            // get an instance of the db
            val db = RxFirebaseDatabase.getInstance()

            subscriptions.add(User.fetchUserSubscription(user.uid, db)
                    .subscribeBy(
                            onSuccess = {
                                data ->

                                Log.d(TAG, "Successfully got the user entry: $data")

                                if(data.value == null) {
                                    // launch the profile builder
                                    launchProfileActivity(user)
                                } else {
                                    launchMainActivity(false, data.getValue(User::class.java))
                                }
                            },
                            onError = {
                                launchMainActivity(true, null)
                                Log.e(TAG, "Unable to get user profile!", it)
                            }))

            // add a timeout subscription that forces
            // the main activity launch offline
            subscriptions.add(Schedulers.computation()
                    .scheduleDirect({ launchMainActivity(true, null) }
                            , 2000L, TimeUnit.MILLISECONDS))
        } else {
            launchMainActivity(true, null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if(requestCode == RC_FIREBASE_AUTH) {
            val response = IdpResponse.fromResultIntent(data)
            // check the auth status
            if(resultCode == Activity.RESULT_OK) {
                // make sure the user is registered in the database
                registerUser()
            } else {
                // find the error
                if(response == null) {
                    // user pressed back button
                    Toast.makeText(this, R.string.firebase_sign_in_cancelled, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this,
                            when(response.errorCode) {
                                ErrorCodes.NO_NETWORK -> R.string.firebase_sign_in_no_network
                                else -> R.string.firebase_sign_in_unknown
                            }, Toast.LENGTH_LONG).show()
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    finishAndRemoveTask()
                }

                finish()
            }
        }
    }
}
