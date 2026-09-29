package com.situ.aichat.prompt.schedule

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.DynamicInterest
import com.situ.aichat.data.model.EconomicStatusTier
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.MoodHistoryEntry
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.diagnostics.ContextLogService
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * T2-4（图纸 2026-07-10 日程专项 §7·E7/E8）：buildPrompt 合龙——满配段序、零素材恒变四处回归钉、
 * backfill 精简、输出格式指令与示例字节级锁定（「重新打字」为测试字面量）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduleLivenessInputTest {

    private lateinit var db: AppDatabase
    private lateinit var genService: ScheduleGenerationService

    private val zone = ZoneOffset.UTC
    // 2026-07-13 = 表内普通周一（非节假日非补班）→ dayTypeHint 走旧「（工作日）」字面
    private val dateMillis = LocalDate.of(2026, 7, 13).atStartOfDay(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        genService = ScheduleGenerationService(mockk<ContextLogService>(), db.scheduleDao(), io.mockk.mockk(relaxed = true)) // [zCODE] P4 relationGate
    }

    @After
    fun tearDown() = db.close()

    private fun bareCharacter() = CharacterEntity(uuid = "c1", name = "夏晴子", creationDate = 0L)

    /** 全料角色（R1 🟡-2）：让 C4 四段（兴趣/心情走向/关系/长期记忆）在集成层真出现，正向锁 !isBackfill 门控。 */
    private fun loadedCharacter() = bareCharacter().copy(
        dynamicInterestsJSON = GrowthJson.encodeDynamicInterests(listOf(DynamicInterest(name = "看展", heat = 80))),
        moodHistoryJSON = GrowthJson.encodeMoodHistory(
            (1..3).map { MoodHistoryEntry(timestamp = it * 1000L, emoji = "🙂", colorName = "green") },
        ),
        relationshipQualityJSON = GrowthJson.encode(
            RelationshipQuality(familiarity = 80, trust = 80, closeness = 80, attachment = 80),
        ),
        firstMessageDate = 0L,
        memorySummary = "【长期事实】\n她养了一只猫",
    )

    private fun req(
        character: CharacterEntity = bareCharacter(),
        isBackfill: Boolean = false,
        economicTier: EconomicStatusTier? = null,
        liveness: ScheduleLivenessContext? = null,
    ) = ScheduleGenerationRequest(
        character = character, dateMillis = dateMillis, zone = zone,
        yesterdayEvents = emptyList(), recentConversationSummary = null,
        otherCharacterSchedules = emptyList(), crossCharacterLevel = 0,
        isBackfill = isBackfill, economicTier = economicTier, liveness = liveness,
    )

    private fun fullLiveness() = ScheduleLivenessContext(
        todayMeetings = listOf(ScheduleLivenessContext.MeetingLine("15:00", "美术馆", "看展")),
        todayPromises = listOf("陪用户去配眼镜"),
        upcomingPromises = listOf(ScheduleLivenessContext.UpcomingPromise("一起看烟花", "7月20日")),
        openLoops = listOf("用户下周面试"),
        recentMeetingAfterglow = ScheduleLivenessContext.AfterglowLine("昨天", "公园", "野餐"),
        recentDaysDigest = listOf("7月11日：上午画画、下午散步"),
    )

    // ── 满配段序（图纸 §3.3 表）──

    @Test
    fun `满配_全部新块出现且按图纸段序`() {
        // 全料角色（R1 🟡-2）：C4 四段正向出现+位置也入段序锁——若 !isBackfill 门控被改成恒 false 本测即红。
        // 关系块用块内独有行「关系阶段：」做标记（D-2 口径：块名【和用户的关系】被恒在的规则 13 引用会误伤）。
        val user = genService.buildPrompt(
            req(character = loadedCharacter(), economicTier = EconomicStatusTier.TIGHT, liveness = fullLiveness()),
        ).second
        val order = listOf(
            "【角色信息】",
            "最近热衷：",
            "最近心情走向：",
            "关系阶段：",
            "【TA的长期记忆】",
            "【最近几天做过什么】",
            "【今天的约定】（这是TA今天必须兑现的真实约定）",
            "【近期已定的约定】（还没到日子，今天不要安排）",
            "【TA心里惦记的事】",
            "【最近见面】",
            "【TA的经济状况】：紧张",
            "【生成要求】",
        )
        var last = -1
        for (marker in order) {
            val idx = user.indexOf(marker)
            assertTrue("缺段或乱序: $marker", idx > last)
            last = idx
        }
        assertTrue(user.contains("- 今天15:00和用户见面，地点：美术馆，一起看展"))
        assertTrue(user.contains("- 陪用户去配眼镜"))
        assertTrue(user.contains("- 一起看烟花（7月20日）"))
        assertTrue(user.contains("昨天你们线下见过面：野餐（在公园）。"))
        assertTrue(user.contains("13. innerThought 的分寸："))
    }

    // ── §4-A/A2 节假日集成（R1 🟡-3·锁定文案自图纸 §4 重新打字）──

    @Test
    fun `法定假_开头行含节假日判定_气息行逐字紧跟开头行`() {
        // 2026-10-01 = 国庆节首日（周四）；A2 行必须紧跟开头行（sections 以 \n 连接 → startsWith 即断言相邻）
        val holidayMillis = LocalDate.of(2026, 10, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val user = genService.buildPrompt(req().copy(dateMillis = holidayMillis)).second
        assertTrue(
            user.startsWith(
                "请为以下角色生成10月1日（星期四）（法定节假日·国庆节假期）的日程安排。\n" +
                    "今天是国庆节假期。日程可以自然带上节日气息（按角色性格与习俗来，比如春节团聚、中秋吃月饼），" +
                    "不强制、不夸张，角色也可以有自己的过节方式。",
            ),
        )
    }

    @Test
    fun `调休补班_开头行按工作日安排_无气息行`() {
        // 2026-10-10 = 国庆补班周六：不再被冤枉成「休息日」，也不得出现节日气息行
        val makeupMillis = LocalDate.of(2026, 10, 10).atStartOfDay(zone).toInstant().toEpochMilli()
        val user = genService.buildPrompt(req().copy(dateMillis = makeupMillis)).second
        assertTrue(user.startsWith("请为以下角色生成10月10日（星期六）（调休补班·按工作日安排）的日程安排。"))
        assertFalse(user.contains("日程可以自然带上节日气息"))
    }

    // ── E7 零素材恒变四处回归钉 ──

    @Test
    fun `E7 零素材_全部新块缺席_恒变恰四处`() {
        val (system, user) = genService.buildPrompt(req())
        // 新可选块全缺席。注意：规则 12 例外句/规则 13 恒引用「【今天的约定】」「【和用户的关系】」块名，
        // 故用块特有标记（头行全文/块内独有行）断言缺席，不用裸块名。
        for (header in listOf(
            "最近热衷：", "最近心情走向：", "关系阶段：", "【TA的长期记忆】", "【最近几天做过什么】",
            "【今天的约定】（这是TA", "【近期已定的约定】（", "【TA心里惦记的事】", "【最近见面】", "【TA的经济状况】",
        )) {
            assertFalse("零素材不应出现: $header", user.contains(header))
        }
        // 开头行 = 旧字面（表内普通周一）
        assertTrue(user.startsWith("请为以下角色生成7月13日（星期一）（工作日）的日程安排。"))
        // 恒变①（K 系统 prompt·重新打字锁定）
        assertEquals(
            "你是一个生活模拟作家，任务是为一个真实生活着的虚拟角色还原TA普通的一天。TA有自己的职业、朋友、爱好和心事；" +
                "TA的日子大多平淡真实，偶尔有小波澜。日程必须紧扣TA的职业、性格、兴趣和生活习惯，体现TA的个人特色，" +
                "而不是千篇一律的模板。\n输出严格的 JSON 对象格式 {\"events\":[...]}, 不要包含任何其他文字。",
            system,
        )
        // 恒变②（规则 12 尾接例外句）+ 恒变③（新规则 13）
        assertTrue(user.contains("唯一例外：【今天的约定】块里列出的约定，按该块的要求安排成事件。"))
        assertTrue(user.contains("13. innerThought 的分寸：按【和用户的关系】给出的参考控制想到用户的频率；其余事件的 innerThought 写TA自己的生活感受。"))
        // 恒变④（F 禁令）零素材下无聊天块 → 不出现；见下一测
        assertFalse(user.contains("【最近和用户聊到的事】"))
    }

    @Test
    fun `F 禁令收窄_新文案逐字_旧文案绝迹`() {
        val user = genService.buildPrompt(
            req().copy(recentConversationSummary = "你：明天一起去看展\n夏晴子：好呀"),
        ).second
        // 图纸二·人称指名：:269 的「和你」→「和${request.userName}」；此处 req() 默认 userName="用户"，故渲染「和用户」。
        assertTrue(
            user.contains(
                "⚠️ 这段聊天只用来了解TA最近的生活状态和心情，可作为 innerThought 的素材。" +
                    "不要从聊天里自行提取约定排进日程——今天要赴的约定一律以【今天的约定】为准。" +
                    "禁止在 activity 里写「和用户发消息/聊天/分享」之类的互动动作，禁止虚构任何对话引用。",
            ),
        )
        assertFalse(user.contains("只有当上方聊天记录里明确约定过某件事"))
    }

    // ── E8 backfill 精简 ──

    @Test
    fun `E8 backfill_只留经济块_角色派生与素材块恒缺席`() {
        val moody = bareCharacter().copy(
            moodHistoryJSON = GrowthJson.encodeMoodHistory(
                (1..5).map { MoodHistoryEntry(timestamp = it * 1000L, emoji = "🙂", colorName = "red") },
            ),
            firstMessageDate = 0L,
            memorySummary = "【长期事实】\n她养了一只猫",
        )
        val user = genService.buildPrompt(
            req(character = moody, isBackfill = true, economicTier = EconomicStatusTier.COMFORTABLE),
        ).second
        assertFalse(user.contains("最近心情走向："))
        assertFalse(user.contains("关系阶段：")) // 块特有行（裸块名会被规则 13 误伤）
        assertFalse(user.contains("【TA的长期记忆】"))
        assertFalse(user.contains("【今天的约定】（这是TA")) // 块头全文（裸块名会被规则 12 例外句误伤）
        assertTrue(user.contains("【TA的经济状况】：宽裕"))
        assertTrue(user.contains("【注意】")) // 既有 backfill 注意块仍在
    }

    // ── 输出侧字节级锁定 ──

    @Test
    fun `输出格式指令与示例_字节级锁定重新打字`() {
        val user = genService.buildPrompt(req(economicTier = EconomicStatusTier.NORMAL, liveness = fullLiveness())).second
        assertTrue(user.contains("输出格式（严格 JSON 对象 {\"events\":[...]}, 不要有任何其他文字、注释、代码块标记）："))
        // EXAMPLE_JSON 关键结构行（首事件全字段）原样在尾部
        assertTrue(user.contains("\"periodLabel\": \"凌晨\""))
        assertTrue(user.contains("\"isPhoneAvailable\": false"))
        assertTrue(user.contains("\"relatedCharacterName\": null"))
        assertTrue(user.trimEnd().endsWith("]}"))
    }

    // ── 见面行可选分句 ──

    @Test
    fun `见面行_地点活动为空省略分句_模糊时刻空串不出今天今天`() {
        val liveness = ScheduleLivenessContext(
            todayMeetings = listOf(ScheduleLivenessContext.MeetingLine("", "", "")),
        )
        val user = genService.buildPrompt(req(liveness = liveness)).second
        assertTrue(user.contains("- 今天和用户见面\n"))
        assertFalse(user.contains("今天今天"))
        assertFalse(user.contains("，地点："))
        assertFalse(user.contains("，一起"))
    }
}
