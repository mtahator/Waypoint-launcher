package app.olauncher.features.space.platform

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Reads text in a screenshot on the phone (ML Kit via Google Play services; the model is managed by the
 * system, nothing is uploaded). Latin script. Returns "" when unavailable.
 */
object Ocr {
    suspend fun read(context: Context, file: File): String = runCatching {
        val image = InputImage.fromFilePath(context, Uri.fromFile(file))
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            suspendCancellableCoroutine { cont ->
                client.process(image)
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it.text) }
                    .addOnFailureListener { if (cont.isActive) cont.resume("") }
            }
        } finally {
            client.close()
        }
    }.getOrDefault("")
}
