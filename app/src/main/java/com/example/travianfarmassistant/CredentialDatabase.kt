package com.example.travianfarmassistant

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore

/** Persistent credential storage. Password is encrypted with an Android Keystore AES-GCM key. */
class CredentialDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    data class Credential(val server: String, val username: String, val password: String)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE credentials (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                server TEXT NOT NULL,
                username TEXT NOT NULL,
                password_cipher TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("DROP TABLE IF EXISTS credentials")
            onCreate(db)
        }
    }

    fun save(server: String, username: String, password: String): Boolean {
        if (server.isBlank() || username.isBlank() || password.isBlank()) return false
        val values = ContentValues().apply {
            put("id", 1)
            put("server", server)
            put("username", username)
            put("password_cipher", encrypt(password))
            put("updated_at", System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict(
            "credentials", null, values, SQLiteDatabase.CONFLICT_REPLACE
        ) != -1L
    }

    fun read(): Credential? {
        readableDatabase.query(
            "credentials",
            arrayOf("server", "username", "password_cipher"),
            "id = 1", null, null, null, null, "1"
        ).use { c ->
            if (!c.moveToFirst()) return null
            val server = c.getString(0).orEmpty()
            val username = c.getString(1).orEmpty()
            val cipherText = c.getString(2).orEmpty()
            val password = decrypt(cipherText)
            if (server.isBlank() || username.isBlank() || password.isBlank()) return null
            return Credential(server, username, password)
        }
    }

    fun clear() {
        writableDatabase.delete("credentials", "id = 1", null)
    }

    private fun getKey(): javax.crypto.SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return keyStore.getKey(KEY_ALIAS, null) as javax.crypto.SecretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val packed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        return try {
            val packed = Base64.decode(value, Base64.DEFAULT)
            if (packed.size <= 12) return ""
            val iv = packed.copyOfRange(0, 12)
            val encrypted = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    companion object {
        private const val DB_NAME = "travian_credentials.db"
        private const val DB_VERSION = 2
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "TravianFarmAssistantCredentialKey"
    }
}
