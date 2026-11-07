package com.wjljh.andbna.local

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 群级配置：<botdir>/groups/<group_openid>.json。
 *
 * 一个群一个文件，字段与 Python 侧 _group_conf 一一对应：
 *   {"auto_reply": true|null, "daily_limit": null, "web_search": true|null, "enabled": true}
 *
 * 三条硬约定（Python 侧就是照这个读的，改格式要两边一起改）：
 *  · null = 跟随（该 bot 的覆盖 / 全局），**不是 false**（所以三态值在 Kotlin 里是 Boolean?）；
 *  · 文件不存在 = 这个群完全没有覆盖 → 行为与升级前一致；
 *  · 全默认（跟随 + 未静音）= 不落盘，落盘等于「恢复默认」，直接删文件。
 *
 * 群人格（<openid>.persona.txt / conf.persona）已下线：群里一律用 bot 详情页那一份人格。
 */
object GroupConfig {

    /**
     * 一个群的覆盖配置（缺项 = 跟随）。
     *
     * [dailyLimit] 是三态：**null = 跟随全局**（设置页「每日上限」那一档，llm.json 的 daily_limit）、
     * 0 = 这个群不限、>0 = 这个群自己的额度。老版本写下的 "daily_limit": 0 就是「不限」，行为不变。
     */
    data class Conf(
        val autoReply: Boolean? = null,
        val dailyLimit: Int? = null,
        val webSearch: Boolean? = null,
        val enabled: Boolean = true,
        /**
         * 本群主人（可选覆盖，键名 owner_member_openid）。
         *
         * 正常情况下不需要它：同一个 bot 下群成员的 openid 与私聊 openid 是同一套，
         * Python 侧直接比对「群里发言的人 == 私聊记录里的主人」就认得出主人。
         * 这个字段只是「识别不到」时的手工覆盖通道（本轮没有 UI 入口）。
         * null / 空 = 未设置。
         */
        val ownerMemberOpenid: String? = null,
    ) {
        /** 全部跟随 + 未静音：与「没有这份文件」等价。 */
        val isDefault: Boolean
            get() = autoReply == null && dailyLimit == null && webSearch == null && enabled &&
                ownerMemberOpenid.isNullOrBlank()
    }

    /** 群配置页一行：一个群 + 它的运行记录（最后一次活跃时间 / 条数）+ 有没有覆盖。 */
    data class Row(
        val id: String,
        val last: String = "",
        val count: Int = 0,
        val overridden: Boolean = false,
    )

    fun dir(ctx: Context, appid: String): File =
        File(BotHost.dataDir(ctx), appid.trim()).resolve("groups")

    fun file(ctx: Context, appid: String, gid: String): File =
        File(dir(ctx, appid), gid.trim() + ".json")

    /** 这个群有没有覆盖（= 配置文件在）。列表上的「已覆盖 / 跟随全局」就靠它。 */
    fun hasOverride(ctx: Context, appid: String, gid: String): Boolean =
        file(ctx, appid, gid).isFile

    /** 读一个群的覆盖；没有文件 / 坏 JSON / 脏值 → 全部按「跟随全局」处理。 */
    fun load(ctx: Context, appid: String, gid: String): Conf {
        val f = file(ctx, appid, gid)
        if (!f.isFile) return Conf()
        val o = runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull() ?: return Conf()
        fun tri(name: String): Boolean? = if (o.isNull(name)) null else o.optBoolean(name, false)

        return Conf(
            autoReply = tri("auto_reply"),
            // 缺项 / null = 跟随全局；显式 0 = 本群不限（老文件里的 0 就是这个意思）
            dailyLimit = if (o.isNull("daily_limit")) null
            else o.optInt("daily_limit", 0).coerceAtLeast(0),
            webSearch = tri("web_search"),
            enabled = if (o.isNull("enabled")) true else o.optBoolean("enabled", true),
            // 缺项 / null / 空串 = 未设置（org.json 的 optString 会把 JSON null 读成 "null"，先挡一层）
            ownerMemberOpenid = str(o, "owner_member_openid").ifBlank { null },
        )
    }

    /**
     * 写一份覆盖；[Conf.isDefault] 时等于「恢复默认」，直接删覆盖。
     * 返回 null = 成功，否则是给人看的原因（页面直接显示）。
     */
    fun save(ctx: Context, appid: String, gid: String, conf: Conf): String? {
        if (gid.isBlank()) return "没有群 openid，写不了"
        if (conf.isDefault) {
            clear(ctx, appid, gid)
            return null
        }
        return try {
            dir(ctx, appid).mkdirs()
            val o = JSONObject()
                .put("auto_reply", conf.autoReply ?: JSONObject.NULL)
                .put("daily_limit", conf.dailyLimit?.coerceAtLeast(0) ?: JSONObject.NULL)
                .put("web_search", conf.webSearch ?: JSONObject.NULL)
                .put("enabled", conf.enabled)
                // 本群主人：Python 侧读的就是这个键，缺项 / null = 未设置
                .put(
                    "owner_member_openid",
                    conf.ownerMemberOpenid?.trim()?.ifBlank { null } ?: JSONObject.NULL,
                )
            file(ctx, appid, gid).writeText(o.toString(1), Charsets.UTF_8)
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    /** 恢复默认（删除覆盖）。 */
    fun clear(ctx: Context, appid: String, gid: String): Boolean = try {
        file(ctx, appid, gid).delete()
        true
    } catch (e: Exception) {
        false
    }

    /**
     * 群配置页的数据源：bot_runner.ids_for 的 groups（known_chats.json 的群 + memory.json
     * 的 g+openid 键 + last_sender 的群，全部是运行期真落下来的记录，没有一个是编的）。
     * 群名平台不下发，所以行标题只写「群 + openid 前 8 位」。
     */
    fun rows(ctx: Context, appid: String): List<Row> {
        val raw = runCatching { BotHost.idsFor(ctx, appid) }.getOrNull().orEmpty()
        val arr = runCatching { JSONObject(raw).optJSONArray("groups") }.getOrNull()
            ?: return emptyList()
        val out = ArrayList<Row>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "").trim()
            if (id.isBlank()) continue
            out.add(
                Row(
                    id = id,
                    last = o.optString("last", "").trim(),
                    count = o.optInt("count", 0),
                    overridden = hasOverride(ctx, appid, id),
                ),
            )
        }
        // 已覆盖的排前面（改过的正是想看的那几个），其余按最后活跃时间倒序（Python 已排好）
        return out.sortedByDescending { if (it.overridden) 1 else 0 }
    }

    /** 读一个字符串字段：缺项 / JSON null / 字面量 "null" 一律当空串（org.json 会把它读成 "null"）。 */
    private fun str(o: JSONObject, key: String): String =
        if (o.isNull(key)) "" else o.optString(key, "").trim().let { if (it == "null") "" else it }
}
