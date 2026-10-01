package ar.com.notifmp

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.ds by preferencesDataStore("notifmp")

object Keys {
  val THEME = stringPreferencesKey("theme")
  val SOUND = booleanPreferencesKey("sound")
  val TTS = booleanPreferencesKey("tts")
  val KEEP_ON = booleanPreferencesKey("keep_on")
  val NORMAL_SEC = intPreferencesKey("normal_sec")
  val WAIT_SEC = intPreferencesKey("wait_sec")
  val LAST_POLL = longPreferencesKey("last_poll")
  val SERVICE_ON = booleanPreferencesKey("service_on")
}

class TokenStore(ctx: Context) {
  private val mk = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
  private val p = EncryptedSharedPreferences.create(ctx, "mp_tokens", mk,
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
  fun save(access: String, refresh: String?, expiresIn: Long, collector: String?) {
    p.edit().putString("access", access).putString("refresh", refresh)
      .putLong("exp", System.currentTimeMillis() + expiresIn * 1000)
      .putString("collector", collector).apply()
  }
  fun access(): String? = p.getString("access", null)
  fun refresh(): String? = p.getString("refresh", null)
  fun exp(): Long = p.getLong("exp", 0)
  fun linked(): Boolean = !access().isNullOrEmpty()
  fun clear() = p.edit().clear().apply()
  suspend fun needsRefresh(): Boolean =
    ctx.ds.data.map { it[Keys.LAST_POLL] ?: 0 }.first().let {
      val e = exp()
      e != 0L && e < System.currentTimeMillis() + 5 * 60_000
    }
}
