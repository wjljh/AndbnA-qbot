package com.wjljh.andbna.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.wjljh.andbna.local.BotStore.replyJudge

/**
 * 回复判定模式的进程内单点：llm = 模型判断，random = 按「闲聊概率」掷骰子。
 *
 * 落盘仍是 prefs 的 reply_judge 一个键（[replyJudge]，Python 侧 set_reply_judge(勾选模式, 概率) 读它）；
 * 这一层只补「设置页切了档，别的页面立刻跟着重组」—— 随机模式要收起主动程度、Bot 编辑页的
 * 判定行要变灰，切回模型判断要恢复，都不该靠重启 App。
 */
object ReplyJudgeMode {
    const val LLM = "llm"
    const val RANDOM = "random"

    /** 变更计数：切档 +1；组合期读过它的页面因此重组。模式本身仍从 prefs 读，不存第二份。 */
    private var revision by mutableStateOf(0)

    /** 落盘值：任何线程都能读。 */
    fun read(ctx: Context): String = with(ctx) { replyJudge }

    /** 组合期读：带上订阅，切档后这一页立刻跟着重组。 */
    @Composable
    fun current(ctx: Context): String {
        val rev = revision
        return remember(ctx, rev) { read(ctx) }
    }

    /** 随机判定 = 主动程度那一档不生效。 */
    @Composable
    fun isRandom(ctx: Context): Boolean = current(ctx) == RANDOM

    /** 设置页切档：落盘 + 通知所有读过它的页面。 */
    fun set(ctx: Context, mode: String) {
        ctx.replyJudge = mode
        revision++
    }
}
