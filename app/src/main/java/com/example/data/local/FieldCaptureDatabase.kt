
package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        InspectionJobEntity::class,
        CaptureEvidenceEntity::class,
        SyncOutboxEntity::class,
        TelemetryMetricEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class FieldCaptureDatabase : RoomDatabase() {

    abstract fun inspectionDao(): InspectionDao

    companion object {
        @Volatile
        private var INSTANCE: FieldCaptureDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE capture_evidences ADD COLUMN evidenceSource TEXT NOT NULL DEFAULT 'UNKNOWN'"
                )
                db.execSQL(
                    "ALTER TABLE capture_evidences ADD COLUMN imageSha256 TEXT"
                )
            }
        }

        // Version 2 already contains the two columns.
        // This migration preserves the existing data and allows
        // Room to validate the updated version 3 schema.
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No physical schema changes required.
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No physical schema changes.
                // Room will validate the existing schema.
            }
        }

        fun getInstance(context: Context): FieldCaptureDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FieldCaptureDatabase::class.java,
                    "fieldcapture.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
