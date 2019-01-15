package com.asdev.geofire.models

import com.asdev.geofire.DB_NODE_GAMES
import com.asdev.geofire.DB_NODE_GAMES_PROTECTED
import io.ashdavies.rx.rxfirebase.RxFirebaseDatabase
import java.io.Serializable

class Game: Serializable {

    companion object {

        fun fetchGameSubscription(uuid: String, db: RxFirebaseDatabase) = db.child(DB_NODE_GAMES).child(uuid).onSingleValueEvent()!!

    }

    var admin: String = ""
    var name: String = ""
    var uuid: String = ""
    var start_timestamp: Int = -1
    var end_timestamp: Int = -1
    var is_private: Boolean = false

    fun commit(db: RxFirebaseDatabase) = db.child(DB_NODE_GAMES).child(uuid).setValue(this).subscribe()

}


class GameProtected: Serializable {

    companion object {

        fun fetchGameProtectedSubscription(uuid: String, db: RxFirebaseDatabase) = db.child(DB_NODE_GAMES_PROTECTED).child(uuid).onSingleValueEvent()!!

    }

    var uuid: String = ""
    var participants = HashMap<String, Boolean>()

    fun commit(db: RxFirebaseDatabase) = db.child(DB_NODE_GAMES_PROTECTED).child(uuid).setValue(this).subscribe()

}

// TODO: game method which does
// 1. create base game entry with this uid as admin
// 2. create base game protected entry with no participants and base uuid
// 3. create a geofire entry with the supplied location