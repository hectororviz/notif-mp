package ar.com.notifmp

import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.*

// TEMPORAL: log de diagnóstico OAuth visible en la app. Se quita al estabilizar.
// Nunca guarda tokens: el code va truncado.
object OauthLog {
  data class Row(val time: String, val stage: String, val detail: String)
  val rows = mutableStateListOf<Row>()
  private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
  @Synchronized
  fun add(stage: String, detail: String) {
    val safe = detail.replace(Regex("TG-[A-Za-z0-9-]+"), "TG-…")
      .replace(Regex("APP_USR-[A-Za-z0-9-]+"), "APP_USR-…")
      .replace(Regex("TEST-[A-Za-z0-9-]+"), "TEST-…")
    rows.add(0, Row(fmt.format(Date()), stage, safe.take(600)))
    while (rows.size > 100) rows.removeLast()
  }
  fun dump(): String = rows.joinToString("\n") { "${it.time} [${it.stage}] ${it.detail}" }
  fun clear() = rows.clear()
}
