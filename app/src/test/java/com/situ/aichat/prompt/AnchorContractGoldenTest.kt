package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.AnchorSource
import com.situ.aichat.data.local.entity.StoryAnchorSnapshotEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [zCODE] P1·第3项 锚点格式**正式契约金样**（分隔符 ·/｜/： 锁定·与《锚点格式》规范 v1 同源）。
 *
 * 三形态归一断言：①存量块式（【场景状态】键值）；②规范单行（【锚点】{个人·基线}｜{名}：在场/不在场（位置））；
 * ③简式（[场景：x·y]）。LB-4 规格澄清锁死：位置字段 = 原始整值（值内 · 如船名"雷德·佛斯号"绝不切分），
 * · 仅用于 locationKey 层的船团基线前缀比较——全链（解析→落库字段→"当前"行显示）按整值断言。
 *
 * [zCODE] 工单#1 追加金样 3+1：双层格式（全团：/个人：/在场：/节点：段）——
 * ①双角色锚点均含全团层前缀（解析+注入渲染）②航行中=海域级粒度（模型漂移写港口也强制退级）
 * ④防脆化漂移样本（半角冒号/motion 不识/无括号三层/船名含·/杂串拒收/垃圾串不炸）。
 * ③MotionState 不一致 A3 拦截在 GateDoubleCardTest（闸门双卡同源）。
 */
class AnchorContractGoldenTest {

    /** 工单#1 快照装配：解析块 → 落库字段语义（fleetLayerJson=encode·motionStateRaw 舰级优先）。 */
    private fun snapOf(block: AnchorBlockParser.AnchorBlock, now: Long = 1_000_000L): StoryAnchorSnapshotEntity {
        val fleetMotion = block.fleetLayer?.let { AnchorVocabulary.fleetMotionFromText(it.motionText) }
        val normalized = AnchorVocabulary.normalize(block.locationRaw)
        return StoryAnchorSnapshotEntity(
            characterUuid = "c1",
            eventName = block.eventName,
            locationRaw = block.locationRaw,
            motionStateRaw = (fleetMotion ?: normalized.state).raw,
            sourceRaw = AnchorSource.DIALOG_BLOCK.raw,
            effectiveAt = now,
            capturedAt = now,
            fleetLayerJson = AnchorBlockParser.FleetLayerCodec.encode(block.fleetLayer),
        )
    }

    // ── 规范单行形态：分隔符契约 + 在场名单 ──

    @Test fun canonical_single_line_full_form() {
        val b = AnchorBlockParser.parseLastBlock("————【锚点】甲板·白团海上旗舰｜贝克曼：不在场（酒馆）｜艾斯：在场")
        assertEquals("甲板·白团海上旗舰", b!!.locationRaw) // 整值（个人·基线原始串，· 不切分）
        assertEquals(2, b.presentList.size)
        assertEquals("贝克曼", b.presentList[0].name)
        assertEquals(false, b.presentList[0].present)
        assertEquals("酒馆", b.presentList[0].location)
        assertEquals("艾斯", b.presentList[1].name)
        assertEquals(true, b.presentList[1].present)
    }

    @Test fun canonical_no_present_list() {
        val b = AnchorBlockParser.parseLastBlock("【锚点】街道·港口镇")
        assertEquals("街道·港口镇", b!!.locationRaw)
        assertTrue(b.presentList.isEmpty())
    }

    // ── LB-4：地点值含 ·（船名）全链——解析→落库字段→"当前"行显示 均整值 ──

    @Test fun lb4_dot_inside_location_value_full_chain() {
        // ① 解析：整值不切分
        val b = AnchorBlockParser.parseLastBlock("————【锚点】雷德·佛斯号甲板·夜航")
        assertEquals("雷德·佛斯号甲板·夜航", b!!.locationRaw)
        // ② 落库字段语义（Repository 落库即 AnchorBlock.locationRaw 原样入 locationRaw 列）+ 归一：
        //    词表命中"甲板"→ sailing（key=甲板，整值内的 · 不影响命中）
        val n = AnchorVocabulary.normalize(b.locationRaw)
        assertEquals(AnchorVocabulary.MotionState.SAILING, n.state)
        // ③ "当前"行显示（InjectionBuilder·LB-4 后 eventName 空整值直出·无括号重组）
        val now = 1_000_000L
        val snap = StoryAnchorSnapshotEntity(
            characterUuid = "c1", eventName = "", locationRaw = b.locationRaw,
            motionStateRaw = n.state.raw, sourceRaw = AnchorSource.DIALOG_BLOCK.raw,
            effectiveAt = now, capturedAt = now,
        )
        val module = AnchorInjectionBuilder.buildModule(snap, emptyList(), now)
        assertTrue("显示行必须含原始整值（· 不重组）: $module", module.contains("雷德·佛斯号甲板·夜航"))
        assertTrue(module.contains("刚刚更新"))
    }

    // ── 三形态并存：最后输出优先（模型最终落点） ──

    @Test fun three_forms_last_wins() {
        val text = "[场景：酒馆]早期\n【场景状态】\n地点：甲板\n中期\n————【锚点】街道·最终形态"
        val b = AnchorBlockParser.parseLastBlock(text)
        assertEquals("街道·最终形态", b!!.locationRaw) // 块式在中、规范单行在后 → 取最后
    }

    // ── 简式（[场景：x·y]）既有协议不受 LB-4 影响（· 切分为该形态自身协议） ──

    @Test fun legacy_inline_form_unchanged() {
        val b = AnchorBlockParser.parseLastBlock("[场景：港口·补给装卸中·晨]正文")
        assertEquals("港口", b!!.locationRaw)
        assertEquals("补给装卸中", b.eventName)
        assertEquals("晨", b.timeText)
    }

    // ── [zCODE] 工单#1：金样① 双角色锚点均含全团层前缀（双层格式全链：解析→落库→注入渲染） ──

    @Test fun workorder1_dual_layer_both_roles_carry_fleet_prefix() {
        // 角色1（马尔科）完整双层：全团+个人+在场（名单式）+节点
        val marco = AnchorBlockParser.parseLastBlock(
            "【锚点】全团：白胡子海贼团·新世界·香波地泊地（莫比迪克号·停靠）｜个人：甲板·瞭望｜在场：比斯塔、以藏｜节点：靠港补给",
        )!!
        // 角色2（艾斯）破折号引导 + 省略在场段——同团两人各自输出，全团层必须都在
        val ace = AnchorBlockParser.parseLastBlock(
            "————【锚点】全团：白胡子海贼团·新世界·香波地泊地（莫比迪克号·停靠）｜个人：码头·钓鱼｜节点：午睡",
        )!!

        for (b in listOf(marco, ace)) {
            assertNotNull("全团层前缀必须解析出", b.fleetLayer)
            val layer = b.fleetLayer!!
            assertEquals("白胡子海贼团", layer.fleetName)
            assertEquals("新世界", layer.seaArea)
            assertEquals("香波地泊地", layer.port)
            assertEquals("莫比迪克号", layer.shipName)
            assertEquals("停靠", layer.motionText)
            assertEquals(AnchorVocabulary.MotionState.DOCKED, AnchorVocabulary.fleetMotionFromText(layer.motionText))
        }
        // 个人层/在场名单（新式 在场：{名单} 全在场）/节点段
        assertEquals("甲板·瞭望", marco.locationRaw)
        assertEquals(listOf("比斯塔", "以藏"), marco.presentList.map { it.name })
        assertTrue(marco.presentList.all { it.present })
        assertEquals("靠港补给", marco.eventName)
        assertEquals("码头·钓鱼", ace.locationRaw)
        assertEquals(0, ace.presentList.size) // 省略在场段 → 空名单不硬造

        // 注入渲染：两角色的【剧情位置】首行均为全团层（三层人称客观·船位事实行）
        val now = 1_000_000L
        for (b in listOf(marco, ace)) {
            val module = AnchorInjectionBuilder.buildModule(snapOf(b, now), emptyList(), now)
            assertTrue("渲染必须含全团层行: $module", module.contains("全团：白胡子海贼团·新世界·香波地泊地（莫比迪克号·停靠）"))
        }
    }

    // ── [zCODE] 工单#1：金样② 航行中=海域级粒度（MotionState 粒度联动·漂移防护） ──

    @Test fun workorder1_sailing_renders_sea_area_granularity() {
        // 模型漂移：航行中仍写了具体港口 → 解析保真留档（port 原样），渲染强制退到海域级
        val b = AnchorBlockParser.parseLastBlock(
            "【锚点】全团：白胡子海贼团·新世界·香波地（莫比迪克号·航行中）｜个人：甲板·掌舵",
        )!!
        val layer = b.fleetLayer!!
        assertEquals("香波地", layer.port) // 解析层保真（漂移原文留档，供 P3 管理台比对）
        assertEquals(AnchorVocabulary.MotionState.SAILING, AnchorVocabulary.fleetMotionFromText(layer.motionText))
        assertEquals("sailing", snapOf(b).motionStateRaw) // 落库：舰级事实源优先于个人层词表归一

        val now = 1_000_000L
        val module = AnchorInjectionBuilder.buildModule(snapOf(b, now), emptyList(), now)
        assertTrue("航行中必须退到海域级: $module", module.contains("全团：白胡子海贼团·新世界·某海域（莫比迪克号·航行中）"))
        assertFalse("航行中渲染不得出现具体港口", module.contains("香波地"))
        assertTrue("个人层行仍在", module.contains("甲板·掌舵"))
    }

    // ── [zCODE] 工单#1：金样④ 防脆化漂移样本（畸形变体不炸·降级有据） ──

    @Test fun workorder1_drift_variants_never_throw_and_degrade() {
        // ④-1 半角冒号 + 括号内 motion 不识 → 舰名整值保留、状态 UNKNOWN（不硬猜）、块不丢
        val halfColon = AnchorBlockParser.parseLastBlock("【锚点】全团: 白团·新世界·香波地（莫比迪克号·休整）｜个人：甲板")!!
        val driftLayer = halfColon.fleetLayer!!
        assertEquals("莫比迪克号·休整", driftLayer.shipName) // motion 不识 → 括号整值作船名
        assertTrue(driftLayer.motionText.isEmpty())
        assertNull(AnchorVocabulary.fleetMotionFromText(driftLayer.motionText))
        assertEquals("甲板", halfColon.locationRaw)

        // ④-2 锚泊 → 海域级+状态注明（港口位退「某海域」，括号内注明状态）
        val anchored = AnchorBlockParser.parseLastBlock("【锚点】全团：白团·新世界·香波地（莫比迪克号·锚泊）｜个人：船舱")!!
        val now = 1_000_000L
        val anchoredModule = AnchorInjectionBuilder.buildModule(snapOf(anchored, now), emptyList(), now)
        assertTrue(anchoredModule.contains("全团：白团·新世界·某海域（莫比迪克号·锚泊）"))

        // ④-3 无团双层简式（无「全团：」段·首段以 个人： 起写）→ 首段吸收为个人层、fleetLayer=null
        val solo = AnchorBlockParser.parseLastBlock("【锚点】个人：风车村·家中｜在场：零")!!
        assertEquals("风车村·家中", solo.locationRaw)
        assertNull(solo.fleetLayer)

        // ④-4 船名含 ·（雷德·佛斯号）→ 按最后一个 · 切 motion，船名整值不破
        val redForce = AnchorBlockParser.parseLastBlock("【锚点】全团：红发海贼团·乐园·某海域（雷德·佛斯号·航行中）｜个人：甲板")!!
        assertEquals("雷德·佛斯号", redForce.fleetLayer!!.shipName)
        assertEquals("航行中", redForce.fleetLayer!!.motionText)

        // ④-5 全团段杂串（两层无括号·parseFleetLayer 守卫拒收）→ 退化为位置整值，不误判团名
        val junk = AnchorBlockParser.parseLastBlock("【锚点】甲板·白团旗舰｜节点：闲谈")!!
        assertEquals("甲板·白团旗舰", junk.locationRaw)
        assertNull(junk.fleetLayer)
        assertEquals("闲谈", junk.eventName)

        // ④-6 垃圾串整体不抛（红线=不炸；块丢或降级皆合法）+ codec：垃圾 JSON 解出 null、roundtrip 对称
        val garbage = AnchorBlockParser.parseLastBlock("【锚点】！！｜｜｜")
        assertTrue(garbage == null || garbage.locationRaw.isNotEmpty() || garbage.fleetLayer != null)
        assertNull(AnchorBlockParser.FleetLayerCodec.decode("not-json"))
        assertNull(AnchorBlockParser.FleetLayerCodec.decode(""))
        val roundtrip = AnchorBlockParser.FleetLayerCodec.decode(
            AnchorBlockParser.FleetLayerCodec.encode(AnchorBlockParser.FleetLayer("白团", "新世界", "香波地", "莫比迪克号", "停靠")),
        )
        assertEquals(AnchorBlockParser.FleetLayer("白团", "新世界", "香波地", "莫比迪克号", "停靠"), roundtrip)
    }
}
