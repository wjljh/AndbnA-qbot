package com.wjljh.andbna.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * 人格档的两处来源：
 *
 *  · 内置：APK 里 assets/personas 目录下的 txt（现在只剩「-完整」档 + 独立的猫娘，没有变体的分档）
 *  · 自定义：用户在人格下拉里点「自定义…（导入文件）」导入的 md/txt，
 *    复制到应用私有目录 files/personas/<安全文件名>.txt —— 只读系统文件选择器给的那一份，
 *    从不写 /sdcard。
 *
 * 下拉里自定义项的显示名统一带 [CUSTOM_PREFIX] 前缀（「自定义：<文件名>」），
 * 读内容时按前缀分流：有前缀读 files/personas/，否则读 assets/。
 */
object PersonaAssets {

    private const val DIR = "personas"
    private const val PREF = "andbna_local"
    private const val KEY = "local_persona"

    /** 自定义人格的显示前缀（也是 bot.persona 落盘的值的一部分）。 */
    const val CUSTOM_PREFIX = "自定义："

    /** 导入上限 256KB，超了直接拒绝。 */
    const val MAX_BYTES = 256 * 1024

    /** 内置预设的删除拒绝文案（页面直接显示这一句）。 */
    const val BUILTIN_LOCKED = "内置预设不可删除"

    private const val TAG = "PersonaAssets"

    /** 原文件名的侧车后缀：<档名>.orig 里存导入时的原始文件名，删除时一起删。 */
    private const val ORIG_SUFFIX = ".orig"

    /**
     * 人格来源标记（<botdir>/persona_meta.json）。
     *
     * Python 侧以前按**人格名字**猜内置：用户导入的同名人格（也叫「鲸鱼娘」）就被当成内置档，
     * 定时问候锁死、提示「内置人格不能更改」。这份标记才是唯一可信的来源：
     * {"builtin": true|false, "name": "<人格名>"}（name 不带「自定义：」前缀）。
     */
    const val META = "persona_meta.json"

    /** 导入结果：失败也要给一句人能读的原因；成功时 message = 导入时的原文件名。 */
    class ImportResult(val ok: Boolean = false, val name: String = "", val message: String = "")

    /** 删除结果：同上，失败也说清为什么。 */
    class DeleteResult(val ok: Boolean = false, val name: String = "", val message: String = "")

    /** 列出内置档名（去掉 .txt） */
    fun list(ctx: Context): List<String> = try {
        ctx.assets.list(DIR)
            ?.filter { it.endsWith(".txt") }
            ?.mapNotNull { it.removeSuffix(".txt").takeIf { n -> n.isNotBlank() } }
            ?.sorted() ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    /** 自定义人格的落盘目录（应用私有，不需要任何存储权限）。 */
    fun customDir(ctx: Context): File = File(ctx.filesDir, DIR)

    private fun customFile(ctx: Context, name: String): File =
        File(customDir(ctx), name.removePrefix(CUSTOM_PREFIX).trim() + ".txt")

    /** 已导入的自定义人格名（不带前缀，按名字排序）。 */
    fun customNames(ctx: Context): List<String> = try {
        customDir(ctx).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".txt") }
            ?.mapNotNull { it.name.removeSuffix(".txt").takeIf { n -> n.isNotBlank() } }
            ?.sorted() ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    /** 下拉的数据源：内置档（按名字）+ 自定义档（带前缀），自定义排在末尾。 */
    fun listAll(ctx: Context): List<String> =
        list(ctx) + customNames(ctx).map { CUSTOM_PREFIX + it }

    fun isCustom(name: String): Boolean = name.startsWith(CUSTOM_PREFIX)

    /** 读某档正文：自定义读私有文件，内置读 assets。读不到返回 null。 */
    fun readText(ctx: Context, name: String): String? = try {
        if (isCustom(name)) {
            val f = customFile(ctx, name)
            if (f.isFile) f.readText(Charsets.UTF_8).trim() else null
        } else {
            ctx.assets.open("$DIR/$name.txt").bufferedReader().use { it.readText() }.trim()
        }
    } catch (e: Exception) {
        null
    }

    fun selected(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    /**
     * 旧版的「选人格」入口：原来只写 files/localbot/persona.txt —— 那是单 bot 时代的共享目录，
     * 多 bot 之后 Python 读的是 files/localbot/<appid>/persona.txt，**那份谁都不读**，
     * 所以它写下去也不生效（本轮修的就是这类「改了不生效」）。
     *
     * 现在改成：选中值照记，正文给**每个** bot 目录各写一份（人格清单仍由编辑页的保存流程负责）。
     * 返回值是写入的字数，0 表示读不到这一档。
     */
    fun select(ctx: Context, name: String): Int {
        val text = readText(ctx, name) ?: return 0
        return try {
            val base = File(ctx.filesDir, "localbot").apply { mkdirs() }
            BotStore.load(ctx).map { it.appid }.filter { it.isNotBlank() }.forEach { id ->
                val dir = File(base, id).apply { mkdirs() }
                File(dir, "persona.txt").writeText(text + "\n")
                File(dir, "persona_name.txt").writeText(name + "\n")
                // 来源标记跟正文同步写：切换人格也是「应用人格」的一种
                writePersonaMeta(dir, isBuiltinName(ctx, name), name)
            }
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY, name).apply()
            text.length
        } catch (e: Exception) {
            0
        }
    }

    /**
     * 分档视图（「内容」页用）。
     *
     * 现在 assets 里只剩「-完整」档 + 猫娘，所以 精华 / 精简 两组通常为空 ——
     * 空组由 filterValues 丢掉，页面只显示还剩的那几组。
     */
    fun group(ctx: Context): Map<String, List<String>> {
        val all = list(ctx)
        val out = LinkedHashMap<String, MutableList<String>>()
        out["完整"] = mutableListOf()
        out["精华"] = mutableListOf()
        out["精简"] = mutableListOf()
        all.forEach { n ->
            when {
                n.endsWith("-完整") -> out["完整"]!!.add(n)
                n.endsWith("-精华") -> out["精华"]!!.add(n)
                else -> out["精简"]!!.add(n)
            }
        }
        return out.filterValues { it.isNotEmpty() }
    }

    /**
     * 把某档人格写进指定目录（多 bot 时每个 bot 一份；自定义与内置走同一入口）。
     *
     * persona.txt / persona_name.txt / persona_meta.json 三份一起写：只写正文的话，
     * Python 侧就只能按名字猜来源 —— 这正是「导入的同名人格被当成内置」的根因。
     */
    fun writeTo(ctx: Context, name: String, dir: java.io.File): Boolean {
        val text = readText(ctx, name) ?: return false
        return try {
            dir.mkdirs()
            File(dir, "persona.txt").writeText(text + "\n")
            File(dir, "persona_name.txt").writeText(name + "\n")
            writePersonaMeta(dir, isBuiltinName(ctx, name), name)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 这个名字是不是内置档（assets/personas 里那份）。自定义档一律带前缀，所以先看前缀。 */
    fun isBuiltinName(ctx: Context, name: String): Boolean =
        !isCustom(name) && list(ctx).contains(name.trim())

    /**
     * 写 <botdir>/persona_meta.json —— 所有写人格的路径都从这里过，别各处散写。
     *
     * 名字去掉「自定义：」前缀再落盘：Python 侧拿它跟内置档名比对，前缀会让比对永远落空。
     */
    fun writePersonaMeta(dir: File, builtin: Boolean, name: String): Boolean = try {
        dir.mkdirs()
        val o = JSONObject()
            .put("builtin", builtin)
            .put("name", name.removePrefix(CUSTOM_PREFIX).trim())
        File(dir, META).writeText(o.toString(1), Charsets.UTF_8)
        true
    } catch (e: Exception) {
        Log.w(TAG, "写 " + META + " 失败：" + (e.message ?: e.javaClass.simpleName))
        false
    }

    /**
     * 老 bot 没有 persona_meta.json：按**当前人格的来源**补写一次。
     *
     * Python 侧对缺文件的兼容是「回退按名字」，所以不补也能跑（同名人格照旧判错），
     * 但打开 / 保存这个 bot 时就地补上，老数据会自然收敛。
     */
    fun ensurePersonaMeta(ctx: Context, name: String, dir: File): Boolean {
        if (name.isBlank()) return false
        if (File(dir, META).isFile) return false
        return writePersonaMeta(dir, isBuiltinName(ctx, name), name)
    }

    // ── 自定义人格：导入 / 删除 ─────────────────────────────────────────

    /**
     * 导入一个 md/txt：OpenDocument 给的是只读 uri，这里只读它，从不回写。
     *
     * 校验顺序：扩展名（.md/.txt）→ 大小（256KB）→ 编码（严格 UTF-8，坏了回退 GBK）
     * → 安全文件名 → 落 files/personas/<名>.txt。
     */
    fun importFrom(ctx: Context, uri: Uri): ImportResult {
        val display = displayName(ctx, uri)
        val ext = display.substringAfterLast('.', "").lowercase()
        if (ext != "md" && ext != "txt") {
            return ImportResult(
                message = "只支持 .md / .txt（选的是 " + display.ifBlank { "无扩展名文件" } + "）"
            )
        }
        val bytes: ByteArray = try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(chunk)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_BYTES) {
                        return ImportResult(
                            message = "文件超过 256KB（已读到 " + (total / 1024) + "KB），拒绝导入"
                        )
                    }
                    buf.write(chunk, 0, n)
                }
                buf.toByteArray()
            } ?: return ImportResult(message = "打不开这个文件（拿不到读取流）")
        } catch (e: SecurityException) {
            return ImportResult(message = "没有读取权限：" + (e.message ?: "被系统拒绝"))
        } catch (e: Exception) {
            return ImportResult(message = "读取失败：" + (e.message ?: e.javaClass.simpleName))
        }
        if (bytes.isEmpty()) return ImportResult(message = "文件是空的")
        val text = decode(bytes) ?: return ImportResult(message = "内容既不是 UTF-8 也不是 GBK 文本")
        return try {
            val dir = customDir(ctx)
            dir.mkdirs()
            // 撞名内置预设就加个后缀：否则这个自定义档会被「内置预设不可删」挡死，永远删不掉
            var stem = safeName(display.substringBeforeLast('.', display))
            if (list(ctx).contains(stem)) stem = safeName(stem + "-自定义")
            File(dir, stem + ".txt").writeText(text.trimEnd() + "\n", Charsets.UTF_8)
            // 落盘的档名是安全文件名（可能改过），原文件名另存一份侧车，管理页显示用
            File(dir, stem + ORIG_SUFFIX).writeText(display.trim(), Charsets.UTF_8)
            ImportResult(ok = true, name = stem, message = display.trim())
        } catch (e: Exception) {
            ImportResult(message = "写入失败：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    /** 导入时的原文件名（侧车 <档名>.orig）；老数据没有侧车就退回档名本身。 */
    fun originalName(ctx: Context, name: String): String {
        val stem = name.removePrefix(CUSTOM_PREFIX).trim()
        return try {
            val side = File(customDir(ctx), stem + ORIG_SUFFIX)
            if (side.isFile) side.readText(Charsets.UTF_8).trim().ifBlank { stem } else stem
        } catch (e: Exception) {
            stem
        }
    }

    /**
     * 删除一个已导入的人格。
     *
     * 内置预设只在 assets 里，**任何代码路径都删不掉**：名字命中 assets 就直接拒绝并回
     * [BUILTIN_LOCKED]，不会落到 files/personas 里找同名文件（导入时的撞名已经加过后缀，
     * 所以这里不存在「内置名其实是自定义档」的歧义）。
     */
    fun removePersona(ctx: Context, name: String): DeleteResult {
        val clean = name.removePrefix(CUSTOM_PREFIX).trim()
        if (clean.isBlank()) return DeleteResult(message = "名字是空的")
        if (list(ctx).contains(clean)) {
            Log.w(TAG, "拒绝删除内置预设：$clean")
            return DeleteResult(name = clean, message = BUILTIN_LOCKED)
        }
        val f = customFile(ctx, clean)
        val ok = try {
            // 只删 files/personas 下的那份；名字里的 .. 之类由 removePrefix+File 归一，删之前再确认一次目录
            if (!f.isFile || f.canonicalFile.parentFile != customDir(ctx).canonicalFile) false
            else f.delete()
        } catch (e: Exception) {
            false
        }
        if (!ok) return DeleteResult(name = clean, message = "没找到这个人格，可能已经删掉了")
        try {
            File(customDir(ctx), clean + ORIG_SUFFIX).delete()
        } catch (e: Exception) {
        }
        Log.i(TAG, "已删除自定义人格：$clean")
        return DeleteResult(ok = true, name = clean, message = "已删除 " + clean)
    }

    /** 兼容老调用点（只要成功/失败）。 */
    fun deleteCustom(ctx: Context, name: String): Boolean = removePersona(ctx, name).ok

    /** 严格 UTF-8 解不出就按 GBK 解（老 txt 常见），BOM 顺手去掉。 */
    private fun decode(bytes: ByteArray): String? {
        val strict = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val out = try {
            strict.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            try {
                String(bytes, charset("GBK"))
            } catch (e2: Exception) {
                return null
            }
        }
        return out.removePrefix("\uFEFF")
    }

    /** 安全文件名：去掉路径分隔符与 Windows/Android 都不接受的字符，限长 40。 */
    private fun safeName(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
            .take(40)
        return cleaned.ifBlank { "自定义人格" + (System.currentTimeMillis() % 100000) }
    }

    /** 文件选择器给的多半只有 uri：先问 OpenableColumns.DISPLAY_NAME，问不到再退回 uri 末段。 */
    private fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0) {
                            val v = c.getString(i)
                            if (!v.isNullOrBlank()) return v
                        }
                    }
                }
        } catch (e: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    }
}
