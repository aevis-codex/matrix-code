package com.matrixcode.document;

import com.matrixcode.document.application.DocumentService;
import com.matrixcode.document.application.DocumentRepository;
import com.matrixcode.document.domain.DocumentState;
import com.matrixcode.document.domain.DocumentType;
import com.matrixcode.document.domain.DocumentVersion;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentServiceTest {

    private final DocumentService service = new DocumentService();

    @Test
    void 创建草稿时默认生成第一版草稿() {
        var draft = service.createDraft("project-1", DocumentType.PRD, "支付重构 PRD", "初始需求");

        assertThat(draft.projectId()).isEqualTo("project-1");
        assertThat(draft.type()).isEqualTo(DocumentType.PRD);
        assertThat(draft.title()).isEqualTo("支付重构 PRD");
        assertThat(draft.content()).isEqualTo("初始需求");
        assertThat(draft.version()).isEqualTo(1);
        assertThat(draft.state()).isEqualTo(DocumentState.DRAFT);
        assertThat(draft.parentVersionId()).isNull();
    }

    @Test
    void 非冻结文档可以更新内容() {
        var draft = service.createDraft("project-1", DocumentType.UI_BRIEF, "支付页面 UI", "初始界面");

        var updated = service.updateContent(draft.id(), "补充失败态");

        assertThat(updated.id()).isEqualTo(draft.id());
        assertThat(updated.content()).isEqualTo("补充失败态");
        assertThat(updated.state()).isEqualTo(DocumentState.DRAFT);
    }

    @Test
    void 冻结文档后不能修改原版本并记录冻结信息() {
        var draft = service.createDraft("project-1", DocumentType.PRD, "支付重构 PRD", "初始需求");

        var frozen = service.freeze(draft.id(), "user-product");

        assertThat(frozen.state()).isEqualTo(DocumentState.FROZEN);
        assertThat(frozen.frozenBy()).isEqualTo("user-product");
        assertThat(frozen.frozenAt()).isNotNull();
        assertThatThrownBy(() -> service.updateContent(frozen.id(), "修改冻结内容"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("冻结版本不能修改");
    }

    @Test
    void 重复冻结同一文档会被拒绝并保留首次冻结信息() {
        var draft = service.createDraft("project-1", DocumentType.PRD, "支付重构 PRD", "初始需求");
        var frozen = service.freeze(draft.id(), "user-product");

        assertThatThrownBy(() -> service.freeze(frozen.id(), "other-user"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("文档已冻结");
        assertThat(service.findById(frozen.id()).frozenBy()).isEqualTo("user-product");
        assertThat(service.findById(frozen.id()).frozenAt()).isEqualTo(frozen.frozenAt());
    }

    @Test
    void 需求变更会基于冻结版本创建新草稿且不影响父版本() {
        var draft = service.createDraft("project-1", DocumentType.PRD, "支付重构 PRD", "初始需求");
        var frozen = service.freeze(draft.id(), "user-product");

        var changeDraft = service.createChangeDraft(frozen.id(), "新增退款规则");
        var updatedChangeDraft = service.updateContent(changeDraft.id(), "新增退款和对账规则");

        assertThat(changeDraft.state()).isEqualTo(DocumentState.DRAFT);
        assertThat(changeDraft.parentVersionId()).isEqualTo(frozen.id());
        assertThat(changeDraft.version()).isEqualTo(2);
        assertThat(updatedChangeDraft.content()).isEqualTo("新增退款和对账规则");
        assertThat(service.findById(frozen.id()).content()).isEqualTo("初始需求");
    }

    @Test
    void 同一冻结父版本只能创建一个活跃变更草稿() {
        var draft = service.createDraft("project-1", DocumentType.PRD, "支付重构 PRD", "初始需求");
        var frozen = service.freeze(draft.id(), "user-product");
        var changeDraft = service.createChangeDraft(frozen.id(), "新增退款规则");

        assertThat(changeDraft.parentVersionId()).isEqualTo(frozen.id());
        assertThatThrownBy(() -> service.createChangeDraft(frozen.id(), "新增对账规则"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已存在变更草稿");
    }

    @Test
    void 非冻结父版本不能创建变更草稿() {
        var draft = service.createDraft("project-1", DocumentType.API_DOC, "支付 API", "初始接口");

        assertThatThrownBy(() -> service.createChangeDraft(draft.id(), "新增接口"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("只能基于冻结版本创建变更草稿");
    }

    @Test
    void 可以按项目查询文档且返回副本() {
        service.createDraft("project-1", DocumentType.PRD, "PRD", "需求");
        service.createDraft("project-2", DocumentType.UI_BRIEF, "界面", "说明");

        var documents = service.listByProject("project-1");

        assertThat(documents).hasSize(1);
        assertThat(documents.getFirst().title()).isEqualTo("PRD");
        assertThatThrownBy(documents::clear)
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 按项目查询文档时按标题排序() {
        service.createDraft("project-1", DocumentType.PRD, "B 文档", "需求");
        service.createDraft("project-1", DocumentType.UI_BRIEF, "A 文档", "说明");

        var documents = service.listByProject("project-1");

        assertThat(documents).extracting("title")
                .containsExactly("A 文档", "B 文档");
    }

    @Test
    void 创建草稿时缺少必要字段会被拒绝() {
        assertThatThrownBy(() -> service.createDraft(" ", DocumentType.PRD, "PRD", "需求"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("项目编号不能为空");
        assertThatThrownBy(() -> service.createDraft("project-1", null, "PRD", "需求"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文档类型不能为空");
        assertThatThrownBy(() -> service.createDraft("project-1", DocumentType.PRD, " ", "需求"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文档标题不能为空");
        assertThatThrownBy(() -> service.createDraft("project-1", DocumentType.PRD, "PRD", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文档内容不能为空");
    }

    @Test
    void 查询项目文档时空项目编号会被拒绝() {
        assertThatThrownBy(() -> service.listByProject(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("项目编号不能为空");
    }

    @Test
    void 找不到文档时抛出清晰异常() {
        assertThatThrownBy(() -> service.updateContent("missing-document", "任意内容"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文档版本不存在")
                .hasMessageContaining("missing-document");
    }

    @Test
    void 服务重建后恢复文档版本和冻结状态() {
        var store = new InMemoryWorkbenchStateStore();
        var firstService = new DocumentService(store);
        var draft = firstService.createDraft("project-1", DocumentType.PRD, "支付重构 PRD", "初始需求");
        var frozen = firstService.freeze(draft.id(), "user-product");

        var secondService = new DocumentService(store);

        assertThat(secondService.listByProject("project-1")).singleElement().satisfies(document -> {
            assertThat(document.id()).isEqualTo(frozen.id());
            assertThat(document.state()).isEqualTo(DocumentState.FROZEN);
            assertThat(document.frozenBy()).isEqualTo("user-product");
            assertThat(document.frozenAt()).isEqualTo(frozen.frozenAt());
        });
        assertThatThrownBy(() -> secondService.updateContent(frozen.id(), "修改冻结内容"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("冻结版本不能修改");
    }

    @Test
    void 正式文档仓储有数据时优先从仓储恢复() {
        var store = new InMemoryWorkbenchStateStore();
        new DocumentService(store).createDraft("project-1", DocumentType.PRD, "快照文档", "快照内容");
        var repository = new MemoryDocumentRepository(List.of(new DocumentVersion(
                "doc-repository",
                "project-1",
                DocumentType.CODING_AGENT_HANDOFF,
                "编码智能体交付回溯",
                "交付证据",
                1,
                DocumentState.DRAFT,
                null,
                java.time.Instant.parse("2026-06-25T09:00:00Z"),
                null,
                null
        )));

        var service = new DocumentService(store, repository);

        assertThat(service.listByProject("project-1")).singleElement().satisfies(document -> {
            assertThat(document.id()).isEqualTo("doc-repository");
            assertThat(document.type()).isEqualTo(DocumentType.CODING_AGENT_HANDOFF);
        });
    }

    @Test
    void 正式文档仓储为空时从快照恢复并回填仓储() {
        var store = new InMemoryWorkbenchStateStore();
        var firstService = new DocumentService(store);
        var draft = firstService.createDraft("project-1", DocumentType.PRD, "支付重构 PRD", "初始需求");
        var frozen = firstService.freeze(draft.id(), "user-product");
        var repository = new MemoryDocumentRepository(List.of());

        var service = new DocumentService(store, repository);

        assertThat(service.listByProject("project-1")).singleElement()
                .satisfies(document -> assertThat(document.id()).isEqualTo(frozen.id()));
        assertThat(repository.documents).singleElement()
                .satisfies(document -> assertThat(document.id()).isEqualTo(frozen.id()));
    }

    @Test
    void 并发冻结和更新同一文档时不会让草稿覆盖冻结版本() throws Exception {
        for (var round = 0; round < 200; round++) {
            var localService = new DocumentService();
            var draft = localService.createDraft("project-1", DocumentType.PRD, "支付重构 PRD", "初始需求");
            var executor = Executors.newFixedThreadPool(16);
            var ready = new CountDownLatch(16);
            var start = new CountDownLatch(1);
            try {
                List<Callable<Void>> tasks = new ArrayList<>();
                tasks.add(() -> {
                    ready.countDown();
                    start.await();
                    localService.freeze(draft.id(), "user-product");
                    return null;
                });
                for (var index = 0; index < 15; index++) {
                    var updateIndex = index;
                    tasks.add(() -> {
                        ready.countDown();
                        start.await();
                        try {
                            localService.updateContent(draft.id(), "并发修改 " + updateIndex);
                        } catch (IllegalStateException ignored) {
                        }
                        return null;
                    });
                }

                var results = new ArrayList<java.util.concurrent.Future<Void>>();
                for (var task : tasks) {
                    results.add(executor.submit(task));
                }
                ready.await(1, TimeUnit.SECONDS);
                start.countDown();
                for (var result : results) {
                    result.get();
                }
                executor.shutdown();
                assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
            } finally {
                executor.shutdownNow();
            }

            var stored = localService.findById(draft.id());
            assertThat(stored.state()).isEqualTo(DocumentState.FROZEN);
            assertThat(stored.frozenBy()).isEqualTo("user-product");
            assertThat(stored.frozenAt()).isNotNull();
        }
    }

    private static class MemoryDocumentRepository implements DocumentRepository {

        private List<DocumentVersion> documents;

        private MemoryDocumentRepository(List<DocumentVersion> documents) {
            this.documents = List.copyOf(documents);
        }

        @Override
        public List<DocumentVersion> load() {
            return documents;
        }

        @Override
        public void save(List<DocumentVersion> documents) {
            this.documents = List.copyOf(documents);
        }
    }
}
