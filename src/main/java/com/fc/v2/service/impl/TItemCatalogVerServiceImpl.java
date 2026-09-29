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
 * <p>版次单走一条有头有尾的线：草拟(0)→复核(1)→付发布(2)→已上柜(3)。两条硬杠杠：</p>
 * <ul>
 *   <li><b>次序</b>——四档挨着走，一次只过一档；停在复核的单替它过付发布不算过成，
 *       跳着过、倒着过一律不算。当下停在哪一档，屏上写的号不作数，系统照库里
 *       已过完的档数自己算：流转入口只收单号与记事，从不收档号，档号以库里算的为准。</li>
 *   <li><b>重复</b>——同一档第二遍报上来，账上只认先前那一遍，第二遍不许再多落一句
 *       记事：凡被拒的操作一律零写库（档号、记事、过口动作都保持先前那一遍的样子）。
 *       真要改口，只能把单往回收一档重来；收回去那档先前落过的名目压在底下，
 *       最近过口动作只留最新一笔，不与后补的并列同屏。</li>
 * </ul>
 * <p>上柜（stage=3）之后各栏随即封住：进不得、退不得、改不得、删不得，要改另立新单。
 * 哪一档点明"这一版不再往下走"，单就此打住（status=2 已作废），事后不能追接一档；
 * 已作废的单记事一句改不得，整张也删不得——删了年底就查不着当年按哪一版口径办的。</p>
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TItemCatalogVerServiceImpl implements ITItemCatalogVerService {

    /** 档次：0草拟 1复核 2付发布 3已上柜 */
    private static final int STAGE_DRAFT = 0;
    private static final int STAGE_ON_SHELF = 3;

    /** 落点：0未起 1在办 2已作废 */
    private static final int STATUS_NOT_STARTED = 0;
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_VOIDED = 2;

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
     * 推进一档。四档挨着走，一次只过一档：草拟→复核→付发布→已上柜。
     * 已打住的单事后不能追接；已上柜的单各栏封住。这两类再报上来都算
     * "同一档第二遍"，账上只认先前那一遍，一字不再落，直接拒。
     */
    @Override
    public TItemCatalogVer advance(Long id, String remark) {
        TItemCatalogVer r = this.itemCatalogVerMapper.selectById(id);
        if (r == null) {
            return null;
        }
        int st = stageOf(r);
        if (statusOf(r) == STATUS_VOIDED || st >= STAGE_ON_SHELF) {
            return null;
        }
        r.setStage(st + 1);
        r.setStatus(STATUS_ACTIVE);
        r.setLastAction(remark);
        this.itemCatalogVerMapper.updateById(r);
        return r;
    }

    /**
     * 往回收一档。真要改口只能收一档重来，不许一撸到底；草拟底下没有可收的档。
     * 已上柜的单封住退不得，已作废的单不能动。
     */
    @Override
    public TItemCatalogVer rollback(Long id, String remark) {
        TItemCatalogVer r = this.itemCatalogVerMapper.selectById(id);
        if (r == null) {
            return null;
        }
        int st = stageOf(r);
        if (statusOf(r) == STATUS_VOIDED || st >= STAGE_ON_SHELF || st <= STAGE_DRAFT) {
            return null;
        }
        r.setStage(st - 1);
        r.setStatus(STATUS_ACTIVE);
        r.setLastAction(remark);
        this.itemCatalogVerMapper.updateById(r);
        return r;
    }

    /**
     * 打住：哪一档点明"这一版不再往下走"，单就此打住（档停在当下，落点记已作废）。
     * 已上柜的单封住不能回头打住；已打住的单第二遍报上来不再多落一句记事。
     */
    @Override
    public TItemCatalogVer terminate(Long id, String remark) {
        TItemCatalogVer r = this.itemCatalogVerMapper.selectById(id);
        if (r == null) {
            return null;
        }
        if (statusOf(r) == STATUS_VOIDED || stageOf(r) >= STAGE_ON_SHELF) {
            return null;
        }
        r.setStatus(STATUS_VOIDED);
        r.setLastAction(remark);
        this.itemCatalogVerMapper.updateById(r);
        return r;
    }

    /**
     * 改版次记事。上柜之后各栏随即封住；已作废的单记事一句改不得。
     */
    @Override
    public boolean updateContent(Long id, String remark) {
        TItemCatalogVer r = this.itemCatalogVerMapper.selectById(id);
        if (r == null) {
            return false;
        }
        if (statusOf(r) == STATUS_VOIDED || stageOf(r) >= STAGE_ON_SHELF) {
            return false;
        }
        r.setContent(remark);
        return this.itemCatalogVerMapper.updateById(r) > 0;
    }

    /**
     * 删单。办结的单（已上柜、已作废）整张删不得——删了年底就查不着
     * 这一项当年是按哪一版口径办的；只有在办、未起的单可删。
     */
    @Override
    public boolean remove(Long id) {
        TItemCatalogVer r = this.itemCatalogVerMapper.selectById(id);
        if (r == null) {
            return false;
        }
        if (statusOf(r) == STATUS_VOIDED || stageOf(r) >= STAGE_ON_SHELF) {
            return false;
        }
        return this.itemCatalogVerMapper.deleteById(id) > 0;
    }

    /**
     * 版次履历审计：抽一张单，验它是不是真走到了头。机上只认——档在已上柜、
     * 落点在办、未被删、最近过口动作在，四样齐全才算走成；缺一档（档未到上柜）、
     * 多一笔或事后由人手补进去的（档与落点打架、没有过口动作），见着一处就算没走成。
     */
    @Override
    public boolean audit(Long id) {
        TItemCatalogVer r = this.itemCatalogVerMapper.selectById(id);
        if (r == null) {
            return false;
        }
        return stageOf(r) == STAGE_ON_SHELF
                && statusOf(r) == STATUS_ACTIVE
                && (r.getDelFlag() == null || r.getDelFlag().intValue() == 0)
                && r.getLastAction() != null && !r.getLastAction().trim().isEmpty();
    }

    /** 档号以库里算的为准；库里没落的当未起草拟 */
    private static int stageOf(TItemCatalogVer r) {
        return r.getStage() == null ? STAGE_DRAFT : r.getStage().intValue();
    }

    /** 落点以库里算的为准；库里没落的当未起 */
    private static int statusOf(TItemCatalogVer r) {
        return r.getStatus() == null ? STATUS_NOT_STARTED : r.getStatus().intValue();
    }

}
