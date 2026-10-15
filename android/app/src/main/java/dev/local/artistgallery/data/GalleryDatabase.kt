package dev.local.artistgallery.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.local.artistgallery.data.archive.ArchiveTaskEntity
import dev.local.artistgallery.data.archive.ArchiveGroupEntity

@Database(
    entities = [ArtistEntity::class, CollectionEntity::class, MediaAssetEntity::class, CollectionMediaEntity::class, ImportBatchEntity::class, ImportItemEntity::class, ArchiveTaskEntity::class, ArchiveGroupEntity::class],
    version = 6,
    exportSchema = true,
)
abstract class GalleryDatabase : RoomDatabase() {
    abstract fun galleryDao(): GalleryDao

    companion object {
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE WorkCollection ADD COLUMN isDefault INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_CollectionMedia_collectionId_manualOrder_assetId ON CollectionMedia (collectionId, manualOrder, assetId)")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Artist ADD COLUMN coverAssetId TEXT")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ImportItem ADD COLUMN archiveGroupId TEXT")
                db.execSQL("ALTER TABLE ImportItem ADD COLUMN sourceRelativePath TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS ArchiveTask (id TEXT NOT NULL PRIMARY KEY, artistId TEXT NOT NULL, format TEXT NOT NULL, mainName TEXT NOT NULL, selectedNames TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS ArchiveGroup (id TEXT NOT NULL PRIMARY KEY, batchId TEXT NOT NULL, artistId TEXT NOT NULL, name TEXT NOT NULL, relativeDirectory TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ArchiveGroup_batchId ON ArchiveGroup (batchId)")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS MediaAsset (
                    id TEXT NOT NULL PRIMARY KEY,
                    originalName TEXT NOT NULL,
                    mediaType TEXT NOT NULL,
                    byteSize INTEGER NOT NULL,
                    width INTEGER,
                    height INTEGER,
                    sha256 TEXT NOT NULL,
                    storageKey TEXT NOT NULL,
                    state TEXT NOT NULL,
                    createdAt INTEGER NOT NULL
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_MediaAsset_sha256_byteSize ON MediaAsset (sha256, byteSize)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_MediaAsset_state ON MediaAsset (state)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS CollectionMedia (
                    collectionId TEXT NOT NULL,
                    assetId TEXT NOT NULL,
                    manualOrder INTEGER NOT NULL,
                    sourceName TEXT NOT NULL,
                    sourceRelativePath TEXT,
                    PRIMARY KEY(collectionId, assetId),
                    FOREIGN KEY(collectionId) REFERENCES WorkCollection(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(assetId) REFERENCES MediaAsset(id) ON UPDATE NO ACTION ON DELETE RESTRICT
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_CollectionMedia_collectionId ON CollectionMedia (collectionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_CollectionMedia_assetId ON CollectionMedia (assetId)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS ImportBatch (
                    id TEXT NOT NULL PRIMARY KEY,
                    targetCollectionId TEXT,
                    status TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS ImportItem (
                    id TEXT NOT NULL PRIMARY KEY,
                    batchId TEXT NOT NULL,
                    targetCollectionId TEXT,
                    sourceUri TEXT NOT NULL,
                    hasPersistedPermission INTEGER NOT NULL,
                    sourceName TEXT NOT NULL,
                    mediaType TEXT NOT NULL,
                    stagingKey TEXT NOT NULL,
                    storageKey TEXT NOT NULL,
                    sha256 TEXT,
                    byteSize INTEGER,
                    status TEXT NOT NULL,
                    errorMessage TEXT,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ImportItem_batchId ON ImportItem (batchId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ImportItem_status ON ImportItem (status)")
            }
        }
    }
}
