package com.fc.v2.service;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.model.auto.TItemCatalogVer;

/**
 * 事项目录条目版次单 Service接口（state-machine 形状：单据流转，不提供增删改查全套）
 *
 * <p>四档挨着走：草拟(0)→复核(1)→付发布(2)→已上柜(3)，一次只过一档，不跳不替；
 * 当下停在哪一档以系统照库里档数算出来的为准，屏上写的号不作数。
 * 同一档第二遍报上来，账上只认先前那一遍——凡被拒的操作一律零写库。</p>
 *
 * @author fuce
 * @date 2026-09-14
 */
public interface ITItemCatalogVerService {

    /** 按主键查询单据 */
    TItemCatalogVer selectTItemCatalogVerById(Long id);

    /** 列表查询（流转台账） */
    List<TItemCatalogVer> selectTItemCatalogVerList(QueryWrapper<TItemCatalogVer> queryWrapper);

    /** 推进一档：返回更新后的单据；已上柜、已作废（打住）的单被拒，返回 null 且一字不落 */
    TItemCatalogVer advance(Long id, String remark);

    /** 回退一档：返回更新后的单据；草拟底下无可收、已上柜、已作废的单被拒，返回 null 且一字不落 */
    TItemCatalogVer rollback(Long id, String remark);

    /** 打住：点明"这一版不再往下走"，单就此打住（落点记已作废）；已上柜、已打住的单被拒，返回 null 且一字不落 */
    TItemCatalogVer terminate(Long id, String remark);

    /** 修改内容：已办结（已上柜、已作废）不得再改，被拒返回 false 且一字不落 */
    boolean updateContent(Long id, String remark);

    /** 删除单据：已办结（已上柜、已作废）不得删除，被拒返回 false */
    boolean remove(Long id);

    /** 版次履历审计：验一张单是不是真走到了头（档在已上柜、落点在办、履历对得拢）；走成了返回 true */
    boolean audit(Long id);
}
