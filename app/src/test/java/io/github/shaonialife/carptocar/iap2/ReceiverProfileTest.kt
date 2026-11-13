package io.github.shaonialife.carptocar.iap2

import io.github.shaonialife.carptocar.net.AirPlayFeatures
import org.junit.Assert.assertEquals
import org.junit.Test

/** 车机画像判定（纯函数）：信号组合 → era/wifiGen + 可解释依据。 */
class ReceiverProfileTest {
    @Test
    fun unknownWhenNoSignals() {
        val p = ReceiverProfile.classify(ReceiverProfile.Signals())
        assertEquals(ReceiverProfile.Era.UNKNOWN, p.era)
        assertEquals(ReceiverProfile.WifiGen.UNKNOWN, p.wifiGen)
    }

    @Test
    fun preHomeKitWhenBit38Clear() {
        // 奥迪 4xxx / CarPlay 210.81：features 无 bit38 → 明文 + AES-CTR 零密钥档
        val features =
            (AirPlayFeatures.parse("0x5653aee2,0x61") ?: 0L) and
                AirPlayFeatures.HK_PAIRING_AND_ENCRYPT.inv()
        val p =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(features = features, srcvers = "210.81"),
            )
        assertEquals(ReceiverProfile.Era.PRE_HOMEKIT, p.era)
    }

    @Test
    fun modernWhenBit38Set() {
        val features =
            (AirPlayFeatures.parse("0x5653aee2,0x61") ?: 0L) or
                AirPlayFeatures.HK_PAIRING_AND_ENCRYPT
        val p =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(features = features, srcvers = "450.14.2"),
            )
        assertEquals(ReceiverProfile.Era.MODERN, p.era)
    }

    /**
     * **防回归的关键用例**：奥迪 3315 的清单同样声明「可收 0x4300 / 会发 0x4301」
     * （发送 0x4301、0xea03；可收 0x4e0c/0x4e09/0x4300/0xea00），但它走的是旧协议
     * 0x5702→0x5703。2026-10-28 大众车机的清单形态几乎一样（9 项、同样含 0x4300/0x4301），
     * 却是新版协议。
     *
     * 两者**只能靠实车行为区分，清单分不开**——所以这里必须保持 UNKNOWN，
     * 不允许「声明了 0x4300/0x4301 就判 NEW」（那会让奥迪跳过 0x5702，
     * 而它对 0x4300 只回 ACK 不出 0x4301，整轮必挂）。代际不确定由发送端
     * 「两条请求并行发、谁先回用谁」兜底。
     */
    @Test
    fun sparseCarPlayListsStayUnknown() {
        val p =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(
                    name = "Audi_MMI_0000",
                    model = "AUDI 0000",
                    manufacturer = "DELPHI",
                    sendIds = setOf(0x4301, 0xea03),
                    receiveIds = setOf(0x4e0c, 0x4e09, 0x4300, 0xea00),
                ),
            )
        // 0x4301 声明不足以排除旧凭据路径，稀疏清单保留探测。
        assertEquals(ReceiverProfile.WifiGen.UNKNOWN, p.wifiGen)
        assertEquals("Audi_MMI_0000 AUDI 0000 DELPHI", p.identityKey)
    }

    @Test
    fun wifiGenNewWhenFullListsAdvertiseStartSession() {
        // 已声明 0x4301 的完整清单保留新代路径。
        val p =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(
                    sendIds = setOf(0x4301, 0x5000, 0x5002, 0x5200, 0x5203, 0xae00, 0xae02, 0x4157),
                    receiveIds = setOf(0x4300, 0x4e09, 0xaa00, 0xaa02, 0x5001, 0x5201, 0x4158),
                ),
            )
        assertEquals(ReceiverProfile.WifiGen.NEW, p.wifiGen)
    }

    @Test
    fun fullMediaListsDoNotProveNewWifiProtocol() {
        // 同一台车重连时的普通 iAP 视图：26 项均未提供无线凭据协议证据。
        val profile =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(
                    sendIds = setOf(
                        0x5000, 0x5002, 0x5003, 0x6800, 0x6802, 0x6803, 0x4c00,
                        0x4c02, 0x4c03, 0x4c05, 0x4c07, 0xae00, 0xae02, 0xea03,
                    ),
                    receiveIds = setOf(
                        0x4e09, 0x4e0a, 0x4e0b, 0x4e0c, 0x5001, 0x6801,
                        0x4c01, 0x4c04, 0x4e0d, 0xae01, 0xea00, 0xea01,
                    ),
                ),
            )
        assertEquals(ReceiverProfile.WifiGen.UNKNOWN, profile.wifiGen)
    }

    @Test
    fun sparseSendListAloneStaysUnknown() {
        // 单份稀疏清单（2 项）无 0x5703：不判「新」——奥迪的反例证明稀疏清单
        // 不列 0x57xx 也可以走旧协议
        val p =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(sendIds = setOf(0x4301, 0xea03)),
            )
        assertEquals(ReceiverProfile.WifiGen.UNKNOWN, p.wifiGen)
    }

    /**
     * 组件声明（0x1D01 参 12/24/30）目前只作**可解释信号**，不改变 era / wifiGen：
     * 这些组在部分车机上缺失或走私有编号，当硬分支会把已跑通的车型判进未知路径。
     */
    @Test
    fun componentSignalsDoNotChangeGeneration() {
        val base =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(sendIds = setOf(0x5703), receiveIds = setOf(0x5702)),
            )
        val withComponents =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(
                    sendIds = setOf(0x5703),
                    receiveIds = setOf(0x5702),
                    currentLanguage = "zh-CN",
                    wirelessCarPlayComponent = true,
                    navigationDisplayComponent = true,
                ),
            )
        assertEquals(ReceiverProfile.WifiGen.LEGACY, base.wifiGen)
        assertEquals(base.era, withComponents.era)
        assertEquals(base.wifiGen, withComponents.wifiGen)
    }

    /**
     * **配对策略只有一个来源**：era → PairingPolicy。
     *
     * 这条用例钉的是「老车机空转 120 秒配对窗口」的拦截点：TXT 无 bit38 时必须落到
     * PLAINTEXT_ONLY，连接侧（SessionController）据此跳过 pair-setup；若哪天有人
     * 又在别处直接读 bit38 并判错，这条会先红。
     */
    @Test
    fun pairingPolicyFollowsEra() {
        assertEquals(
            ReceiverProfile.PairingPolicy.UNKNOWN,
            ReceiverProfile.classify(ReceiverProfile.Signals()).pairingPolicy,
        )
        val modernFeatures = AirPlayFeatures.parse("0x44540380,0x61") ?: 0L
        val legacyFeatures = modernFeatures and AirPlayFeatures.HK_PAIRING_AND_ENCRYPT.inv()
        assertEquals(
            ReceiverProfile.PairingPolicy.PLAINTEXT_ONLY,
            ReceiverProfile.classify(ReceiverProfile.Signals(features = legacyFeatures)).pairingPolicy,
        )
        assertEquals(
            ReceiverProfile.PairingPolicy.REQUIRED,
            ReceiverProfile.classify(ReceiverProfile.Signals(features = modernFeatures)).pairingPolicy,
        )
    }

}
