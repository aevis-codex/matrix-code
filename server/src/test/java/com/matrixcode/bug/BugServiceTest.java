package com.matrixcode.bug;

import com.matrixcode.bug.application.BugRepository;
import com.matrixcode.bug.application.BugService;
import com.matrixcode.bug.domain.BugSeverity;
import com.matrixcode.bug.domain.BugStatus;
import com.matrixcode.bug.domain.ProjectBug;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BugServiceTest {

    private final BugService service = new BugService();

    @Test
    void 创建Bug后默认为新建状态并按项目查询() {
        var bug = service.create("project-1", "支付失败未提示原因", BugSeverity.HIGH,
                "提交支付", "展示失败原因", "只返回空白页", "测试", "开发");

        assertThat(bug.status()).isEqualTo(BugStatus.NEW);
        assertThat(service.listByProject("project-1")).containsExactly(bug);
        assertThat(service.listByProject("project-2")).isEmpty();
    }

    @Test
    void Bug可以按合法路径流转() {
        var bug = service.create("project-1", "支付失败未提示原因", BugSeverity.HIGH,
                "提交支付", "展示失败原因", "只返回空白页", "测试", "开发");

        var confirmed = service.transition(bug.id(), BugStatus.CONFIRMED, "测试确认");
        var fixing = service.transition(confirmed.id(), BugStatus.FIXING, "开发处理中");
        var regression = service.transition(fixing.id(), BugStatus.REGRESSION_PENDING, "等待回归");
        var closed = service.transition(regression.id(), BugStatus.CLOSED, "回归通过");

        assertThat(closed.status()).isEqualTo(BugStatus.CLOSED);
        assertThat(closed.lastNote()).isEqualTo("回归通过");
    }

    @Test
    void 已关闭Bug可以重新打开但不能直接进入修复中() {
        var bug = service.create("project-1", "支付失败未提示原因", BugSeverity.HIGH,
                "提交支付", "展示失败原因", "只返回空白页", "测试", "开发");

        var confirmed = service.transition(bug.id(), BugStatus.CONFIRMED, "测试确认");
        var fixing = service.transition(confirmed.id(), BugStatus.FIXING, "开发处理中");
        var regression = service.transition(fixing.id(), BugStatus.REGRESSION_PENDING, "等待回归");
        var closed = service.transition(regression.id(), BugStatus.CLOSED, "回归通过");
        var reopened = service.transition(closed.id(), BugStatus.REOPENED, "线上复现");

        assertThat(reopened.status()).isEqualTo(BugStatus.REOPENED);
        var unconfirmed = service.create("project-1", "支付失败未提示原因", BugSeverity.HIGH,
                "提交支付", "展示失败原因", "只返回空白页", "测试", "开发");

        assertThatThrownBy(() -> service.transition(unconfirmed.id(), BugStatus.FIXING, "跳过确认"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非法 Bug 状态流转");
    }

    @Test
    void 服务重建后恢复Bug状态和流转备注() {
        var store = new InMemoryWorkbenchStateStore();
        var firstService = new BugService(store);
        var bug = firstService.create("project-1", "支付失败未提示原因", BugSeverity.HIGH,
                "提交支付", "展示失败原因", "只返回空白页", "测试", "开发");
        firstService.transition(bug.id(), BugStatus.CONFIRMED, "测试确认");
        var fixing = firstService.transition(bug.id(), BugStatus.FIXING, "开发处理中");

        var secondService = new BugService(store);

        assertThat(secondService.listByProject("project-1")).singleElement().satisfies(restored -> {
            assertThat(restored.id()).isEqualTo(fixing.id());
            assertThat(restored.status()).isEqualTo(BugStatus.FIXING);
            assertThat(restored.lastNote()).isEqualTo("开发处理中");
        });
    }

    @Test
    void 正式Bug仓储有数据时优先从仓储恢复() {
        var store = new InMemoryWorkbenchStateStore();
        new BugService(store).create("project-1", "快照 Bug", BugSeverity.LOW,
                "打开页面", "展示文案", "文案缺失", "测试", "开发");
        var repository = new MemoryBugRepository(List.of(new ProjectBug(
                "bug-repository",
                "project-1",
                "正式仓储 Bug",
                BugSeverity.HIGH,
                BugStatus.CONFIRMED,
                "提交支付",
                "展示失败原因",
                "只返回空白页",
                "测试",
                "开发",
                "测试确认",
                Instant.parse("2026-06-25T11:00:00Z")
        )));

        var service = new BugService(store, repository);

        assertThat(service.listByProject("project-1")).singleElement().satisfies(bug -> {
            assertThat(bug.id()).isEqualTo("bug-repository");
            assertThat(bug.status()).isEqualTo(BugStatus.CONFIRMED);
        });
    }

    @Test
    void 正式Bug仓储为空时从快照恢复并回填仓储() {
        var store = new InMemoryWorkbenchStateStore();
        var firstService = new BugService(store);
        var bug = firstService.create("project-1", "支付失败未提示原因", BugSeverity.HIGH,
                "提交支付", "展示失败原因", "只返回空白页", "测试", "开发");
        var confirmed = firstService.transition(bug.id(), BugStatus.CONFIRMED, "测试确认");
        var repository = new MemoryBugRepository(List.of());

        var service = new BugService(store, repository);

        assertThat(service.listByProject("project-1")).singleElement()
                .satisfies(restored -> assertThat(restored.id()).isEqualTo(confirmed.id()));
        assertThat(repository.bugs).singleElement()
                .satisfies(restored -> assertThat(restored.id()).isEqualTo(confirmed.id()));
    }

    private static class MemoryBugRepository implements BugRepository {

        private List<ProjectBug> bugs;

        private MemoryBugRepository(List<ProjectBug> bugs) {
            this.bugs = List.copyOf(bugs);
        }

        @Override
        public List<ProjectBug> load() {
            return bugs;
        }

        @Override
        public void save(List<ProjectBug> bugs) {
            this.bugs = List.copyOf(bugs);
        }
    }
}
