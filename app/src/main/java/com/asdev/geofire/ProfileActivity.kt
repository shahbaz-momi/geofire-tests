package com.asdev.geofire

import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import com.asdev.geofire.models.User
import io.ashdavies.rx.rxfirebase.RxFirebaseDatabase
import io.reactivex.disposables.CompositeDisposable

class ProfileActivity : AppCompatActivity() {

    private val subscriptions = CompositeDisposable()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        // update the registration
        val user = intent.getSerializableExtra(EXTRA_KEY_USER) as User
        val db = RxFirebaseDatabase.getInstance()
        // commit the user, add the subscription to the queue
        subscriptions.add(user.commit(db))

        // no need to commit friends or games
    }

    override fun onStop() {
        super.onStop()

        subscriptions.dispose()
        // TODO: reset subscriptions if using again
    }
}
