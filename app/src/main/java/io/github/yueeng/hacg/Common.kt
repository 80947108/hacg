@file:Suppress("unused")

package io.github.yueeng.hacg

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.ReplacementSpan
import android.util.AttributeSet
import android.view.*
import android.webkit.CookieManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.PermissionChecker
import androidx.core.os.LocaleListCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.children
import androidx.core.view.descendants
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commitNow
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.paging.PagingSource
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.*
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Registry
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.annotation.Excludes
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.module.AppGlideModule
import com.bumptech.glide.request.RequestOptions
import com.github.clans.fab.FloatingActionButton
import com.github.clans.fab.FloatingActionMenu
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.parcelize.Parcelize
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import okio.buffer
import okio.sink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.Serializable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlin.math.sign


fun debug(call: () -> Unit) {
    if (BuildConfig.DEBUG) call()
}

var user: Int
    get() = PreferenceManager.getDefaultSharedPreferences(HAcgApplication.instance).getInt("user.id", 0)
    set(value) = PreferenceManager.getDefaultSharedPreferences(HAcgApplication.instance).edit().putInt("user.id", value).apply()

val gson: Gson = GsonBuilder().create()
val okhttp = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .writeTimeout(20, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .cache(Cache(HAcgApplication.instance.cacheDir, 1024L * 1024L * 256L))
    .cookieJar(WebkitCookieJar(CookieManager.getInstance()))
    .apply { debug { addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }) } }
    .build()
val okdownloader = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .cache(Cache(HAcgApplication.instance.cacheDir, 1024L * 1024L * 256L))
    .cookieJar(WebkitCookieJar(CookieManager.getInstance()))
    .apply { debug { addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }) } }
    .build()

@GlideModule
@Excludes(com.bumptech.glide.integration.okhttp3.OkHttpLibraryGlideModule::class)
class HacgAppGlideModule : AppGlideModule() {
    override fun applyOptions(context: Context, builder: GlideBuilder) {
        builder.setDefaultRequestOptions(RequestOptions().format(DecodeFormat.PREFER_RGB_565))
    }

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        registry.replace(GlideUrl::class.java, InputStream::class.java, OkHttpUrlLoader.Factory(okhttp))
    }
}

fun RequestBuilder<Drawable>.crossFade(): RequestBuilder<Drawable> =
    this.transition(DrawableTransitionOptions.withCrossFade())

class HAcgApplication : Application() {
    companion object {

        private lateinit var _instance: HAcgApplication

        val instance: HAcgApplication get() = _instance
    }

    init {
        _instance = this
    }
}

class WebkitCookieJar(private val cm: CookieManager) : CookieJar {
    override fun loadForRequest(url: HttpUrl): List<Cookie> = cm.getCookie(url.toString())?.split("; ")?.mapNotNull { Cookie.parse(url, it) }?.toList() ?: emptyList()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { cookie ->
            cm.setCookie(url.toString(), cookie.toString())
        }
    }
}

suspend fun <T> Call.await(action: (Call, Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation {
        cancel()
    }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (!continuation.isCancelled) continuation.resume(action(call, response))
        }
    })
}

val datefmt get() = SimpleDateFormat("yyyy-MM-dd'T'hh:mm:ssZZZZZ", LocaleListCompat.getDefault()[0])
val datefmtcn get() = SimpleDateFormat("yyyy年MM月dd日 ahh:mm", LocaleListCompat.getDefault()[0])
fun String.toDate(fmt: SimpleDateFormat? = null): Date? = try {
    (fmt ?: datefmt).parse(this)
} catch (_: ParseException) {
    null
}

val String.html: Spanned get() = HtmlCompat.fromHtml(this, HtmlCompat.FROM_HTML_MODE_COMPACT)
fun String.isSameHost(base: String?) = try {
    if (base != null) Uri.parse(this).host == Uri.parse(base).host else false
} catch (_: Exception) {
    false
}

val String.isWordpress
    get() = try {
        Uri.parse(this).path?.startsWith("/wp/") ?: false
    } catch (_: Exception) {
        false
    }

fun Context.openUri(url: String?, web: Boolean? = null): Boolean = when {
    web == true -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    url.isNullOrEmpty() -> null
    !url.isWordpress -> Uri.parse(url).let { uri ->
        startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), uri.scheme))
    }

    Article.getIdFromUrl(url) != null -> startActivity(Intent(this, InfoActivity::class.java).putExtra("url", url))
    Article.isList(url) -> startActivity(Intent(this, ListActivity::class.java).putExtra("url", url))
    web == null -> startActivity(Intent(this, WebActivity::class.java).putExtra("url", url))
    else -> null
} != null

val random = Random(System.currentTimeMillis())

fun randomColor(alpha: Int = 0xFF): Int = android.graphics.Color.HSVToColor(alpha, arrayOf(random.nextInt(360).toFloat(), 1F, 0.5F).toFloatArray())

fun Context.toast(msg: Int): Toast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).also { it.show() }

fun Context.toast(msg: String): Toast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).also { it.show() }

fun Context.clipboard(label: String, text: String) {
    val clipboard = this.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clipboard.setPrimaryClip(clip)
    this.toast(this.getString(R.string.app_copied, text))
}

fun ViewGroup.inflate(layout: Int, attach: Boolean = false): View =
    LayoutInflater.from(this.context).inflate(layout, this, attach)

fun View.childrenSequence(): Sequence<View> = (this as? ViewGroup)?.children ?: emptySequence()
fun View.childrenRecursiveSequence(): Sequence<View> = ViewChildrenRecursiveSequence(this)
private class ViewChildrenRecursiveSequence(private val view: View) : Sequence<View> {
    override fun iterator(): Iterator<View> {
        if (view !is ViewGroup) return emptyList<View>().iterator()
        return RecursiveViewIterator(view)
    }

    private class RecursiveViewIterator(view: View) : Iterator<View> {
        private val sequences = arrayListOf(view.childrenSequence())
        private var current = sequences.removeLast().iterator()

        override fun next(): View {
            if (!hasNext()) throw NoSuchElementException()
            val view = current.next()
            if (view is ViewGroup && view.childCount > 0) {
                sequences.add(view.childrenSequence())
            }
            return view
        }

        override fun hasNext(): Boolean {
            if (!current.hasNext() && sequences.isNotEmpty()) {
                current = sequences.removeLast().iterator()
            }
            return current.hasNext()
        }

        @Suppress("NOTHING_TO_INLINE")
        private inline fun <T : Any> MutableList<T>.removeLast(): T {
            if (isEmpty()) throw NoSuchElementException()
            return removeAt(size - 1)
        }
    }
}

fun FloatingActionButton.setRandomColor(): FloatingActionButton = apply {
    colorNormal = randomColor()
    colorPressed = randomColor()
    colorRipple = randomColor()
}

fun FloatingActionMenu.setRandomColor(): FloatingActionMenu = apply {
    menuButtonColorNormal = randomColor()
    menuButtonColorPressed = randomColor()
    menuButtonColorRipple = randomColor()
}

private val img = listOf(".jpg", ".png", ".webp")

@SuppressLint("DefaultLocale")
fun String.isImg(): Boolean = img.any { this.lowercase(Locale.getDefault()).endsWith(it) }

suspend fun String.httpGetAwait(): Pair<String, String>? = try {
    val request = Request.Builder().get().url(this).build()
    val (html, url) = okhttp.newCall(request).await { _, response -> response.body!!.string() to response.request.url.toString() }
    if (url.startsWith(HAcg.wordpress)) {
        """"user_id":"(\d+)"""".toRegex().find(html)?.let {
            user = it.groups[1]?.value?.toIntOrNull() ?: 0
        }
    }
    html to url
} catch (e: Exception) {
    e.printStackTrace(); null
}

suspend fun String.httpPostAwait(post: Map<String, String>): Pair<String, String>? = try {
    val data = post.toList().fold(MultipartBody.Builder().setType(MultipartBody.FORM)) { b, o -> b.addFormDataPart(o.first, o.second) }.build()
    val request = Request.Builder().url(this).post(data).build()
    val response = okhttp.newCall(request).await { _, response ->
        (response.body!!.string() to response.request.url.toString())
    }
    response
} catch (_: Exception) {
    null
}

suspend fun String.httpDownloadAwait(file: String? = null): File? = try {
    val request = Request.Builder().get().url(this).build()
    okdownloader.newCall(request).await { _, response ->
        val target = if (file == null) {
            val path = response.request.url.toUri().path
            File(HAcgApplication.instance.externalCacheDir, path.substring(path.lastIndexOf('/') + 1))
        } else {
            File(file)
        }
        val sink = target.sink().buffer()
        sink.writeAll(response.body!!.source())
        sink.close()
        target
    }
} catch (e: Exception) {
    e.printStackTrace(); null
}

fun String.test(timeout: Int = 1000): Pair<Boolean, Int> = try {
    val uri = Uri.parse("https://$this")
    (Socket()).use { socket ->
        val address = InetSocketAddress(InetAddress.getByName(uri.host), uri.port.takeIf { it > 0 } ?: 443)
        val begin = System.currentTimeMillis()
        socket.connect(address, timeout)
        (socket.isConnected to (System.currentTimeMillis() - begin).toInt())
    }
} catch (e: Exception) {
    e.printStackTrace(); (false to 0)
}

val rmagnet = """(?<=[^\da-z])([a-z0-9]{40}|[a-z0-9]{32})(?=[^\da-z])""".toRegex(RegexOption.IGNORE_CASE)
val rbaidu = """\b([a-z0-9]{8})\b\s+\b([a-z0-9]{4})\b""".toRegex(RegexOption.IGNORE_CASE)
fun String.magnet(): Sequence<String> = rmagnet.findAll(this).map { it.value } +
        rbaidu.findAll(this).map { m -> "${m.groups[1]!!.value},${m.groups[2]!!.value}" }

fun <T> Gson.fromJsonOrNull(json: String?, clazz: Class<T>): T? = try {
    fromJson(json, clazz)
} catch (_: Exception) {
    null
}

inline fun <reified T> Gson.fromJsonOrNull(json: String?): T? = fromJsonOrNull(json, T::class.java)

fun String.jsoup(uri: String): Document = Jsoup.parse(this, uri)

fun Pair<String, String>.jsoup(): Document = this.let { h ->
    Jsoup.parse(h.first, h.second)
}

fun <T> Pair<String, String>.jsoup(f: (Document) -> T?): T? = f(this.jsoup())
fun Context.version(): Version? = runCatching { Version(BuildConfig.VERSION_NAME) }.getOrNull()
inline fun <reified T : View> View.findViewByViewType(id: Int = 0): Sequence<T> =
    this.childrenRecursiveSequence().mapNotNull { it as? T }.filter { id == 0 || id == it.id }

fun Activity.snack(text: CharSequence, duration: Int = Snackbar.LENGTH_SHORT): Snackbar = this.window.decorView.let { view ->
    view.findViewByViewType<CoordinatorLayout>().firstOrNull()
        ?: view
}.let { Snackbar.make(it, text, duration) }

fun Fragment.arguments(b: Bundle?): Fragment = this.also { it.arguments = b }

fun Bundle.string(key: String, value: String): Bundle = this.also { it.putString(key, value) }

fun Bundle.parcelable(key: String, value: Parcelable): Bundle = this.also { it.putParcelable(key, value) }

suspend fun <A, B> Iterable<A>.pmap(f: suspend (A) -> B): List<B> = coroutineScope { map { async { f(it) } }.awaitAll() }

abstract class DataAdapter<V, VH : RecyclerView.ViewHolder>(private val diffCallback: DiffUtil.ItemCallback<V>) : RecyclerView.Adapter<VH>() {
    private val differ by lazy {
        AsyncListDiffer(AdapterListUpdateCallback(this), AsyncDifferConfig.Builder(diffCallback).build())
    }
    val data: List<V> get() = differ.currentList
    override fun getItemCount(): Int = differ.currentList.size

    open fun clear(): DataAdapter<V, VH> = apply {
        differ.submitList(null)
    }

    open fun add(v: V, index: Int = data.size): DataAdapter<V, VH> = apply {
        differ.submitList(data.toMutableList().apply { add(index, v) })
    }

    open fun addAll(v: List<V>): DataAdapter<V, VH> = apply {
        differ.submitList(data.toMutableList().apply { addAll(v) })
    }

    open fun remove(v: V): DataAdapter<V, VH> = apply {
        differ.submitList(data.toMutableList().apply { remove(v) })
    }

    open fun getItem(position: Int): V? = data[position]
}

abstract class PagingAdapter<V, VH : RecyclerView.ViewHolder>(diffCallback: DiffUtil.ItemCallback<V>) : DataAdapter<V, VH>(diffCallback) {
    private val refreshCh = Channel<Boolean>()
    val refreshFlow = refreshCh.consumeAsFlow()
    val state = MutableLiveData<LoadState>()
    fun withLoadStateFooter(footer: LoadStateAdapter<*>): ConcatAdapter {
        state.observeForever { footer.loadState = it }
        return ConcatAdapter(this, footer)
    }

    override fun add(v: V, index: Int): DataAdapter<V, VH> = apply {
        val fist = itemCount == 0
        super.add(v, index)
        if (fist && itemCount != 0) refreshCh.trySend(true)
    }

    override fun addAll(v: List<V>): DataAdapter<V, VH> = apply {
        val fist = itemCount == 0
        super.addAll(v)
        if (fist && itemCount != 0) refreshCh.trySend(true)
    }
}

abstract class LoadStateAdapter<VH : RecyclerView.ViewHolder> : RecyclerView.Adapter<VH>() {
    private var display: Boolean = false
        set(value) {
            if (field != value) {
                if (value) notifyItemInserted(0) else notifyItemRemoved(0)
                field = value
            }
        }
    var loadState: LoadState = LoadState.NotLoading(endOfPaginationReached = false)
        set(value) {
            if (field != value) field = value
            val new = displayLoadStateAsItem(value)
            if (display != new) display = new else {
                if (field != value) notifyItemChanged(0)
            }
        }

    final override fun getItemCount(): Int = if (display) 1 else 0
    final override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return onCreateViewHolder(parent, loadState)
    }

    final override fun onBindViewHolder(holder: VH, position: Int) {
        onBindViewHolder(holder, loadState)
    }

    final override fun getItemViewType(position: Int): Int = getStateViewType(loadState)
    abstract fun onCreateViewHolder(parent: ViewGroup, loadState: LoadState): VH
    abstract fun onBindViewHolder(holder: VH, loadState: LoadState)
    open fun getStateViewType(loadState: LoadState): Int = 0
    open fun displayLoadStateAsItem(loadState: LoadState): Boolean = loadState is LoadState.Loading || loadState is LoadState.Error
}

fun <T : Any> SavedStateHandle.saveAsJson(it: T, name: String) {
    set("${name}-Class", it.javaClass.name)
    set("${name}-Json", gson.toJson(it))
}

fun <T : Any> SavedStateHandle.loadForJson(name: String, def: () -> T?): T? {
    val clazz = get<String>("${name}-Class")?.let { Class.forName(it) }
    val json = get<String>("${name}-Json")
    @Suppress("UNCHECKED_CAST")
    return (if (clazz != null && json != null)
        gson.fromJson(json, clazz) as? T
    else null) ?: def()
}

@Parcelize
open class LoadState : Parcelable {
    @Parcelize
    class NotLoading(val endOfPaginationReached: Boolean) : LoadState() {
        override fun toString(): String = "NotLoading(endOfPaginationReached=$endOfPaginationReached)"
        override fun equals(other: Any?): Boolean = other is NotLoading && endOfPaginationReached == other.endOfPaginationReached
        override fun hashCode(): Int = endOfPaginationReached.hashCode()

        internal companion object {
            internal val Complete = NotLoading(endOfPaginationReached = true)
            internal val Incomplete = NotLoading(endOfPaginationReached = false)
        }
    }

    @Parcelize
    data object Loading : LoadState() {
        override fun toString(): String = "Loading"
    }

    @Parcelize
    class Error(val error: Throwable) : LoadState() {
        override fun equals(other: Any?): Boolean = other is Error && error == other.error
        override fun hashCode(): Int = error.hashCode()
        override fun toString(): String = "Error(error=$error)"
    }
}

class Paging<K : Any, V : Any>(private val handle: SavedStateHandle, private val k: K?, factory: () -> PagingSource<K, V>) {
    private var key: K?
        get() = if (handle.contains("key")) handle["key"] else k
        set(value) = handle.set("key", value)
    val state = handle.getLiveData<LoadState>("state", LoadState.NotLoading(false))
    private val source by lazy(factory)
    private val mutex = Mutex()
    suspend fun query(refresh: Boolean = false): Pair<List<V>?, Throwable?> {
        mutex.withLock {
            if (state.value is LoadState.Loading) return null to null
            if (refresh) handle.remove<String?>("key")
            if (key == null) return null to null
            state.setValue(LoadState.Loading)
        }
        return when (val result = source.load(PagingSource.LoadParams.Append(key!!, 20, false))) {
            is PagingSource.LoadResult.Page -> {
                key = result.nextKey
                state.postValue(LoadState.NotLoading(result.nextKey == null))
                result.data to null
            }

            is PagingSource.LoadResult.Error -> {
                state.postValue(LoadState.Error(result.throwable))
                null to result.throwable
            }

            is PagingSource.LoadResult.Invalid -> {
                state.postValue(LoadState.Error(Exception("Invalid")))
                null to Exception("Invalid")
            }
        }
    }
}

class RoundedBackgroundColorSpan(private val backgroundColor: Int) : ReplacementSpan() {
    private val linePadding = 2f // play around with these as needed
    private val sidePadding = 5f // play around with these as needed
    private fun measureText(paint: Paint, text: CharSequence, start: Int, end: Int): Float =
        paint.measureText(text, start, end)

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, p4: Paint.FontMetricsInt?): Int =
        (measureText(paint, text, start, end) + (2 * sidePadding)).roundToInt()

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val rect = RectF(
            x, y + paint.fontMetrics.top - linePadding,
            x + getSize(paint, text, start, end, paint.fontMetricsInt),
            y + paint.fontMetrics.bottom + linePadding
        )
        paint.color = backgroundColor
        canvas.drawRoundRect(rect, 5F, 5F, paint)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawText(text, start, end, x + sidePadding, y * 1F, paint)
    }

}

class TagClickableSpan<T>(private val tag: T, private val call: ((T) -> Unit)? = null) : ClickableSpan() {
    override fun onClick(widget: View) {
        call?.invoke(tag)
    }

    override fun updateDrawState(ds: TextPaint) {
        ds.color = 0xFFFFFFFF.toInt()
        ds.isUnderlineText = false
    }
}

fun <T> List<T>.spannable(separator: CharSequence = " ", string: (T) -> String = { "$it" }, call: ((T) -> Unit)?): SpannableStringBuilder {

    val tags = this.joinToString(separator) { string(it) }
    val span = SpannableStringBuilder(tags)
    fold(0) { i, it ->
        val p = tags.indexOf(string(it), i)
        val e = p + string(it).length
        if (call != null) span.setSpan(TagClickableSpan(it, call), p, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(RoundedBackgroundColorSpan(randomColor(0xBF)), p, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        e
    }
    return span
}

class Once {
    private var init = false

    @Synchronized
    fun run(call: () -> Unit) {
        if (init) return
        init = true
        call()
    }
}

fun RecyclerView.loading(last: Int = 1, call: () -> Unit) {
    this.addOnScrollListener(object : RecyclerView.OnScrollListener() {
        fun load(recycler: RecyclerView) {
            when (val layout = recycler.layoutManager) {
                is StaggeredGridLayoutManager -> {
                    val vis = layout.findLastVisibleItemPositions(null)
                    val v = vis.maxOrNull() ?: 0
                    if (v >= (this@loading.adapter!!.itemCount - last)) call()
                }

                is GridLayoutManager ->
                    if (layout.findLastVisibleItemPosition() >= this@loading.adapter!!.itemCount - last) call()

                is LinearLayoutManager ->
                    if (layout.findLastVisibleItemPosition() >= this@loading.adapter!!.itemCount - last) call()
            }
        }

        val once = Once()

        override fun onScrolled(recycler: RecyclerView, dx: Int, dy: Int) {
            once.run {
                load(recycler)
            }
        }

        override fun onScrollStateChanged(recycler: RecyclerView, state: Int) {
            if (state != RecyclerView.SCROLL_STATE_IDLE) return
            load(recycler)
        }
    })
}

open class SwipeFinishActivity : AppCompatActivity() {
    @Deprecated("Disable super setContentView", ReplaceWith("super.setContentView(layoutResID)", "io.github.yueeng.hacg.SwipeFinishActivity"))
    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
    }

    @Deprecated("Disable super setContentView", ReplaceWith("super.setContentView(layoutResID)", "io.github.yueeng.hacg.SwipeFinishActivity"))
    override fun setContentView(view: View?) {
        super.setContentView(view)
    }

    @Deprecated("Disable super setContentView", ReplaceWith("super.setContentView(layoutResID)", "io.github.yueeng.hacg.SwipeFinishActivity"))
    override fun setContentView(view: View?, params: ViewGroup.LayoutParams?) {
        super.setContentView(view, params)
    }

    fun setContentView(layout: Int, callback: (View) -> Unit) {
        setContentView(layoutInflater.inflate(layout, null), callback)
    }

    fun setContentView(layout: View, callback: (View) -> Unit) {
        super.setContentView(R.layout.activity_swipeback)
        val pager = super.findViewById<ViewPager2>(R.id.swipe_host)
        pager.adapter = SwipeBackAdapter(layout, callback)
        pager.offscreenPageLimit = 2
        pager.setCurrentItem(1, false)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            var pos = -1

            override fun onPageSelected(position: Int) {
                pos = position
            }

            override fun onPageScrollStateChanged(state: Int) {
                if (state != ViewPager2.SCROLL_STATE_IDLE || pos != 0) return
                finish()
                if (Build.VERSION.SDK_INT >= 34) {
                    overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
                } else {
                    @Suppress("DEPRECATION") overridePendingTransition(0, 0)
                }
            }
        })
    }

    override fun <T : View?> findViewById(id: Int): T? {
        val pager = super.findViewById<ViewPager2>(R.id.swipe_host)?.children?.firstOrNull() as? RecyclerView
        val holder = pager?.findViewHolderForLayoutPosition(1)
        return holder?.itemView?.findViewById<T>(id)
    }

    class SwipeBackHolder(view: View) : RecyclerView.ViewHolder(view)

    class SwipeBackAdapter(private val layout: View, private val callback: (View) -> Unit) : RecyclerView.Adapter<SwipeBackHolder>() {
        override fun getItemViewType(position: Int): Int = when (position) {
            0 -> 0
            1 -> 1
            else -> throw IllegalArgumentException("Position $position is not supported")
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SwipeBackHolder = when (viewType) {
            0 -> SwipeBackHolder(LayoutInflater.from(parent.context).inflate(R.layout.swipeback_start, parent, false))
            1 -> LayoutInflater.from(parent.context).let { inflater ->
                val host = inflater.inflate(R.layout.swipeback_container, parent, false) as ViewGroup
                host.addView(layout)
                SwipeBackHolder(host)
            }

            else -> throw IllegalArgumentException("ViewType $viewType is not supported")
        }


        override fun onBindViewHolder(holder: SwipeBackHolder, position: Int) {
            if (position == 1) holder.itemView.post { callback(holder.itemView) }
        }

        override fun getItemCount(): Int = 2
    }

}

/**
 * Layout to wrap a scrollable component inside a ViewPager2. Provided as a solution to the problem
 * where pages of ViewPager2 have nested scrollable elements that scroll in the same direction as
 * ViewPager2. The scrollable element needs to be the immediate and only child of this host layout.
 *
 * This solution has limitations when using multiple levels of nested scrollable elements
 * (e.g. a horizontal RecyclerView in a vertical RecyclerView in a horizontal ViewPager2).
 */
class NestedScrollableHost : FrameLayout {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    private var touchSlop = 0
    private var initialX = 0f
    private var initialY = 0f
    private val parentViewPager: ViewPager2?
        get() {
            var v: View? = parent as? View
            while (v != null && v !is ViewPager2) {
                v = v.parent as? View
            }
            return v as? ViewPager2
        }

    init {
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    }

    private fun canChildScroll(orientation: Int, delta: Float): Boolean {
        val direction = -delta.sign.toInt()
        return when (orientation) {
            0 -> descendants.any { it.canScrollHorizontally(direction) }
            1 -> descendants.any { it.canScrollVertically(direction) }
            else -> throw IllegalArgumentException()
        }
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        handleInterceptTouchEvent(e)
        return super.onInterceptTouchEvent(e)
    }

    private fun handleInterceptTouchEvent(e: MotionEvent) {
        val orientation = parentViewPager?.orientation ?: return

        // Early return if child can't scroll in same direction as parent
        if (!canChildScroll(orientation, -1f) && !canChildScroll(orientation, 1f)) {
            return
        }

        if (e.action == MotionEvent.ACTION_DOWN) {
            initialX = e.x
            initialY = e.y
            parent.requestDisallowInterceptTouchEvent(true)
        } else if (e.action == MotionEvent.ACTION_MOVE) {
            val dx = e.x - initialX
            val dy = e.y - initialY
            val isVpHorizontal = orientation == ViewPager2.ORIENTATION_HORIZONTAL

            // assuming ViewPager2 touch-slop is 2x touch-slop of child
            val scaledDx = dx.absoluteValue * if (isVpHorizontal) .5f else 1f
            val scaledDy = dy.absoluteValue * if (isVpHorizontal) 1f else .5f

            if (scaledDx > touchSlop || scaledDy > touchSlop) {
                if (isVpHorizontal == (scaledDy > scaledDx)) {
                    // Gesture is perpendicular, allow all parents to intercept
                    parent.requestDisallowInterceptTouchEvent(false)
                } else {
                    // Gesture is parallel, query child if movement in that direction is possible
                    if (canChildScroll(orientation, if (isVpHorizontal) dx else dy)) {
                        // Child can scroll, disallow all parents to intercept
                        parent.requestDisallowInterceptTouchEvent(true)
                    } else {
                        // Child cannot scroll, allow all parents to intercept
                        parent.requestDisallowInterceptTouchEvent(false)
                    }
                }
            }
        }
    }
}

class HacgPermissionFragment : Fragment() {
    private lateinit var callback: (Map<String, Boolean>) -> Unit
    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        callback.invoke(permissions)
    }

    fun request(vararg permissions: String, call: (Map<String, Boolean>) -> Unit) {
        callback = call
        requestPermissions.launch(arrayOf(*permissions))
    }

}

@Suppress("MemberVisibilityCanBePrivate", "unused")
class HacgPermission(val fragmentManager: FragmentManager) {
    fun request(vararg permissions: String, call: (Map<String, Boolean>) -> Unit) {
        val fragment = HacgPermissionFragment()
        fragmentManager.commitNow { add(fragment, HacgPermissionFragment::class.java.simpleName) }
        fragment.request(*permissions) {
            fragmentManager.commitNow { remove(fragment) }
            call(it)
        }
    }

    companion object {
        fun with(fragment: Fragment) = HacgPermission(fragment.childFragmentManager)
        fun with(activity: FragmentActivity) = HacgPermission(activity.supportFragmentManager)
        fun Context.isPermissionGranted(vararg permission: String): Boolean = permission.all {
            PermissionChecker.checkSelfPermission(this, it) == PermissionChecker.PERMISSION_GRANTED
        }

        fun Activity.showRequestPermissionRationale(permission: String) =
            !isPermissionGranted(permission) && ActivityCompat.shouldShowRequestPermissionRationale(this, permission)

        fun HacgPermission.checkPermissions(activity: Activity, vararg permission: String, granted: () -> Unit) = activity.run {
            if (isPermissionGranted(*permission)) {
                granted()
                return@run
            }
            request(*permission) { permissions ->
                if (permissions.all { it.value }) granted()
                else {
                    val message = permissions.filter { !it.value }
                        .map { packageManager.getPermissionInfo(it.key, PackageManager.GET_META_DATA) }
                        .mapNotNull { it.loadDescription(packageManager) }
                        .joinToString(",")
                    activity.snack(message, Snackbar.LENGTH_LONG)
                        .setAction(R.string.app_settings) {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                        }.show()
                }
            }
        }

        fun FragmentActivity.checkPermissions(vararg permission: String, granted: () -> Unit) =
            with(this).checkPermissions(this, *permission, granted = granted)

        fun Fragment.checkPermissions(vararg permission: String, granted: () -> Unit) =
            with(this).checkPermissions(requireActivity(), *permission, granted = granted)
    }
}

inline fun <reified T> Intent.getParcelableExtraCompat(key: String): T? = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getParcelableExtra(key, T::class.java)
    else -> @Suppress("DEPRECATION") getParcelableExtra(key) as? T?
}

inline fun <reified T : Serializable> Intent.getSerializableExtraCompat(key: String): T? = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getSerializableExtra(key, T::class.java)
    else -> @Suppress("DEPRECATION") getSerializableExtra(key) as? T?
}

inline fun <reified T> Bundle.getParcelableCompat(key: String): T? = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getParcelable(key, T::class.java)
    else -> @Suppress("DEPRECATION") getParcelable(key) as? T?
}

fun OnBackPressedDispatcher.bubbleOnBackPressed(callback: OnBackPressedCallback) {
    callback.isEnabled = false
    onBackPressed()
    callback.isEnabled = true
}

fun ComponentActivity.addOnBackPressedCallback(owner: LifecycleOwner? = this, callback: OnBackPressedCallback.() -> Boolean): OnBackPressedCallback = onBackPressedDispatcher.addCallback(owner) {
    if (callback(this)) return@addCallback
    onBackPressedDispatcher.bubbleOnBackPressed(this)
}