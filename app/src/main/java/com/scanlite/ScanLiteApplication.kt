package com.scanlite

import android.app.Application
import androidx.room.Room
import com.scanlite.data.local.ScanDatabase

class ScanLiteApplication : Application() {
    val database by lazy { Room.databaseBuilder(this, ScanDatabase::class.java, "scanlite.db").build() }
}
