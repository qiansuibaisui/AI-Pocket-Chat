package com.situ.aichat.prompt.schedule

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.world.cast.WorldAffinityService
import com.situ.aichat.world.stage.WorldStageService
import com.situ.aichat.world.travel.WorldTravelService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * 日程生成世界输入 T2-3（W9d 图纸 §7·E7）：buildPrompt 输出字符串断言 + 入库列断言 + 未加入角色字节级回归 +
 * WorldStageService.scheduleContextFor（剔除你的家/城名/天气行）。响应格式指令/解析器零碰——本测只验输入侧。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduleWorldInputTest {

    private lateinit var db: AppDatabase
    private lateinit var genService: ScheduleGenerationService
    private lateinit var stageService: WorldStageService
    private lateinit var contextLog: ContextLogService

    private val dateMillis = LocalDate.of(2026, 6, 15).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        contextLog = mockk()
        genService = ScheduleGenerationService(contextLog, db.scheduleDao(), io.mockk.mockk(relaxed = true)) // [zCODE] P4
        val travel = WorldTravelService(db.worldDao(), db.characterDao(), mockk(relaxed = true), db, mockk(relaxed = true), mockk(relaxed = true))
        stageService = WorldStageService(
            db.characterDao(), db.scheduleDao(), db.worldNativeDao(), db.petDao(), db.worldDao(), travel,
            WorldAffinityService(db.worldNativeDao(), db.worldDao()),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun char(uuid: String = "c1", cityName: String = "老城", joined: Boolean = false, home: String = "city_yunye") =
        CharacterEntity(uuid = uuid, name = "小南", creationDate = 0L, cityName = cityName, joinedWorld = joined, worldHomeCityId = home)

    private fun req(
        character: CharacterEntity = char(),
        worldCityName: String? = null,
        worldPlaceNames: List<String> = emptyList(),
        worldWeatherLine: String? = null,
        worldWeatherCondition: String? = null,
        worldWeatherEmoji: String? = null,
    ) = ScheduleGenerationRequest(
        character = character, dateMillis = dateMillis, zone = ZoneOffset.UTC,
        yesterdayEvents = emptyList(), recentConversationSummary = null,
        otherCharacterSchedules = emptyList(), crossCharacterLevel = 0,
        worldCityName = worldCityName, worldPlaceNames = worldPlaceNames, worldWeatherLine = worldWeatherLine,
        worldWeatherCondition = worldWeatherCondition, worldWeatherEmoji = worldWeatherEmoji,
    )

    // ---- buildPrompt 字符串断言 ----

    @Test
    fun `E7 joinedWorld_prompt含城名覆盖+地点段+天气行`() {
        val user = genService.buildPrompt(
            req(
                worldCityName = "云野镇",
                worldPlaceNames = listOf("拾光咖啡馆", "青苔书店", "河畔公园"),
                worldWeatherLine = "今天云野镇的天气：晴",
            ),
        ).second
        assertTrue(user.contains("所在城市：云野镇"))
        assertFalse("不应再出现旧城名", user.contains("所在城市：老城"))
        assertTrue(user.contains("【这座城里真实存在的地方】"))
        assertTrue(user.contains("拾光咖啡馆、青苔书店、河畔公园"))
        assertTrue(user.contains("安排外出活动时，location 优先从上面这些地方里选；在家的活动照常写「家里」；都不合适就写「在城中」。"))
        assertTrue(user.contains("今天云野镇的天气：晴"))
    }

    @Test
    fun `E7 未加入_字节级现行为_无世界段`() {
        val user = genService.buildPrompt(req()).second
        assertTrue(user.contains("所在城市：老城")) // 维持 character.cityName
        assertFalse(user.contains("【这座城里真实存在的地方】"))
        assertFalse(user.contains("的天气："))
    }

    @Test
    fun `E7 程序城_只天气行无地点段`() {
        val user = genService.buildPrompt(
            req(worldCityName = "临江城", worldPlaceNames = emptyList(), worldWeatherLine = "今天临江城的天气：雨天"),
        ).second
        assertTrue(user.contains("所在城市：临江城"))
        assertFalse(user.contains("【这座城里真实存在的地方】"))
        assertTrue(user.contains("今天临江城的天气：雨天"))
    }

    // ---- 入库列断言 ----

    private val cannedJson = """
        {"events":[
          {"startHour":0,"startMinute":0,"endHour":7,"endMinute":30,"periodLabel":"凌晨","location":"家里","activity":"睡觉","moodEmoji":"😴","moodText":"","innerThought":"","isPhoneAvailable":false,"relatedCharacterName":null},
          {"startHour":7,"startMinute":30,"endHour":12,"endMinute":0,"periodLabel":"上午","location":"拾光咖啡馆","activity":"画画","moodEmoji":"🎨","moodText":"","innerThought":"","isPhoneAvailable":true,"relatedCharacterName":null},
          {"startHour":12,"startMinute":0,"endHour":23,"endMinute":59,"periodLabel":"下午","location":"家里","activity":"休息","moodEmoji":"🛋️","moodText":"","innerThought":"","isPhoneAvailable":true,"relatedCharacterName":null}
        ]}
    """.trimIndent()

    private fun stubLlm() {
        coEvery {
            contextLog.streamedCompletion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns cannedJson
    }

    @Test
    fun `E7 入库三列写世界值`() = runBlocking {
        stubLlm()
        val c = char(uuid = "cw", joined = true)
        db.characterDao().upsert(c)
        val ok = genService.generateSchedule(
            req(character = c, worldCityName = "云野镇", worldWeatherCondition = "晴", worldWeatherEmoji = "☀️"),
            mockk<ApiConfigValues>(relaxed = true),
        )
        assertTrue(ok)
        val sched = db.scheduleDao().scheduleFor("cw", dateMillis)!!
        assertEquals("云野镇", sched.cityName)
        assertEquals("晴", sched.weatherCondition)
        assertEquals("☀️", sched.weatherEmoji)
    }

    @Test
    fun `E7 入库_未加入维持现值`() = runBlocking {
        stubLlm()
        val c = char(uuid = "cn", cityName = "老城", joined = false)
        db.characterDao().upsert(c)
        genService.generateSchedule(req(character = c), mockk<ApiConfigValues>(relaxed = true))
        val sched = db.scheduleDao().scheduleFor("cn", dateMillis)!!
        assertEquals("老城", sched.cityName)
        assertNull(sched.weatherCondition)
        assertNull(sched.weatherEmoji)
    }

    // ---- scheduleContextFor ----

    @Test
    fun `E7 scheduleContextFor_剔除你的家_城名_天气行格式`() = runBlocking {
        db.worldDao().upsertState(WorldStateEntity(seed = 7L, userTimezoneId = "UTC", createdAt = 0L))
        val ctx = stageService.scheduleContextFor(char(joined = true, home = "city_yunye"), dateMillis)!!
        assertEquals("云野镇", ctx.cityName)
        assertFalse("必须剔除你的家", ctx.placeNames.contains("你的家"))
        assertTrue(ctx.placeNames.contains("拾光咖啡馆"))
        assertTrue(ctx.placeNames.contains("青苔书店"))
        assertEquals("今天云野镇的天气：${ctx.weatherCondition}", ctx.weatherLine)
        assertTrue("天气词应为日间三词之一", ctx.weatherCondition in setOf("晴", "雨天", "雪天"))
        assertTrue(ctx.weatherEmoji in setOf("☀️", "🌧️", "❄️"))
    }

    @Test
    fun `E7 scheduleContextFor_无世界态返null`() = runBlocking {
        assertNull(stageService.scheduleContextFor(char(joined = true), dateMillis))
    }
}
