package com.kuma.motointercom

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PairingRecord::class],
    version = 1,
    exportSchema = true
)
internal abstract class PairingDatabase : RoomDatabase() {
    abstract fun pairingDao(): PairingDao

    companion object {
        @Volatile
        private var instance: PairingDatabase? = null

        fun getInstance(context: Context): PairingDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PairingDatabase::class.java,
                    "pairings.db"
                ).build().also { instance = it }
            }
    }
}
