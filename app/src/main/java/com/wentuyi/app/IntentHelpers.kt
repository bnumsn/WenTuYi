package com.wentuyi.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build

/** Tiny helpers shared by every Activity for picking images and sharing artefacts. */
internal object IntentHelpers {

    fun pickImage(activity: Activity, requestCode: Int, allowMultiple: Boolean, title: String) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
        }
        try {
            activity.startActivityForResult(intent, requestCode)
        } catch (e: ActivityNotFoundException) {
            val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
            }
            activity.startActivityForResult(Intent.createChooser(fallback, title), requestCode)
        }
    }

    fun getSelectedImageUris(data: Intent?): List<Uri> {
        if (data == null) return emptyList()
        val uris = ArrayList<Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let { uris += it }
            }
        }
        data.data?.let { if (!uris.contains(it)) uris += it }
        return uris
    }

    @Suppress("DEPRECATION")
    fun getStreamUri(intent: Intent): Uri? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }

    @Suppress("DEPRECATION")
    fun getStreamUris(intent: Intent): List<Uri> {
        val uris = ArrayList<Uri>()
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let { uris += it }
            }
        }
        val extras = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        extras?.forEach { if (it != null && !uris.contains(it)) uris += it }
        return uris
    }

    fun shareImage(activity: Activity, uri: Uri, title: String): Boolean {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(activity.contentResolver, title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return startChooser(activity, share, title)
    }

    fun shareImages(activity: Activity, uris: List<Uri>, title: String): Boolean {
        if (uris.isEmpty()) return false
        if (uris.size == 1) return shareImage(activity, uris[0], title)

        val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/png"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            val clip = ClipData.newUri(activity.contentResolver, title, uris[0])
            for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return startChooser(activity, share, title)
    }

    // The share intent carries FLAG_GRANT_READ_URI_PERMISSION + clipData, so the system
    // grants a transient read only to the app the user actually picks in the chooser. We
    // deliberately don't pre-grant to every package that *could* handle the intent — that
    // left a lingering read on the cached PNG for far more apps than ever receive it.
    private fun startChooser(activity: Activity, share: Intent, title: String): Boolean {
        val chooser = Intent.createChooser(share, title).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try { activity.startActivity(chooser); true } catch (e: ActivityNotFoundException) { false }
    }
}
