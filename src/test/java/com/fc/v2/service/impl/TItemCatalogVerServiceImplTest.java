package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fc.v2.mapper.auto.TItemCatalogVerMapper;
import com.fc.v2.model.auto.TItemCatalogVer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 版次单状态机单测：次序（挨着走、一次一档）、重复（第二遍不落记事）、
 * 回退一档、打住、上柜封存、作废保护、版次履历审计。
 */
@ExtendWith(MockitoExtension.class)
class TItemCatalogVerServiceImplTest {

    private static final long ID = 1L;

    @Mock
    private TItemCatalogVerMapper itemCatalogVerMapper;

    @InjectMocks
    private TItemCatalogVerServiceImpl service;

    /** 造一张停在某档、某落点的单 */
    private TItemCatalogVer slip(int stage, int status) {
        TItemCatalogVer r = new TItemCatalogVer();
        r.setId(ID);
        r.setBizNo("BC2026-0001");
        r.setStage(stage);
        r.setStatus(status);
        r.setDelFlag(0);
        r.setLastAction("上一档过口");
        return r;
    }

    /** 让 mapper 回出这张单；写库动作放行（拒绝用例里本就不该被调到） */
    private TItemCatalogVer stub(TItemCatalogVer r) {
        when(itemCatalogVerMapper.selectById(ID)).thenReturn(r);
        lenient().when(itemCatalogVerMapper.updateById(any())).thenReturn(1);
        return r;
    }

    // ---------- 次序：四档挨着走，一次只过一档 ----------

    @Test
    void advanceMovesExactlyOneStage() {
        TItemCatalogVer r = stub(slip(0, 1));
        TItemCatalogVer out = service.advance(ID, "草拟过口");
        assertSame(r, out);
        assertEquals(1, r.getStage().intValue(), "一次只过一档，不许跳着过");
        assertEquals(1, r.getStatus().intValue());
        assertEquals("草拟过口", r.getLastAction());
        verify(itemCatalogVerMapper).updateById(r);
    }

    @Test
    void advanceWalksStageByStageToOnShelf() {
        TItemCatalogVer r = stub(slip(1, 1));
        service.advance(ID, "复核过口");
        assertEquals(2, r.getStage().intValue(), "复核之后是付发布，不许替它直过上柜");
        service.advance(ID, "付发布过口");
        assertEquals(3, r.getStage().intValue());
    }

    @Test
    void advanceOnShelfRejectedAndNothingWritten() {
        TItemCatalogVer r = stub(slip(3, 1));
        assertNull(service.advance(ID, "再报一遍"), "已上柜各栏封住，第二遍报上来不算");
        assertEquals(3, r.getStage().intValue());
        assertEquals("上一档过口", r.getLastAction(), "第二遍不许再多落一句记事");
        verify(itemCatalogVerMapper, never()).updateById(any());
    }

    @Test
    void advanceVoidedRejected() {
        TItemCatalogVer r = stub(slip(1, 2));
        assertNull(service.advance(ID, "追接"), "打住的单事后不能追接一档");
        assertEquals(1, r.getStage().intValue());
        verify(itemCatalogVerMapper, never()).updateById(any());
    }

    @Test
    void advanceMissingSlipReturnsNull() {
        when(itemCatalogVerMapper.selectById(ID)).thenReturn(null);
        assertNull(service.advance(ID, "x"));
    }

    // ---------- 回退：真要改口只能往回收一档 ----------

    @Test
    void rollbackMovesBackExactlyOneStage() {
        TItemCatalogVer r = stub(slip(2, 1));
        TItemCatalogVer out = service.rollback(ID, "收回改口");
        assertSame(r, out);
        assertEquals(1, r.getStage().intValue(), "只收一档，不许一撸到底");
        assertEquals("收回改口", r.getLastAction(), "最近过口动作只留最新一笔，不与后补并列同屏");
    }

    @Test
    void rollbackThenAdvanceRedoesTheStage() {
        TItemCatalogVer r = stub(slip(2, 1));
        service.rollback(ID, "收回复核");
        assertEquals(1, r.getStage().intValue());
        service.advance(ID, "复核重报");
        assertEquals(2, r.getStage().intValue(), "收回去一档重来，重报后回到付发布");
        assertEquals("复核重报", r.getLastAction());
    }

    @Test
    void rollbackAtDraftRejected() {
        TItemCatalogVer r = stub(slip(0, 1));
        assertNull(service.rollback(ID, "x"), "草拟底下没有可收的档");
        assertEquals(0, r.getStage().intValue());
        verify(itemCatalogVerMapper, never()).updateById(any());
    }

    @Test
    void rollbackOnShelfRejected() {
        TItemCatalogVer r = stub(slip(3, 1));
        assertNull(service.rollback(ID, "x"), "上柜之后封住，退不得");
        verify(itemCatalogVerMapper, never()).updateById(any());
    }

    @Test
    void rollbackVoidedRejected() {
        TItemCatalogVer r = stub(slip(1, 2));
        assertNull(service.rollback(ID, "x"), "已作废的单不能动");
        verify(itemCatalogVerMapper, never()).updateById(any());
    }

    // ---------- 打住：这一版不再往下走 ----------

    @Test
    void terminateStopsAtCurrentStage() {
        TItemCatalogVer r = stub(slip(1, 1));
        TItemCatalogVer out = service.terminate(ID, "这一版不再往下走");
        assertSame(r, out);
        assertEquals(2, r.getStatus().intValue(), "落点记已作废");
        assertEquals(1, r.getStage().intValue(), "单就此打住，档停在当下");
        assertEquals("这一版不再往下走", r.getLastAction());
    }

    @Test
    void terminateTwiceRejected() {
        TItemCatalogVer r = stub(slip(1, 2));
        assertNull(service.terminate(ID, "再打住一遍"), "同一档第二遍报上来，账上只认先前那一遍");
        verify(itemCatalogVerMapper, never()).updateById(any());
    }

    @Test
    void terminateOnShelfRejected() {
        TItemCatalogVer r = stub(slip(3, 1));
        assertNull(service.terminate(ID, "x"), "已上柜的单封住，不能回头打住");
        verify(itemCatalogVerMapper, never()).updateById(any());
    }

    @Test
    void noAdvanceAfterTerminate() {
        TItemCatalogVer r = stub(slip(2, 1));
        service.terminate(ID, "打住");
        assertNull(service.advance(ID, "追接付发布"), "打住之后不能追接一档");
        assertEquals(2, r.getStage().intValue());
    }

    // ---------- 封存与作废保护：记事一句改不得，整张删不得 ----------

    @Test
    void updateContentAllowedWhileInProgress() {
        TItemCatalogVer r = stub(slip(1, 1));
        assertTrue(service.updateContent(ID, "改一句记事"));
        assertEquals("改一句记事", r.getContent());
    }

    @Test
    void updateContentVoidedRejected() {
        TItemCatalogVer r = stub(slip(1, 2));
        assertFalse(service.updateContent(ID, "改口"), "已作废的单记事一句改不得");
        assertNull(r.getContent());
        verify(itemCatalogVerMapper, never()).updateById(any());
    }

    @Test
    void updateContentOnShelfRejected() {
        TItemCatalogVer r = stub(slip(3, 1));
        assertFalse(service.updateContent(ID, "改口"), "上柜之后各栏随即封住");
        verify(itemCatalogVerMapper, never()).updateById(any());
    }

    @Test
    void removeInProgressAllowed() {
        stub(slip(1, 1));
        when(itemCatalogVerMapper.deleteById(ID)).thenReturn(1);
        assertTrue(service.remove(ID));
    }

    @Test
    void removeVoidedRejected() {
        stub(slip(1, 2));
        assertFalse(service.remove(ID), "已作废的单整张删不得，删了年底查不着当年口径");
        verify(itemCatalogVerMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void removeOnShelfRejected() {
        stub(slip(3, 1));
        assertFalse(service.remove(ID), "已上柜的单办结，删不得");
        verify(itemCatalogVerMapper, never()).deleteById(any(Long.class));
    }

    // ---------- 版次履历：走到头且对得拢才算走成 ----------

    @Test
    void auditCompletedSlipPasses() {
        TItemCatalogVer r = stub(slip(3, 1));
        r.setLastAction("上柜过口");
        assertTrue(service.audit(ID), "档档齐全、在办、有过口动作，这单走成了");
    }

    @Test
    void auditMissingStageFails() {
        stub(slip(2, 1));
        assertFalse(service.audit(ID), "缺一档，没走到头不算走成");
    }

    @Test
    void auditDoctoredCombinationFails() {
        stub(slip(3, 0));
        assertFalse(service.audit(ID), "档在上柜落点却未起，档与落点打架算手补");

        TItemCatalogVer voided = stub(slip(3, 2));
        voided.setLastAction("x");
        assertFalse(service.audit(ID), "档在上柜落点却作废，对不拢算没走成");
    }

    @Test
    void auditHandInsertedFails() {
        TItemCatalogVer r = stub(slip(3, 1));
        r.setLastAction(null);
        assertFalse(service.audit(ID), "没有过口动作，像事后由人手补进去的");
    }

    @Test
    void auditMissingSlipFails() {
        when(itemCatalogVerMapper.selectById(ID)).thenReturn(null);
        assertFalse(service.audit(ID));
    }
}
