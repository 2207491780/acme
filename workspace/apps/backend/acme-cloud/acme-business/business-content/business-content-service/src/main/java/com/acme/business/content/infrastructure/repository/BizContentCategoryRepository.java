package com.acme.business.content.infrastructure.repository;

import com.acme.business.content.domain.BizContentCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 内容分类仓库（biz_content_category）。 */
public interface BizContentCategoryRepository extends JpaRepository<BizContentCategory, Long> {

    /** 分类清单（sortOrder 升序；同序按 code 稳定排序）。 */
    List<BizContentCategory> findAllByOrderBySortOrderAscCodeAsc();
}
