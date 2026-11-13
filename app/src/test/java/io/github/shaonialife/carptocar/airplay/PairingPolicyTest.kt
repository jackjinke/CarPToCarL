package io.github.shaonialife.carptocar.airplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 配对失败的定性策略：哪些是「车机此刻的状态」（别重试），哪些是「这次抖动」（要重试）。 */
class PairingPolicyTest {
    @Test
    fun authenticationIsDeterministic() {
        assertTrue(PairingPolicy.isDeterministic(PairingStage.PAIR_VERIFY, PairingErrorCode.AUTHENTICATION))
        assertTrue(PairingPolicy.isDeterministic(PairingStage.PAIR_SETUP, PairingErrorCode.AUTHENTICATION))
    }

    @Test
    fun capacityAndAttemptLimitsAreDeterministic() {
        assertTrue(PairingPolicy.isDeterministic(PairingStage.PAIR_SETUP, PairingErrorCode.MAX_PEERS))
        assertTrue(PairingPolicy.isDeterministic(PairingStage.PAIR_SETUP, PairingErrorCode.MAX_TRIES))
    }

    @Test
    fun transientCodesAreRetryable() {
        assertFalse(PairingPolicy.isDeterministic(PairingStage.PAIR_SETUP, PairingErrorCode.BACKOFF))
        assertFalse(PairingPolicy.isDeterministic(PairingStage.PAIR_SETUP, PairingErrorCode.BUSY))
        assertFalse(PairingPolicy.isDeterministic(PairingStage.PAIR_SETUP, PairingErrorCode.UNAVAILABLE))
        // 规范外的私有码：一概按可重试处理，宁可多试一次也不要误判成终态
        assertFalse(PairingPolicy.isDeterministic(PairingStage.PAIR_SETUP, null))
        assertTrue(PairingPolicy.shouldRetrySameStage(null))
    }

    @Test
    fun onlySetupAuthenticationWarrantsThePairedFastPath() {
        // pair-setup 被拒 ≈ 车机已经存了我们的公钥 → 直接 pair-verify
        assertTrue(PairingPolicy.shouldTryPairedFastPath(PairingStage.PAIR_SETUP, PairingErrorCode.AUTHENTICATION))
        // pair-verify 被拒 = 车机不认这个身份，再走快路径毫无意义
        assertFalse(PairingPolicy.shouldTryPairedFastPath(PairingStage.PAIR_VERIFY, PairingErrorCode.AUTHENTICATION))
        // 配对满了 / 次数用尽：换路径也过不去
        assertFalse(PairingPolicy.shouldTryPairedFastPath(PairingStage.PAIR_SETUP, PairingErrorCode.MAX_PEERS))
    }

    @Test
    fun everyCodeGetsAnActionableAdvice() {
        for (stage in PairingStage.entries) {
            for (code in PairingErrorCode.entries + null) {
                val advice = PairingPolicy.advice(stage, code)
                assertTrue("stage=$stage code=$code 建议不能为空", advice.isNotBlank())
            }
        }
        // 最要紧的一条必须说清「去车机侧删配对记录」，否则用户只能一直重试
        assertTrue(
            PairingPolicy
                .advice(PairingStage.PAIR_VERIFY, PairingErrorCode.AUTHENTICATION)
                .contains("删"),
        )
    }

    @Test
    fun exceptionMessageCarriesBothCodeAndState() {
        val error = PairingRejectedException(PairingStage.PAIR_VERIFY, PairingErrorCode.AUTHENTICATION, 2, 4)
        val message = error.message.orEmpty()
        assertTrue(message.contains("pair-verify"))
        assertTrue(message.contains("Error=2"))
        assertTrue(message.contains("认证失败"))
        assertTrue(message.contains("State=4"))
        assertTrue(error.deterministic)
    }

    @Test
    fun unknownPrivateCodeIsDescribedAsSuch() {
        val error = PairingRejectedException(PairingStage.PAIR_SETUP, null, 9, 2)
        assertTrue(error.message.orEmpty().contains("规范外私有码"))
        assertFalse(error.deterministic)
    }

}
