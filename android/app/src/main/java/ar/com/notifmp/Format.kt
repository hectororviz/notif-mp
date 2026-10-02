package ar.com.notifmp

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.Date

private val AR = Locale("es", "AR")
private val TZ = TimeZone.getTimeZone("America/Argentina")

fun fmtARS(v: Double): String {
  val nf = NumberFormat.getNumberInstance(AR).apply {
    minimumFractionDigits = 2
    maximumFractionDigits = 2
  }
  return "$ ${nf.format(v)}"
}

fun fmtARSshort(v: Double): String {
  val nf = NumberFormat.getNumberInstance(AR).apply {
    minimumFractionDigits = 0
    maximumFractionDigits = 0
  }
  return "$ ${nf.format(v)}"
}

fun fmtFechaHora(ts: Long): String =
  SimpleDateFormat("dd/MM/yyyy HH:mm", AR).apply { timeZone = TZ }.format(Date(ts))

fun fmtHoraCorta(ts: Long): String =
  SimpleDateFormat("dd/MM HH:mm", AR).apply { timeZone = TZ }.format(Date(ts))

val BTN_PRESET = listOf(
  0xFF00A7FB.toInt(), // celeste MP
  0xFF00C853.toInt(), // verde
  0xFFFF6D00.toInt(), // naranja
  0xFF7C4DFF.toInt(), // violeta
  0xFFE91E63.toInt(), // rosa
  0xFF009688.toInt(), // teal
  0xFF616161.toInt(), // gris
)
