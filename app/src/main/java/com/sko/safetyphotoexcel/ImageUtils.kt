package com.sko.safetyphotoexcel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

data class ProcessedImage(
    val bytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int
)

data class NormalizedStroke(
    val points: List<Pair<Float, Float>>, // 0f..1f normalized coordinates
    val colorArgb: Int,
    val strokeRatio: Float = 0.012f
)

object ImageUtils {
    fun loadUprightBitmap(context: Context, uri: Uri, maxDimensionPx: Int = 1600): Bitmap {
        val orientation = context.contentResolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }

        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimensionPx)
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val rawBitmap = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOpts)
        } ?: error("사진을 불러올 수 없습니다: $uri")

        val exifRotated = applyExifRotation(rawBitmap, orientation)
        if (exifRotated !== rawBitmap) rawBitmap.recycle()

        val scaled = scaleDownIfNeeded(exifRotated, maxDimensionPx)
        if (scaled !== exifRotated) exifRotated.recycle()
        return scaled
    }

    fun saveAnnotatedCopy(
        context: Context,
        sourceUri: Uri,
        strokes: List<NormalizedStroke>
    ): Uri {
        val base = loadUprightBitmap(context, sourceUri, 1600)
        val mutable = base.copy(Bitmap.Config.ARGB_8888, true)
        if (mutable !== base) base.recycle()

        val canvas = Canvas(mutable)
        val minSide = minOf(mutable.width, mutable.height).toFloat()

        for (stroke in strokes) {
            if (stroke.points.size < 2) continue
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = stroke.colorArgb
                style = Paint.Style.STROKE
                strokeWidth = (minSide * stroke.strokeRatio).coerceAtLeast(5f)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val path = Path()
            val first = stroke.points.first()
            path.moveTo(first.first * mutable.width, first.second * mutable.height)
            for (i in 1 until stroke.points.size) {
                val pt = stroke.points[i]
                path.lineTo(pt.first * mutable.width, pt.second * mutable.height)
            }
            canvas.drawPath(path, paint)
        }

        val photoDir = File(context.cacheDir, "photos").apply { mkdirs() }
        val outFile = File(photoDir, "MARKUP_${System.currentTimeMillis()}.jpg")
        FileOutputStream(outFile).use { fos ->
            mutable.compress(Bitmap.CompressFormat.JPEG, 92, fos)
        }
        mutable.recycle()
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
    }

    fun loadNormalizedJpeg(
        context: Context,
        uri: Uri,
        extraRotationDegrees: Int = 0,
        watermarkText: String? = null,
        maxDimensionPx: Int = 1600,
        jpegQuality: Int = 88,
        cropToAspectRatio: Double? = null
    ): ProcessedImage {
        val base = loadUprightBitmap(context, uri, maxDimensionPx)

        val userRotated = if (extraRotationDegrees % 360 != 0) {
            val m = Matrix().apply { postRotate((extraRotationDegrees % 360).toFloat()) }
            val r = Bitmap.createBitmap(base, 0, 0, base.width, base.height, m, true)
            if (r !== base) base.recycle()
            r
        } else {
            base
        }

        val cropped = if (cropToAspectRatio != null && cropToAspectRatio > 0.0) {
            centerCropToAspect(userRotated, cropToAspectRatio).also {
                if (it !== userRotated) userRotated.recycle()
            }
        } else {
            userRotated
        }

        val watermarked = if (!watermarkText.isNullOrBlank()) {
            drawWatermark(cropped, watermarkText).also {
                if (it !== cropped) cropped.recycle()
            }
        } else {
            cropped
        }
        val finalBmp = drawPhotoBorder(watermarked).also {
            if (it !== watermarked) watermarked.recycle()
        }

        val out = ByteArrayOutputStream()
        finalBmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
        val w = finalBmp.width
        val h = finalBmp.height
        finalBmp.recycle()

        return ProcessedImage(out.toByteArray(), w, h)
    }

    private fun centerCropToAspect(src: Bitmap, targetAspect: Double): Bitmap {
        val w = src.width
        val h = src.height
        val currentAspect = w.toDouble() / h.toDouble()
        if (kotlin.math.abs(currentAspect - targetAspect) < 0.02) return src
        return if (currentAspect > targetAspect) {
            val newW = (h * targetAspect).roundToInt().coerceIn(1, w)
            val x = ((w - newW) / 2).coerceAtLeast(0)
            Bitmap.createBitmap(src, x, 0, newW, h)
        } else {
            val newH = (w / targetAspect).roundToInt().coerceIn(1, h)
            val y = ((h - newH) / 2).coerceAtLeast(0)
            Bitmap.createBitmap(src, 0, y, w, newH)
        }
    }

    private fun drawPhotoBorder(src: Bitmap): Bitmap {
        val mutable = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)
        val strokeW = (minOf(mutable.width, mutable.height) * 0.006f).coerceIn(2f, 6f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 58, 95)
            style = Paint.Style.STROKE
            strokeWidth = strokeW
        }
        val half = strokeW / 2f
        canvas.drawRect(half, half, mutable.width - half, mutable.height - half, paint)
        return mutable
    }

    private fun drawWatermark(src: Bitmap, text: String): Bitmap {
        val mutable = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)
        val textSize = (mutable.width * 0.026f).coerceIn(24f, 44f)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            isFakeBoldText = true
        }
        val padH = textSize * 0.6f
        val padV = textSize * 0.35f
        val textW = textPaint.measureText(text)
        val margin = textSize * 0.7f
        val rect = RectF(
            mutable.width - textW - padH * 2 - margin,
            mutable.height - textSize - padV * 2 - margin,
            mutable.width - margin,
            mutable.height - margin
        )
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(165, 15, 23, 42)
        }
        canvas.drawRoundRect(rect, 10f, 10f, bgPaint)
        canvas.drawText(text, rect.left + padH, rect.bottom - padV - textPaint.descent() * 0.3f, textPaint)
        return mutable
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var inSampleSize = 1
        val largest = max(width, height)
        while (largest / inSampleSize > maxDim * 2) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    private fun applyExifRotation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap, maxDim: Int): Bitmap {
        val largest = max(bitmap.width, bitmap.height)
        if (largest <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / largest.toFloat()
        val newW = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
        val newH = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }
}
