package com.wjljh.andbna.local

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 口癖 / 称呼 / 问候语：**全局一份**（不按 bot），落在应用私有目录：
 *
 *  · files/phrases.json    {"tics": ["…"], "aliases": {"对谁": "怎么叫"}}
 *  · files/greetings.json  ["早上好呀", …]
 *
 * 这两份**全局**文件已被「每个 bot 一份」取代（见本文件末尾 botDir / loadBot 那段）：
 * bot_runner 运行时只读 <botdir>/phrases.json 与 <botdir>/greetings.json，全局这两份
 * 只在 bot 启动时被 _migrate_words 迁移一次。上半部分的全局读写留给旧「文案」页，
 * 下一轮改 UI 时换成 loadBot / saveBotAliases / saveBotSlots。读取一律容错：
 * 文件缺失 / 解析失败 → 当空，不抛给页面。
 * 写入是 tmp + rename 原子替换，避免进程被杀时留下半截 JSON。
 */
object WordsStore {

    const val PHRASES = "phrases.json"
    const val GREETINGS = "greetings.json"

    /**
     * 三份内容；aliases 用有序对，保住页面上的行顺序（JSONObject 的键序就是写入序）。
     *
     * 只有旧「人格 / 文案」页用过它，那张页已经拆掉（口癖下线、称呼 / 问候搬到 bot 编辑页），
     * 所以整套全局读写都标 Deprecated：留着只为不删文件、不破坏旧版本升级过来的数据，
     * 现在没有任何调用点（新的入口一律走下面的 loadBot / saveBot*）。
     */
    @Deprecated("全局文案已下线：称呼/问候改成每个 bot 一份，用 loadBot / saveBot*")
    class Words(
        val tics: List<String> = emptyList(),
        val aliases: List<Pair<String, String>> = emptyList(),
        val greetings: List<String> = emptyList(),
    )

    fun phrasesFile(ctx: Context): File = File(ctx.filesDir, PHRASES)

    fun greetingsFile(ctx: Context): File = File(ctx.filesDir, GREETINGS)

    @Deprecated("全局文案已下线：改用 loadBot(ctx, appid)")
    @Suppress("DEPRECATION") // 这个函数自己就是旧的：里面构造 Words 也算「用旧 API」
    fun load(ctx: Context): Words {
        val p = try {
            val o = JSONObject(phrasesFile(ctx).readText())
            val tics = trimAll(o.optJSONArray("tics"))
            val aliasObj = o.optJSONObject("aliases")
            val aliases = ArrayList<Pair<String, String>>()
            if (aliasObj != null) {
                val it = aliasObj.keys()
                while (it.hasNext()) {
                    val k = it.next().trim()
                    val v = aliasObj.optString(k, "").trim()
                    if (k.isNotEmpty() && v.isNotEmpty()) aliases.add(k to v)
                }
            }
            tics to aliases
        } catch (e: Exception) {
            emptyList<String>() to emptyList<Pair<String, String>>()
        }
        return Words(p.first, p.second, loadGreetings(ctx))
    }

    private fun loadGreetings(ctx: Context): List<String> = try {
        trimAll(JSONArray(greetingsFile(ctx).readText()))
    } catch (e: Exception) {
        emptyList()
    }

    private fun trimAll(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "").trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    /** 写三份；返回 true = 都写成功。空行在这里再滤一遍，页面传什么都不会落空串。 */
    @Deprecated("全局文案已下线：改用 saveBotAliases / saveBotSlots / saveBotEnabled / saveBotTargets")
    fun save(ctx: Context, tics: List<String>, aliases: List<Pair<String, String>>, greetings: List<String>): Boolean {
        val t = tics.map { it.trim() }.filter { it.isNotEmpty() }
        val g = greetings.map { it.trim() }.filter { it.isNotEmpty() }
        val phrases = try {
            val o = JSONObject()
            val ta = JSONArray()
            t.forEach { ta.put(it) }
            o.put("tics", ta)
            val al = JSONObject()
            aliases.forEach { (k, v) ->
                val kk = k.trim()
                val vv = v.trim()
                if (kk.isNotEmpty() && vv.isNotEmpty()) al.put(kk, vv)
            }
            o.put("aliases", al)
            o.toString(1)
        } catch (e: Exception) {
            return false
        }
        val greet = try {
            val a = JSONArray()
            g.forEach { a.put(it) }
            a.toString(1)
        } catch (e: Exception) {
            return false
        }
        val ok1 = writeAtomic(phrasesFile(ctx), phrases)
        val ok2 = writeAtomic(greetingsFile(ctx), greet)
        return ok1 && ok2
    }

    // ── 每个 bot 一份（新格式，运行期在 Python 侧）────────────────────────
    //
    // <botdir>/phrases.json    {"aliases": {"master": "怎么叫主人", "others": "怎么叫其他人"}}
    // <botdir>/greetings.json  {"preset": true|false,
    //                           "slots": [{"name": "早上", "at": "08:00", "window_min": 30,
    //                                      "disabled": false, "lines": ["…"]}]}
    // 口癖（tics）已下线，这里不再有 tics 字段；旧全局两份文件只由 Python 侧迁移一次。
    // 内置人格的时段内容由 bot_runner._PRESET_GREETINGS 定死（页面只读文案），
    // 但「什么时候发 / 关不关」由文件说了算：disabled=true 的时段留着不调度。

    /** 一个问候时段：at 起 window_min 分钟内随机一刻发一条，内容只从本时段 lines 里抽。 */
    class Slot(
        val name: String = "",
        val at: String = "08:00",
        val windowMin: Int = 30,
        val lines: List<String> = emptyList(),
        /** true = 页面上关掉了这个时段：还留在文件里（随时能开回来），但运行时不发。 */
        val disabled: Boolean = false,
    )

    class BotWords(
        val master: String = "",
        val others: String = "",
        val preset: Boolean = false,
        val enabled: Boolean = true,
        val ownerTarget: Boolean = true,
        val groupsTarget: Boolean = true,
        val slots: List<Slot> = emptyList(),
    )

    fun botDir(ctx: Context, appid: String): File =
        File(File(ctx.filesDir, "localbot"), appid.trim())

    fun botFile(ctx: Context, appid: String, name: String): File = File(botDir(ctx, appid), name)

    /** 读本 bot 的称呼与问候；文件缺失 / 结构不对一律当空（不抛给页面）。 */
    fun loadBot(ctx: Context, appid: String): BotWords {
        val aliases = try {
            JSONObject(botFile(ctx, appid, PHRASES).readText()).optJSONObject("aliases")
        } catch (e: Exception) {
            null
        }
        val slots = ArrayList<Slot>()
        var preset = false
        var enabled = true
        var ownerTarget = true
        var groupsTarget = true
        try {
            val o = JSONObject(botFile(ctx, appid, GREETINGS).readText())
            preset = o.optBoolean("preset", false)
            enabled = o.optBoolean("enabled", true)
            val t = o.optJSONObject("targets")
            if (t != null) {
                ownerTarget = t.optBoolean("owner", true)
                groupsTarget = t.optBoolean("groups", true)
            }
            val arr = o.optJSONArray("slots")
            for (i in 0 until (arr?.length() ?: 0)) {
                val s = arr?.optJSONObject(i) ?: continue
                val at = s.optString("at", "").trim()
                val lines = trimAll(s.optJSONArray("lines"))
                val off = s.optBoolean("disabled", false)
                // 内置人格的 lines 恒为空（内容在代码里），不能因此把这条时段丢掉，
                // 否则页面读不到文件里的 at / window_min / disabled；关着的时段同理（内容可以空，
                // 打开时页面会提示补候选）。自定义人格没内容也没关 = 没配，跳过。
                if (at.isEmpty() || (lines.isEmpty() && !preset && !off)) continue
                slots.add(
                    Slot(
                        name = s.optString("name", "").trim().ifEmpty { at },
                        at = at,
                        windowMin = s.optInt("window_min", 30).let { if (it <= 0) 30 else it },
                        lines = lines,
                        disabled = off,
                    )
                )
            }
        } catch (e: Exception) {
        }
        return BotWords(
            master = aliases?.optString("master", "")?.trim().orEmpty(),
            others = aliases?.optString("others", "")?.trim().orEmpty(),
            preset = preset,
            enabled = enabled,
            ownerTarget = ownerTarget,
            groupsTarget = groupsTarget,
            slots = slots,
        )
    }

    /** 只改称呼：写本 bot 的 phrases.json（空串照样落盘，表示「不称呼」）。 */
    fun saveBotAliases(ctx: Context, appid: String, master: String, others: String): Boolean {
        val o = try {
            val al = JSONObject()
            al.put("master", master.trim())
            al.put("others", others.trim())
            JSONObject().put("aliases", al).toString(1)
        } catch (e: Exception) {
            return false
        }
        return writeAtomic(botFile(ctx, appid, PHRASES), o)
    }

    /** 发到哪（主人私聊 / 所有已知群）：整份文件其它键原样留着。 */
    fun saveBotTargets(ctx: Context, appid: String, owner: Boolean, groups: Boolean): Boolean {
        val f = botFile(ctx, appid, GREETINGS)
        val o = try {
            JSONObject(f.readText())
        } catch (e: Exception) {
            JSONObject()
        }
        return try {
            val t = JSONObject()
            t.put("owner", owner)
            t.put("groups", groups)
            o.put("targets", t)
            writeAtomic(f, o.toString(1))
        } catch (e: Exception) {
            false
        }
    }

    /** 开关定时问候（默认关）：整份文件其它键原样留着。 */
    fun saveBotEnabled(ctx: Context, appid: String, enabled: Boolean): Boolean {
        val f = botFile(ctx, appid, GREETINGS)
        val o = try {
            JSONObject(f.readText())
        } catch (e: Exception) {
            JSONObject()
        }
        return try {
            o.put("enabled", enabled)
            writeAtomic(f, o.toString(1))
        } catch (e: Exception) {
            false
        }
    }

    /** 改自定义人格的时段表；内置人格（文件里 preset=true）拒绝写入，返回 false。 */
    fun saveBotSlots(ctx: Context, appid: String, slots: List<Slot>): Boolean {
        val f = botFile(ctx, appid, GREETINGS)
        val cur = try {
            JSONObject(f.readText())
        } catch (e: Exception) {
            JSONObject()
        }
        if (cur.optBoolean("preset", false)) return false
        return try {
            val arr = JSONArray()
            slots.forEach { s ->
                val o = JSONObject()
                o.put("name", s.name.trim())
                o.put("at", s.at.trim())
                o.put("window_min", s.windowMin)
                // 关掉的时段不删：disabled=true 留在文件里，运行时跳过，页面下次还能打开
                o.put("disabled", s.disabled)
                val lines = JSONArray()
                s.lines.map { it.trim() }.filter { it.isNotEmpty() }.forEach { lines.put(it) }
                o.put("lines", lines)
                arr.put(o)
            }
            cur.put("preset", false)
            cur.put("slots", arr)
            writeAtomic(f, cur.toString(1))
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 内置人格只改「什么时候发、关不关」：name / at / window_min / disabled 落盘，preset 保持 true。
     *
     * 内置人格的候选文案由 bot_runner._PRESET_GREETINGS 定死（运行时按名字取），
     * 所以这里**不写 lines**，也绝不把 preset 翻成 false —— 翻掉就等于把用户的内置人格降级成
     * 自定义人格，下次启动会把时段内容清空。
     * disabled=true 的那条照样落盘（运行时读到就跳过），所以「关掉某个时段」对内置人格也成立；
     * 整条从文件里删掉则是更强的意思（运行时不再补回，见 bot_runner._greetings_conf）。
     */
    fun saveBotSlotTimes(ctx: Context, appid: String, slots: List<Slot>): Boolean {
        val f = botFile(ctx, appid, GREETINGS)
        val cur = try {
            JSONObject(f.readText())
        } catch (e: Exception) {
            JSONObject()
        }
        return try {
            val arr = JSONArray()
            slots.forEach { s ->
                val o = JSONObject()
                o.put("name", s.name.trim().ifEmpty { s.at.trim() })
                o.put("at", s.at.trim())
                o.put("window_min", s.windowMin)
                o.put("disabled", s.disabled)
                o.put("lines", JSONArray())
                arr.put(o)
            }
            cur.put("preset", true)
            cur.put("slots", arr)
            writeAtomic(f, cur.toString(1))
        } catch (e: Exception) {
            false
        }
    }

    private fun writeAtomic(f: File, text: String): Boolean = try {
        val dir = f.parentFile
        if (dir != null && !dir.exists()) dir.mkdirs()
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        // 同一目录内 rename 是原子的；目标已存在时 POSIX rename 直接替换
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
