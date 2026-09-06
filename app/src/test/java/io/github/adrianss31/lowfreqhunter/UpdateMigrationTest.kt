package io.github.adrianss31.lowfreqhunter

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.test.core.app.ApplicationProvider
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import io.github.adrianss31.lowfreqhunter.data.LfhDb
import io.github.adrianss31.lowfreqhunter.update.AppUpdater
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSigningInfo
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class UpdateMigrationTest {
    @Test fun updaterRejectsWrongPackageSignatureAndDowngrade() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val pm = shadowOf(ctx.packageManager)
        fun info(name: String, version: Long, signer: String) = PackageInfo().apply {
            packageName = name; longVersionCode = version
            signingInfo = SigningInfo().also { Shadow.extract<ShadowSigningInfo>(it).setSignatures(arrayOf(Signature(signer))) }
        }
        pm.installPackage(info(ctx.packageName, 10, "abcd"))
        val file = File(ctx.cacheDir, "candidate.apk")
        for (candidate in listOf(info("other.app", 11, "abcd"), info(ctx.packageName, 10, "abcd"), info(ctx.packageName, 11, "ffff"))) {
            pm.setPackageArchiveInfo(file.absolutePath, candidate)
            assertTrue(runCatching { AppUpdater.validateApk(ctx, file) }.isFailure)
        }
        pm.setPackageArchiveInfo(file.absolutePath, info(ctx.packageName, 11, "abcd"))
        AppUpdater.validateApk(ctx, file)
    }

    @Test fun migrationKeepsExistingRowsAndMarksMetadataUnknown() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(null).callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY NOT NULL, label TEXT NOT NULL)")
                    db.execSQL("INSERT INTO sessions VALUES ('old','Notte precedente')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            }).build())
        try {
            val db = helper.writableDatabase
            LfhDb.MIGRATION_3_4.migrate(db)
            db.query("SELECT label, contextJson, deviceJson FROM sessions WHERE id='old'").use {
                assertTrue(it.moveToFirst())
                assertEquals("Notte precedente", it.getString(0))
                assertEquals("{}", it.getString(1)); assertEquals("{}", it.getString(2))
            }
        } finally { helper.close() }
    }
}
