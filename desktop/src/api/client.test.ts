import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  authorizeLocalWorkspace,
  applyCodingAgentPatch,
  cancelLocalExecutionTask,
  captureComposeLogs,
  claimAgentRun,
  claimNextAgentRun,
  captureGitDiff,
  acceptProjectInvitation,
  batchUpdateProjectMembers,
  configureComposeEnvironment,
  configureDeploymentTarget,
  createProjectInvitation,
  createBug,
  createProductDrafts,
  createRoleModelRequest,
  decideLocalCommandApproval,
  bindRoleModel,
  freezeDocument,
  loadLocalExecutionSummary,
  loadLocalExecutionTaskLogs,
  loadModelGatewayConfig,
  loadProjectOverview,
  loadProjectWorkbench,
  loadProjectInvitations,
  loadProjectMembers,
  loadProjectModelCostTrends,
  expireProjectInvitations,
  importDeploymentReleaseAudit,
  loadRoleAgentConfigs,
  loadAgentRunEvents,
  loadAgentRunModelRequests,
  loadAgentRunRecoveryPlan,
  loadAgentRunUserAudit,
  loadAgentRuns,
  loadActorSession,
  loadActorSessions,
  listLocalFiles,
  loadRuntimeDiagnostics,
  loadUserAuditRecords,
  loadUserProjects,
  loginActorSession,
  logoutActorSession,
  kickoutActorSessions,
  markAllRuntimeNotificationsRead,
  markRuntimeNotificationRead,
  prepareCodingAgentExecution,
  readLocalFile,
  recordCodingAgentHandoff,
  recordDeploymentOperation,
  reissueProjectInvitation,
  retryAgentRun,
  renewActorSession,
  revokeProjectInvitation,
  runDeploymentHealthCheck,
  startComposeEnvironment,
  stopComposeEnvironment,
  subscribeProjectEvents,
  submitLocalCommand,
  submitAcceptance,
  submitDeveloperDelivery,
  submitTestReport,
  transitionBug,
  addProjectMember,
  updateProjectMember,
  updateRoleAgentConfig,
  validateComposeEnvironment,
  writeLocalFile
} from './client';

class FakeEventSource {
  static instances: FakeEventSource[] = [];

  listeners = new Map<string, Set<(event: MessageEvent<string>) => void>>();
  closed = false;
  onerror: (() => void) | null = null;

  constructor(public url: string) {
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: (event: MessageEvent<string>) => void) {
    const listeners = this.listeners.get(type) ?? new Set();
    listeners.add(listener);
    this.listeners.set(type, listeners);
  }

  removeEventListener(type: string, listener: (event: MessageEvent<string>) => void) {
    this.listeners.get(type)?.delete(listener);
  }

  close() {
    this.closed = true;
  }

  emit(type: string, data: unknown) {
    this.listeners.get(type)?.forEach((listener) => listener({ data: JSON.stringify(data) } as MessageEvent<string>));
  }
}

describe('项目概览 API 客户端', () => {
  afterEach(() => {
    localStorage.removeItem('matrixcode.actorToken');
    vi.unstubAllGlobals();
  });

  it('从团队服务器加载项目概览时提交当前操作者身份', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        projectId: 'demo',
        projectName: '支付系统重构',
        roles: ['产品', '开发', '测试', '运维'],
        stages: ['需求', '开发', '部署文档', '测试环境', '测试', '上线'],
        cacheHitRate: 0.86,
        sessionTokens: 221000,
        currentStage: '测试执行'
      })
    });
    vi.stubGlobal('fetch', fetchMock);
    localStorage.setItem('matrixcode.actorToken', 'overview-token');

    const overview = await loadProjectOverview('demo', 'user-product', 'http://localhost:8080/');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/overview', {
      headers: {
        Accept: 'application/json',
        Authorization: 'Bearer overview-token',
        'X-MatrixCode-User-Id': 'user-product'
      }
    });
    expect(overview.projectName).toBe('支付系统重构');
  });

  it('默认连接真实本地后端端口', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        projectId: 'demo',
        projectName: 'MatrixCode 项目',
        roles: [],
        stages: [],
        cacheHitRate: 0,
        sessionTokens: 0,
        currentStage: '测试中'
      })
    });
    vi.stubGlobal('fetch', fetchMock);

    await loadProjectOverview('demo', 'user-product');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/overview', {
      headers: {
        Accept: 'application/json',
        'X-MatrixCode-User-Id': 'user-product'
      }
    });
  });

  it('服务端返回错误状态时给出通用中文错误', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 503,
        statusText: 'Service Unavailable'
      })
    );

    await expect(loadProjectOverview('demo', 'http://localhost:8080')).rejects.toThrow(
      '团队服务器请求失败：503 Service Unavailable'
    );
  });

  it('服务端返回业务错误时优先透出错误消息', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 400,
        statusText: 'Bad Request',
        json: async () => ({ message: '产品需求不能为空' })
      })
    );

    await expect(
      createProductDrafts('demo', { requirement: '' }, 'http://localhost:8080')
    ).rejects.toThrow('产品需求不能为空');
  });

  it('网络异常时透出连接失败提示', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('ECONNREFUSED')));

    await expect(loadProjectOverview('demo', 'http://localhost:8080')).rejects.toThrow('团队服务器连接失败');
  });
});

describe('角色工作台 API 客户端', () => {
  afterEach(() => {
    localStorage.removeItem('matrixcode.actorToken');
    vi.unstubAllGlobals();
  });

  it('加载角色工作台', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        projectId: 'demo',
        projectName: '支付系统重构',
        currentStage: '需求录入',
        roles: [],
        documents: [],
        bugs: [],
        deploymentTargets: [],
        metrics: { cacheHitRate: 0.86, sessionTokens: 221000, eventCount: 0, documentCount: 0, openBugCount: 0 },
        events: []
      })
    });
    vi.stubGlobal('fetch', fetchMock);

    const workbench = await loadProjectWorkbench('demo', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/workbench', {
      headers: { Accept: 'application/json' }
    });
    expect(workbench.currentStage).toBe('需求录入');
  });

  it('加载角色工作台时可以携带当前操作者身份', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        projectId: 'demo',
        projectName: '支付系统重构',
        currentStage: '需求录入',
        roles: [],
        documents: [],
        bugs: [],
        deploymentTargets: [],
        metrics: { cacheHitRate: 0.86, sessionTokens: 221000, eventCount: 0, documentCount: 0, openBugCount: 0 },
        events: [],
        runtimeNotifications: []
      })
    });
    vi.stubGlobal('fetch', fetchMock);

    await loadProjectWorkbench('demo', 'user-product', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/workbench', {
      headers: {
        Accept: 'application/json',
        'X-MatrixCode-User-Id': 'user-product'
      }
    });
  });

  it('加载模型网关配置', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        providers: [
          {
            id: 'local-deterministic',
            name: '本地确定性模型',
            protocol: 'LOCAL',
            baseUrl: '',
            apiKeySource: 'NONE',
            enabled: true
          }
        ],
        bindings: [],
        metrics: {
          requestCount: 0,
          cacheHitTokens: 0,
          cacheMissInputTokens: 0,
          outputTokens: 0,
          cacheHitRate: 0,
          estimatedCost: 0,
          currency: 'CNY',
          recentContextTypes: []
        },
        recentRequests: []
      })
    });
    vi.stubGlobal('fetch', fetchMock);

    const config = await loadModelGatewayConfig('demo', 'user-product', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/model-gateway/config', {
      headers: { Accept: 'application/json', 'X-MatrixCode-User-Id': 'user-product' }
    });
    expect(config.providers[0].name).toBe('本地确定性模型');
  });

  it('加载 Agent 运行记录列表', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => [
        {
          id: 'run-1',
          projectId: 'demo',
          roleKey: 'DEVELOPER',
          agentKind: 'coding',
          actorUserId: 'user-dev',
          providerId: 'deepseek',
          modelName: 'deepseek-chat',
          goal: '修复支付失败重试',
          status: 'RUNNING',
          summary: '执行准备已生成',
          startedAt: '2026-06-25T08:00:00Z',
          finishedAt: null,
          createdAt: '2026-06-25T08:00:00Z',
          updatedAt: '2026-06-25T08:01:00Z'
        }
      ]
    });
    vi.stubGlobal('fetch', fetchMock);

    window.localStorage.setItem('matrixcode.actorToken', 'token-agent-runs');

    const runs = await loadAgentRuns('demo', 'user-dev', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/agent-runs', {
      headers: {
        Accept: 'application/json',
        Authorization: 'Bearer token-agent-runs',
        'X-MatrixCode-User-Id': 'user-dev'
      }
    });
    expect(runs[0].goal).toBe('修复支付失败重试');
  });

  it('加载 Agent 运行事件时间线', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => [
        {
          id: 'event-1',
          runId: 'run-1',
          projectId: 'demo',
          eventType: 'EXECUTION_PREPARED',
          eventTitle: '执行准备完成',
          eventPayload: '{"taskId":"task-1"}',
          occurredAt: '2026-06-25T08:01:00Z'
        }
      ]
    });
    vi.stubGlobal('fetch', fetchMock);

    window.localStorage.setItem('matrixcode.actorToken', 'token-agent-events');

    const events = await loadAgentRunEvents('demo', 'run-1', 'user-dev', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/agent-runs/run-1/events', {
      headers: {
        Accept: 'application/json',
        Authorization: 'Bearer token-agent-events',
        'X-MatrixCode-User-Id': 'user-dev'
      }
    });
    expect(events[0].eventType).toBe('EXECUTION_PREPARED');
  });

  it('加载 Agent 运行恢复计划时提交当前操作者', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        projectId: 'demo',
        sourceRunId: 'run-1',
        canRetry: true,
        retryGoal: '恢复失败运行',
        blockedReason: ''
      })
    });
    vi.stubGlobal('fetch', fetchMock);
    window.localStorage.setItem('matrixcode.actorToken', 'token-agent-recovery');

    const plan = await loadAgentRunRecoveryPlan('demo', 'run-1', 'user-dev', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/agent-runs/run-1/recovery-plan', {
      headers: {
        Accept: 'application/json',
        Authorization: 'Bearer token-agent-recovery',
        'X-MatrixCode-User-Id': 'user-dev'
      }
    });
    expect(plan.canRetry).toBe(true);
  });

  it('重试 Agent 运行时调用项目运行恢复地址并提交操作者', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        id: 'run-retry',
        projectId: 'demo',
        roleKey: 'DEVELOPER',
        agentKind: 'coding',
        actorUserId: 'user-product',
        providerId: 'deepseek',
        modelName: 'deepseek-chat',
        goal: '修复支付失败重试',
        status: 'QUEUED',
        summary: '等待从失败运行恢复',
        retryOfRunId: 'run-1',
        createdAt: '2026-06-25T08:03:00Z',
        startedAt: null,
        finishedAt: null,
        updatedAt: '2026-06-25T08:03:00Z'
      })
    });
    vi.stubGlobal('fetch', fetchMock);

    const retryRun = await retryAgentRun('demo', 'run-1', 'user-product', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/projects/demo/agent-runs/run-1/retry?actorUserId=user-product',
      {
        method: 'POST',
        headers: { Accept: 'application/json', 'X-MatrixCode-User-Id': 'user-product' }
      }
    );
    expect(retryRun.retryOfRunId).toBe('run-1');
  });

  it('认领 Agent 运行时调用项目运行认领地址并提交操作者', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        id: 'run-retry',
        projectId: 'demo',
        roleKey: 'DEVELOPER',
        agentKind: 'coding',
        actorUserId: 'user-product',
        providerId: 'deepseek',
        modelName: 'deepseek-chat',
        goal: '修复支付失败重试',
        status: 'RUNNING',
        summary: '运行已认领',
        failureSummary: '测试命令超时',
        retryable: false,
        retryOfRunId: 'run-1',
        createdAt: '2026-06-25T08:03:00Z',
        startedAt: '2026-06-25T08:04:00Z',
        finishedAt: null,
        updatedAt: '2026-06-25T08:04:00Z'
      })
    });
    vi.stubGlobal('fetch', fetchMock);

    const claimedRun = await claimAgentRun('demo', 'run-retry', 'user-product', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/projects/demo/agent-runs/run-retry/claim?actorUserId=user-product',
      {
        method: 'POST',
        headers: { Accept: 'application/json', 'X-MatrixCode-User-Id': 'user-product' }
      }
    );
    expect(claimedRun.status).toBe('RUNNING');
  });

  it('认领下一条 Agent 运行时调用项目队列认领地址并提交操作者', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({
        id: 'run-retry',
        projectId: 'demo',
        roleKey: 'DEVELOPER',
        agentKind: 'coding',
        actorUserId: 'user-product',
        providerId: 'deepseek',
        modelName: 'deepseek-chat',
        goal: '修复支付失败重试',
        status: 'RUNNING',
        summary: '运行已认领',
        retryOfRunId: 'run-1',
        claimedByUserId: 'worker-1',
        claimedAt: '2026-06-25T08:04:00Z',
        claimExpiresAt: '2026-06-25T08:19:00Z',
        createdAt: '2026-06-25T08:03:00Z',
        startedAt: '2026-06-25T08:04:00Z',
        finishedAt: null,
        updatedAt: '2026-06-25T08:04:00Z'
      })
    });
    vi.stubGlobal('fetch', fetchMock);

    const claimedRun = await claimNextAgentRun('demo', 'worker-1', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/projects/demo/agent-runs/claim-next?actorUserId=worker-1',
      {
        method: 'POST',
        headers: { Accept: 'application/json', 'X-MatrixCode-User-Id': 'worker-1' }
      }
    );
    expect(claimedRun?.id).toBe('run-retry');
  });

  it('Agent Runtime 写操作透传本地身份令牌', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        id: 'run-retry',
        projectId: 'demo',
        roleKey: 'DEVELOPER',
        agentKind: 'coding',
        actorUserId: 'user-product',
        providerId: 'deepseek',
        modelName: 'deepseek-chat',
        goal: '修复支付失败重试',
        status: 'QUEUED',
        summary: '等待从失败运行恢复',
        retryOfRunId: 'run-1',
        createdAt: '2026-06-25T08:03:00Z',
        startedAt: null,
        finishedAt: null,
        updatedAt: '2026-06-25T08:03:00Z'
      })
    });
    vi.stubGlobal('fetch', fetchMock);
    localStorage.setItem('matrixcode.actorToken', 'signed-token');

    await retryAgentRun('demo', 'run-1', 'user-product', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/projects/demo/agent-runs/run-1/retry?actorUserId=user-product',
      {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          Authorization: 'Bearer signed-token',
          'X-MatrixCode-User-Id': 'user-product'
        }
      }
    );
  });

  it('认领下一条 Agent 运行遇到空队列时返回 null', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 204,
      statusText: 'No Content'
    });
    vi.stubGlobal('fetch', fetchMock);

    await expect(claimNextAgentRun('demo', 'worker-1', 'http://localhost:8080')).resolves.toBeNull();
  });

  it('绑定角色模型时调用角色绑定地址', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ role: 'TESTER', model: 'matrixcode-local-tester-v2' })
    });
    vi.stubGlobal('fetch', fetchMock);
    const input = {
      providerId: 'local-deterministic',
      model: 'matrixcode-local-tester-v2',
      currency: 'CNY',
      cacheHitPerMillion: 0,
      cacheMissInputPerMillion: 0,
      outputPerMillion: 0,
      contextBudgetTokens: 48000,
      toolContractVersion: 'tools-v2'
    };

    await bindRoleModel('demo', 'tester', input, 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/roles/tester/model-binding', {
      method: 'POST',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify(input)
    });
  });

  it('绑定角色模型时可透传当前操作者', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ role: 'TESTER', model: 'matrixcode-local-tester-v2' })
    });
    vi.stubGlobal('fetch', fetchMock);
    const input = {
      providerId: 'local-deterministic',
      model: 'matrixcode-local-tester-v2',
      currency: 'CNY',
      cacheHitPerMillion: 0,
      cacheMissInputPerMillion: 0,
      outputPerMillion: 0,
      contextBudgetTokens: 48000,
      toolContractVersion: 'tools-v2'
    };

    await bindRoleModel('demo', 'tester', input, 'user-owner', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/roles/tester/model-binding', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'X-MatrixCode-User-Id': 'user-owner'
      },
      body: JSON.stringify(input)
    });
  });

  it('准备编码智能体执行时调用角色执行准备地址', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        task: { taskId: 'coding-task-1', goal: '修复支付失败重试', status: 'PLANNED' },
        executionSteps: [],
        testCommandTask: { taskId: 'task-1', command: 'git status', status: 'APPROVAL_PENDING' },
        gitDiffSummary: { repository: true, changedFiles: [], stat: '' }
      })
    });
    vi.stubGlobal('fetch', fetchMock);
    const input = {
      goal: '修复支付失败重试',
      workspaceId: 'workspace-1',
      actorId: 'user-dev',
      testCommand: 'git status'
    };

    await prepareCodingAgentExecution('demo', 'developer', input, 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/projects/demo/roles/developer/coding-agent/execution-plans',
      {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          'X-MatrixCode-User-Id': 'user-dev'
        },
        body: JSON.stringify(input)
      }
    );
  });

  it('应用受控编码智能体 patch 时调用角色 patch 地址', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        projectId: 'demo',
        role: 'DEVELOPER',
        workspaceId: 'workspace-1',
        actorId: 'user-dev',
        relativePath: 'src/App.java',
        summary: '补充入口',
        bytesWritten: 28,
        gitDiffSummary: { repository: true, changedFiles: ['src/App.java'], stat: '1 file changed' }
      })
    });
    vi.stubGlobal('fetch', fetchMock);
    const input = {
      workspaceId: 'workspace-1',
      actorId: 'user-dev',
      relativePath: 'src/App.java',
      expectedContent: 'class App {}\n',
      nextContent: 'class App { void run() {} }\n',
      summary: '补充入口',
      approved: true
    };

    await applyCodingAgentPatch('demo', 'developer', input, 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/projects/demo/roles/developer/coding-agent/patches',
      {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          'X-MatrixCode-User-Id': 'user-dev'
        },
        body: JSON.stringify(input)
      }
    );
  });

  it('记录编码智能体交付回溯时调用角色 handoff 地址', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        id: 'doc-handoff-1',
        type: 'CODING_AGENT_HANDOFF',
        title: '编码智能体交付回溯',
        content: '交付结论：测试通过',
        version: 1,
        state: 'DRAFT',
        createdAt: '2026-06-25T07:00:00Z'
      })
    });
    vi.stubGlobal('fetch', fetchMock);
    const input = {
      workspaceId: 'workspace-1',
      actorId: 'user-dev',
      goal: '修复支付失败重试',
      relativePath: 'src/App.java',
      patchSummary: '补充入口',
      diffSummary: '1 file changed',
      testTaskId: 'task-1',
      testTaskStatus: 'SUCCESS',
      testCommand: 'mvn test',
      deliveryConclusion: '测试通过，可以交付'
    };

    await recordCodingAgentHandoff('demo', 'developer', input, 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/projects/demo/roles/developer/coding-agent/handoffs',
      {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          'X-MatrixCode-User-Id': 'user-dev'
        },
        body: JSON.stringify(input)
      }
    );
  });

  it('编码智能体写接口会透传 actor token', async () => {
    localStorage.setItem('matrixcode.actorToken', 'signed-coding-token');
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        projectId: 'demo',
        role: 'DEVELOPER',
        workspaceId: 'workspace-1',
        actorId: 'user-dev',
        relativePath: 'src/App.java',
        summary: '补充入口',
        bytesWritten: 28,
        gitDiffSummary: { repository: true, changedFiles: ['src/App.java'], stat: '1 file changed' }
      })
    });
    vi.stubGlobal('fetch', fetchMock);
    const input = {
      workspaceId: 'workspace-1',
      actorId: 'user-dev',
      relativePath: 'src/App.java',
      expectedContent: 'class App {}\n',
      nextContent: 'class App { void run() {} }\n',
      summary: '补充入口',
      approved: true
    };

    await applyCodingAgentPatch('demo', 'developer', input, 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/projects/demo/roles/developer/coding-agent/patches',
      {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          Authorization: 'Bearer signed-coding-token',
          'Content-Type': 'application/json',
          'X-MatrixCode-User-Id': 'user-dev'
        },
        body: JSON.stringify(input)
      }
    );
  });

  it('加载角色智能体配置列表', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => [
        {
          projectId: 'demo',
          role: 'DEVELOPER',
          displayName: '开发智能体',
          agentKind: 'coding',
          providerId: 'local-deterministic',
          model: 'matrixcode-local-developer',
          toolContractVersion: 'tools-v1',
          cachePolicyId: 'stable-platform-prefix-v1',
          volatileSuffixStrategy: 'role-prompt-and-dynamic-context',
          cacheScopeStrategy: 'provider-model',
          systemPrompt: '你是开发编码智能体。',
          userPromptTemplate: '请执行：{{instruction}}',
          themeColor: '#2563eb',
          fontFamily: 'Inter',
          fontSize: 14,
          sortOrder: 2,
          enabled: true,
          updatedAt: '2026-06-25T00:00:00Z'
        }
      ]
    });
    vi.stubGlobal('fetch', fetchMock);

    window.localStorage.setItem('matrixcode.actorToken', 'token-role-configs');

    const configs = await loadRoleAgentConfigs('demo', 'user-dev', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/role-agent-configs', {
      headers: {
        Accept: 'application/json',
        Authorization: 'Bearer token-role-configs',
        'X-MatrixCode-User-Id': 'user-dev'
      }
    });
    expect(configs[0].displayName).toBe('开发智能体');
  });

  it('加载项目成员列表时携带当前操作者身份', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => [
        {
          id: 'member-1',
          projectId: 'demo',
          userId: 'user-dev',
          roleKey: 'DEVELOPER',
          status: 'ACTIVE',
          joinedAt: '2026-06-25T14:00:00Z',
          createdAt: '2026-06-25T14:00:00Z',
          updatedAt: '2026-06-25T14:00:00Z'
        }
      ]
    });
    vi.stubGlobal('fetch', fetchMock);
    window.localStorage.setItem('matrixcode.actorToken', 'token-members');

    const members = await loadProjectMembers('demo', 'user-owner', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/identity/members', {
      headers: {
        Accept: 'application/json',
        Authorization: 'Bearer token-members',
        'X-MatrixCode-User-Id': 'user-owner'
      }
    });
    expect(members[0].userId).toBe('user-dev');
  });

  it('添加项目成员时携带当前操作者身份', async () => {
    const input = { userId: 'user-tester', displayName: '测试同学', roleKey: 'TESTER' };
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        id: 'member-2',
        projectId: 'demo',
        userId: 'user-tester',
        roleKey: 'TESTER',
        status: 'ACTIVE',
        joinedAt: '2026-06-25T14:05:00Z',
        createdAt: '2026-06-25T14:05:00Z',
        updatedAt: '2026-06-25T14:05:00Z'
      })
    });
    vi.stubGlobal('fetch', fetchMock);
    window.localStorage.setItem('matrixcode.actorToken', 'token-1');

    const member = await addProjectMember('demo', input, 'user-owner', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/identity/members', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        Authorization: 'Bearer token-1',
        'Content-Type': 'application/json',
        'X-MatrixCode-User-Id': 'user-owner'
      },
      body: JSON.stringify(input)
    });
    expect(member.roleKey).toBe('TESTER');
  });

  it('更新项目成员时携带当前操作者身份', async () => {
    const input = { roleKey: 'TESTER', status: 'DISABLED' };
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        id: 'member-dev',
        projectId: 'demo',
        userId: 'user-dev',
        roleKey: 'TESTER',
        status: 'DISABLED',
        joinedAt: '2026-06-25T14:05:00Z',
        createdAt: '2026-06-25T14:05:00Z',
        updatedAt: '2026-06-25T14:10:00Z'
      })
    });
    vi.stubGlobal('fetch', fetchMock);
    window.localStorage.setItem('matrixcode.actorToken', 'token-update-member');

    const member = await updateProjectMember('demo', 'user-dev', input, 'user-owner', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/identity/members/user-dev', {
      method: 'PATCH',
      headers: {
        Accept: 'application/json',
        Authorization: 'Bearer token-update-member',
        'Content-Type': 'application/json',
        'X-MatrixCode-User-Id': 'user-owner'
      },
      body: JSON.stringify(input)
    });
    expect(member.status).toBe('DISABLED');
  });

  it('加载项目邀请列表时携带当前操作者身份', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => [
        {
          id: 'invitation-1',
          projectId: 'demo',
          inviteeUserId: 'user-dev',
          displayName: '开发同学',
          roleKey: 'DEVELOPER',
          status: 'PENDING',
          createdByUserId: 'user-owner',
          expiresAt: '2026-07-01T00:00:00Z',
          acceptedAt: null,
          createdAt: '2026-06-29T08:00:00Z',
          updatedAt: '2026-06-29T08:00:00Z'
        }
      ]
    });
    vi.stubGlobal('fetch', fetchMock);
    window.localStorage.setItem('matrixcode.actorToken', 'token-invitations');

    const invitations = await loadProjectInvitations('demo', 'user-owner', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/identity/invitations', {
      headers: {
        Accept: 'application/json',
        Authorization: 'Bearer token-invitations',
        'X-MatrixCode-User-Id': 'user-owner'
      }
    });
    expect(invitations[0].inviteeUserId).toBe('user-dev');
  });

  it('创建项目邀请时携带当前操作者身份并返回一次性令牌', async () => {
    const input = {
      userId: 'user-dev',
      displayName: '开发同学',
      roleKey: 'DEVELOPER',
      expiresAt: '2026-07-01T00:00:00.000Z'
    };
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        invitation: {
          id: 'invitation-1',
          projectId: 'demo',
          inviteeUserId: 'user-dev',
          displayName: '开发同学',
          roleKey: 'DEVELOPER',
          status: 'PENDING',
          createdByUserId: 'user-owner',
          expiresAt: input.expiresAt,
          acceptedAt: null,
          createdAt: '2026-06-29T08:00:00Z',
          updatedAt: '2026-06-29T08:00:00Z'
        },
        token: 'invite-token'
      })
    });
    vi.stubGlobal('fetch', fetchMock);
    window.localStorage.setItem('matrixcode.actorToken', 'token-create-invitation');

    const issued = await createProjectInvitation('demo', input, 'user-owner', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/identity/invitations', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        Authorization: 'Bearer token-create-invitation',
        'Content-Type': 'application/json',
        'X-MatrixCode-User-Id': 'user-owner'
      },
      body: JSON.stringify(input)
    });
    expect(issued.token).toBe('invite-token');
  });

  it('接受项目邀请时使用令牌路径和当前登录身份', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        id: 'member-dev',
        projectId: 'demo',
        userId: 'user-dev',
        roleKey: 'DEVELOPER',
        status: 'ACTIVE',
        joinedAt: '2026-06-29T08:05:00Z',
        createdAt: '2026-06-29T08:05:00Z',
        updatedAt: '2026-06-29T08:05:00Z'
      })
    });
    vi.stubGlobal('fetch', fetchMock);
    window.localStorage.setItem('matrixcode.actorToken', 'token-accept-invitation');

    const member = await acceptProjectInvitation('demo', 'invite/token', 'user-dev', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/identity/invitations/invite%2Ftoken/accept', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        Authorization: 'Bearer token-accept-invitation',
        'X-MatrixCode-User-Id': 'user-dev'
      }
    });
    expect(member.userId).toBe('user-dev');
  });

  it('撤销项目邀请时调用邀请生命周期地址', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        id: 'invitation-1',
        projectId: 'demo',
        inviteeUserId: 'user-dev',
        displayName: '开发同学',
        roleKey: 'DEVELOPER',
        status: 'REVOKED',
        createdByUserId: 'user-owner',
        expiresAt: '2026-07-01T00:00:00Z',
        acceptedAt: null,
        createdAt: '2026-06-29T08:00:00Z',
        updatedAt: '2026-06-29T08:10:00Z'
      })
    });
    vi.stubGlobal('fetch', fetchMock);
    window.localStorage.setItem('matrixcode.actorToken', 'token-revoke-invitation');

    const invitation = await revokeProjectInvitation('demo', 'invitation-1', 'user-owner', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/projects/demo/identity/invitations/invitation-1/revoke',
      {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          Authorization: 'Bearer token-revoke-invitation',
          'X-MatrixCode-User-Id': 'user-owner'
        }
      }
    );
    expect(invitation.status).toBe('REVOKED');
  });

  it('重发项目邀请时提交新的过期时间并返回新令牌', async () => {
    const input = { expiresAt: '2026-07-02T00:00:00Z' };
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        token: 'new-token',
        invitation: {
          id: 'invitation-1',
          projectId: 'demo',
          inviteeUserId: 'user-dev',
          displayName: '开发同学',
          roleKey: 'DEVELOPER',
          status: 'PENDING',
          createdByUserId: 'user-owner',
          expiresAt: input.expiresAt,
          acceptedAt: null,
          createdAt: '2026-06-29T08:00:00Z',
          updatedAt: '2026-06-29T08:10:00Z'
        }
      })
    });
    vi.stubGlobal('fetch', fetchMock);
    window.localStorage.setItem('matrixcode.actorToken', 'token-reissue-invitation');

    const issued = await reissueProjectInvitation('demo', 'invitation-1', input, 'user-owner', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/projects/demo/identity/invitations/invitation-1/reissue',
      {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          Authorization: 'Bearer token-reissue-invitation',
          'Content-Type': 'application/json',
          'X-MatrixCode-User-Id': 'user-owner'
        },
        body: JSON.stringify(input)
      }
    );
    expect(issued.token).toBe('new-token');
  });

  it('清理项目过期邀请时返回已过期邀请列表', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => [
        {
          id: 'invitation-expired',
          projectId: 'demo',
          inviteeUserId: 'user-dev',
          displayName: '开发同学',
          roleKey: 'DEVELOPER',
          status: 'EXPIRED',
          createdByUserId: 'user-owner',
          expiresAt: '2026-01-01T00:00:00Z',
          acceptedAt: null,
          createdAt: '2026-06-29T08:00:00Z',
          updatedAt: '2026-06-29T08:10:00Z'
        }
      ]
    });
    vi.stubGlobal('fetch', fetchMock);
    window.localStorage.setItem('matrixcode.actorToken', 'token-expire-invitations');

    const invitations = await expireProjectInvitations('demo', 'user-owner', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/identity/invitations:expire', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        Authorization: 'Bearer token-expire-invitations',
        'X-MatrixCode-User-Id': 'user-owner'
      }
    });
    expect(invitations[0].status).toBe('EXPIRED');
  });

  it('批量治理项目成员时携带当前操作者身份', async () => {
    const input = {
      updates: [
        { userId: 'user-dev', roleKey: 'TESTER', status: 'ACTIVE' },
        { userId: 'user-tester', status: 'DISABLED' }
      ]
    };
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => [
        {
          id: 'member-dev',
          projectId: 'demo',
          userId: 'user-dev',
          roleKey: 'TESTER',
          status: 'ACTIVE',
          joinedAt: '2026-06-29T08:05:00Z',
          createdAt: '2026-06-29T08:05:00Z',
          updatedAt: '2026-06-29T08:10:00Z'
        }
      ]
    });
    vi.stubGlobal('fetch', fetchMock);
    window.localStorage.setItem('matrixcode.actorToken', 'token-batch-members');

    const members = await batchUpdateProjectMembers('demo', input, 'user-owner', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/identity/members:batch', {
      method: 'PATCH',
      headers: {
        Accept: 'application/json',
        Authorization: 'Bearer token-batch-members',
        'Content-Type': 'application/json',
        'X-MatrixCode-User-Id': 'user-owner'
      },
      body: JSON.stringify(input)
    });
    expect(members[0].roleKey).toBe('TESTER');
  });

  it('加载用户项目和用户级审计记录时携带当前操作者身份', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ['demo']
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => [
          {
            id: 'audit-1',
            projectId: 'demo',
            actorUserId: 'user-reviewer',
            actorRole: 'OWNER',
            actionKey: 'SHELL',
            targetType: 'LOCAL_EXECUTION_TASK',
            targetId: 'task-1',
            decision: 'ALLOW',
            summary: '允许执行 git status',
            occurredAt: '2026-06-25T14:10:00Z'
          }
        ]
      });
    vi.stubGlobal('fetch', fetchMock);
    window.localStorage.setItem('matrixcode.actorToken', 'token-audit');

    const projects = await loadUserProjects('demo', 'user-reviewer', 'user-owner', 'http://localhost:8080');
    const records = await loadUserAuditRecords('demo', 'user-reviewer', 'user-owner', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      'http://localhost:8080/api/projects/demo/identity/users/user-reviewer/projects',
      {
        headers: {
          Accept: 'application/json',
          Authorization: 'Bearer token-audit',
          'X-MatrixCode-User-Id': 'user-owner'
        }
      }
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      'http://localhost:8080/api/projects/demo/identity/users/user-reviewer/audit-records',
      {
        headers: {
          Accept: 'application/json',
          Authorization: 'Bearer token-audit',
          'X-MatrixCode-User-Id': 'user-owner'
        }
      }
    );
    expect(projects).toEqual(['demo']);
    expect(records[0].summary).toContain('git status');
  });

  it('加载运行诊断报告时提交当前操作者身份', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        status: 'FAIL',
        generatedAt: '2026-06-25T06:00:00Z',
        items: [
          {
            key: 'jdbc',
            label: 'MySQL',
            status: 'FAIL',
            detail: '127.0.0.1:3306 不可达',
            blocking: true
          }
        ],
        nextActions: ['检查 MySQL 服务和网络']
      })
    });
    vi.stubGlobal('fetch', fetchMock);
    window.localStorage.setItem('matrixcode.actorToken', 'diagnostics-token');

    const report = await loadRuntimeDiagnostics('demo', 'user-ops', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/runtime-diagnostics', {
      headers: {
        Accept: 'application/json',
        Authorization: 'Bearer diagnostics-token',
        'X-MatrixCode-User-Id': 'user-ops'
      }
    });
    expect(report.items[0].label).toBe('MySQL');
  });

  it('更新角色智能体配置时携带当前操作者身份', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ role: 'DEVELOPER', displayName: '开发智能体 Pro' })
    });
    vi.stubGlobal('fetch', fetchMock);
    window.localStorage.setItem('matrixcode.actorToken', 'token-2');
    const input = {
      displayName: '开发智能体 Pro',
      agentKind: 'coding',
      providerId: 'local-deterministic',
      model: 'matrixcode-local-developer-pro',
      toolContractVersion: 'tools-v2',
      cachePolicyId: 'deepseek-prefix-v2',
      volatileSuffixStrategy: 'stable-prefix-dynamic-tail',
      cacheScopeStrategy: 'provider-role',
      systemPrompt: '你是开发编码智能体，必须先读代码再修改。',
      userPromptTemplate: '请基于以下任务输出计划并执行：{{instruction}}',
      themeColor: '#0f766e',
      fontFamily: 'Inter',
      fontSize: 15,
      sortOrder: 2,
      enabled: true
    };

    await updateRoleAgentConfig('demo', 'developer', input, 'user-owner', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/projects/demo/role-agent-configs/developer',
      {
        method: 'PUT',
        headers: {
          Accept: 'application/json',
          Authorization: 'Bearer token-2',
          'Content-Type': 'application/json',
          'X-MatrixCode-User-Id': 'user-owner'
        },
        body: JSON.stringify(input)
      }
    );
  });

  it('创建角色模型请求时提交上下文块', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ answer: '产品需求草稿' })
    });
    vi.stubGlobal('fetch', fetchMock);
    const input = {
      actorUserId: 'user-product',
      instruction: '支付失败后允许用户重新发起支付。',
      contextBlocks: [{ type: 'PROJECT_RULE', summary: '保持中文输出', allowedByGate: true }]
    };

    await createRoleModelRequest('demo', 'product', input, 'user-product', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/roles/product/model-requests', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'X-MatrixCode-User-Id': 'user-product'
      },
      body: JSON.stringify(input)
    });
  });

  it('按 Agent 运行读取模型请求分页和成本趋势', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ projectId: 'demo', agentRunId: 'run-1', page: 0, size: 10, total: 2, requests: [] })
    });
    vi.stubGlobal('fetch', fetchMock);

    await loadAgentRunModelRequests('demo', 'run-1', { page: 0, size: 10 }, 'user-dev', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/projects/demo/model-gateway/agent-runs/run-1/model-requests?page=0&size=10',
      {
        headers: { Accept: 'application/json', 'X-MatrixCode-User-Id': 'user-dev' }
      }
    );
  });

  it('读取项目级模型成本趋势', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ projectId: 'demo', days: 45, metrics: { requestCount: 0 } })
    });
    vi.stubGlobal('fetch', fetchMock);

    await loadProjectModelCostTrends('demo', 45, 'user-dev', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/model-gateway/cost-trends?days=45', {
      headers: { Accept: 'application/json', 'X-MatrixCode-User-Id': 'user-dev' }
    });
  });

  it('按用户读取 Agent Runtime 责任审计报告', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        projectId: 'demo',
        userId: 'user-dev',
        totalRuns: 1,
        activeResponsibilities: 1,
        modelRequestCount: 1,
        entries: []
      })
    });
    vi.stubGlobal('fetch', fetchMock);

    await loadAgentRunUserAudit('demo', 'user-dev', 30, 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/projects/demo/agent-runs/user-audit?userId=user-dev&limit=30',
      {
        headers: { Accept: 'application/json', 'X-MatrixCode-User-Id': 'user-dev' }
      }
    );
  });

  it('读取 Agent Runtime 责任审计时透传本地身份令牌', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        projectId: 'demo',
        userId: 'user-dev',
        totalRuns: 0,
        activeResponsibilities: 0,
        modelRequestCount: 0,
        entries: []
      })
    });
    vi.stubGlobal('fetch', fetchMock);
    localStorage.setItem('matrixcode.actorToken', 'signed-token');

    await loadAgentRunUserAudit('demo', 'user-dev', 30, 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/projects/demo/agent-runs/user-audit?userId=user-dev&limit=30',
      {
        headers: {
          Accept: 'application/json',
          Authorization: 'Bearer signed-token',
          'X-MatrixCode-User-Id': 'user-dev'
        }
      }
    );
  });

  it('签发身份令牌时只在请求头传递启动凭证', async () => {
    const { issueActorToken } = await import('./client');
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        userId: 'user-dev',
        token: 'signed-token',
        expiresAt: '2026-06-27T06:00:00Z'
      })
    });
    vi.stubGlobal('fetch', fetchMock);

    const token = await issueActorToken(
      'demo',
      { userId: 'user-dev', ttlSeconds: 3600, bootstrapToken: 'test-signing-ticket' },
      'http://localhost:8080'
    );

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/identity/auth/actor-token', {
      method: 'POST',
      body: JSON.stringify({ userId: 'user-dev', ttlSeconds: 3600 }),
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'X-MatrixCode-Bootstrap-Token': 'test-signing-ticket'
      }
    });
    expect(token.token).toBe('signed-token');
  });

  it('登录 Sa-Token 会话时调用登录地址', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        userId: 'user-dev',
        token: 'sa-token',
        expiresAt: '2026-06-27T06:00:00Z'
      })
    });
    vi.stubGlobal('fetch', fetchMock);

    const token = await loginActorSession('demo', { username: 'user-dev', password: 'secret' }, 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/identity/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username: 'user-dev', password: 'secret' }),
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json'
      }
    });
    expect(token.token).toBe('sa-token');
  });

  it('退出 Sa-Token 会话时透传当前用户和本地令牌', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 204
    });
    vi.stubGlobal('fetch', fetchMock);
    localStorage.setItem('matrixcode.actorToken', 'sa-token');

    await logoutActorSession('demo', 'user-dev', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/identity/auth/logout', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        Authorization: 'Bearer sa-token',
        'X-MatrixCode-User-Id': 'user-dev'
      }
    });
  });

  it('读取 Sa-Token 当前会话时调用会话地址', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ authenticated: true, userId: 'user-dev' })
    });
    vi.stubGlobal('fetch', fetchMock);
    localStorage.setItem('matrixcode.actorToken', 'sa-token');

    const session = await loadActorSession('demo', 'user-dev', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/identity/auth/session', {
      headers: {
        Accept: 'application/json',
        Authorization: 'Bearer sa-token',
        'X-MatrixCode-User-Id': 'user-dev'
      }
    });
    expect(session.userId).toBe('user-dev');
  });

  it('续期 Sa-Token 当前会话时透传当前用户和本地令牌', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        tokenFingerprint: 'fp-user-dev',
        deviceType: 'WEB',
        deviceId: 'browser-1',
        createdAt: '2026-06-27T05:00:00Z',
        timeoutSeconds: 7200
      })
    });
    vi.stubGlobal('fetch', fetchMock);
    localStorage.setItem('matrixcode.actorToken', 'sa-token');

    const session = await renewActorSession('demo', 'user-dev', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/identity/auth/session/renew', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        Authorization: 'Bearer sa-token',
        'X-MatrixCode-User-Id': 'user-dev'
      }
    });
    expect(session.tokenFingerprint).toBe('fp-user-dev');
  });

  it('读取用户会话列表时提交治理操作者身份', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => [
        {
          tokenFingerprint: 'fp-user-dev',
          deviceType: 'WEB',
          deviceId: 'browser-1',
          createdAt: '2026-06-27T05:00:00Z',
          timeoutSeconds: 3600
        }
      ]
    });
    vi.stubGlobal('fetch', fetchMock);
    localStorage.setItem('matrixcode.actorToken', 'sa-token');

    const sessions = await loadActorSessions('demo', 'user-dev', 'user-owner', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/identity/auth/users/user-dev/sessions', {
      headers: {
        Accept: 'application/json',
        Authorization: 'Bearer sa-token',
        'X-MatrixCode-User-Id': 'user-owner'
      }
    });
    expect(sessions).toHaveLength(1);
  });

  it('踢下线用户会话时提交治理操作者身份', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 204
    });
    vi.stubGlobal('fetch', fetchMock);
    localStorage.setItem('matrixcode.actorToken', 'sa-token');

    await kickoutActorSessions('demo', 'user-dev', 'user-owner', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/projects/demo/identity/auth/users/user-dev/sessions/kickout',
      {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          Authorization: 'Bearer sa-token',
          'X-MatrixCode-User-Id': 'user-owner'
        }
      }
    );
  });

  it('授权本地工作区时携带身份头', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ id: 'workspace-1', name: '当前项目' })
    });
    vi.stubGlobal('fetch', fetchMock);

    await authorizeLocalWorkspace(
      'demo',
      { name: '当前项目', rootPath: '/repo/matrixcode' },
      'user-dev',
      'http://localhost:8080'
    );

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/local-execution/workspaces', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'X-MatrixCode-User-Id': 'user-dev'
      },
      body: JSON.stringify({ name: '当前项目', rootPath: '/repo/matrixcode' })
    });
  });

  it('读取本地执行摘要和本地文件时携带身份头', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ workspaces: [], recentFileOperations: [], recentTasks: [], recentAuditRecords: [] })
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => [{ name: 'README.md', relativePath: 'README.md', directory: false, sizeBytes: 6 }]
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ workspaceId: 'workspace-1', relativePath: 'README.md', content: '说明', sizeBytes: 6 })
      });
    vi.stubGlobal('fetch', fetchMock);

    await loadLocalExecutionSummary('demo', 'user-dev', 'http://localhost:8080');
    await listLocalFiles('demo', { workspaceId: 'workspace-1', relativePath: '.' }, 'user-dev', 'http://localhost:8080');
    await readLocalFile(
      'demo',
      { workspaceId: 'workspace-1', relativePath: 'README.md' },
      'user-dev',
      'http://localhost:8080'
    );

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/local-execution/summary', {
      headers: {
        Accept: 'application/json',
        'X-MatrixCode-User-Id': 'user-dev'
      }
    });
    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/local-execution/files/list', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'X-MatrixCode-User-Id': 'user-dev'
      },
      body: JSON.stringify({ workspaceId: 'workspace-1', relativePath: '.' })
    });
    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/local-execution/files/read', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'X-MatrixCode-User-Id': 'user-dev'
      },
      body: JSON.stringify({ workspaceId: 'workspace-1', relativePath: 'README.md' })
    });
  });

  it('写入本地文件时调用文件写入地址并携带身份头', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ workspaceId: 'workspace-1', relativePath: 'agent.md', bytesWritten: 12 })
    });
    vi.stubGlobal('fetch', fetchMock);
    const input = {
      workspaceId: 'workspace-1',
      relativePath: 'agent.md',
      content: '执行说明',
      actorId: 'user-dev'
    };

    await writeLocalFile('demo', input, 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/local-execution/files/write', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'X-MatrixCode-User-Id': 'user-dev'
      },
      body: JSON.stringify(input)
    });
  });

  it('写入本地文件时会透传 actor token', async () => {
    localStorage.setItem('matrixcode.actorToken', 'signed-file-token');
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ workspaceId: 'workspace-1', relativePath: 'agent.md', bytesWritten: 12 })
    });
    vi.stubGlobal('fetch', fetchMock);
    const input = {
      workspaceId: 'workspace-1',
      relativePath: 'agent.md',
      content: '执行说明',
      actorId: 'user-dev'
    };

    await writeLocalFile('demo', input, 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/local-execution/files/write', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        Authorization: 'Bearer signed-file-token',
        'Content-Type': 'application/json',
        'X-MatrixCode-User-Id': 'user-dev'
      },
      body: JSON.stringify(input)
    });
  });

  it('提交本地命令和 Git diff 请求', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ status: 'APPROVAL_PENDING' }) });
    vi.stubGlobal('fetch', fetchMock);

    await submitLocalCommand(
      'demo',
      { workspaceId: 'workspace-1', actorId: 'user-dev', command: 'ssh prod' },
      'http://localhost:8080'
    );
    await captureGitDiff('demo', { workspaceId: 'workspace-1' }, 'user-dev', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/local-execution/commands', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'X-MatrixCode-User-Id': 'user-dev'
      },
      body: JSON.stringify({ workspaceId: 'workspace-1', actorId: 'user-dev', command: 'ssh prod' })
    });
    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/local-execution/git-diff', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'X-MatrixCode-User-Id': 'user-dev'
      },
      body: JSON.stringify({ workspaceId: 'workspace-1' })
    });
  });

  it('审批本地命令时调用任务审批地址', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ taskId: 'task-1', status: 'DENIED', approvalDecision: 'DENY' })
    });
    vi.stubGlobal('fetch', fetchMock);
    const input = { actorId: 'user-reviewer', decision: 'DENY' as const, note: '本轮不执行' };

    await decideLocalCommandApproval('demo', 'task/1', input, 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/projects/demo/local-execution/commands/task%2F1/approval',
      {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          'X-MatrixCode-User-Id': 'user-reviewer'
        },
        body: JSON.stringify(input)
      }
    );
  });

  it('本地命令审批会透传 actor token', async () => {
    localStorage.setItem('matrixcode.actorToken', 'signed-review-token');
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ taskId: 'task-1', status: 'QUEUED', approvalDecision: 'ALLOW' })
    });
    vi.stubGlobal('fetch', fetchMock);
    const input = { actorId: 'user-reviewer', decision: 'ALLOW' as const, note: '批准执行' };

    await decideLocalCommandApproval('demo', 'task-1', input, 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/projects/demo/local-execution/commands/task-1/approval',
      {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          Authorization: 'Bearer signed-review-token',
          'Content-Type': 'application/json',
          'X-MatrixCode-User-Id': 'user-reviewer'
        },
        body: JSON.stringify(input)
      }
    );
  });

  it('取消本地长任务并查询任务日志', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ taskId: 'task-1', status: 'CANCELED' })
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => [{ taskId: 'task-1', stream: 'SYSTEM', content: '任务已取消' }]
      });
    vi.stubGlobal('fetch', fetchMock);
    const input = { actorId: 'user-reviewer', note: '停止验证' };

    await cancelLocalExecutionTask('demo', 'task/1', input, 'http://localhost:8080');
    await loadLocalExecutionTaskLogs('demo', 'task/1', 'user-reviewer', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/projects/demo/local-execution/commands/task%2F1/cancel',
      {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          'X-MatrixCode-User-Id': 'user-reviewer'
        },
        body: JSON.stringify(input)
      }
    );
    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/projects/demo/local-execution/commands/task%2F1/logs',
      {
        headers: {
          Accept: 'application/json',
          'X-MatrixCode-User-Id': 'user-reviewer'
        }
      }
    );
  });

  it('标记运行态提醒已读时调用提醒已读地址', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        id: 'approval:task-1',
        projectId: 'demo',
        level: 'ACTION',
        title: '需要审批本地命令',
        message: 'git status',
        sourceType: 'APPROVAL',
        sourceId: 'task-1',
        occurredAt: '2026-06-25T06:00:00Z',
        readAt: '2026-06-25T06:01:00Z'
      })
    });
    vi.stubGlobal('fetch', fetchMock);

    await markRuntimeNotificationRead('demo', 'approval:task-1', 'user-reviewer', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/projects/demo/runtime-notifications/approval%3Atask-1/read',
      {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          'X-MatrixCode-User-Id': 'user-reviewer'
        },
        body: JSON.stringify({ actorUserId: 'user-reviewer' })
      }
    );
  });

  it('批量标记运行态提醒已读时调用全部已读地址', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => [
        {
          id: 'approval:task-1',
          projectId: 'demo',
          level: 'ACTION',
          title: '需要审批本地命令',
          message: 'git status',
          sourceType: 'APPROVAL',
          sourceId: 'task-1',
          occurredAt: '2026-06-25T06:00:00Z',
          readAt: '2026-06-25T06:01:00Z'
        }
      ]
    });
    vi.stubGlobal('fetch', fetchMock);

    await markAllRuntimeNotificationsRead('demo', 'user-reviewer', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/runtime-notifications/read-all', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'X-MatrixCode-User-Id': 'user-reviewer'
      },
      body: JSON.stringify({ actorUserId: 'user-reviewer' })
    });
  });

  it('提交角色动作时使用 JSON 请求体', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => [] });
    vi.stubGlobal('fetch', fetchMock);

    await createProductDrafts(
      'demo',
      { requirement: '支付失败后允许用户重新发起支付。' },
      'user-product',
      'http://localhost:8080'
    );

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/roles/product/drafts', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'X-MatrixCode-User-Id': 'user-product'
      },
      body: JSON.stringify({ requirement: '支付失败后允许用户重新发起支付。' })
    });
  });

  it('冻结文档时不发送 JSON 请求体', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        id: 'doc-1',
        type: 'PRD',
        title: '支付失败重试',
        content: '允许用户重新发起支付。',
        version: 2,
        state: 'FROZEN',
        createdAt: '2026-06-24T09:40:00Z'
      })
    });
    vi.stubGlobal('fetch', fetchMock);

    await freezeDocument('demo 项目', 'doc/1', 'user-product', 'http://localhost:8080/');

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/projects/demo%20%E9%A1%B9%E7%9B%AE/documents/doc%2F1/freeze',
      {
        method: 'POST',
        headers: { Accept: 'application/json', 'X-MatrixCode-User-Id': 'user-product' }
      }
    );
  });

  it('提交开发交付时调用开发角色交付地址', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => [] });
    vi.stubGlobal('fetch', fetchMock);
    const input = {
      workspacePath: '/tmp/matrixcode',
      implementationNote: '已完成支付重试入口。',
      selfTestResult: '单元测试通过。',
      apiDoc: '接口文档已更新。',
      databaseScript: '无需数据库变更。',
      deploymentDoc: '按常规流程部署。'
    };

    await submitDeveloperDelivery('demo', input, 'user-dev', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/roles/developer/deliveries', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'X-MatrixCode-User-Id': 'user-dev'
      },
      body: JSON.stringify(input)
    });
  });

  it('创建缺陷时调用缺陷创建地址', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        id: 'bug-1',
        projectId: 'demo',
        title: '支付失败后没有重试按钮',
        severity: 'HIGH',
        status: 'NEW',
        steps: '触发支付失败。',
        expected: '显示重试按钮。',
        actual: '只显示失败提示。',
        createdByRole: '测试',
        currentOwnerRole: '开发',
        lastNote: '请优先处理。',
        updatedAt: '2026-06-24T10:00:00Z'
      })
    });
    vi.stubGlobal('fetch', fetchMock);
    const input = {
      title: '支付失败后没有重试按钮',
      severity: 'HIGH',
      steps: '触发支付失败。',
      expected: '显示重试按钮。',
      actual: '只显示失败提示。',
      createdByRole: '测试',
      currentOwnerRole: '开发'
    } satisfies Parameters<typeof createBug>[1];

    await createBug('demo', input, 'user-tester', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/bugs', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'X-MatrixCode-User-Id': 'user-tester'
      },
      body: JSON.stringify(input)
    });
  });

  it('流转缺陷时调用指定缺陷流转地址', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        id: 'bug/1',
        projectId: 'demo',
        title: '支付失败后没有重试按钮',
        severity: 'HIGH',
        status: 'REGRESSION_PENDING',
        steps: '触发支付失败。',
        expected: '显示重试按钮。',
        actual: '只显示失败提示。',
        createdByRole: '测试',
        currentOwnerRole: '测试',
        lastNote: '已修复，请复测。',
        updatedAt: '2026-06-24T10:30:00Z'
      })
    });
    vi.stubGlobal('fetch', fetchMock);
    const input = { nextStatus: 'REGRESSION_PENDING', note: '已修复，请复测。' } satisfies Parameters<
      typeof transitionBug
    >[2];

    await transitionBug('demo', 'bug/1', input, 'user-tester', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/bugs/bug%2F1/transitions', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'X-MatrixCode-User-Id': 'user-tester'
      },
      body: JSON.stringify(input)
    });
  });

  it('提交测试报告时调用测试角色报告地址', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        id: 'test-report-1',
        type: 'QA_REPORT',
        title: '支付失败重试测试报告',
        content: '测试通过。',
        version: 1,
        state: 'DRAFT',
        createdAt: '2026-06-24T11:10:00Z'
      })
    });
    vi.stubGlobal('fetch', fetchMock);

    await submitTestReport('demo', { report: '测试通过。' }, 'user-tester', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/roles/tester/reports', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'X-MatrixCode-User-Id': 'user-tester'
      },
      body: JSON.stringify({ report: '测试通过。' })
    });
  });

  it('配置部署目标时调用部署目标地址', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        id: 'target-1',
        projectId: 'demo',
        environmentName: '测试环境',
        environmentUrl: 'https://test.example.com',
        sshAddress: 'deploy@test.example.com',
        deployNote: '执行部署脚本。',
        healthCheckUrl: 'https://test.example.com/health',
        rollbackNote: '回滚上一版本。',
        status: 'RECORDED',
        remoteExecuted: false,
        updatedAt: '2026-06-24T11:00:00Z'
      })
    });
    vi.stubGlobal('fetch', fetchMock);
    const input = {
      environmentName: '测试环境',
      environmentUrl: 'https://test.example.com',
      sshAddress: 'deploy@test.example.com',
      deployNote: '执行部署脚本。',
      healthCheckUrl: 'https://test.example.com/health',
      rollbackNote: '回滚上一版本。'
    };

    await configureDeploymentTarget('demo', input, 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/deployments/targets', {
      method: 'POST',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify(input)
    });
  });

  it('配置部署目标时可透传当前操作者', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        id: 'target-1',
        projectId: 'demo',
        environmentName: '测试环境',
        environmentUrl: 'https://test.example.com',
        sshAddress: 'deploy@test.example.com',
        deployNote: '执行部署脚本。',
        healthCheckUrl: 'https://test.example.com/health',
        rollbackNote: '回滚上一版本。',
        status: 'RECORDED',
        remoteExecuted: false,
        updatedAt: '2026-06-24T11:00:00Z'
      })
    });
    vi.stubGlobal('fetch', fetchMock);
    const input = {
      environmentName: '测试环境',
      environmentUrl: 'https://test.example.com',
      sshAddress: 'deploy@test.example.com',
      deployNote: '执行部署脚本。',
      healthCheckUrl: 'https://test.example.com/health',
      rollbackNote: '回滚上一版本。'
    };

    await configureDeploymentTarget('demo', input, 'user-ops', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/deployments/targets', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'X-MatrixCode-User-Id': 'user-ops'
      },
      body: JSON.stringify(input)
    });
  });

  it('运行部署健康检查时编码部署目标并提交操作者', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        id: 'health-1',
        projectId: 'demo',
        targetId: 'target/1',
        actorId: 'user-ops',
        status: 'HEALTHY',
        httpStatus: 200,
        durationMillis: 18,
        summary: 'HTTP 200',
        checkedAt: '2026-06-24T12:00:00Z'
      })
    });
    vi.stubGlobal('fetch', fetchMock);

    await runDeploymentHealthCheck('demo', 'target/1', { actorId: 'user-ops' }, 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/projects/demo/deployments/targets/target%2F1/health-checks',
      {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          'X-MatrixCode-User-Id': 'user-ops'
        },
        body: JSON.stringify({ actorId: 'user-ops' })
      }
    );
  });

  it('记录部署操作时编码部署目标并提交 JSON 请求体', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        id: 'operation-1',
        projectId: 'demo',
        targetId: 'target/1',
        actorId: 'user-ops',
        type: 'DEPLOYMENT',
        status: 'SUCCEEDED',
        note: '预发部署完成。',
        createdAt: '2026-06-24T12:05:00Z'
      })
    });
    vi.stubGlobal('fetch', fetchMock);
    const input = {
      actorId: 'user-ops',
      type: 'DEPLOYMENT' as const,
      status: 'SUCCEEDED' as const,
      note: '预发部署完成。'
    };

    await recordDeploymentOperation('demo', 'target/1', input, 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/projects/demo/deployments/targets/target%2F1/operations',
      {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          'X-MatrixCode-User-Id': 'user-ops'
        },
        body: JSON.stringify(input)
      }
    );
  });

  it('导入发布脚本审计时调用发布审计导入地址', async () => {
    const input = {
      actorId: 'user-ops',
      sourceId: 'rollback-audit.jsonl',
      jsonLines: [
        '{"action":"rollback","status":"SUCCEEDED","occurredAt":"2026-06-29T09:00:00Z","targetDir":"/opt/matrixcode"}'
      ]
    };
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        importedCount: 1,
        skippedCount: 0,
        records: [
          {
            id: 'release-audit-1',
            projectId: 'demo',
            targetId: 'target/1',
            actorId: 'user-ops',
            type: 'ROLLBACK',
            status: 'SUCCEEDED',
            note: '发布脚本审计 rollback SUCCEEDED',
            createdAt: '2026-06-29T09:00:00Z'
          }
        ]
      })
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await importDeploymentReleaseAudit('demo', 'target/1', input, 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/projects/demo/deployments/targets/target%2F1/release-audit-imports',
      {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          'X-MatrixCode-User-Id': 'user-ops'
        },
        body: JSON.stringify(input)
      }
    );
    expect(result.importedCount).toBe(1);
  });

  it('配置 Compose 演示环境时编码部署目标并提交 JSON 请求体', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        id: 'compose-1',
        projectId: 'demo',
        targetId: 'target/1',
        workspaceId: 'workspace-1',
        composeFilePath: 'compose.yml',
        projectName: 'matrixcode-demo',
        serviceName: 'web',
        status: 'CONFIGURED',
        createdAt: '2026-06-25T06:20:00Z',
        updatedAt: '2026-06-25T06:20:00Z'
      })
    });
    vi.stubGlobal('fetch', fetchMock);
    const input = {
      workspaceId: 'workspace-1',
      composeFilePath: 'compose.yml',
      projectName: 'matrixcode-demo',
      serviceName: 'web'
    };

    await configureComposeEnvironment('demo', 'target/1', input, 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/projects/demo/deployments/targets/target%2F1/compose-environments',
      {
        method: 'POST',
        headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
        body: JSON.stringify(input)
      }
    );
  });

  it('配置 Compose 演示环境时可透传当前操作者', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        id: 'compose-1',
        projectId: 'demo',
        targetId: 'target/1',
        workspaceId: 'workspace-1',
        composeFilePath: 'compose.yml',
        projectName: 'matrixcode-demo',
        serviceName: 'web',
        status: 'CONFIGURED',
        createdAt: '2026-06-25T06:20:00Z',
        updatedAt: '2026-06-25T06:20:00Z'
      })
    });
    vi.stubGlobal('fetch', fetchMock);
    const input = {
      workspaceId: 'workspace-1',
      composeFilePath: 'compose.yml',
      projectName: 'matrixcode-demo',
      serviceName: 'web'
    };

    await configureComposeEnvironment('demo', 'target/1', input, 'user-ops', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/projects/demo/deployments/targets/target%2F1/compose-environments',
      {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          'X-MatrixCode-User-Id': 'user-ops'
        },
        body: JSON.stringify(input)
      }
    );
  });

  it('执行 Compose 运行态动作时编码环境编号并提交操作者身份', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        id: 'compose-operation-1',
        projectId: 'demo',
        environmentId: 'compose/1',
        actorId: 'user-ops',
        type: 'VALIDATE',
        status: 'SUCCEEDED',
        summary: 'Compose 配置有效',
        logExcerpt: 'services:\n  web:',
        createdAt: '2026-06-25T06:21:00Z'
      })
    });
    vi.stubGlobal('fetch', fetchMock);
    localStorage.setItem('matrixcode.actorToken', 'signed-compose-token');
    const input = { actorId: 'user-ops' };

    await validateComposeEnvironment('demo', 'compose/1', input, 'http://localhost:8080');
    await startComposeEnvironment('demo', 'compose/1', input, 'http://localhost:8080');
    await stopComposeEnvironment('demo', 'compose/1', input, 'http://localhost:8080');
    await captureComposeLogs('demo', 'compose/1', input, 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/projects/demo/compose-environments/compose%2F1/validate',
      {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          Authorization: 'Bearer signed-compose-token',
          'Content-Type': 'application/json',
          'X-MatrixCode-User-Id': 'user-ops'
        },
        body: JSON.stringify(input)
      }
    );
    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/compose-environments/compose%2F1/start', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        Authorization: 'Bearer signed-compose-token',
        'Content-Type': 'application/json',
        'X-MatrixCode-User-Id': 'user-ops'
      },
      body: JSON.stringify(input)
    });
    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/compose-environments/compose%2F1/stop', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        Authorization: 'Bearer signed-compose-token',
        'Content-Type': 'application/json',
        'X-MatrixCode-User-Id': 'user-ops'
      },
      body: JSON.stringify(input)
    });
    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/compose-environments/compose%2F1/logs', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        Authorization: 'Bearer signed-compose-token',
        'Content-Type': 'application/json',
        'X-MatrixCode-User-Id': 'user-ops'
      },
      body: JSON.stringify(input)
    });
  });

  it('提交验收结果时调用产品验收地址', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        id: 'acceptance-1',
        type: 'ACCEPTANCE_RECORD',
        title: '支付失败重试验收',
        content: '验收通过。',
        version: 1,
        state: 'DRAFT',
        createdAt: '2026-06-24T11:30:00Z'
      })
    });
    vi.stubGlobal('fetch', fetchMock);
    const input = { accepted: true, note: '验收通过。', returnToRole: '开发' as const };

    await submitAcceptance('demo', input, 'user-product', 'http://localhost:8080');

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/roles/product/acceptance', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'X-MatrixCode-User-Id': 'user-product'
      },
      body: JSON.stringify(input)
    });
  });

  it('订阅项目运行态事件并在关闭时清理监听器', () => {
    FakeEventSource.instances = [];
    vi.stubGlobal('EventSource', FakeEventSource);
    window.localStorage.setItem('matrixcode.actorToken', 'signed-event-token');
    const onEvent = vi.fn();

    const subscription = subscribeProjectEvents('demo/项目', { onEvent }, 'user-dev', 'http://localhost:8080/');
    const source = FakeEventSource.instances[0];

    expect(source.url).toBe(
      'http://localhost:8080/api/projects/demo%2F%E9%A1%B9%E7%9B%AE/events/stream?actorUserId=user-dev&actorToken=signed-event-token'
    );
    source.emit('LOCAL_COMMAND_COMPLETED', {
      id: 'event-1',
      projectId: 'demo/项目',
      type: 'LOCAL_COMMAND_COMPLETED',
      message: '任务完成',
      occurredAt: '2026-06-25T00:00:00Z'
    });
    source.emit('RUNTIME_NOTIFICATION_READ', {
      id: 'event-2',
      projectId: 'demo/项目',
      type: 'RUNTIME_NOTIFICATION_READ',
      message: '运行态提醒已读',
      occurredAt: '2026-06-25T00:00:01Z'
    });
    source.emit('RUNTIME_NOTIFICATIONS_READ', {
      id: 'event-3',
      projectId: 'demo/项目',
      type: 'RUNTIME_NOTIFICATIONS_READ',
      message: '运行态提醒已全部已读',
      occurredAt: '2026-06-25T00:00:02Z'
    });
    source.emit('UNRELATED_EVENT', { id: 'event-4' });

    expect(onEvent).toHaveBeenCalledTimes(3);
    expect(onEvent).toHaveBeenNthCalledWith(1, {
      id: 'event-1',
      projectId: 'demo/项目',
      type: 'LOCAL_COMMAND_COMPLETED',
      message: '任务完成',
      occurredAt: '2026-06-25T00:00:00Z'
    });
    expect(onEvent).toHaveBeenNthCalledWith(2, {
      id: 'event-2',
      projectId: 'demo/项目',
      type: 'RUNTIME_NOTIFICATION_READ',
      message: '运行态提醒已读',
      occurredAt: '2026-06-25T00:00:01Z'
    });
    expect(onEvent).toHaveBeenNthCalledWith(3, {
      id: 'event-3',
      projectId: 'demo/项目',
      type: 'RUNTIME_NOTIFICATIONS_READ',
      message: '运行态提醒已全部已读',
      occurredAt: '2026-06-25T00:00:02Z'
    });
    subscription.close();
    expect(source.closed).toBe(true);
    expect(source.listeners.get('LOCAL_COMMAND_COMPLETED')?.size).toBe(0);
    expect(source.listeners.get('RUNTIME_NOTIFICATION_READ')?.size).toBe(0);
    expect(source.listeners.get('RUNTIME_NOTIFICATIONS_READ')?.size).toBe(0);
  });

  it('EventSource不可用时返回空订阅并通知调用方', () => {
    vi.stubGlobal('EventSource', undefined);
    const onUnsupported = vi.fn();

    const subscription = subscribeProjectEvents('demo', { onEvent: vi.fn(), onUnsupported }, 'http://localhost:8080');

    subscription.close();
    expect(onUnsupported).toHaveBeenCalledOnce();
  });
});
