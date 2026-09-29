package com.fc.v2;

import com.fc.v2.mapper.auto.TItemCatalogVerMapper;
import com.fc.v2.model.auto.TItemCatalogVer;
import com.fc.v2.service.impl.TItemCatalogVerServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 版次单四档流转规矩：0草拟 → 1复核 → 2付发布 → 3已上柜。
 * 四档挨着走；已作废打住、已上柜封住；办结的单改不得也删不得。
 */
public class ItemCatalogVerStateMachineTest {

    private TItemCatalogVerMapper mapper;
    private TItemCatalogVerServiceImpl service;

    @BeforeEach
    public void setUp() {
        mapper = Mockito.mock(TItemCatalogVerMapper.class);
        service = new TItemCatalogVerServiceImpl();
        ReflectionTestUtils.setField(service, "itemCatalogVerMapper", mapper);
    }

    private TItemCatalogVer bill(long id, Integer stage, Integer status) {
        TItemCatalogVer r = new TItemCatalogVer();
        r.setId(Long.valueOf(id));
        r.setStage(stage);
        r.setStatus(status);
        r.setDelFlag(Integer.valueOf(0));
        return r;
    }

    private void stubGet(TItemCatalogVer r) {
        when(mapper.selectById(r.getId())).thenReturn(r);
        when(mapper.updateById(any(TItemCatalogVer.class))).thenReturn(Integer.valueOf(1));
        when(mapper.deleteById(r.getId())).thenReturn(Integer.valueOf(1));
    }

    @Test
    public void advanceWalksOneStageAtATime() {
        TItemCatalogVer r = bill(1L, 0, 1);
        stubGet(r);
        // 草拟→复核→付发布→已上柜，一次只过一档，跳着过不算
        assertEquals(1, service.advance(1L, "过草拟").getStage());
        assertEquals(2, service.advance(1L, "过复核").getStage());
        assertEquals(3, service.advance(1L, "过付发布").getStage());
        // 已上柜：各栏封住，同一档第二遍报上来不算，也不许多落一句记事
        assertNull(service.advance(1L, "第二遍报上柜"));
        assertEquals(3, r.getStage().intValue());
        assertEquals("过付发布", r.getLastAction());
    }

    @Test
    public void terminatedBillCannotAdvanceOrRollback() {
        TItemCatalogVer r = bill(2L, 1, 2); // 已作废：单就此打住
        stubGet(r);
        assertNull(service.advance(2L, "追接一档"));
        assertNull(service.rollback(2L, "追接一档"));
        assertEquals(1, r.getStage().intValue());
        assertNull(r.getLastAction());
        verify(mapper, never()).updateById(any(TItemCatalogVer.class));
    }

    @Test
    public void shelvedBillIsSealed() {
        TItemCatalogVer r = bill(3L, 3, 1); // 已上柜
        stubGet(r);
        assertNull(service.advance(3L, "x"));
        assertNull(service.rollback(3L, "x"));
        assertFalse(service.updateContent(3L, "改口"));
        assertFalse(service.remove(3L));
        assertNull(r.getContent());
        verify(mapper, never()).updateById(any(TItemCatalogVer.class));
        verify(mapper, never()).deleteById(any(Long.class));
    }

    @Test
    public void rollbackTakesBackExactlyOneStage() {
        TItemCatalogVer r = bill(4L, 2, 1);
        stubGet(r);
        TItemCatalogVer back = service.rollback(4L, "收一档重来");
        assertNotNull(back);
        // 付发布 → 复核：只收一档，不得一把退回草拟
        assertEquals(1, r.getStage().intValue());
        assertEquals("收一档重来", r.getLastAction());
    }

    @Test
    public void rollbackBelowDraftIsRejected() {
        TItemCatalogVer r = bill(5L, 0, 1);
        stubGet(r);
        assertNull(service.rollback(5L, "x"));
        assertEquals(0, r.getStage().intValue());
        verify(mapper, never()).updateById(any(TItemCatalogVer.class));
    }

    @Test
    public void voidedBillIsImmutableAndUndeletable() {
        TItemCatalogVer r = bill(6L, 1, 2); // 已作废
        stubGet(r);
        assertFalse(service.updateContent(6L, "改"));
        assertFalse(service.remove(6L));
        verify(mapper, never()).updateById(any(TItemCatalogVer.class));
        verify(mapper, never()).deleteById(any(Long.class));
    }

    @Test
    public void inflightBillAllowsContentAndRemove() {
        TItemCatalogVer r = bill(7L, 1, 1); // 在办未办结
        stubGet(r);
        assertTrue(service.updateContent(7L, "补一句"));
        assertEquals("补一句", r.getContent());
        assertTrue(service.remove(7L));
    }

    @Test
    public void missingBillIsRejectedEverywhere() {
        when(mapper.selectById(Long.valueOf(99L))).thenReturn(null);
        assertNull(service.advance(99L, "x"));
        assertNull(service.rollback(99L, "x"));
        assertFalse(service.updateContent(99L, "x"));
        assertFalse(service.remove(99L));
    }
}
