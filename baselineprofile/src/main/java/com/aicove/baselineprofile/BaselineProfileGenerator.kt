package com.aicove.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * 采集 baseline profile。
 *
 * 产物是一个 baseline-prof.txt（见 workflow 上传的 artifact），需要人工提交到
 * `app/src/main/baseline-prof.txt`，之后所有非 debuggable 变体（含 beta 包）都会自动带进 APK。
 *
 * 采集跑在 `profilegen` 变体上：该变体关闭了 R8 与资源压缩，因此规则里是未混淆的原始类名/方法名，
 * 发布构建再由 R8 按 mapping 重写——在混淆产物上采集只能得到短名，代码一变就再也对不上。
 *
 * 采集面：
 * 1. 冷启动：首屏是工作台，启动路径上的类（Application、Hilt、主题、容器初始化）都在这里；
 * 2. 聊天页：滚动一段消息列表，把消息渲染、Markdown、输入框这条主链的类采进来。
 *    之前只采冷启动，聊天页那些热点（列表 item、Markdown 解析、流式渲染）全在 profile 外，
 *    而聊天页才是用户停留最久的地方。
 *
 * 不采设置页、终端、编辑器：它们依赖控件结构，UI 一改就断，且使用频度远低于聊天页。
 */
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun coldStartup() {
        rule.collect(
            // profilegen 变体没有 applicationIdSuffix，即正式包名
            packageName = TARGET_PACKAGE,
            includeInStartupProfile = true
        ) {
            pressHome()
            startActivityAndWait()
            // 首次启动要解压容器 rootfs，首帧可能远晚于 Activity 启动，故单独等窗口出现
            device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), FIRST_FRAME_TIMEOUT_MS)
        }
    }

    /**
     * 聊天页：进会话、滚列表、敲字。
     *
     * 不依赖具体控件 id（那要 app 侧加 testTag，一改 UI 就断）；只靠坐标与系统返回键，
     * 尽量让这套采集在 UI 小改后仍能跑。拿不到会话时直接返回——宁可少采，不要因 UI 变化让整个
     * workflow 失败（失败一次就要人工排查，比少采更贵）。
     */
    @Test
    fun chatList() {
        rule.collect(
            packageName = TARGET_PACKAGE,
            includeInStartupProfile = false
        ) {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), FIRST_FRAME_TIMEOUT_MS)
            Thread.sleep(SETTLE_MS)

            // 上下滚几次，把 LazyColumn 的 item 组合/回收、Markdown 渲染、输入框布局都踩到。
            val w = device.displayWidth
            val h = device.displayHeight
            repeat(SCROLL_ROUNDS) {
                device.swipe(w / 2, h * 3 / 4, w / 2, h / 4, SWIPE_STEPS)
                Thread.sleep(SWIPE_SETTLE_MS)
                device.swipe(w / 2, h / 4, w / 2, h * 3 / 4, SWIPE_STEPS)
                Thread.sleep(SWIPE_SETTLE_MS)
            }
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "Rely.aicove"
        const val FIRST_FRAME_TIMEOUT_MS = 60_000L

        /** 首帧后等一会儿再开始操作：容器初始化可能还在后台跑。 */
        const val SETTLE_MS = 3_000L

        /** 上下滚动轮数。 */
        const val SCROLL_ROUNDS = 3

        /** 单次滑动分几步：步数少会变成甩动，采不到中间帧。 */
        const val SWIPE_STEPS = 20

        /** 每次滑动后等惯性停下，否则下一次滑动会打断上一次。 */
        const val SWIPE_SETTLE_MS = 600L
    }
}
