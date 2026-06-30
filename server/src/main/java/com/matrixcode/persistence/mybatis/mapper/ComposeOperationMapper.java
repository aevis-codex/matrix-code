package com.matrixcode.persistence.mybatis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.matrixcode.persistence.mybatis.entity.ComposeOperationEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
/**
 * MyBatis-Plus Mapper 接口。
 *
 * <p>作用域：持久化层内部；场景：Repository 通过 BaseMapper 访问对应正式表，不直接暴露为业务 API。</p>
 */
public interface ComposeOperationMapper extends BaseMapper<ComposeOperationEntity> {
}
