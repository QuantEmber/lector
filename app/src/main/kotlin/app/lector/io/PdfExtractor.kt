package app.lector.io

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.InputStream

/**
 * PDF text extraction via PdfBox-Android (Apache-2.0). Extracts the text layer;
 * scanned/image-only PDFs yield little or nothing (OCR is a future feature).
 *
 * PdfBox needs a one-time resource init with a Context for its font handling.
 */
class PdfExtractor(context: Context) {

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    fun extract(input: InputStream): String =
        PDDocument.load(input).use { doc ->
            PDFTextStripper().apply { sortByPosition = true }.getText(doc).trim()
        }
}
