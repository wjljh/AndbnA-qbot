package com.wjljh.andbna.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 本地表情 / 图片库：目录结构与 Python 侧约定一字不改（bot_runner 只读这些文件）。
 *
 *   <botdir>/stickers/<id>.<ext>        原图（png / jpg / gif / webp / bmp，单张 ≤5 MB）
 *   <botdir>/stickers/.thumb/<id>.jpg   缩略图（长边 ≤256px，列表只解码它，不碰原图）
 *   <botdir>/stickers/library.json      {"items":[{"id","file","desc","tags",
 *                                        "added_at","size","w","h"}]}
 *
 * 三条约定的落盘口径（跨语言契约，改这里要同步 Python）：
 *  · [Item.file] 只存**文件名**（如 s1731234567890_1.png），和 library.json 同在 stickers/ 下；
 *  · [Item.addedAt] 是 epoch **毫秒**（System.currentTimeMillis）；
 *  · [Item.size] 是原图字节数，[Item.w] / [Item.h] 是原图像素尺寸。
 *
 * 整库上限 200 MB：导入前按「已有 size 之和 + 新图」预判，超了就拒绝（不落半张）。
 * 描述留空的条目照样落盘（页面显示「待补充描述」角标），Python 侧会跳过它。
 * library.json 一律 .tmp → rename 原子替换，进程被杀不会留下半截 JSON。
 */
object StickerStore {

    const val DIR = "stickers"
    const val THUMB_DIR = ".thumb"
    const val LIBRARY = "library.json"

    /** 单张上限 5 MB。 */
    const val MAX_ITEM: Long = 5L * 1024 * 1024

    /** 整库上限 200 MB。 */
    const val MAX_LIBRARY: Long = 200L * 1024 * 1024

    /** 缩略图长边像素。 */
    private const val THUMB_PX = 256

    /** 允许的扩展名（jpeg 一律归一成 jpg）。 */
    private val EXTS = listOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

    /** 一条表情。 */
    data class Item(
        val id: String,
        val file: String,
        val desc: String = "",
        val tags: List<String> = emptyList(),
        val addedAt: Long = 0L,
        val size: Long = 0L,
        val w: Int = 0,
        val h: Int = 0,
    )

    /** 一批导入的结果：成功的条目 + 每条失败原因（给页面直接显示）。 */
    class Imported(val added: List<Item> = emptyList(), val errors: List<String> = emptyList())

    fun dirOf(ctx: Context, appid: String): File = File(WordsStore.botDir(ctx, appid), DIR)

    fun thumbDirOf(ctx: Context, appid: String): File = File(dirOf(ctx, appid), THUMB_DIR)

    fun fileOf(ctx: Context, appid: String, item: Item): File = File(dirOf(ctx, appid), item.file)

    fun thumbOf(ctx: Context, appid: String, item: Item): File =
        File(thumbDirOf(ctx, appid), item.id + ".jpg")

    /** 整库已用字节（按 library.json 里记的 size 累加）。 */
    fun usedBytes(items: List<Item>): Long = items.sumOf { if (it.size > 0) it.size else 0L }

    /** 容量小字：「已用 12.3 MB / 200 MB」。 */
    fun mbText(bytes: Long): String = String.format("%.1f", bytes / 1048576.0)

    /**
     * 读整库。文件缺失 / 解析失败 → 空表（不抛给页面）；
     * 原图已经不在的条目直接跳过 —— 页面上看不到，下次保存就顺手清掉。
     */
    fun load(ctx: Context, appid: String): List<Item> {
        val dir = dirOf(ctx, appid)
        val f = File(dir, LIBRARY)
        if (!f.isFile) return emptyList()
        val arr = try {
            JSONObject(f.readText()).optJSONArray("items")
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        val out = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "").trim()
            val file = o.optString("file", "").trim()
            if (id.isEmpty() || file.isEmpty()) continue
            val real = File(dir, file)
            if (!real.isFile) continue
            val tags = ArrayList<String>()
            val ta = o.optJSONArray("tags")
            for (k in 0 until (ta?.length() ?: 0)) {
                val t = ta?.optString(k, "")?.trim().orEmpty()
                if (t.isNotEmpty()) tags.add(t)
            }
            out.add(
                Item(
                    id = id,
                    file = real.name,
                    desc = o.optString("desc", "").trim(),
                    tags = tags,
                    addedAt = o.optLong("added_at", 0L),
                    size = o.optLong("size", 0L).let { if (it > 0) it else real.length() },
                    w = o.optInt("w", 0),
                    h = o.optInt("h", 0),
                )
            )
        }
        return out
    }

    /** 写整库：.tmp → rename（同目录内 rename 原子）。 */
    fun save(ctx: Context, appid: String, items: List<Item>): Boolean = try {
        val arr = JSONArray()
        items.forEach { it ->
            val tags = JSONArray()
            it.tags.map { t -> t.trim() }.filter { t -> t.isNotEmpty() }.forEach { t -> tags.put(t) }
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("file", it.file)
                    .put("desc", it.desc.trim())
                    .put("tags", tags)
                    .put("added_at", it.addedAt)
                    .put("size", it.size)
                    .put("w", it.w)
                    .put("h", it.h)
            )
        }
        writeAtomic(File(dirOf(ctx, appid), LIBRARY), JSONObject().put("items", arr).toString(2))
    } catch (e: Exception) {
        false
    }

    /** 改一条的描述 / 标签（其余字段原样留着）。 */
    fun update(ctx: Context, appid: String, id: String, desc: String, tags: List<String>): Boolean {
        val items = load(ctx, appid).map {
            val t = tags.map { s -> s.trim() }.filter { s -> s.isNotEmpty() }
            if (it.id == id) it.copy(desc = desc.trim(), tags = t) else it
        }
        return save(ctx, appid, items)
    }

    /** 删一条：原图 + 缩略图 + library.json 一起（先删文件再写库，写库失败也不留孤儿）。 */
    fun delete(ctx: Context, appid: String, id: String): Boolean {
        val items = load(ctx, appid)
        val target = items.firstOrNull { it.id == id } ?: return false
        try {
            File(dirOf(ctx, appid), target.file).delete()
            thumbOf(ctx, appid, target).delete()
        } catch (e: Exception) {
        }
        return save(ctx, appid, items.filter { it.id != id })
    }

    /**
     * 从系统文件选择器（SAF，不申请存储权限）导入多张：
     * 逐个校验格式与 5 MB / 整库 200 MB → 复制到 stickers/<id>.<ext> → 生成缩略图 →
     * 全部追加进 library.json（描述留空，页面立刻要求用户填写）。
     */
    fun importUris(ctx: Context, appid: String, uris: List<Uri>): Imported {
        val dir = dirOf(ctx, appid)
        if (!dir.exists()) dir.mkdirs()
        val existing = load(ctx, appid)
        val taken = existing.map { it.id }.toMutableSet()
        val added = ArrayList<Item>()
        val errors = ArrayList<String>()
        var used = usedBytes(existing)
        val base = System.currentTimeMillis()
        uris.forEachIndexed { index, uri ->
            val name = displayName(ctx, uri)
            val ext = extOf(ctx, uri)
            if (ext == null) {
                errors.add(name.ifBlank { "第 " + (index + 1) + " 张" } + "：不是支持的图片格式")
                return@forEachIndexed
            }
            val id = newId(base, index + 1, taken)
            val dst = File(dir, id + "." + ext)
            val copied = copyCapped(ctx, uri, dst)
            if (copied < 0) {
                dst.delete()
                errors.add(name.ifBlank { id } + "：超过 5 MB")
                return@forEachIndexed
            }
            if (copied == 0L) {
                dst.delete()
                errors.add(name.ifBlank { id } + "：读不出内容")
                return@forEachIndexed
            }
            if (used + copied > MAX_LIBRARY) {
                dst.delete()
                errors.add(name.ifBlank { id } + "：整库已满 200 MB")
                return@forEachIndexed
            }
            val bounds = boundsOf(dst)
            if (bounds == null) {
                dst.delete()
                errors.add(name.ifBlank { id } + "：解不出图片（文件坏了？）")
                return@forEachIndexed
            }
            val item = Item(
                id = id,
                file = dst.name,
                desc = "",
                tags = emptyList(),
                addedAt = System.currentTimeMillis(),
                size = copied,
                w = bounds.first,
                h = bounds.second,
            )
            makeThumb(dst, thumbOf(ctx, appid, item))
            taken.add(id)
            used += copied
            added.add(item)
        }
        if (added.isNotEmpty()) save(ctx, appid, existing + added)
        return Imported(added, errors)
    }

    /**
     * 缩略图：有缓存就直接读缓存文件，没有（或原图更新过）就现生成一次。
     * 列表滚动只会走这条路，绝不每帧解码原图。
     */
    fun decodeThumb(ctx: Context, appid: String, item: Item): Bitmap? {
        val t = thumbOf(ctx, appid, item)
        val src = fileOf(ctx, appid, item)
        if (!t.isFile || (src.isFile && src.lastModified() > t.lastModified())) {
            if (!src.isFile) return null
            makeThumb(src, t)
        }
        return try {
            BitmapFactory.decodeFile(t.absolutePath)
        } catch (e: Exception) {
            null
        }
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    /** 时间戳 + 序号；撞了就往后顺延，保证 id 在整库里唯一。 */
    private fun newId(base: Long, seq: Int, taken: Set<String>): String {
        var n = seq
        while (true) {
            val id = "s" + base + "_" + n
            if (!taken.contains(id)) return id
            n++
        }
    }

    /** 复制并限长：返回字节数；超过 [MAX_ITEM] 返回 -1；打不开返回 0。 */
    private fun copyCapped(ctx: Context, uri: Uri, dst: File): Long {
        val ins = try {
            ctx.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            null
        } ?: return 0L
        var total = 0L
        try {
            FileOutputStream(dst).use { os ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_ITEM) return -1L
                    os.write(buf, 0, n)
                }
            }
        } catch (e: Exception) {
            return 0L
        } finally {
            try {
                ins.close()
            } catch (e: Exception) {
            }
        }
        return total
    }

    /** 原图像素尺寸：只读头，不整张解码。 */
    private fun boundsOf(f: File): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeFile(f.absolutePath, o)
        } catch (e: Exception) {
            return null
        }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        return o.outWidth to o.outHeight
    }

    /** 生成缩略图（长边 ≤256px 的 JPEG）。失败就当作没有缩略图，页面显示占位。 */
    private fun makeThumb(src: File, dst: File): Boolean = try {
        val bounds = boundsOf(src) ?: return false
        var scale = 1
        while (bounds.first / (scale * 2) >= THUMB_PX || bounds.second / (scale * 2) >= THUMB_PX) scale *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = scale }
        val raw = BitmapFactory.decodeFile(src.absolutePath, opts) ?: return false
        val maxSide = if (raw.width > raw.height) raw.width else raw.height
        val scaled = if (maxSide > THUMB_PX) {
            val r = THUMB_PX.toFloat() / maxSide
            Bitmap.createScaledBitmap(
                raw,
                (raw.width * r).toInt().coerceAtLeast(1),
                (raw.height * r).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            raw
        }
        dst.parentFile?.mkdirs()
        FileOutputStream(dst).use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (scaled !== raw) scaled.recycle()
        raw.recycle()
        true
    } catch (e: Exception) {
        false
    }

    /** SAF 里拿到的显示名（没有就当空串）。 */
    private fun displayName(ctx: Context, uri: Uri): String = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0).orEmpty() else "" }
            .orEmpty()
    } catch (e: Exception) {
        ""
    }

    /** MIME 定格式；MIME 不可信时回落到文件名后缀。认不出返回 null（拒绝导入）。 */
    private fun extOf(ctx: Context, uri: Uri): String? {
        val mime = try {
            ctx.contentResolver.getType(uri).orEmpty().lowercase()
        } catch (e: Exception) {
            ""
        }
        when (mime) {
            "image/png" -> return "png"
            "image/jpeg", "image/jpg", "image/pjpeg" -> return "jpg"
            "image/gif" -> return "gif"
            "image/webp" -> return "webp"
            "image/bmp", "image/x-ms-bmp", "image/x-bmp" -> return "bmp"
        }
        val e = displayName(ctx, uri).lowercase().substringAfterLast('.', "")
        if (e.isEmpty() || !EXTS.contains(e)) return null
        return if (e == "jpeg") "jpg" else e
    }

    private fun writeAtomic(f: File, text: String): Boolean = try {
        val dir = f.parentFile
        if (dir != null && !dir.exists()) dir.mkdirs()
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (tmp.renameTo(f)) {
            true
        } else {
            f.delete()
            tmp.renameTo(f)
        }
    } catch (e: Exception) {
        false
    }
}
