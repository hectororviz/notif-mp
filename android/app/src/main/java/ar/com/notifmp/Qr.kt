package ar.com.notifmp

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

fun qrBitmap(text: String, size: Int = 512): Bitmap {
  val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
  val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
  for (x in 0 until size) for (y in 0 until size)
    bmp.setPixel(x, y, if (m.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
  return bmp
}
