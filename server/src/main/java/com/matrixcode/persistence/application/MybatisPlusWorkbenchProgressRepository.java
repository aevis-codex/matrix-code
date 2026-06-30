package com.matrixcode.persistence.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.matrixcode.persistence.mybatis.entity.AcceptanceStateEntity;
import com.matrixcode.persistence.mybatis.entity.MatrixProjectEntity;
import com.matrixcode.persistence.mybatis.entity.WorkflowEventEntity;
import com.matrixcode.persistence.mybatis.entity.WorkflowItemEntity;
import com.matrixcode.persistence.mybatis.mapper.AcceptanceStateMapper;
import com.matrixcode.persistence.mybatis.mapper.MatrixProjectMapper;
import com.matrixcode.persistence.mybatis.mapper.WorkflowEventMapper;
import com.matrixcode.persistence.mybatis.mapper.WorkflowItemMapper;
import com.matrixcode.workbench.application.WorkbenchProgressRepository;
import com.matrixcode.workbench.application.WorkbenchStateSnapshot;
import com.matrixcode.workflow.domain.WorkflowEvent;
import com.matrixcode.workflow.domain.WorkflowItem;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Repository
@ConditionalOnProperty(prefix = "matrixcode.persistence", name = "mode", havingValue = "jdbc")
public class MybatisPlusWorkbenchProgressRepository implements WorkbenchProgressRepository {

    private final WorkflowItemMapper itemMapper;
    private final WorkflowEventMapper eventMapper;
    private final AcceptanceStateMapper acceptanceMapper;
    private final MatrixProjectMapper projectMapper;

    public MybatisPlusWorkbenchProgressRepository(
            WorkflowItemMapper itemMapper,
            WorkflowEventMapper eventMapper,
            AcceptanceStateMapper acceptanceMapper,
            MatrixProjectMapper projectMapper
    ) {
        this.itemMapper = itemMapper;
        this.eventMapper = eventMapper;
        this.acceptanceMapper = acceptanceMapper;
        this.projectMapper = projectMapper;
    }

    @Override
    public List<WorkflowItem> loadWorkflowItems() {
        return itemMapper.selectList(new LambdaQueryWrapper<WorkflowItemEntity>()
                        .orderByAsc(WorkflowItemEntity::getProjectId)
                        .orderByAsc(WorkflowItemEntity::getUpdatedAt)
                        .orderByAsc(WorkflowItemEntity::getId))
                .stream()
                .map(WorkflowItemEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public void saveWorkflowItems(List<WorkflowItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (var item : items) {
            ensureProject(item.projectId(), "工作流");
            var entity = WorkflowItemEntity.fromDomain(item, Instant.now());
            if (itemMapper.updateById(entity) == 0) {
                itemMapper.insert(entity);
            }
        }
    }

    @Override
    public Map<String, List<WorkflowEvent>> loadWorkflowEvents() {
        var grouped = new LinkedHashMap<String, List<WorkflowEvent>>();
        eventMapper.selectList(new LambdaQueryWrapper<WorkflowEventEntity>()
                        .orderByAsc(WorkflowEventEntity::getItemId)
                        .orderByAsc(WorkflowEventEntity::getOccurredAt)
                        .orderByAsc(WorkflowEventEntity::getId))
                .stream()
                .map(WorkflowEventEntity::toDomain)
                .forEach(event -> grouped.computeIfAbsent(event.itemId(), ignored -> new ArrayList<>()).add(event));
        return immutableGrouped(grouped);
    }

    @Override
    @Transactional
    public void saveWorkflowEvents(Map<String, List<WorkflowEvent>> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        for (var entry : events.entrySet()) {
            var projectId = projectIdForItem(entry.getKey());
            if (projectId == null) {
                continue;
            }
            eventMapper.delete(new LambdaQueryWrapper<WorkflowEventEntity>()
                    .eq(WorkflowEventEntity::getItemId, entry.getKey()));
            for (var event : entry.getValue()) {
                eventMapper.insert(WorkflowEventEntity.fromDomain(projectId, event));
            }
        }
    }

    @Override
    public Map<String, WorkbenchStateSnapshot.AcceptanceState> loadAcceptances() {
        var acceptances = new LinkedHashMap<String, WorkbenchStateSnapshot.AcceptanceState>();
        acceptanceMapper.selectList(new LambdaQueryWrapper<AcceptanceStateEntity>()
                        .orderByAsc(AcceptanceStateEntity::getProjectId))
                .forEach(acceptance -> acceptances.put(acceptance.getProjectId(), acceptance.toDomain()));
        return Map.copyOf(acceptances);
    }

    @Override
    @Transactional
    public void saveAcceptances(Map<String, WorkbenchStateSnapshot.AcceptanceState> acceptances) {
        if (acceptances == null || acceptances.isEmpty()) {
            return;
        }
        acceptances.forEach((projectId, acceptance) -> {
            ensureProject(projectId, "验收");
            var entity = AcceptanceStateEntity.fromDomain(projectId, Objects.requireNonNull(acceptance, "acceptance 不能为空"), Instant.now());
            if (acceptanceMapper.updateById(entity) == 0) {
                acceptanceMapper.insert(entity);
            }
        });
    }

    private String projectIdForItem(String itemId) {
        var item = itemMapper.selectById(itemId);
        return item == null ? null : item.getProjectId();
    }

    private void ensureProject(String projectId, String stage) {
        if (projectMapper.update(null, new LambdaUpdateWrapper<MatrixProjectEntity>()
                .set(MatrixProjectEntity::getUpdatedAt, Instant.now())
                .eq(MatrixProjectEntity::getId, projectId)) > 0) {
            return;
        }
        var now = Instant.now();
        var project = new MatrixProjectEntity();
        project.setId(projectId);
        project.setName(projectId);
        project.setDescription("");
        project.setOwnerUserId(null);
        project.setStatus("ACTIVE");
        project.setCurrentStage(stage);
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        projectMapper.insert(project);
    }

    private <T> Map<String, List<T>> immutableGrouped(Map<String, List<T>> grouped) {
        var copy = new LinkedHashMap<String, List<T>>();
        grouped.forEach((key, records) -> copy.put(key, List.copyOf(records)));
        return Map.copyOf(copy);
    }
}
