package com.fc.v2.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TItemCatalogVerMapper;
import com.fc.v2.model.auto.TItemCatalogVer;
import com.fc.v2.service.ITItemCatalogVerService;

/**
 * 事项目录条目版次单 Service业务层处理（state-machine 形状：单据流转）
 *
 * <p>四档挨着走：0草拟 → 1复核 → 2付发布 → 3已上柜。停在某一档的单，当下算哪一档
 * 由系统照已过完的档数自己算（以库内记录为准），屏上写的号不作数。跳着过、倒着过
 * 一律不算；被拒的报送不许多落一句记事。已上柜各栏封住，已作废就此打住——
 * 办结的单改不得也删不得，留着年底翻检版次履历。</p>
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TItemCatalogVerServiceImpl implements ITItemCatalogVerService {

    /** 最后一档：3已上柜 */
    private static final int MAX_STAGE = 3;
    /** 版次落点：1在办 */
    private static final int STATUS_ACTIVE = 1;
    /** 版次落点：2已作废（点明不再往下走，单就此打住） */
    private static final int STATUS_TERMINAL = 2;

    @javax.annotation.Resource
    private TItemCatalogVerMapper itemCatalogVerMapper;

    @Override
    public TItemCatalogVer selectTItemCatalogVerById(Long id) {
        return this.itemCatalogVerMapper.selectById(id);
    }

    @Override
    public List<TItemCatalogVer> selectTItemCatalogVerList(QueryWrapper<TItemCatalogVer> queryWrapper) {
        return this.itemCatalogVerMapper.selectList(queryWrapper);
    }

    /**
     * 推进一档。四档挨着走，一次只过一档，跳着过不算；
     * 已作废（打住）或已上柜（封住）的单不能再往前，被拒时不落任何记事。
     */
    @Override
    public TItemCatalogVer advance(Long id, String remark) {
        TItemCatalogVer r = this.itemCatalogVerMapper.selectById(id);
        if (r == null || isSealed(r)) {
            return null;
        }
        int st = r.getStage() == null ? 0 : r.getStage();
        r.setStage(Integer.valueOf(st + 1));
        r.setStatus(Integer.valueOf(STATUS_ACTIVE));
        r.setLastAction(remark);
        this.itemCatalogVerMapper.updateById(r);
        return r;
    }

    /**
     * 回退一档。真要改口只能往回收一档重来，不得一把退回草拟；
     * 已作废或已上柜的单收不动，草拟之前无档可收，被拒时不落任何记事。
     */
    @Override
    public TItemCatalogVer rollback(Long id, String remark) {
        TItemCatalogVer r = this.itemCatalogVerMapper.selectById(id);
        if (r == null || isSealed(r)) {
            return null;
        }
        int st = r.getStage() == null ? 0 : r.getStage();
        if (st <= 0) {
            return null;
        }
        r.setStage(Integer.valueOf(st - 1));
        r.setStatus(Integer.valueOf(STATUS_ACTIVE));
        r.setLastAction(remark);
        this.itemCatalogVerMapper.updateById(r);
        return r;
    }

    /**
     * 修改版次记事。已办结（已上柜封住 / 已作废打住）的单，记事一句改不得。
     */
    @Override
    public boolean updateContent(Long id, String remark) {
        TItemCatalogVer r = this.itemCatalogVerMapper.selectById(id);
        if (r == null || isSealed(r)) {
            return false;
        }
        r.setContent(remark);
        return this.itemCatalogVerMapper.updateById(r) > 0;
    }

    /**
     * 删除单据。已办结（已上柜 / 已作废）的单整张也删不得——
     * 删了，年底就查不着这一项当年是按哪一版口径办的。
     */
    @Override
    public boolean remove(Long id) {
        TItemCatalogVer r = this.itemCatalogVerMapper.selectById(id);
        if (r == null || isSealed(r)) {
            return false;
        }
        return this.itemCatalogVerMapper.deleteById(id) > 0;
    }

    /**
     * 是否已办结：已上柜（各栏随即封住）或已作废（就此打住，事后不能追接一档）。
     * 档次数以库内记录为准，空值按草拟起算。
     */
    private static boolean isSealed(TItemCatalogVer r) {
        int st = r.getStage() == null ? 0 : r.getStage().intValue();
        if (st >= MAX_STAGE) {
            return true;
        }
        return r.getStatus() != null && r.getStatus().intValue() == STATUS_TERMINAL;
    }

}
