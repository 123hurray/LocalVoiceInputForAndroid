package com.example.localvoiceime

import org.junit.Assert.assertEquals
import org.junit.Test

class CandidateFeedbackTest {
    @Test fun separatesLoadingFromSuccessfulEmptyResultAndFailure() {
        assertEquals("正在纠错…", CandidateFeedback.text(true, true, true, true, false, false, 0))
        assertEquals("无其他候选", CandidateFeedback.text(true, false, true, true, true, false, 0))
        assertEquals("纠错失败，点此重试", CandidateFeedback.text(true, false, true, true, true, false, 1))
    }
    @Test fun reportsMissingAudioAndModelsInsteadOfSilentlyDoingNothing() {
        assertEquals("重说后暂无音频候选", CandidateFeedback.text(true, false, false, true, false, false, 0))
        assertEquals("需下载其他语音包", CandidateFeedback.text(true, false, true, false, false, false, 0))
        assertEquals("", CandidateFeedback.text(false, false, false, false, false, false, 0))
    }
    @Test fun preservesReadySuggestionsWhenAnotherModelFails() {
        assertEquals("部分候选已就绪", CandidateFeedback.text(true, false, true, true, true, true, 1))
    }
}
