package ar.com.notifmp

import android.content.Context
import android.net.Uri
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.text.SimpleDateFormat
import java.util.*

fun movementsToCsv(list: List<Movement>): String {
  val sb = StringBuilder("\uFEFFfecha;hora;monto;pagador;email;payment_id;tipo;estado\n")
  val df = SimpleDateFormat("yyyy-MM-dd;HH:mm", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("America/Argentina") }
  for (m in list) {
    val parts = df.format(Date(m.notifiedAt)).split(";")
    sb.append("${parts[0]};${parts[1]};${m.amount};${m.payer ?: ""};${m.email ?: ""};${m.mpId};${m.type ?: ""};approved\n")
  }
  return sb.toString()
}

fun movementsToXlsx(ctx: Context, uri: Uri, list: List<Movement>) {
  val wb = XSSFWorkbook(); val sh = wb.createSheet("Movimientos")
  val head = arrayOf("fecha", "hora", "monto", "pagador", "email", "payment_id", "tipo", "estado")
  val hr = sh.createRow(0); head.forEachIndexed { i, h -> hr.createCell(i).setCellValue(h) }
  val df = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("America/Argentina") }
  val hf = SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("America/Argentina") }
  list.forEachIndexed { r, m ->
    val row = sh.createRow(r + 1)
    row.createCell(0).setCellValue(df.format(Date(m.notifiedAt)))
    row.createCell(1).setCellValue(hf.format(Date(m.notifiedAt)))
    row.createCell(2).setCellValue(m.amount)
    row.createCell(3).setCellValue(m.payer ?: "")
    row.createCell(4).setCellValue(m.email ?: "")
    row.createCell(5).setCellValue(m.mpId)
    row.createCell(6).setCellValue(m.type ?: "")
    row.createCell(7).setCellValue("approved")
  }
  ctx.contentResolver.openOutputStream(uri)?.use { wb.write(it) }
  wb.close()
}
