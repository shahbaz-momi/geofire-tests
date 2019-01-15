package com.asdev.geofire.models

import com.asdev.geofire.DB_NODE_USERS
import com.asdev.geofire.DB_NODE_USERS_PROTECTED
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import io.ashdavies.rx.rxfirebase.RxFirebaseDatabase
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import java.io.Serializable

// empty constructor for serialization
class User(): Serializable {

    companion object {

        fun fetchUserSubscription(uid: String, db: RxFirebaseDatabase): Single<DataSnapshot> {
            // request to keep this data offline as well
            FirebaseDatabase.getInstance().reference.child(DB_NODE_USERS).child(uid).keepSynced(true)

            return db.child(DB_NODE_USERS).child(uid).onSingleValueEvent().subscribeOn(Schedulers.io())
        }
    }

    var uid = ""
    var name = ""

    constructor(uid: String, name: String): this() {
        this.uid = uid
        this.name = name
    }

    override fun toString() = "com.asdev.geofire.models.User[uid=$uid,name=$name]"

    fun commit(db: RxFirebaseDatabase) =
            db.child(DB_NODE_USERS).child(uid).setValue(this).subscribe()!!

}



class UserProtected : Serializable {

    companion object {

        fun fetchUserProtectedSubscription(uid: String, db: RxFirebaseDatabase): Single<DataSnapshot> {
            FirebaseDatabase.getInstance().reference.child(DB_NODE_USERS_PROTECTED).child(uid).keepSynced(true)

            return db.child(DB_NODE_USERS_PROTECTED)
                    .child(uid).onSingleValueEvent().subscribeOn(Schedulers.io())
        }
    }

    var uid: String = ""
    var friends = HashMap<String, Boolean>()
    var games = listOf<String>()

    override fun toString() = "com.asdev.geofire.models.UserProtected[uid=$uid,friends=[" +
            friends.entries.joinToString (separator = ",") { "<${it.key},${it.value}>" } + "],games=[" +
            games.joinToString(separator = ",") + "]]"

    fun commit(db: RxFirebaseDatabase) =
            db.child(DB_NODE_USERS_PROTECTED).child(uid).setValue(this).subscribe()!!
}