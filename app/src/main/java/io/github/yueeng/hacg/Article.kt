@file:Suppress("MayBeConstant", "unused", "MemberVisibilityCanBePrivate", "FunctionName")

package io.github.yueeng.hacg

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.os.Parcelable
import android.text.InputType
import android.view.LayoutInflater
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import com.google.android.material.snackbar.Snackbar
import com.google.gson.annotations.SerializedName
import kotlinx.android.parcel.IgnoredOnParcel
import kotlinx.android.parcel.Parcelize
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.io.File
import java.io.PrintWriter
import java.util.*

object HAcg {
    private val SYSTEM_HOST: String = "system.host"
    private val SYSTEM_HOSTS: String = "system.hosts"

    private val config_file: File get() = File(HAcgApplication.instance.filesDir, "config.json")

    @Synchronized
    private fun config_string(): String = if (config_file.exists())
        config_file.readText()
    else HAcgApplication.instance.assets.open("config.json").use { s ->
        s.reader().use { it.readText() }
    }

    @Synchronized
    private fun default_config(): JSONObject? = try {
        JSONObject(config_string())
    } catch (_: Exception) {
        null
    }

    private fun default_hosts(cfg: JSONObject? = null): Sequence<String> = try {
        (cfg ?: default_config())!!.getJSONArray("host").let { json ->
            (0 until json.length()).asSequence().map { json.getString(it) }
        }
    } catch (_: Exception) {
        sequenceOf("www.hacg.me")
    }

    private fun default_category(cfg: JSONObject? = null): Sequence<Pair<String, String>> = try {
        (cfg ?: default_config())!!.getJSONArray("category").let { a ->
            (0 until a.length()).asSequence().map { a.getJSONObject(it) }.map { it.getString("url") to it.getString("name") }
        }
    } catch (_: Exception) {
        sequenceOf()
    }

    private fun default_bbs(cfg: JSONObject? = null): String = try {
        (cfg ?: default_config())!!.getString("bbs")
    } catch (_: Exception) {
        "/wp/bbs"
    }

    private val config = PreferenceManager.getDefaultSharedPreferences(HAcgApplication.instance).also { c ->
        val avc = "app.version.code"
        if (c.getInt(avc, 0) < BuildConfig.VERSION_CODE) {
            c.edit()
                    .remove(SYSTEM_HOST)
                    .remove(SYSTEM_HOSTS)
                    .putInt(avc, BuildConfig.VERSION_CODE)
                    .apply()
            config_file.delete()
        }
    }

    private var save_hosts: Sequence<String>
        get() = try {
            config.getString(SYSTEM_HOSTS, null).let { s ->
                JSONArray(s).let { a -> (0 until a.length()).asSequence().map { k -> a.getString(k) } }
            }
        } catch (_: Exception) {
            sequenceOf()
        }
        set(hosts): Unit = config.edit().also { c ->
            c.remove(SYSTEM_HOSTS)
            if (hosts.any())
                c.remove(SYSTEM_HOSTS).putString(SYSTEM_HOSTS, hosts.distinct().fold(JSONArray()) { j, i -> j.put(i) }.toString())
        }.apply()

    fun hosts(): Sequence<String> = (save_hosts + default_hosts()).distinct()

    private fun _host(): String? = config.getString(SYSTEM_HOST, null)

    var host: String
        get() = _host()?.takeIf { it.isNotEmpty() } ?: (hosts().first())
        set(host): Unit = config.edit().also { c ->
            if (host.isEmpty()) c.remove(SYSTEM_HOST) else c.putString(SYSTEM_HOST, host)
        }.apply()

    val bbs: String
        get() = default_bbs()

    val categories: Sequence<Pair<String, String>>
        get() = default_category()

    init {
        if (_host().isNullOrEmpty()) host = (hosts().first())
    }

    fun update(context: Activity, tip: Boolean, f: () -> Unit) {
        "https://raw.githubusercontent.com/yueeng/hacg/master/app/src/main/assets/config.json".httpGetAsync(context) { html ->
            when {
                html == null -> {
                }
                html.first != config_string() -> {
                    context.snack(context.getString(R.string.settings_config_updating), Snackbar.LENGTH_LONG)
                            .setAction(R.string.settings_config_update) { _ ->
                                try {
                                    val config = JSONObject(html.first)
                                    host = (default_hosts(config).first())
                                    PrintWriter(config_file).use { it.write(html.first) }
                                    f()
                                } catch (_: Exception) {
                                }
                            }.show()
                }
                else -> {
                    if (tip) context.toast(R.string.settings_config_newest)
                }
            }
        }
    }

    val IsHttp: Regex = """^https?://.*$""".toRegex()
    val RELEASE = "https://github.com/yueeng/hacg/releases"

    val web get() = "https://$host"

    val domain: String
        get() = host.indexOf('/').takeIf { it >= 0 }?.let { host.substring(0, it) } ?: host

    val wordpress: String get() = "$web/wp"

    val philosophy: String
        get() = bbs.takeIf { IsHttp.matches(it) }?.let { bbs } ?: "$web$bbs"

    @SuppressLint("InflateParams")
    fun setHostEdit(context: Context, title: Int, list: () -> Sequence<String>, cur: () -> String, set: (String) -> Unit, ok: (String) -> Unit, reset: () -> Unit) {
        val view = LayoutInflater.from(context).inflate(R.layout.alert_host, null)
        val edit = view.findViewById<EditText>(R.id.edit1)
        edit.inputType = InputType.TYPE_TEXT_VARIATION_URI
        AlertDialog.Builder(context)
                .setTitle(title)
                .setView(view)
                .setNegativeButton(R.string.app_cancel, null)
                .setOnDismissListener { setHostList(context, title, list, cur, set, ok, reset) }
                .setNeutralButton(R.string.settings_host_reset) { _, _ -> reset() }
                .setPositiveButton(R.string.app_ok) { _, _ ->
                    val host = edit.text.toString()
                    if (host.isNotEmpty()) {
                        ok(host)
                    }
                }
                .create().show()
    }

    fun setHostList(context: Context, title: Int, list: () -> Sequence<String>, cur: () -> String, set: (String) -> Unit, ok: (String) -> Unit, reset: () -> Unit) {
        val hosts = list().toList()
        AlertDialog.Builder(context)
                .setTitle(title)
                .setSingleChoiceItems(hosts.map { it as CharSequence }.toTypedArray(), hosts.indexOf(cur()).takeIf { it >= 0 }
                        ?: 0, null)
                .setNegativeButton(R.string.app_cancel, null)
                .setNeutralButton(R.string.settings_host_more) { _, _ -> setHostEdit(context, title, list, cur, set, ok, reset) }
                .setPositiveButton(R.string.app_ok) { d, _ -> set(hosts[(d as AlertDialog).listView.checkedItemPosition]) }
                .create().show()
    }

    fun setHost(context: Context, ok: (String) -> Unit = {}) {
        setHostList(context,
                R.string.settings_host,
                { hosts() },
                { host },
                {
                    host = it
                    ok(host)
                },
                { host -> save_hosts = (save_hosts + host) },
                { save_hosts = (sequenceOf()) }
        )
    }
}

@Parcelize
data class Tag(val name: String, val url: String) : Parcelable {
    constructor(e: Element) :
            this(e.text(), e.attr("abs:href"))
}

@Parcelize
data class Comment(val id: Int, val parent: Int, val content: String, val user: String, val face: String,
                   var moderation: Int, val time: String, val children: MutableList<Comment>, val depth: Int = 1) : Parcelable {
    companion object {
        val ID: Regex = """wpd-comm-(\d+)_(\d+)""".toRegex()
    }

    val uniqueId: String get() = "${id}_$parent"

    constructor(e: Element, depth: Int = 1) :
            this(ID.find(e.attr("id"))?.let { it.groups[1]?.value }?.toInt() ?: 0,
                    ID.find(e.attr("id"))?.let { it.groups[2]?.value }?.toInt() ?: 0,
                    e.select(">.wpd-comment-wrap .wpd-comment-text").text(),
                    e.select(">.wpd-comment-wrap .wpd-comment-author").text(),
                    e.select(">.wpd-comment-wrap .avatar").attr("abs:src"),
                    e.select(">.wpd-comment-wrap .wpd-vote-result").text().toIntOrNull() ?: 0,
                    e.select(">.wpd-comment-wrap .wpd-comment-date").text(),
                    e.select(">.wpd-comment-wrap~.wpd-reply").map { Comment(it, depth + 1) }.toMutableList(),
                    depth
            )
}

data class JComment(
        @SerializedName("last_parent_id") val lastParentId: String,
        @SerializedName("is_show_load_more") val isShowLoadMore: Boolean,
        @SerializedName("comment_list") val commentList: String,
        @SerializedName("loadLastCommentId") val loadLastCommentId: String
)

data class JWpdiscuzComment(
        @SerializedName("success") val success: Boolean,
        @SerializedName("data") val data: JComment
)

data class JCommentResult(
        @SerializedName("code") val code: String,
        @SerializedName("comment_author") val commentAuthor: String,
        @SerializedName("comment_author_email") val commentAuthorEmail: String,
        @SerializedName("comment_author_url") val commentAuthorUrl: String,
        @SerializedName("held_moderate") val held_moderate: Int,
        @SerializedName("is_in_same_container") val isInSameContainer: String,
        @SerializedName("is_main") val is_main: Int,
        @SerializedName("message") val message: String,
        @SerializedName("new_comment_id") val newCommentId: Int,
        @SerializedName("redirect") val redirect: Int,
        @SerializedName("uniqueid") val uniqueid: String,
        @SerializedName("wc_all_comments_count_new") val wcAllCommentsCountNew: String,
        @SerializedName("wc_all_comments_count_new_html") val wcAllCommentsCountNewHtml: String
)

data class JWpdiscuzCommentResult(
        @SerializedName("success") val success: Boolean,
        @SerializedName("data") val data: JCommentResult
)

data class JWpdiscuzVote(
        @SerializedName("success") val success: Boolean,
        @SerializedName("data") val data: String
)

data class JWpdiscuzVoteSucceed(
        @SerializedName("success") val success: Boolean,
        @SerializedName("data") val data: JWpdiscuzVoteSucceedData
)

data class JWpdiscuzVoteSucceedData(
        @SerializedName("buttonsStyle") val buttonsStyle: String,
        @SerializedName("votes") val votes: String
)

@Parcelize
data class Article(val id: Int, val title: String,
                   val link: String?,
                   val image: String?,
                   val content: String?,
                   val time: Date?,
                   val comments: Int,
                   val author: Tag?,
                   val category: Tag?,
                   val tags: List<Tag>) : Parcelable {
    companion object {
        val ID: Regex = """post-(\d+)""".toRegex()
    }

    constructor(msg: String) : this(0, msg, null, null, null, null, 0, null, null, listOf())

    @IgnoredOnParcel
    private val defimg = "${ContentResolver.SCHEME_ANDROID_RESOURCE}://${this::class.java.`package`!!.name}/drawable/placeholder"

    @IgnoredOnParcel
    val img: String
        get() = if (image.isNullOrEmpty()) defimg else image

    @IgnoredOnParcel
    val expend: List<Tag> by lazy { (tags + category + author).mapNotNull { it } }

    constructor(e: Element) :
            this(ID.find(e.attr("id"))?.let { it.groups[1]?.value?.toInt() } ?: 0,
                    e.select("header .entry-title a").text().trim(),
                    e.select("header .entry-title a").attr("abs:href"),
                    e.select(".entry-content img").let { img ->
                        img.takeIf { it.hasClass("avatar") }?.let { "" } ?: img.attr("abs:src")
                    },
                    e.select(".entry-content p,.entry-summary p").text().trim(),
                    e.select("time").attr("datetime").toDate(),
                    e.select("header .comments-link").text().trim().toIntOrNull() ?: 0,
                    e.select(".author a").take(1).map { Tag(it) }.firstOrNull(),
                    e.select("footer .cat-links a").take(1).map { Tag(it) }.firstOrNull(),
                    e.select("footer .tag-links a").map { Tag(it) }.toList())
}