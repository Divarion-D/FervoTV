package app.iptvplayer.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** QR code of [text] as rows of modules (true = dark), without the quiet zone: the caller draws the white margin. */
fun qrModules(text: String): List<BooleanArray> {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    return List(m.height) { y -> BooleanArray(m.width) { x -> m[x, y] } }
}
