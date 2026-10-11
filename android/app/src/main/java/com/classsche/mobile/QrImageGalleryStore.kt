package com.classsche.mobile

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/** Writes only the QR bitmap explicitly selected by the user. */
internal object QrImageGalleryStore {
  @Suppress("DEPRECATION")
  fun save(context: Context, bitmap: Bitmap) {
    val resolver = context.contentResolver
    val name = "微信登录二维码_${System.currentTimeMillis()}.png"
    var legacyFile: File? = null
    val values = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, name)
      put(MediaStore.Images.Media.MIME_TYPE, "image/png")
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/周三课表")
        put(MediaStore.Images.Media.IS_PENDING, 1)
      } else {
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "周三课表")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("相册目录创建失败，请检查存储空间")
        legacyFile = File(directory, name)
        put(MediaStore.Images.Media.DATA, legacyFile!!.absolutePath)
      }
    }
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val imageUri = resolver.insert(collection, values) ?: throw IOException("相册图片创建失败")
    try {
      val stream = resolver.openOutputStream(imageUri, "w") ?: throw IOException("相册图片写入失败")
      stream.use {
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("二维码图片编码失败")
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val published = resolver.update(imageUri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        if (published == 0) throw IOException("相册图片发布失败")
      }
    } catch (error: Exception) {
      // A failed write must not leave a broken or permanently pending gallery item.
      runCatching { resolver.delete(imageUri, null, null) }
      legacyFile?.let { runCatching { it.delete() } }
      throw error
    }
  }
}
