package com.petcare.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import android.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.max

/**
 * Utility helper for managing pet photo persistence and camera integration.
 *
 * Responsibilities:
 * - Creates FileProvider temporary URIs for [androidx.activity.result.contract.ActivityResultContracts.TakePicture]
 *   without requiring CAMERA permissions.
 * - Imports photos from Gallery ([androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia])
 *   or Camera and copies them into the app's private internal [Context.getFilesDir]/pet_photos.
 * - Automatically downscales images to a max dimension of 1024px and compresses to JPEG (quality 85)
 *   to ensure low storage footprint and fast RecyclerView loading.
 * - Respects EXIF orientation metadata so mobile camera photos are oriented right side up.
 * - Safely cleans up replaced or deleted photo files.
 */
object PhotoStorageHelper {

    private const val MAX_DIMENSION_PX = 1024
    private const val JPEG_QUALITY = 85
    private const val PHOTOS_DIR_NAME = "pet_photos"
    private const val CAMERA_CACHE_DIR_NAME = "camera_temp"

    /**
     * Generates a temporary content [Uri] for camera capture via [FileProvider].
     *
     * @param context Application context.
     * @return Content URI grantable to the camera intent.
     */
    fun createTempCameraUri(context: Context): Uri {
        val cacheDir = File(context.cacheDir, CAMERA_CACHE_DIR_NAME).apply { mkdirs() }
        val tempFile = File(cacheDir, "camera_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            tempFile
        )
    }

    /**
     * Reads an image from [sourceUri], downscales it to ~1024px, applies EXIF rotation,
     * compresses to JPEG, and writes it to internal private storage ([Context.getFilesDir]/pet_photos).
     *
     * If [oldPhotoPath] is provided, the old file is deleted after the new file is saved
     * successfully, ensuring no orphan files accumulate on repeated photo changes.
     *
     * Runs off the main thread on [Dispatchers.IO].
     *
     * @param context       Application context.
     * @param sourceUri     Source content or file URI.
     * @param oldPhotoPath  Optional URI string of the previous photo to delete (may be null).
     * @return String representation of the saved file URI (e.g. "file:///...").
     */
    suspend fun savePhotoToInternalStorage(
        context: Context,
        sourceUri: Uri,
        oldPhotoPath: String? = null
    ): String = withContext(Dispatchers.IO) {
        val photosDir = File(context.filesDir, PHOTOS_DIR_NAME).apply { mkdirs() }
        val targetFile = File(photosDir, "pet_${System.currentTimeMillis()}.jpg")

        // 1. Decode bounds to determine downsampling
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }

        val originalWidth = options.outWidth
        val originalHeight = options.outHeight
        val maxDim = max(originalWidth, originalHeight)
        var sampleSize = 1
        while (maxDim / (sampleSize * 2) >= MAX_DIMENSION_PX) {
            sampleSize *= 2
        }

        // 2. Decode sampled bitmap
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val sampledBitmap: Bitmap = context.contentResolver.openInputStream(sourceUri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        } ?: throw IllegalStateException("Unable to decode image from $sourceUri")

        // 3. Apply EXIF orientation
        val rotatedBitmap = applyExifRotation(context, sourceUri, sampledBitmap)

        // 4. Save to target file
        FileOutputStream(targetFile).use { out ->
            rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }

        // Clean up temporary bitmaps
        if (rotatedBitmap != sampledBitmap) {
            sampledBitmap.recycle()
        }
        rotatedBitmap.recycle()

        // 5. Delete old photo file after new one is safely written
        if (!oldPhotoPath.isNullOrBlank()) {
            deletePhotoFile(oldPhotoPath)
        }

        Uri.fromFile(targetFile).toString()
    }

    /**
     * Safely deletes a photo file from internal storage if it exists.
     *
     * @param photoUriString URI string of the file to remove.
     */
    fun deletePhotoFile(photoUriString: String?) {
        if (photoUriString.isNullOrBlank()) return
        try {
            val uri = Uri.parse(photoUriString)
            if (uri.scheme == "file") {
                val file = uri.path?.let { File(it) }
                if (file != null && file.exists()) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            // Ignore deletion errors on already deleted or inaccessible files
        }
    }

    /**
     * Reads EXIF orientation and returns a correctly rotated Bitmap.
     */
    private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        var inputStream: InputStream? = null
        try {
            inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream != null) {
                val exif = ExifInterface(inputStream)
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                val matrix = Matrix()
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                    else -> return bitmap
                }
                return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
        } catch (e: Exception) {
            // Fall back to original bitmap if EXIF read fails
        } finally {
            inputStream?.close()
        }
        return bitmap
    }
}
