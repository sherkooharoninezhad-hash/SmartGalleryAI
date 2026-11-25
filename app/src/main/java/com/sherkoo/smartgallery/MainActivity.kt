package com.sherkoo.smartgallery

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private lateinit var queryBox: TextInputEditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var resultsView: RecyclerView
    private lateinit var modeButton: MaterialButton
    private lateinit var adapter: PhotoAdapter
    private var referenceUri: Uri? = null
    private var onlineMode = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val labeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.25f).build()) }

    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            referenceUri = uri
            status.text = "عکس نمونه انتخاب شد؛ حالا «پیدا کردن مشابه‌ها» را بزن."
            findSimilar(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        adapter = PhotoAdapter { uri -> openPhoto(uri) }
        resultsView.adapter = adapter
        resultsView.layoutManager = GridLayoutManager(this, 2)
        if (hasImagePermission()) status.text = "آماده جست‌وجو در گالری هستم."
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(12))
            setBackgroundColor(0xFF101820.toInt())
        }
        val title = TextView(this).apply {
            text = "Smart Gallery AI"
            textSize = 25f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "جست‌وجوی هوشمند بین عکس‌های گوشی"
            textSize = 14f; setTextColor(0xFFB9C7C9.toInt()); gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(18))
        }
        queryBox = TextInputEditText(this).apply {
            hint = "مثلاً چوب، ماشین، درخت، گربه..."
            textSize = 16f; setTextColor(0xFF17212B.toInt()); setHintTextColor(0xFF66717B.toInt())
            setSingleLine(true); setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        val textSearch = button("جست‌وجو در تمام عکس‌ها") { searchText() }
        val similar = button("انتخاب عکس نمونه و یافتن مشابه‌ها") { imagePicker.launch(arrayOf("image/*")) }
        modeButton = button("حالت: آفلاین روی گوشی  |  تغییر حالت") {
            onlineMode = !onlineMode
            if (onlineMode) {
                status.text = "حالت آنلاین انتخاب شد؛ اتصال سرویس هوش مصنوعی ابری هنوز نیاز به تنظیم کلید API دارد. جست‌وجوی آفلاین همچنان در دسترس است."
                modeText(mode = true)
            } else {
                status.text = "حالت آفلاین فعال است؛ تحلیل تصاویر روی گوشی انجام می‌شود."
                modeText(mode = false)
            }
        }
        status = TextView(this).apply {
            text = "برای شروع، اجازه دسترسی به عکس‌ها را بده."
            textSize = 13f; setTextColor(0xFFE4ECEB.toInt()); setPadding(0, dp(12), 0, dp(6))
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE; max = 100
        }
        resultsView = RecyclerView(this).apply {
            setBackgroundColor(0xFF101820.toInt())
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        root.addView(title)
        root.addView(subtitle)
        root.addView(queryBox, LinearLayout.LayoutParams(-1, dp(54)))
        root.addView(textSearch, marginParams())
        root.addView(similar, marginParams())
        root.addView(modeButton, marginParams())
        root.addView(status)
        root.addView(progress)
        root.addView(resultsView)
        setContentView(root)
    }

    private fun modeText(mode: Boolean) {
        modeButton.text = if (mode) "حالت: آنلاین (نیازمند API) | تغییر حالت" else "حالت: آفلاین روی گوشی | تغییر حالت"
    }

    private fun button(label: String, action: () -> Unit): MaterialButton = MaterialButton(this).apply {
        text = label; isAllCaps = false; textSize = 14f
        setOnClickListener { action() }
    }
    private fun marginParams() = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun hasImagePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
        }
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestImagePermission(onGranted: () -> Unit) {
        if (hasImagePermission()) { onGranted(); return }
        val permissions = if (Build.VERSION.SDK_INT >= 34) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            else if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        ActivityCompat.requestPermissions(this, permissions, 42)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 42 && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) status.text = "دسترسی داده شد؛ دوباره جست‌وجو را بزن."
        else if (requestCode == 42) status.text = "برای جست‌وجوی کل گالری باید اجازه دسترسی به عکس‌ها را بدهی."
    }

    private fun searchText() {
        val q = queryBox.text?.toString()?.trim().orEmpty()
        if (q.isBlank()) { status.text = "اول کلمه موردنظر را بنویس."; return }
        requestImagePermission {
            runScan("در حال بررسی عکس‌ها برای «$q»...") { uri, labels ->
                val terms = expandQuery(q)
                val score = labels.maxOfOrNull { label ->
                    val name = label.text.lowercase(Locale.ROOT)
                    if (terms.any { name.contains(it) || it.contains(name) }) label.confidence else 0f
                } ?: 0f
                if (score >= 0.22f) score else 0f
            }
        }
    }

    private fun findSimilar(sampleUri: Uri) {
        requestImagePermission {
            scope.launch {
                try {
                    val sample = withContext(Dispatchers.IO) { contentResolver.openInputStream(sampleUri)?.use { BitmapFactory.decodeStream(it) } }
                    if (sample == null) { status.text = "خواندن عکس نمونه ممکن نشد."; return@launch }
                    status.text = "در حال تحلیل عکس نمونه و مقایسه با گالری..."
                    val sampleLabels = labeler.process(InputImage.fromFilePath(this@MainActivity, sampleUri)).await()
                    val sampleNames = sampleLabels.associate { it.text.lowercase(Locale.ROOT) to it.confidence }
                    val sampleHash = averageHash(sample)
                    runScan("در حال یافتن تصاویر مشابه...") { uri, labels ->
                        val names = labels.associate { it.text.lowercase(Locale.ROOT) to it.confidence }
                        val common = sampleNames.keys.intersect(names.keys)
                        val labelScore = if (sampleNames.isEmpty() || names.isEmpty()) 0f else common.size.toFloat() / max(sampleNames.size, names.size)
                        val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                        val hashScore = if (bitmap == null) 0f else 1f - (java.lang.Long.bitCount(sampleHash xor averageHash(bitmap)) / 64f)
                        (labelScore * 0.72f + hashScore * 0.28f).coerceIn(0f, 1f)
                    }
                } catch (e: Exception) { status.text = "خطا در تحلیل عکس نمونه: ${e.localizedMessage ?: "نامشخص"}" }
            }
        }
    }

    private fun runScan(message: String, scorer: suspend (Uri, List<com.google.mlkit.vision.label.ImageLabel>) -> Float) {
        scope.launch {
            progress.visibility = View.VISIBLE; progress.progress = 0; status.text = message
            val found = mutableListOf<PhotoResult>()
            try {
                val items = withContext(Dispatchers.IO) { queryImages() }
                if (items.isEmpty()) { status.text = "عکسی برای بررسی پیدا نشد یا دسترسی کامل داده نشده است."; progress.visibility = View.GONE; return@launch }
                items.forEachIndexed { index, uri ->
                    try {
                        val labels = labeler.process(InputImage.fromFilePath(this@MainActivity, uri)).await()
                        val score = scorer(uri, labels)
                        if (score > 0f) found.add(PhotoResult(uri, score))
                    } catch (_: Exception) { }
                    progress.progress = ((index + 1) * 100 / items.size)
                    if ((index + 1) % 12 == 0) status.text = "بررسی ${index + 1} از ${items.size} عکس..."
                }
                adapter.submit(found.sortedByDescending { it.score })
                status.text = "تمام شد: ${found.size} نتیجه از ${items.size} عکس بررسی‌شده."
            } catch (e: Exception) { status.text = "جست‌وجو با خطا روبه‌رو شد: ${e.localizedMessage ?: "نامشخص"}" }
            finally { progress.visibility = View.GONE }
        }
    }

    private fun queryImages(): List<Uri> {
        val out = mutableListOf<Uri>()
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val cursor: Cursor? = contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, sort)
        cursor?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (c.moveToNext()) out.add(Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol).toString()))
        }
        return out
    }

    private fun expandQuery(q: String): Set<String> {
        val normalized = q.lowercase(Locale.ROOT)
        val map = mapOf(
            "چوب" to setOf("wood", "wooden", "lumber", "timber", "plank", "tree", "forest", "furniture"),
            "درخت" to setOf("tree", "plant", "forest", "wood"),
            "ماشین" to setOf("car", "vehicle", "automobile", "truck"),
            "گربه" to setOf("cat", "kitten", "animal"),
            "سگ" to setOf("dog", "puppy", "animal"),
            "کوه" to setOf("mountain", "hill", "landscape"),
            "دریا" to setOf("sea", "ocean", "water", "beach"),
            "گل" to setOf("flower", "plant", "blossom"),
            "غذا" to setOf("food", "meal", "dish")
        )
        return map[normalized] ?: setOf(normalized, normalized.replace(" ", ""))
    }

    private fun averageHash(bitmap: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        val pixels = IntArray(64); small.getPixels(pixels, 0, 8, 0, 0, 8, 8)
        val gray = pixels.map { ((android.graphics.Color.red(it) * 299 + android.graphics.Color.green(it) * 587 + android.graphics.Color.blue(it) * 114) / 1000) }
        val avg = gray.average()
        var hash = 0L
        gray.forEachIndexed { i, v -> if (v >= avg) hash = hash or (1L shl i) }
        return hash
    }

    private fun openPhoto(uri: Uri) {
        try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        catch (_: Exception) { Toast.makeText(this, "باز کردن عکس ممکن نشد", Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        super.onDestroy(); scope.cancel(); labeler.close()
    }
}

data class PhotoResult(val uri: Uri, val score: Float)

class PhotoAdapter(private val onClick: (Uri) -> Unit) : RecyclerView.Adapter<PhotoAdapter.Holder>() {
    private val items = mutableListOf<PhotoResult>()
    fun submit(data: List<PhotoResult>) { items.clear(); items.addAll(data); notifyDataSetChanged() }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val box = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL; setPadding(6, 6, 6, 12) }
        val image = ImageView(parent.context).apply { layoutParams = LinearLayout.LayoutParams(-1, 150); scaleType = ImageView.ScaleType.CENTER_CROP }
        val label = TextView(parent.context).apply { textSize = 12f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER; setPadding(0, 5, 0, 0) }
        box.addView(image); box.addView(label)
        return Holder(box, image, label)
    }
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        try { holder.image.setImageURI(item.uri) } catch (_: Exception) { holder.image.setImageDrawable(null) }
        holder.label.text = "شباهت: ${(item.score * 100).toInt()}٪"
        holder.itemView.setOnClickListener { onClick(item.uri) }
    }
    class Holder(v: View, val image: ImageView, val label: TextView) : RecyclerView.ViewHolder(v)
}
