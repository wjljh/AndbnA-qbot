package com.wjljh.andbna.ui

/**
 * 回复判定「主动程度」的唯一文案表（1.0.8）。
 *
 * 落盘值只认 conservative / normal / eager 三个字面值（llm_judge_level 与
 * localbots.json 的 judge_level_override，Python 侧同一口径，认不出就落回 normal），
 * 界面上的叫法与顺序以前三处各写一套（设置页「偏积极（默认）/保守/积极」、Bot 编辑页
 * 「保守/正常/积极」），现在全部从这里取：一律按强度排，[NORMAL] 是默认档。
 */
object JudgeLabels {
    const val CONSERVATIVE = "conservative"
    const val NORMAL = "normal"
    const val EAGER = "eager"

    /** 强度顺序（下拉下标就是 [values] 的下标，别再各自排一遍）。 */
    val values = listOf(CONSERVATIVE, NORMAL, EAGER)

    /** 与 [values] 一一对应的中文名。 */
    val labels = listOf("保守", "标准", "积极")

    /** 设置页那一档没有「跟随全局」，默认档要标出来，否则看不出哪一档是默认。 */
    val labelsWithDefault = listOf("保守", "标准（默认）", "积极")

    /** 脏值（含空串 / 大写 / 老值）一律落回 [NORMAL]。 */
    fun wireOf(raw: String): String =
        raw.trim().lowercase().takeIf { it in values } ?: NORMAL

    /** 落盘值 → [values] 的下标（脏值给 [NORMAL] 的位置）。 */
    fun indexOf(raw: String): Int = values.indexOf(wireOf(raw))

    /** 落盘值 → 中文名（脏值当 [NORMAL]）。 */
    fun labelOf(raw: String): String = labels[indexOf(raw)]
}
