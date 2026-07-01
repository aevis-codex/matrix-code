import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type {
  AgentRunEventRecord,
  AgentRunRecord,
  AgentRunUserAuditReport,
  CodingAgentExecutionPlan,
  CodingAgentPatchResult,
  DocumentSummary,
  ExecutionTask,
  IssuedProjectInvitation,
  ProjectMember,
  ProjectInvitation,
  ProjectEvent,
  ProjectWorkbench,
  RoleAgentConfig,
  RuntimeDiagnosticsReport,
  UserAuditRecord
} from '../api/client';
import App from '../App';
import {
  addProjectMember,
  applyCodingAgentPatch,
  batchUpdateProjectMembers,
  cancelLocalExecutionTask,
  captureComposeLogs,
  changeActorPassword,
  claimAgentRun,
  claimNextAgentRun,
  configureComposeEnvironment,
  configureDeploymentTarget,
  createBug,
  createProductDrafts,
  createRoleModelRequestStream,
  createProjectInvitation,
  createProjectUser,
  decideLocalCommandApproval,
  expireProjectInvitations,
  freezeDocument,
  issueActorToken,
  loadActorSession,
  loadActorSessions,
  loadAgentRunEvents,
  loadAgentRunModelRequests,
  loadProjectModelCostTrends,
  loadAgentRunUserAudit,
  loadAgentRuns,
  loadProjectWorkbench,
  loadProjectInvitations,
  loadProjectMembers,
  loadRoleAgentConfigs,
  loadRuntimeDiagnostics,
  loadUserAuditRecords,
  loginActorSession,
  logoutActorSession,
  kickoutActorSessions,
  markAllRuntimeNotificationsRead,
  markRuntimeNotificationRead,
  prepareCodingAgentExecution,
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
  submitAcceptance,
  submitDeveloperDelivery,
  submitTestReport,
  transitionBug,
  updateProjectMember,
  updateRoleAgentConfig,
  validateComposeEnvironment
} from '../api/client';

vi.mock('../api/client', () => ({
  loadProjectWorkbench: vi.fn(),
  loadAgentRunEvents: vi.fn(),
  loadAgentRunModelRequests: vi.fn(),
  loadProjectModelCostTrends: vi.fn(),
  loadAgentRunUserAudit: vi.fn(),
  loadAgentRuns: vi.fn(),
  applyCodingAgentPatch: vi.fn(),
  addProjectMember: vi.fn(),
  createProjectUser: vi.fn(),
  updateProjectMember: vi.fn(),
  batchUpdateProjectMembers: vi.fn(),
  changeActorPassword: vi.fn(),
  loadProjectMembers: vi.fn(),
  loadProjectInvitations: vi.fn(),
  revokeProjectInvitation: vi.fn(),
  reissueProjectInvitation: vi.fn(),
  expireProjectInvitations: vi.fn(),
  loadUserAuditRecords: vi.fn(),
  loadRoleAgentConfigs: vi.fn(),
  loadRuntimeDiagnostics: vi.fn(),
  updateRoleAgentConfig: vi.fn(),
  createProductDrafts: vi.fn(),
  createRoleModelRequestStream: vi.fn(),
  createProjectInvitation: vi.fn(),
  freezeDocument: vi.fn(),
  issueActorToken: vi.fn(),
  loginActorSession: vi.fn(),
  logoutActorSession: vi.fn(),
  loadActorSession: vi.fn(),
  renewActorSession: vi.fn(),
  loadActorSessions: vi.fn(),
  kickoutActorSessions: vi.fn(),
  submitDeveloperDelivery: vi.fn(),
  createBug: vi.fn(),
  transitionBug: vi.fn(),
  submitTestReport: vi.fn(),
  configureDeploymentTarget: vi.fn(),
  runDeploymentHealthCheck: vi.fn(),
  recordDeploymentOperation: vi.fn(),
  configureComposeEnvironment: vi.fn(),
  validateComposeEnvironment: vi.fn(),
  startComposeEnvironment: vi.fn(),
  stopComposeEnvironment: vi.fn(),
  captureComposeLogs: vi.fn(),
  subscribeProjectEvents: vi.fn(),
  markAllRuntimeNotificationsRead: vi.fn(),
  markRuntimeNotificationRead: vi.fn(),
  prepareCodingAgentExecution: vi.fn(),
  recordCodingAgentHandoff: vi.fn(),
  retryAgentRun: vi.fn(),
  claimAgentRun: vi.fn(),
  claimNextAgentRun: vi.fn(),
  submitAcceptance: vi.fn(),
  decideLocalCommandApproval: vi.fn(),
  cancelLocalExecutionTask: vi.fn()
}));

const 基础工作台: ProjectWorkbench = {
  projectId: 'demo',
  projectName: '支付系统重构',
  currentStage: '测试执行',
  roles: [
    { name: '产品', state: '需求确认', todoCount: 1, latestAction: '等待 PRD 冻结' },
    { name: '开发', state: '编码执行', todoCount: 2, latestAction: '等待交付说明' },
    { name: '测试', state: '用例回归', todoCount: 3, latestAction: '等待缺陷记录' },
    { name: '运维', state: '环境待命', todoCount: 4, latestAction: '等待部署目标' }
  ],
  documents: [],
  bugs: [],
  deploymentTargets: [],
  deploymentRuntimeSummaries: [],
  composeEnvironments: [],
  composeRuntimeViews: [],
  metrics: {
    cacheHitRate: 0.86,
    sessionTokens: 221000,
    eventCount: 2,
    documentCount: 0,
    openBugCount: 0
  },
  modelGateway: {
    bindings: [
      {
        projectId: 'demo',
        role: 'PRODUCT',
        providerId: 'local-deterministic',
        model: 'matrixcode-local-product',
        currency: 'CNY',
        cacheHitPerMillion: 0,
        cacheMissInputPerMillion: 0,
        outputPerMillion: 0,
        contextBudgetTokens: 32000,
        toolContractVersion: 'tools-v1'
      }
    ],
    metrics: {
      requestCount: 2,
      cacheHitTokens: 1200,
      cacheMissInputTokens: 800,
      outputTokens: 600,
      cacheHitRate: 0.6,
      estimatedCost: 0.128,
      currency: 'CNY',
      recentContextTypes: ['PROJECT_RULE', 'FROZEN_PRD']
    },
    recentRequests: [
      {
        requestId: 'request-2',
        projectId: 'demo',
        role: 'DEVELOPER',
        providerId: 'deepseek',
        model: 'deepseek-chat',
        answerSummary: '交接文档草稿',
        agentRunId: 'run-2',
        usage: {
          roleSessionId: 'demo:DEVELOPER',
          model: 'deepseek-chat',
          cacheHitTokens: 0,
          cacheMissInputTokens: 1800,
          outputTokens: 500,
          cacheHitRate: 0,
          estimatedCost: 0.07,
          currency: 'CNY',
          cacheSource: 'ESTIMATED',
          cacheScopeId: 'matrixcode_demo_DEVELOPER_deepseek_deepseek-chat',
          stablePrefixHash: 'fp-cache-002',
          providerUsageAvailable: false,
          cachePolicyId: 'stable-platform-prefix-v1',
          volatileSuffixStrategy: 'role-prompt-and-dynamic-context',
          promptPartitionPolicyId: 'deepseek-reasonix-partitions-v1',
          promptPartitionFingerprint: 'partition-fp-002',
          stablePartitionCount: 2,
          volatilePartitionCount: 3
        },
        contextTypes: ['FROZEN_PRD'],
        createdAt: '2026-06-25T07:02:30Z'
      },
      {
        requestId: 'request-1',
        projectId: 'demo',
        role: 'PRODUCT',
        providerId: 'local-deterministic',
        model: 'matrixcode-local-product',
        answerSummary: '产品需求草稿',
        agentRunId: 'run-1',
        usage: {
          roleSessionId: 'demo:PRODUCT',
          model: 'matrixcode-local-product',
          cacheHitTokens: 1200,
          cacheMissInputTokens: 800,
          outputTokens: 600,
          cacheHitRate: 0.6,
          estimatedCost: 0.128,
          currency: 'CNY',
          cacheSource: 'PROVIDER',
          cacheScopeId: 'matrixcode_demo_PRODUCT_qwen_qwen-plus',
          stablePrefixHash: 'fp-cache-001',
          providerUsageAvailable: true,
          cachePolicyId: 'stable-platform-prefix-v1',
          volatileSuffixStrategy: 'role-prompt-and-dynamic-context',
          promptPartitionPolicyId: 'deepseek-reasonix-partitions-v1',
          promptPartitionFingerprint: 'partition-fp-001',
          stablePartitionCount: 2,
          volatilePartitionCount: 3
        },
        contextTypes: ['PROJECT_RULE', 'FROZEN_PRD'],
        createdAt: '2026-06-24T09:45:00Z'
      }
    ]
  },
  localExecution: {
    workspaces: [
      {
        id: 'workspace-1',
        projectId: 'demo',
        name: '当前项目',
        rootPath: '/repo/matrixcode',
        status: 'AUTHORIZED',
        createdAt: '2026-06-24T09:40:00Z',
        lastAccessedAt: '2026-06-24T09:50:00Z'
      }
    ],
    recentFileOperations: [
      {
        id: 'file-op-1',
        projectId: 'demo',
        workspaceId: 'workspace-1',
        type: 'READ',
        relativePath: 'README.md',
        status: 'SUCCESS',
        summary: '读取文件 README.md',
        createdAt: '2026-06-24T09:55:00Z'
      }
    ],
    recentTasks: [
      {
        taskId: 'task-1',
        projectId: 'demo',
        workspaceId: 'workspace-1',
        actorId: 'user-ops',
        toolType: 'SHELL',
        command: 'ssh prod systemctl restart app',
        approvalDecision: 'ASK',
        approverId: '',
        approvalNote: '',
        decidedAt: null,
        canceledBy: '',
        cancelNote: '',
        canceledAt: null,
        safetyRejectionReason: '',
        status: 'APPROVAL_PENDING',
        exitCode: null,
        stdoutSummary: '',
        stderrSummary: '',
        durationMillis: 0,
        createdAt: '2026-06-24T10:00:00Z'
      }
    ],
    recentGitDiff: {
      projectId: 'demo',
      workspaceId: 'workspace-1',
      repository: true,
      changedFiles: ['server/src/main/java/App.java', 'desktop/src/App.tsx'],
      stat: '2 files changed',
      capturedAt: '2026-06-24T10:05:00Z'
    },
    recentAuditRecords: [
      {
        id: 'audit-1',
        taskId: 'task-1',
        actorId: 'user-ops',
        toolType: 'SHELL',
        workspacePath: '/repo/matrixcode',
        summary: 'ssh prod systemctl restart app',
        decision: 'ASK',
        occurredAt: '2026-06-24T10:00:00Z'
      }
    ],
    activeTasks: [],
    recentTaskLogs: []
  },
  events: [
    {
      id: 'event-1',
      projectId: 'demo',
      type: 'PRODUCT',
      message: '产品补充了支付失败后的重试口径。',
      occurredAt: '2026-06-24T09:00:00Z'
    },
    {
      id: 'event-2',
      projectId: 'demo',
      type: 'DEVELOPMENT',
      message: '开发同步了本地实现计划。',
      occurredAt: '2026-06-24T09:30:00Z'
    }
  ]
};

const 运行中长任务: ExecutionTask = {
  ...基础工作台.localExecution.recentTasks[0],
  taskId: 'task-long',
  actorId: 'user-dev',
  command: 'sleep 5',
  approvalDecision: 'ALLOW',
  approverId: 'user-reviewer',
  approvalNote: '用户在运行中心批准执行',
  decidedAt: '2026-06-24T10:08:00Z',
  status: 'RUNNING',
  createdAt: '2026-06-24T10:09:00Z'
};

const 长任务工作台: ProjectWorkbench = {
  ...基础工作台,
  localExecution: {
    ...基础工作台.localExecution,
    recentTasks: [运行中长任务],
    activeTasks: [运行中长任务],
    recentTaskLogs: [
      {
        id: 'task-log-1',
        projectId: 'demo',
        taskId: 'task-long',
        stream: 'SYSTEM',
        content: '任务开始运行',
        createdAt: '2026-06-24T10:09:01Z'
      },
      {
        id: 'task-log-2',
        projectId: 'demo',
        taskId: 'task-long',
        stream: 'STDOUT',
        content: '等待命令结束',
        createdAt: '2026-06-24T10:09:02Z'
      }
    ]
  }
};

const 完成长任务工作台: ProjectWorkbench = {
  ...长任务工作台,
  localExecution: {
    ...长任务工作台.localExecution,
    activeTasks: [],
    recentTasks: [
      {
        ...运行中长任务,
        status: 'SUCCESS',
        exitCode: 0,
        durationMillis: 2100
      }
    ],
    recentTaskLogs: [
      {
        id: 'task-log-3',
        projectId: 'demo',
        taskId: 'task-long',
        stream: 'SYSTEM',
        content: '任务运行完成，退出码：0',
        createdAt: '2026-06-24T10:09:04Z'
      }
    ]
  }
};

const 草稿工作台: ProjectWorkbench = {
  ...基础工作台,
  currentStage: '需求冻结',
  documents: [
    {
      id: 'prd-1',
      type: 'PRD',
      title: '产品需求草稿',
      content: '支付失败后允许用户重新发起支付。',
      version: 1,
      state: 'DRAFT',
      createdAt: '2026-06-24T09:40:00Z'
    }
  ],
  metrics: { ...基础工作台.metrics, documentCount: 1 }
};

const 多轮草稿工作台: ProjectWorkbench = {
  ...基础工作台,
  currentStage: '需求草稿',
  documents: [
    {
      id: 'prd-old',
      type: 'PRD',
      title: '产品需求草稿',
      content: '第一轮需求。',
      version: 1,
      state: 'DRAFT',
      createdAt: '2026-06-24T09:30:00Z'
    },
    {
      id: 'prd-new',
      type: 'PRD',
      title: '产品需求草稿',
      content: '第二轮需求。',
      version: 1,
      state: 'DRAFT',
      createdAt: '2026-06-24T09:45:00Z'
    }
  ],
  metrics: { ...基础工作台.metrics, documentCount: 2 }
};

const 冻结后工作台: ProjectWorkbench = {
  ...草稿工作台,
  documents: [
    {
      ...草稿工作台.documents[0],
      state: 'FROZEN'
    }
  ]
};

const 开发交付后工作台: ProjectWorkbench = {
  ...冻结后工作台,
  currentStage: '测试执行',
  documents: [
    ...冻结后工作台.documents,
    {
      id: 'implementation-1',
      type: 'IMPLEMENTATION_NOTE',
      title: '开发交付说明',
      content: '已完成支付失败重试入口。',
      version: 1,
      state: 'DRAFT',
      createdAt: '2026-06-24T10:00:00Z'
    }
  ],
  metrics: { ...冻结后工作台.metrics, documentCount: 2 }
};

const 测试通过后工作台: ProjectWorkbench = {
  ...开发交付后工作台,
  currentStage: '上线准备',
  bugs: [
    {
      id: 'bug-1',
      projectId: 'demo',
      title: '支付失败后没有重试按钮',
      severity: 'HIGH',
      status: 'CLOSED',
      steps: '触发支付失败。',
      expected: '显示重试按钮。',
      actual: '只显示失败提示。',
      createdByRole: '测试',
      currentOwnerRole: '开发',
      lastNote: '复测通过。',
      updatedAt: '2026-06-24T10:30:00Z'
    }
  ],
  deploymentTargets: [],
  metrics: { ...开发交付后工作台.metrics, openBugCount: 0 }
};

const 部署运行工作台: ProjectWorkbench = {
  ...测试通过后工作台,
  currentStage: '上线准备',
  deploymentTargets: [
    {
      id: 'target/1',
      projectId: 'demo',
      environmentName: '预发环境',
      environmentUrl: 'https://pre.example.com',
      sshAddress: 'deploy@pre.example.com',
      deployNote: '按发布单执行部署。',
      healthCheckUrl: 'https://pre.example.com/health',
      rollbackNote: '回滚上一稳定版本。',
      status: 'DEPLOYED',
      remoteExecuted: false,
      updatedAt: '2026-06-24T12:08:00Z'
    }
  ],
  deploymentRuntimeSummaries: [
    {
      targetId: 'target/1',
      latestHealthCheck: {
        id: 'health-1',
        projectId: 'demo',
        targetId: 'target/1',
        actorId: 'user-ops',
        status: 'HEALTHY',
        httpStatus: 200,
        durationMillis: 18,
        summary: 'HTTP 200',
        checkedAt: '2026-06-24T12:00:00Z'
      },
      latestDeploymentOperation: {
        id: 'operation-deploy-1',
        projectId: 'demo',
        targetId: 'target/1',
        actorId: 'user-ops',
        type: 'DEPLOYMENT',
        status: 'SUCCEEDED',
        note: '预发部署完成。',
        createdAt: '2026-06-24T12:05:00Z'
      },
      latestRollbackOperation: {
        id: 'operation-rollback-1',
        projectId: 'demo',
        targetId: 'target/1',
        actorId: 'user-ops',
        type: 'ROLLBACK',
        status: 'RECORDED',
        note: '记录回滚方案。',
        createdAt: '2026-06-24T12:06:00Z'
      }
    }
  ]
};

const Compose运行工作台: ProjectWorkbench = {
  ...部署运行工作台,
  composeEnvironments: [
    {
      id: 'compose/1',
      projectId: 'demo',
      targetId: 'target/1',
      workspaceId: 'workspace-1',
      composeFilePath: 'compose.yml',
      projectName: 'matrixcode-demo',
      serviceName: 'web',
      status: 'RUNNING',
      createdAt: '2026-06-25T06:20:00Z',
      updatedAt: '2026-06-25T06:25:00Z'
    }
  ],
  composeRuntimeViews: [
    {
      environmentId: 'compose/1',
      targetId: 'target/1',
      status: 'RUNNING',
      composeFilePath: 'compose.yml',
      projectName: 'matrixcode-demo',
      serviceName: 'web',
      latestOperation: {
        id: 'compose-operation-logs',
        projectId: 'demo',
        environmentId: 'compose/1',
        actorId: 'user-ops',
        type: 'LOGS',
        status: 'SUCCEEDED',
        summary: 'Compose 日志已采集',
        logExcerpt: 'web ready',
        createdAt: '2026-06-25T06:25:00Z'
      }
    }
  ]
};

const Compose已配置工作台: ProjectWorkbench = {
  ...部署运行工作台,
  composeEnvironments: [
    {
      id: 'compose/1',
      projectId: 'demo',
      targetId: 'target/1',
      workspaceId: 'workspace-1',
      composeFilePath: 'compose.yml',
      projectName: 'matrixcode-demo',
      serviceName: 'web',
      status: 'CONFIGURED',
      createdAt: '2026-06-25T06:20:00Z',
      updatedAt: '2026-06-25T06:20:00Z'
    }
  ],
  composeRuntimeViews: [
    {
      environmentId: 'compose/1',
      targetId: 'target/1',
      status: 'CONFIGURED',
      composeFilePath: 'compose.yml',
      projectName: 'matrixcode-demo',
      serviceName: 'web',
      latestOperation: null
    }
  ]
};

const Compose失败工作台: ProjectWorkbench = {
  ...Compose运行工作台,
  composeEnvironments: [
    {
      ...Compose运行工作台.composeEnvironments[0],
      status: 'FAILED',
      updatedAt: '2026-06-25T06:26:00Z'
    }
  ],
  composeRuntimeViews: [
    {
      ...Compose运行工作台.composeRuntimeViews[0],
      status: 'FAILED',
      latestOperation: {
        ...Compose运行工作台.composeRuntimeViews[0].latestOperation!,
        type: 'START',
        status: 'FAILED',
        summary: 'Docker Compose 命令超时',
        logExcerpt: 'Image nginx:alpine Pulling',
        createdAt: '2026-06-25T06:26:00Z'
      }
    }
  ]
};

const 默认角色智能体配置: RoleAgentConfig[] = [
  {
    projectId: 'demo',
    role: 'PRODUCT',
    displayName: '产品智能体',
    agentKind: 'product',
    providerId: 'local-deterministic',
    model: 'matrixcode-local-product',
    toolContractVersion: 'tools-v1',
    cachePolicyId: 'stable-platform-prefix-v1',
    volatileSuffixStrategy: 'role-prompt-and-dynamic-context',
    cacheScopeStrategy: 'provider-model',
    systemPrompt: '你是 MatrixCode 的产品智能体。',
    userPromptTemplate: '请基于当前项目上下文处理产品任务：{{instruction}}',
    themeColor: '#7c3aed',
    fontFamily: 'Inter',
    fontSize: 14,
    sortOrder: 1,
    enabled: true,
    updatedAt: '2026-06-25T00:00:00Z'
  },
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
    systemPrompt: '你是 MatrixCode 的开发编码智能体，负责代码修改和测试验证。',
    userPromptTemplate: '请基于以下开发任务输出计划并执行：{{instruction}}',
    themeColor: '#2563eb',
    fontFamily: 'Inter',
    fontSize: 14,
    sortOrder: 2,
    enabled: true,
    updatedAt: '2026-06-25T00:00:00Z'
  },
  {
    projectId: 'demo',
    role: 'TESTER',
    displayName: '测试智能体',
    agentKind: 'testing',
    providerId: 'local-deterministic',
    model: 'matrixcode-local-tester',
    toolContractVersion: 'tools-v1',
    cachePolicyId: 'stable-platform-prefix-v1',
    volatileSuffixStrategy: 'role-prompt-and-dynamic-context',
    cacheScopeStrategy: 'provider-model',
    systemPrompt: '你是 MatrixCode 的测试智能体。',
    userPromptTemplate: '请基于以下测试任务执行验证：{{instruction}}',
    themeColor: '#dc2626',
    fontFamily: 'Inter',
    fontSize: 14,
    sortOrder: 3,
    enabled: true,
    updatedAt: '2026-06-25T00:00:00Z'
  },
  {
    projectId: 'demo',
    role: 'OPERATIONS',
    displayName: '运维智能体',
    agentKind: 'operations',
    providerId: 'local-deterministic',
    model: 'matrixcode-local-operations',
    toolContractVersion: 'tools-v1',
    cachePolicyId: 'stable-platform-prefix-v1',
    volatileSuffixStrategy: 'role-prompt-and-dynamic-context',
    cacheScopeStrategy: 'provider-model',
    systemPrompt: '你是 MatrixCode 的运维智能体。',
    userPromptTemplate: '请基于以下运维任务执行检查：{{instruction}}',
    themeColor: '#16a34a',
    fontFamily: 'Inter',
    fontSize: 14,
    sortOrder: 4,
    enabled: true,
    updatedAt: '2026-06-25T00:00:00Z'
  }
];

const 默认项目成员: ProjectMember[] = [
  {
    id: 'member-owner',
    projectId: 'demo',
    userId: 'user-owner',
    roleKey: 'OWNER',
    status: 'ACTIVE',
    joinedAt: '2026-06-25T13:00:00Z',
    createdAt: '2026-06-25T13:00:00Z',
    updatedAt: '2026-06-25T13:00:00Z'
  },
  {
    id: 'member-dev',
    projectId: 'demo',
    userId: 'user-dev',
    roleKey: 'DEVELOPER',
    status: 'ACTIVE',
    joinedAt: '2026-06-25T13:05:00Z',
    createdAt: '2026-06-25T13:05:00Z',
    updatedAt: '2026-06-25T13:05:00Z'
  }
];

const 默认项目邀请: ProjectInvitation[] = [];

const 默认签发项目邀请: IssuedProjectInvitation = {
  token: 'invite-token-user-tester',
  invitation: {
    id: 'invitation-tester',
    projectId: 'demo',
    inviteeUserId: 'user-tester',
    displayName: '测试同学',
    roleKey: 'TESTER',
    status: 'PENDING',
    createdByUserId: 'user-product',
    expiresAt: '2026-07-02T13:30:00Z',
    acceptedAt: null,
    createdAt: '2026-06-25T13:30:00Z',
    updatedAt: '2026-06-25T13:30:00Z'
  }
};

const 用户级审计记录: UserAuditRecord[] = [
  {
    id: 'audit-dev-1',
    projectId: 'demo',
    actorUserId: 'user-dev',
    actorRole: 'DEVELOPER',
    actionKey: 'SHELL',
    targetType: 'LOCAL_EXECUTION_TASK',
    targetId: 'task-1',
    decision: 'ALLOW',
    summary: '允许执行 npm test',
    occurredAt: '2026-06-25T13:20:00Z'
  }
];

const 加载项目工作台 = vi.mocked(loadProjectWorkbench);
const 加载Agent运行事件 = vi.mocked(loadAgentRunEvents);
const 加载Agent运行模型请求 = vi.mocked(loadAgentRunModelRequests);
const 加载项目模型成本趋势 = vi.mocked(loadProjectModelCostTrends);
const 加载Agent运行用户审计 = vi.mocked(loadAgentRunUserAudit);
const 加载Agent运行记录 = vi.mocked(loadAgentRuns);
const 应用编码智能体Patch = vi.mocked(applyCodingAgentPatch);
const 添加项目成员 = vi.mocked(addProjectMember);
const 创建项目用户 = vi.mocked(createProjectUser);
const 更新项目成员 = vi.mocked(updateProjectMember);
const 批量更新项目成员 = vi.mocked(batchUpdateProjectMembers);
const 修改身份密码 = vi.mocked(changeActorPassword);
const 加载项目成员 = vi.mocked(loadProjectMembers);
const 加载项目邀请 = vi.mocked(loadProjectInvitations);
const 撤销项目邀请 = vi.mocked(revokeProjectInvitation);
const 重发项目邀请 = vi.mocked(reissueProjectInvitation);
const 清理过期项目邀请 = vi.mocked(expireProjectInvitations);
const 加载用户级审计 = vi.mocked(loadUserAuditRecords);
const 加载角色智能体配置 = vi.mocked(loadRoleAgentConfigs);
const 加载运行诊断 = vi.mocked(loadRuntimeDiagnostics);
const 更新角色智能体配置 = vi.mocked(updateRoleAgentConfig);
const 生成需求草稿 = vi.mocked(createProductDrafts);
const 创建角色模型流式请求 = vi.mocked(createRoleModelRequestStream);
const 创建项目邀请 = vi.mocked(createProjectInvitation);
const 冻结文档 = vi.mocked(freezeDocument);
const 签发身份令牌 = vi.mocked(issueActorToken);
const 登录身份 = vi.mocked(loginActorSession);
const 退出身份 = vi.mocked(logoutActorSession);
const 加载身份会话 = vi.mocked(loadActorSession);
const 续期身份会话 = vi.mocked(renewActorSession);
const 加载身份会话列表 = vi.mocked(loadActorSessions);
const 踢下线身份会话 = vi.mocked(kickoutActorSessions);
const 提交开发交付 = vi.mocked(submitDeveloperDelivery);
const 准备编码智能体执行 = vi.mocked(prepareCodingAgentExecution);
const 记录编码智能体交付回溯 = vi.mocked(recordCodingAgentHandoff);
const 重试Agent运行 = vi.mocked(retryAgentRun);
const 认领Agent运行 = vi.mocked(claimAgentRun);
const 认领下一条Agent运行 = vi.mocked(claimNextAgentRun);
const 创建缺陷 = vi.mocked(createBug);
const 流转缺陷 = vi.mocked(transitionBug);
const 提交测试报告 = vi.mocked(submitTestReport);
const 配置部署目标 = vi.mocked(configureDeploymentTarget);
const 运行部署健康检查 = vi.mocked(runDeploymentHealthCheck);
const 记录部署操作 = vi.mocked(recordDeploymentOperation);
const 配置Compose环境 = vi.mocked(configureComposeEnvironment);
const 校验Compose环境 = vi.mocked(validateComposeEnvironment);
const 启动Compose环境 = vi.mocked(startComposeEnvironment);
const 停止Compose环境 = vi.mocked(stopComposeEnvironment);
const 采集Compose日志 = vi.mocked(captureComposeLogs);
const 订阅项目事件 = vi.mocked(subscribeProjectEvents);
const 批量标记运行态提醒已读 = vi.mocked(markAllRuntimeNotificationsRead);
const 标记运行态提醒已读 = vi.mocked(markRuntimeNotificationRead);
const 提交验收 = vi.mocked(submitAcceptance);
const 审批本地命令 = vi.mocked(decideLocalCommandApproval);
const 取消本地任务 = vi.mocked(cancelLocalExecutionTask);
const 关闭项目事件订阅 = vi.fn();
let 项目事件处理器: ((event: ProjectEvent) => void) | null = null;

const 服务端未读审批提醒 = {
  id: 'approval:task-1',
  projectId: 'demo',
  level: 'ACTION' as const,
  title: '需要审批本地命令',
  message: 'ssh prod systemctl restart app',
  sourceType: 'APPROVAL' as const,
  sourceId: 'task-1',
  occurredAt: '2026-06-24T10:00:00Z',
  readAt: null
};

const 服务端已读审批提醒 = {
  ...服务端未读审批提醒,
  readAt: '2026-06-24T10:01:00Z'
};

const 服务端已读成功提醒 = {
  id: 'local-task:task-success:SUCCESS',
  projectId: 'demo',
  level: 'SUCCESS' as const,
  title: '本地命令执行成功',
  message: 'sleep 3',
  sourceType: 'LOCAL_TASK' as const,
  sourceId: 'task-success',
  occurredAt: '2026-06-24T10:02:00Z',
  readAt: '2026-06-24T10:03:00Z'
};

const 运行诊断报告: RuntimeDiagnosticsReport = {
  status: 'FAIL',
  generatedAt: '2026-06-25T06:00:00Z',
  items: [
    {
      key: 'jdbc',
      label: 'MySQL',
      status: 'FAIL',
      detail: '127.0.0.1:3306 不可达',
      blocking: true
    },
    {
      key: 'milvus',
      label: 'Milvus',
      status: 'WARN',
      detail: '已配置但当前不可达',
      blocking: false
    },
    {
      key: 'qwen',
      label: '千问',
      status: 'PASS',
      detail: '已配置环境变量',
      blocking: false
    }
  ],
  nextActions: ['检查 MySQL 服务和网络', '恢复 Milvus 后重新运行诊断']
};

const 编码智能体执行准备计划: CodingAgentExecutionPlan = {
  task: {
    taskId: 'coding-task-1',
    projectId: 'demo',
    role: 'DEVELOPER',
    goal: '修复支付失败重试',
    workspaceId: 'workspace-1',
    status: 'PLANNED',
    createdAt: '2026-06-25T06:10:00Z',
    steps: []
  },
  executionSteps: [
    {
      order: 1,
      type: 'CODE_EDIT',
      title: '代码编辑',
      localTool: 'apply_patch',
      status: 'APPROVAL_REQUIRED',
      referenceId: 'coding-task-1:code-edit',
      summary: '需要人工审批后修改代码'
    },
    {
      order: 2,
      type: 'TEST_COMMAND',
      title: '测试命令',
      localTool: 'local-command',
      status: 'SUBMITTED',
      referenceId: 'task-prepare-1',
      summary: '测试命令已提交到本地执行服务'
    }
  ],
  testCommandTask: {
    ...基础工作台.localExecution.recentTasks[0],
    taskId: 'task-prepare-1',
    actorId: 'user-dev',
    command: 'git status',
    status: 'APPROVAL_PENDING'
  },
  gitDiffSummary: {
    projectId: 'demo',
    workspaceId: 'workspace-1',
    repository: true,
    changedFiles: ['desktop/src/App.tsx'],
    stat: '1 file changed',
    capturedAt: '2026-06-25T06:10:01Z'
  }
};

const 编码智能体Patch结果: CodingAgentPatchResult = {
  projectId: 'demo',
  role: 'DEVELOPER',
  workspaceId: 'workspace-1',
  actorId: 'user-dev',
  runId: 'run-1',
  relativePath: 'src/App.java',
  summary: '补充入口',
  bytesWritten: 28,
  gitDiffSummary: {
    projectId: 'demo',
    workspaceId: 'workspace-1',
    repository: true,
    changedFiles: ['src/App.java'],
    stat: '1 file changed',
    capturedAt: '2026-06-25T06:30:00Z'
  }
};

const 编码智能体交付回溯文档: DocumentSummary = {
  id: 'doc-handoff-1',
  type: 'CODING_AGENT_HANDOFF',
  title: '编码智能体交付回溯',
  content: '交付结论：测试通过，可以交付',
  version: 1,
  state: 'DRAFT',
  createdAt: '2026-06-25T07:00:00Z'
};

const Agent运行记录: AgentRunRecord[] = [
  {
    id: 'run-1',
    projectId: 'demo',
    roleKey: 'DEVELOPER',
    agentKind: 'coding',
    actorUserId: 'user-dev',
    providerId: 'deepseek',
    modelName: 'deepseek-chat',
    goal: '修复支付失败重试',
    status: 'FAILED',
    summary: '测试命令超时，未产生交接文档',
    failureSummary: '测试命令超时',
    retryable: true,
    retryOfRunId: 'run-0',
    startedAt: '2026-06-25T08:00:00Z',
    finishedAt: '2026-06-25T08:02:00Z',
    createdAt: '2026-06-25T08:00:00Z',
    updatedAt: '2026-06-25T08:02:00Z'
  },
  {
    id: 'run-2',
    projectId: 'demo',
    roleKey: 'DEVELOPER',
    agentKind: 'coding',
    actorUserId: 'user-dev',
    providerId: 'deepseek',
    modelName: 'deepseek-chat',
    goal: '生成交接文档',
    status: 'SUCCEEDED',
    summary: '交接文档已生成',
    failureSummary: '',
    retryable: false,
    retryOfRunId: '',
    startedAt: '2026-06-25T07:00:00Z',
    finishedAt: '2026-06-25T07:03:00Z',
    createdAt: '2026-06-25T07:00:00Z',
    updatedAt: '2026-06-25T07:03:00Z'
  }
];

const Agent运行事件Run1: AgentRunEventRecord[] = [
  {
    id: 'run-event-1',
    runId: 'run-1',
    projectId: 'demo',
    eventType: 'RUN_STARTED',
    eventTitle: '运行开始',
    eventPayload: '{"summary":"执行准备已生成","providerId":"deepseek","modelName":"deepseek-chat","role":"DEVELOPER","agentKind":"coding"}',
    occurredAt: '2026-06-25T08:00:00Z'
  },
  {
    id: 'run-event-2',
    runId: 'run-1',
    projectId: 'demo',
    eventType: 'RUN_FAILED',
    eventTitle: '运行失败',
    eventPayload: '测试命令超时',
    occurredAt: '2026-06-25T08:02:00Z'
  },
  {
    id: 'run-event-3',
    runId: 'run-1',
    projectId: 'demo',
    eventType: 'TOOL_TRACE',
    eventTitle: '工具调用 trace',
    eventPayload:
      '{"toolName":"local-execution.commands","action":"submit-test-command","status":"APPROVAL_PENDING","referenceId":"task-1","summary":"测试命令已提交审批"}',
    occurredAt: '2026-06-25T08:02:30Z'
  }
];

const Agent运行事件Run2: AgentRunEventRecord[] = [
  {
    id: 'run-event-4',
    runId: 'run-2',
    projectId: 'demo',
    eventType: 'RUN_SUCCEEDED',
    eventTitle: '运行成功',
    eventPayload:
      '{"summary":"交接文档已生成","providerId":"deepseek","modelName":"deepseek-chat","role":"DEVELOPER","agentKind":"coding"}',
    occurredAt: '2026-06-25T07:03:00Z'
  },
  {
    id: 'run-event-5',
    runId: 'run-2',
    projectId: 'demo',
    eventType: 'TOOL_TRACE',
    eventTitle: '工具调用 trace',
    eventPayload:
      '{"toolName":"model-gateway.model-requests","action":"complete-model-request","status":"COMPLETED","referenceId":"request-2","summary":"模型请求已完成","metadata":{"providerId":"deepseek","modelName":"deepseek-chat","cacheSource":"ESTIMATED","stablePrefixHash":"fp-cache-002","cachePolicyId":"stable-platform-prefix-v1","volatileSuffixStrategy":"role-prompt-and-dynamic-context","promptPartitionPolicyId":"deepseek-reasonix-partitions-v1","promptPartitionFingerprint":"partition-fp-002","stablePartitionCount":2,"volatilePartitionCount":3}}',
    occurredAt: '2026-06-25T07:03:00Z'
  }
];

const Agent运行用户审计报告: AgentRunUserAuditReport = {
  projectId: 'demo',
  userId: 'user-product',
  totalRuns: 1,
  activeResponsibilities: 1,
  modelRequestCount: 1,
  entries: [
    {
      projectId: 'demo',
      runId: 'run-1',
      userId: 'user-product',
      responsibleUserId: 'user-product',
      responsibilitySource: 'CLAIMED_WORKER',
      roleKey: 'DEVELOPER',
      agentKind: 'coding',
      status: 'FAILED',
      actorUserId: 'user-dev',
      claimedByUserId: 'user-product',
      goal: '修复支付失败重试',
      summary: '测试命令超时，未产生交接文档',
      failureSummary: '测试命令超时',
      eventCount: 3,
      toolTraceCount: 1,
      modelRequestCount: 1,
      lastEventType: 'TOOL_TRACE',
      lastEventTitle: '工具调用 trace',
      lastModelRequestId: 'request-1',
      updatedAt: '2026-06-25T08:02:00Z'
    }
  ]
};

describe('MatrixCode 桌面工作台', () => {
  async function 打开运行中心() {
    expect(await screen.findByText('支付系统重构')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '运行' }));
    return within(await screen.findByRole('dialog', { name: '运行中心' }));
  }

  async function 打开角色工作流() {
    expect(await screen.findByText('支付系统重构')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '工作流' }));
    return within(await screen.findByRole('dialog', { name: '角色工作流' }));
  }

  beforeEach(() => {
    vi.resetAllMocks();
    window.localStorage.clear();
    项目事件处理器 = null;
    关闭项目事件订阅.mockClear();
    应用编码智能体Patch.mockResolvedValue(编码智能体Patch结果);
    签发身份令牌.mockResolvedValue({
      userId: 'user-dev',
      token: 'signed-token',
      expiresAt: '2026-06-27T06:00:00Z'
    });
    登录身份.mockResolvedValue({
      userId: 'user-dev',
      token: 'sa-token',
      expiresAt: '2026-06-27T06:00:00Z'
    });
    退出身份.mockResolvedValue(undefined);
    加载身份会话.mockResolvedValue({ authenticated: true, userId: 'user-dev' });
    续期身份会话.mockResolvedValue({
      tokenFingerprint: 'fp-user-dev-renewed',
      deviceType: 'WEB',
      deviceId: 'browser-1',
      createdAt: '2026-06-27T05:00:00Z',
      timeoutSeconds: 3600
    });
    加载身份会话列表.mockResolvedValue([
      {
        tokenFingerprint: 'fp-user-dev',
        deviceType: 'WEB',
        deviceId: 'browser-1',
        createdAt: '2026-06-27T05:00:00Z',
        timeoutSeconds: 3600
      }
    ]);
    踢下线身份会话.mockResolvedValue(undefined);
    修改身份密码.mockResolvedValue(undefined);
    记录编码智能体交付回溯.mockResolvedValue(编码智能体交付回溯文档);
    重试Agent运行.mockResolvedValue({
      ...Agent运行记录[0],
      id: 'run-retry',
      status: 'QUEUED',
      summary: '等待从失败运行恢复',
      retryable: false,
      retryOfRunId: 'run-1',
      actorUserId: 'user-product'
    });
    认领Agent运行.mockResolvedValue({
      ...Agent运行记录[0],
      id: 'run-retry',
      status: 'RUNNING',
      summary: '运行已认领',
      failureSummary: '测试命令超时',
      retryable: false,
      retryOfRunId: 'run-1',
      actorUserId: 'user-product',
      startedAt: '2026-06-25T08:04:00Z',
      finishedAt: null,
      createdAt: '2026-06-25T08:03:00Z',
      updatedAt: '2026-06-25T08:04:00Z'
    });
    认领下一条Agent运行.mockResolvedValue({
      ...Agent运行记录[0],
      id: 'run-retry',
      status: 'RUNNING',
      summary: '运行已认领',
      retryable: false,
      retryOfRunId: 'run-1',
      actorUserId: 'user-product',
      startedAt: '2026-06-25T08:04:00Z',
      finishedAt: null,
      createdAt: '2026-06-25T08:03:00Z',
      updatedAt: '2026-06-25T08:04:00Z'
    });
    订阅项目事件.mockImplementation((_projectId, handlers) => {
      项目事件处理器 = handlers.onEvent;
      return { close: 关闭项目事件订阅 };
    });
    批量标记运行态提醒已读.mockResolvedValue([服务端已读审批提醒, 服务端已读成功提醒]);
    标记运行态提醒已读.mockResolvedValue(服务端已读审批提醒);
    加载项目工作台.mockResolvedValue(基础工作台);
    加载Agent运行记录.mockResolvedValue(Agent运行记录);
    加载Agent运行模型请求.mockImplementation((_projectId, runId) => {
      const requests = 基础工作台.modelGateway.recentRequests.filter((request) => request.agentRunId === runId);
      return Promise.resolve({
        projectId: 'demo',
        agentRunId: runId,
        page: 0,
        size: 20,
        total: requests.length,
        metrics: {
          requestCount: requests.length,
          cacheHitTokens: requests.reduce((sum, request) => sum + request.usage.cacheHitTokens, 0),
          cacheMissInputTokens: requests.reduce((sum, request) => sum + request.usage.cacheMissInputTokens, 0),
          outputTokens: requests.reduce((sum, request) => sum + request.usage.outputTokens, 0),
          cacheHitRate: requests[0]?.usage.cacheHitRate ?? 0,
          estimatedCost: requests.reduce((sum, request) => sum + request.usage.estimatedCost, 0),
          currency: requests[0]?.usage.currency ?? 'CNY',
          recentContextTypes: requests[0]?.contextTypes ?? []
        },
        trend: requests.map((request) => ({
          requestId: request.requestId,
          createdAt: request.createdAt,
          cacheHitRate: request.usage.cacheHitRate,
          estimatedCost: request.usage.estimatedCost,
          currency: request.usage.currency,
          cacheSource: request.usage.cacheSource ?? 'UNKNOWN'
        })),
        requests
      });
    });
    加载项目模型成本趋势.mockResolvedValue({
      projectId: 'demo',
      days: 30,
      timeZone: 'UTC',
      from: '2026-05-26T00:00:00Z',
      to: '2026-06-25T00:00:00Z',
      metrics: {
        requestCount: 2,
        cacheHitTokens: 1200,
        cacheMissInputTokens: 2600,
        outputTokens: 1100,
        cacheHitRate: 0.32,
        estimatedCost: 0.198,
        currency: 'CNY',
        recentContextTypes: ['FROZEN_PRD']
      },
      dailyTrend: [
        {
          date: '2026-06-25',
          metrics: {
            requestCount: 2,
            cacheHitTokens: 1200,
            cacheMissInputTokens: 2600,
            outputTokens: 1100,
            cacheHitRate: 0.32,
            estimatedCost: 0.198,
            currency: 'CNY',
            recentContextTypes: ['FROZEN_PRD']
          }
        }
      ],
      roleBreakdown: [
        {
          key: 'PRODUCT',
          metrics: {
            requestCount: 1,
            cacheHitTokens: 1200,
            cacheMissInputTokens: 800,
            outputTokens: 600,
            cacheHitRate: 0.6,
            estimatedCost: 0.128,
            currency: 'CNY',
            recentContextTypes: ['PROJECT_RULE']
          }
        }
      ],
      providerBreakdown: [],
      modelBreakdown: []
    });
    加载Agent运行事件.mockImplementation((_projectId, runId) =>
      Promise.resolve(runId === 'run-2' ? Agent运行事件Run2 : Agent运行事件Run1)
    );
    加载Agent运行用户审计.mockResolvedValue(Agent运行用户审计报告);
    加载角色智能体配置.mockResolvedValue(默认角色智能体配置);
    加载项目成员.mockResolvedValue(默认项目成员);
    加载项目邀请.mockResolvedValue(默认项目邀请);
    加载用户级审计.mockResolvedValue(用户级审计记录);
    添加项目成员.mockResolvedValue({
      id: 'member-tester',
      projectId: 'demo',
      userId: 'user-tester',
      roleKey: 'TESTER',
      status: 'ACTIVE',
      joinedAt: '2026-06-25T13:30:00Z',
      createdAt: '2026-06-25T13:30:00Z',
      updatedAt: '2026-06-25T13:30:00Z'
    });
    创建项目用户.mockResolvedValue({
      id: 'member-new',
      projectId: 'demo',
      userId: 'user-new',
      roleKey: 'DEVELOPER',
      status: 'ACTIVE',
      joinedAt: '2026-06-25T13:31:00Z',
      createdAt: '2026-06-25T13:31:00Z',
      updatedAt: '2026-06-25T13:31:00Z'
    });
    更新项目成员.mockResolvedValue({
      ...默认项目成员[1],
      roleKey: 'TESTER',
      status: 'ACTIVE',
      updatedAt: '2026-06-25T13:35:00Z'
    });
    批量更新项目成员.mockResolvedValue([
      {
        ...默认项目成员[1],
        status: 'DISABLED',
        updatedAt: '2026-06-25T13:36:00Z'
      }
    ]);
    创建项目邀请.mockResolvedValue(默认签发项目邀请);
    撤销项目邀请.mockResolvedValue({
      ...默认签发项目邀请.invitation,
      status: 'REVOKED',
      updatedAt: '2026-06-25T13:37:00Z'
    });
    重发项目邀请.mockResolvedValue({
      ...默认签发项目邀请,
      token: 'reissued-invitation-token',
      invitation: {
        ...默认签发项目邀请.invitation,
        status: 'PENDING',
        updatedAt: '2026-06-25T13:38:00Z'
      }
    });
    清理过期项目邀请.mockResolvedValue([]);
    加载运行诊断.mockResolvedValue(运行诊断报告);
    准备编码智能体执行.mockResolvedValue(编码智能体执行准备计划);
    创建角色模型流式请求.mockResolvedValue({
      requestId: 'composer-request-1',
      answer: '已根据当前工作台上下文生成执行建议。',
      contextManifest: {
        role: 'PRODUCT',
        blocks: [
          { type: 'WORKBENCH_STAGE', summary: '当前阶段：测试执行', allowedByGate: true },
          { type: 'COMPOSER_RUNTIME', summary: '工具权限：自动；推理力度：max；协作方式：计划、省 token', allowedByGate: true }
        ],
        omittedTypes: []
      },
      usage: 基础工作台.modelGateway.recentRequests[0].usage,
      binding: 基础工作台.modelGateway.bindings[0],
      promptContract: {
        role: 'PRODUCT',
        model: 'matrixcode-local-product',
        toolContractVersion: 'tools-v1',
        systemPrefix: 'stable prefix',
        stablePrefixHash: 'fp-cache-001',
        estimatedStablePrefixTokens: 128
      },
      createdAt: '2026-06-25T08:20:00Z'
    });
    更新角色智能体配置.mockResolvedValue({
      ...默认角色智能体配置[1],
      systemPrompt: '你是开发编码智能体，必须先读代码再修改。',
      themeColor: '#0f766e',
      updatedAt: '2026-06-25T01:00:00Z'
    });
    生成需求草稿.mockResolvedValue(草稿工作台.documents);
    冻结文档.mockResolvedValue(冻结后工作台.documents[0]);
    提交开发交付.mockResolvedValue([]);
    创建缺陷.mockResolvedValue({
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
    });
    流转缺陷.mockResolvedValue({
      id: 'bug-1',
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
    });
    提交测试报告.mockResolvedValue({
      id: 'test-report-1',
      type: 'QA_REPORT',
      title: '测试报告',
      content: '测试通过。',
      version: 1,
      state: 'DRAFT',
      createdAt: '2026-06-24T11:15:00Z'
    });
    配置部署目标.mockResolvedValue({
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
    });
    运行部署健康检查.mockResolvedValue(部署运行工作台.deploymentRuntimeSummaries[0].latestHealthCheck!);
    记录部署操作.mockResolvedValue(部署运行工作台.deploymentRuntimeSummaries[0].latestDeploymentOperation!);
    配置Compose环境.mockResolvedValue(Compose运行工作台.composeEnvironments[0]);
    校验Compose环境.mockResolvedValue({
      ...Compose运行工作台.composeRuntimeViews[0].latestOperation!,
      type: 'VALIDATE',
      summary: 'Compose 配置有效',
      logExcerpt: 'services:\n  web:'
    });
    启动Compose环境.mockResolvedValue({
      ...Compose运行工作台.composeRuntimeViews[0].latestOperation!,
      type: 'START',
      summary: 'Compose 已启动',
      logExcerpt: 'Container web Started'
    });
    停止Compose环境.mockResolvedValue({
      ...Compose运行工作台.composeRuntimeViews[0].latestOperation!,
      type: 'STOP',
      summary: 'Compose 已停止',
      logExcerpt: 'Container web Stopped'
    });
    采集Compose日志.mockResolvedValue(Compose运行工作台.composeRuntimeViews[0].latestOperation!);
    提交验收.mockResolvedValue({
      id: 'acceptance-1',
      type: 'ACCEPTANCE_RECORD',
      title: '验收记录',
      content: '验收通过。',
      version: 1,
      state: 'DRAFT',
      createdAt: '2026-06-24T11:30:00Z'
    });
    审批本地命令.mockResolvedValue({
      ...基础工作台.localExecution.recentTasks[0],
      approvalDecision: 'DENY',
      status: 'DENIED',
      approverId: 'user-reviewer',
      approvalNote: '本轮不执行',
      decidedAt: '2026-06-24T10:05:00Z',
      safetyRejectionReason: ''
    });
    取消本地任务.mockResolvedValue({
      ...运行中长任务,
      status: 'CANCELED',
      canceledBy: 'user-reviewer',
      cancelNote: '用户在运行中心取消任务',
      canceledAt: '2026-06-24T10:11:00Z'
    });
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    项目事件处理器 = null;
  });

  it('连接期间展示加载状态', () => {
    加载项目工作台.mockReturnValue(new Promise(() => {}));

    render(<App />);

    expect(screen.getByText('正在连接团队服务器...')).toBeTruthy();
  });

  it('本地会话接近过期时自动按服务端窗口续期', async () => {
    window.localStorage.setItem('matrixcode.actorToken', 'sa-token');
    window.localStorage.setItem('matrixcode.actorTokenUserId', 'user-dev');
    window.localStorage.setItem('matrixcode.actorTokenExpiresAt', '2026-01-01T00:00:00Z');

    render(<App />);

    expect(await screen.findByText('支付系统重构')).toBeTruthy();
    await waitFor(() => expect(续期身份会话).toHaveBeenCalledWith('demo', 'user-dev'));
    expect(window.localStorage.getItem('matrixcode.actorTokenExpiresAt')).not.toBe('2026-01-01T00:00:00Z');
  });

  it('展示项目、全部角色、阶段状态和底部状态栏', async () => {
    render(<App />);

    expect(await screen.findByText('支付系统重构')).toBeTruthy();
    expect(screen.getByLabelText('当前操作者')).toBeTruthy();
    expect(screen.getByText(/user-product/)).toBeTruthy();
    const 角色工作区 = within(screen.getByRole('navigation', { name: '角色工作区' }));
    expect(角色工作区.getByRole('button', { name: '产品' })).toBeTruthy();
    expect(角色工作区.getByRole('button', { name: '开发' })).toBeTruthy();
    expect(角色工作区.getByRole('button', { name: '测试' })).toBeTruthy();
    expect(角色工作区.getByRole('button', { name: '运维' })).toBeTruthy();
    expect(screen.getByLabelText('测试，当前')).toBeTruthy();
    expect(screen.getByLabelText('上线，待处理')).toBeTruthy();
    expect(screen.getByLabelText('大模型输出台')).toBeTruthy();
    expect(screen.getByLabelText('大模型对话台')).toBeTruthy();
    expect(screen.getByRole('button', { name: '工作流' })).toBeTruthy();
    expect(screen.queryByLabelText('产品工作区')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '工作流' }));
    expect(await screen.findByRole('dialog', { name: '角色工作流' })).toBeTruthy();
    expect(screen.getByLabelText('产品工作区')).toBeTruthy();
    expect(screen.getByLabelText('输出与预览')).toBeTruthy();
    expect(screen.queryByRole('complementary', { name: '运行指标' })).toBeNull();
    const 底部状态栏 = within(screen.getByLabelText('工作台底部状态'));
    expect(底部状态栏.getByText('当前模型')).toBeTruthy();
    expect(底部状态栏.getByText(/local-deterministic\s*\/\s*matrixcode-local-product/)).toBeTruthy();
    expect(底部状态栏.getByText('运行指标')).toBeTruthy();
    expect(底部状态栏.getByText(/请求\s*2/)).toBeTruthy();
    expect(底部状态栏.getByText(/费用\s*0.128\s*CNY/)).toBeTruthy();
    expect(底部状态栏.getByText(/缓存\s*86%/)).toBeTruthy();
    expect(底部状态栏.getByText(/最近命中\s*60%/)).toBeTruthy();
    expect(底部状态栏.getByText(/prefix\s*fp-cache-001/)).toBeTruthy();
    expect(底部状态栏.getByText('工作摘要')).toBeTruthy();
    expect(底部状态栏.getByText(/会话 tokens\s*221,000/)).toBeTruthy();
    expect(底部状态栏.getByText(/文档\s*0/)).toBeTruthy();
    expect(底部状态栏.getByText(/未关 Bug\s*0/)).toBeTruthy();
    expect(screen.getByRole('button', { name: '运行' })).toBeTruthy();
    expect(screen.queryByLabelText('本地执行代理')).toBeNull();
  });

  it('底部Composer会实时预览并携带运行选项调用后端模型', async () => {
    render(<App />);

    expect(await screen.findByText('支付系统重构')).toBeTruthy();
    const 实时预览 = within(screen.getByLabelText('实时输出预览'));
    expect(实时预览.getByText(/等待下方对话输入/)).toBeTruthy();

    fireEvent.change(screen.getByLabelText('Agent 对话输入'), {
      target: { value: '请梳理下一阶段交付任务' }
    });
    expect(实时预览.getByText(/请梳理下一阶段交付任务/)).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '协作方式' }));
    fireEvent.click(screen.getByRole('menuitemcheckbox', { name: /计划/ }));
    fireEvent.click(screen.getByRole('button', { name: '推理力度：auto' }));
    fireEvent.click(screen.getByRole('menuitemradio', { name: /max/ }));
    fireEvent.click(screen.getByRole('button', { name: '发送给角色智能体' }));

    await waitFor(() => expect(创建角色模型流式请求).toHaveBeenCalled());
    expect(创建角色模型流式请求).toHaveBeenCalledWith(
      'demo',
      'product',
      expect.objectContaining({
        actorUserId: 'user-product',
        instruction: '请梳理下一阶段交付任务',
        providerId: 'local-deterministic',
        model: 'matrixcode-local-product',
        approvalMode: 'auto',
        reasoningEffort: 'max',
        planMode: true,
        goalMode: false,
        tokenEconomy: true
      }),
      expect.any(Function),
      'user-product'
    );
    const requestInput = 创建角色模型流式请求.mock.calls.at(-1)?.[2];
    expect(requestInput?.contextBlocks).toEqual(
      [expect.objectContaining({ type: 'WORKBENCH_STAGE' })]
    );
    expect(requestInput?.contextBlocks).not.toEqual(
      expect.arrayContaining([
        expect.objectContaining({ type: 'RECENT_DOCUMENTS' }),
        expect.objectContaining({ type: 'RECENT_EVENTS' })
      ])
    );
    expect(await screen.findByText('已根据当前工作台上下文生成执行建议。')).toBeTruthy();
  });

  it('底部Composer会去重模型选项并在请求期间给出明确反馈', async () => {
    const 重复模型工作台: ProjectWorkbench = {
      ...基础工作台,
      modelGateway: {
        ...基础工作台.modelGateway,
        bindings: [
          基础工作台.modelGateway.bindings[0],
          {
            ...基础工作台.modelGateway.bindings[0],
            role: 'DEVELOPER',
            providerId: 'deepseek',
            model: 'deepseek-chat'
          },
          {
            ...基础工作台.modelGateway.bindings[0],
            role: 'TESTER',
            providerId: 'deepseek',
            model: 'deepseek-chat'
          },
          基础工作台.modelGateway.bindings[0]
        ]
      }
    };
    let 完成请求: (value: Awaited<ReturnType<typeof createRoleModelRequestStream>>) => void = () => {};

    加载项目工作台.mockResolvedValue(重复模型工作台);
    创建角色模型流式请求.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          完成请求 = resolve;
        }) as ReturnType<typeof createRoleModelRequestStream>
    );

    render(<App />);

    expect(await screen.findByText('支付系统重构')).toBeTruthy();
    const Composer = within(screen.getByRole('form', { name: 'Agent 对话 Composer' }));
    const 模型选择 = Composer.getByLabelText('当前模型') as HTMLSelectElement;
    const 选项值 = Array.from(模型选择.options).map((option) => option.value);
    expect(选项值).toEqual(Array.from(new Set(选项值)));

    fireEvent.change(screen.getByLabelText('Agent 对话输入'), {
      target: { value: '请用一句话说明下一步' }
    });
    fireEvent.click(screen.getByRole('button', { name: '发送给角色智能体' }));

    expect((await screen.findByRole('button', { name: '正在发送给角色智能体' }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText(/正在调用模型/)).toBeTruthy();

    await act(async () => {
      完成请求({
        requestId: 'composer-request-pending',
        answer: '已收到请求。',
        contextManifest: {
          role: 'PRODUCT',
          blocks: [],
          omittedTypes: []
        },
        usage: 基础工作台.modelGateway.recentRequests[0].usage,
        binding: 基础工作台.modelGateway.bindings[0],
        promptContract: {
          role: 'PRODUCT',
          model: 'matrixcode-local-product',
          toolContractVersion: 'tools-v1',
          systemPrefix: 'stable prefix',
          stablePrefixHash: 'fp-cache-001',
          estimatedStablePrefixTokens: 128
        },
        createdAt: '2026-06-25T08:22:00Z'
      });
    });
  });

  it('底部Composer会在模型完成前实时展示流式片段', async () => {
    let 完成请求: (value: Awaited<ReturnType<typeof createRoleModelRequestStream>>) => void = () => {};
    创建角色模型流式请求.mockImplementationOnce(
      (_projectId, _role, _input, onDelta) =>
        new Promise((resolve) => {
          onDelta('第一段');
          onDelta('第二段');
          完成请求 = resolve;
        }) as ReturnType<typeof createRoleModelRequestStream>
    );

    render(<App />);

    expect(await screen.findByText('支付系统重构')).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Agent 对话输入'), {
      target: { value: '请输出实时计划' }
    });
    fireEvent.click(screen.getByRole('button', { name: '发送给角色智能体' }));

    expect(await screen.findByText('模型流式回复')).toBeTruthy();
    expect(screen.getByText('第一段第二段')).toBeTruthy();

    await act(async () => {
      完成请求({
        requestId: 'composer-request-streaming',
        answer: '第一段第二段',
        contextManifest: {
          role: 'PRODUCT',
          blocks: [],
          omittedTypes: []
        },
        usage: 基础工作台.modelGateway.recentRequests[0].usage,
        binding: 基础工作台.modelGateway.bindings[0],
        promptContract: {
          role: 'PRODUCT',
          model: 'matrixcode-local-product',
          toolContractVersion: 'tools-v1',
          systemPrefix: 'stable prefix',
          stablePrefixHash: 'fp-cache-001',
          estimatedStablePrefixTokens: 128
        },
        createdAt: '2026-06-25T08:22:30Z'
      });
    });
  });

  it('模型长回复默认摘要展示并支持展开全文', async () => {
    const 长回复 = `第一行结论。${'需要压缩展示的详细推理内容。'.repeat(80)}最后一句只应在展开后完整出现。`;
    创建角色模型流式请求.mockResolvedValueOnce({
      requestId: 'composer-request-long',
      answer: 长回复,
      contextManifest: {
        role: 'PRODUCT',
        blocks: [],
        omittedTypes: []
      },
      usage: 基础工作台.modelGateway.recentRequests[0].usage,
      binding: 基础工作台.modelGateway.bindings[0],
      promptContract: {
        role: 'PRODUCT',
        model: 'matrixcode-local-product',
        toolContractVersion: 'tools-v1',
        systemPrefix: 'stable prefix',
        stablePrefixHash: 'fp-cache-001',
        estimatedStablePrefixTokens: 128
      },
      createdAt: '2026-06-25T08:23:00Z'
    });

    render(<App />);

    expect(await screen.findByText('支付系统重构')).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Agent 对话输入'), {
      target: { value: '请输出详细计划' }
    });
    fireEvent.click(screen.getByRole('button', { name: '发送给角色智能体' }));

    expect(await screen.findByText('模型回复')).toBeTruthy();
    const 实时预览 = within(screen.getByLabelText('实时输出预览'));
    expect(实时预览.getByLabelText('模型回复摘要')).toBeTruthy();
    expect(实时预览.queryByText(长回复)).toBeNull();

    fireEvent.click(实时预览.getByRole('button', { name: '展开完整回复' }));
    expect(实时预览.getByText(长回复)).toBeTruthy();
  });

  it('底部状态栏和运行中心展示真实 Agent 运行事件', async () => {
    render(<App />);

    expect(await screen.findByText('支付系统重构')).toBeTruthy();
    await waitFor(() => expect(加载Agent运行记录).toHaveBeenCalledWith('demo', 'user-product'));
    expect(加载Agent运行事件).toHaveBeenCalledWith('demo', 'run-1', 'user-product');
    expect(加载Agent运行事件).toHaveBeenCalledWith('demo', 'run-2', 'user-product');
    const Agent运行 = within(screen.getByLabelText('Agent 运行'));
    expect(Agent运行.getByText(/修复支付失败重试/)).toBeTruthy();
    expect(Agent运行.getByText(/失败 · 修复支付失败重试/)).toBeTruthy();
    expect(Agent运行.getByText(/恢复策略 可重试/)).toBeTruthy();
    expect(Agent运行.getByText(/失败摘要 测试命令超时/)).toBeTruthy();
    expect(Agent运行.getByText(/重试来源 run-0/)).toBeTruthy();
    expect(Agent运行.getByText(/运行失败/)).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '运行' }));

    const 运行中心 = within(await screen.findByRole('dialog', { name: '运行中心' }));
    await waitFor(() =>
      expect(加载Agent运行模型请求).toHaveBeenCalledWith('demo', 'run-1', { page: 0, size: 20 }, 'user-product')
    );
    const Agent审计详情 = within(运行中心.getByLabelText('Agent 审计详情'));
    expect(Agent审计详情.getByText('运行 run-1 · 失败 · 可重试')).toBeTruthy();
    expect(Agent审计详情.getByText('目标 修复支付失败重试')).toBeTruthy();
    expect(Agent审计详情.getByText('操作者 user-dev · 角色 开发')).toBeTruthy();
    expect(Agent审计详情.getByText('模型 deepseek/deepseek-chat · coding')).toBeTruthy();
    expect(Agent审计详情.getByText('工具事件 1 · 失败事件 1')).toBeTruthy();
    expect(Agent审计详情.getByText('恢复 run-0 · 可重试')).toBeTruthy();
    expect(Agent审计详情.getByText('摘要 测试命令超时，未产生交接文档')).toBeTruthy();
    expect(Agent审计详情.getByText('失败 测试命令超时')).toBeTruthy();
    expect(Agent审计详情.getByText('模型请求 request-1 · 已关联 run-1 · PROVIDER · prefix fp-cache-001')).toBeTruthy();
    expect(
      Agent审计详情.getByText('运行模型用量 请求 1 · 命中 1,200 · 未命中 800 · 输出 600 · 命中率 60% · 费用 CNY 0.128')
    ).toBeTruthy();
    expect(
      Agent审计详情.getByText('运行缓存策略 stable-platform-prefix-v1 · role-prompt-and-dynamic-context · 来源 PROVIDER')
    ).toBeTruthy();
    expect(Agent审计详情.getByText('Prompt分区 deepseek-reasonix-partitions-v1 · partition-fp-001 · 稳定 2 · 动态 3')).toBeTruthy();
    await waitFor(() => expect(Agent审计详情.getByText('请求分页 共 1 · 第 1 页 · 每页 20')).toBeTruthy());
    expect(Agent审计详情.getByText('成本趋势 request-1 · 命中率 60% · CNY 0.128 · PROVIDER')).toBeTruthy();
    const Agent运行时间线 = within(运行中心.getByLabelText('Agent 运行时间线'));
    expect(Agent运行时间线.getByText('事件 3/3')).toBeTruthy();
    expect(Agent运行时间线.getByText('模型请求线索 request-1 · matrixcode-local-product')).toBeTruthy();
    expect(Agent运行时间线.getByText(/运行开始/)).toBeTruthy();
    expect(Agent运行时间线.getByText(/运行失败/)).toBeTruthy();
    expect(Agent运行时间线.getByText(/测试命令超时/)).toBeTruthy();
    expect(Agent运行时间线.getByText(/工具调用 trace/)).toBeTruthy();
    expect(Agent运行时间线.getByText(/工具 local-execution.commands/)).toBeTruthy();
    expect(Agent运行时间线.getByText(/动作 submit-test-command/)).toBeTruthy();
    expect(Agent运行时间线.getByText(/状态 APPROVAL_PENDING/)).toBeTruthy();
    expect(Agent运行时间线.getByText(/引用 task-1/)).toBeTruthy();
    expect(Agent运行时间线.getByText(/摘要 测试命令已提交审批/)).toBeTruthy();

    const Agent事件筛选 = within(运行中心.getByLabelText('Agent 事件筛选'));
    fireEvent.click(Agent事件筛选.getByRole('button', { name: '工具' }));
    expect(Agent运行时间线.getByText('事件 1/3')).toBeTruthy();
    expect(Agent运行时间线.queryByText(/运行失败/)).toBeNull();
    expect(Agent运行时间线.getByText(/工具 local-execution.commands/)).toBeTruthy();

    fireEvent.click(Agent事件筛选.getByRole('button', { name: '失败' }));
    expect(Agent运行时间线.getByText('事件 1/3')).toBeTruthy();
    expect(Agent运行时间线.getByText(/运行失败/)).toBeTruthy();
    expect(Agent运行时间线.queryByText(/工具 local-execution.commands/)).toBeNull();
  });

  it('运行中心可以选择历史 Agent 运行并查看对应模型请求 trace', async () => {
    render(<App />);

    const 运行中心 = await 打开运行中心();
    const Agent运行选择 = within(运行中心.getByLabelText('Agent 运行选择'));
    fireEvent.click(Agent运行选择.getByRole('button', { name: /run-2/ }));

    const Agent审计详情 = within(运行中心.getByLabelText('Agent 审计详情'));
    expect(Agent审计详情.getByText('运行 run-2 · 成功 · 不可重试')).toBeTruthy();
    expect(Agent审计详情.getByText('目标 生成交接文档')).toBeTruthy();
    expect(Agent审计详情.getByText('摘要 交接文档已生成')).toBeTruthy();
    expect(Agent审计详情.getByText('模型请求 request-2 · 已关联 run-2 · ESTIMATED · prefix fp-cache-002')).toBeTruthy();
    expect(
      Agent审计详情.getByText('运行模型用量 请求 1 · 命中 0 · 未命中 1,800 · 输出 500 · 命中率 0% · 费用 CNY 0.070')
    ).toBeTruthy();
    expect(
      Agent审计详情.getByText('运行缓存策略 stable-platform-prefix-v1 · role-prompt-and-dynamic-context · 来源 ESTIMATED')
    ).toBeTruthy();
    expect(Agent审计详情.getByText('Prompt分区 deepseek-reasonix-partitions-v1 · partition-fp-002 · 稳定 2 · 动态 3')).toBeTruthy();
    expect(Agent审计详情.queryByText('失败 测试命令超时')).toBeNull();

    const Agent运行时间线 = within(运行中心.getByLabelText('Agent 运行时间线'));
    expect(Agent运行时间线.getByText('事件 2/2')).toBeTruthy();
    expect(Agent运行时间线.queryByText(/运行失败/)).toBeNull();
    expect(Agent运行时间线.getByText(/运行成功/)).toBeTruthy();
    expect(Agent运行时间线.getByText(/工具 model-gateway.model-requests/)).toBeTruthy();
    expect(Agent运行时间线.getByText(/动作 complete-model-request/)).toBeTruthy();
    expect(Agent运行时间线.getByText(/状态 COMPLETED/)).toBeTruthy();
    expect(Agent运行时间线.getByText(/引用 request-2/)).toBeTruthy();
    expect(Agent运行时间线.getByText(/模型 deepseek\/deepseek-chat · ESTIMATED · prefix fp-cache-002/)).toBeTruthy();
    expect(Agent运行时间线.getByText(/缓存 stable-platform-prefix-v1 · role-prompt-and-dynamic-context/)).toBeTruthy();
    expect(Agent运行时间线.getByText(/分区 deepseek-reasonix-partitions-v1 · partition-fp-002 · 稳定 2 · 动态 3/)).toBeTruthy();
  });

  it('运行中心展示当前用户 Agent 责任审计', async () => {
    render(<App />);

    const 运行中心 = await 打开运行中心();
    await waitFor(() => expect(加载Agent运行用户审计).toHaveBeenCalledWith('demo', 'user-product', 50));
    const 用户责任审计 = within(运行中心.getByLabelText('用户责任审计'));

    expect(用户责任审计.getByText('当前用户 user-product')).toBeTruthy();
    expect(用户责任审计.getByText('运行 1 · 活跃 1 · 模型请求 1')).toBeTruthy();
    expect(用户责任审计.getByText('运行 run-1 · 认领 Worker · 失败')).toBeTruthy();
    expect(用户责任审计.getByText('目标 修复支付失败重试')).toBeTruthy();
    expect(用户责任审计.getByText('角色 开发 · coding · 工具 1 · 模型请求 1')).toBeTruthy();
    expect(用户责任审计.getByText('最近事件 工具调用 trace')).toBeTruthy();
    expect(用户责任审计.getByText('最近请求 request-1')).toBeTruthy();
  });

  it('切换当前操作者后刷新 Agent 责任审计', async () => {
    const 开发责任审计报告: AgentRunUserAuditReport = {
      ...Agent运行用户审计报告,
      userId: 'user-dev',
      totalRuns: 2,
      activeResponsibilities: 1,
      modelRequestCount: 2,
      entries: [
        {
          ...Agent运行用户审计报告.entries[0],
          userId: 'user-dev',
          responsibleUserId: 'user-dev',
          responsibilitySource: 'RUN_ACTOR',
          claimedByUserId: null,
          runId: 'run-2',
          status: 'SUCCEEDED',
          goal: '生成交接文档',
          summary: '交接文档已生成',
          failureSummary: '',
          eventCount: 2,
          toolTraceCount: 1,
          modelRequestCount: 2,
          lastEventTitle: '运行成功',
          lastModelRequestId: 'request-2'
        }
      ]
    };
    加载Agent运行用户审计
      .mockResolvedValueOnce(Agent运行用户审计报告)
      .mockResolvedValueOnce(开发责任审计报告);

    render(<App />);
    await waitFor(() => expect(加载Agent运行用户审计).toHaveBeenCalledWith('demo', 'user-product', 50));

    fireEvent.change(screen.getByLabelText('当前操作者'), { target: { value: 'user-dev' } });

    const 运行中心 = await 打开运行中心();
    await waitFor(() => expect(加载Agent运行用户审计).toHaveBeenCalledWith('demo', 'user-dev', 50));
    const 用户责任审计 = within(运行中心.getByLabelText('用户责任审计'));
    expect(用户责任审计.getByText('当前用户 user-dev')).toBeTruthy();
    expect(用户责任审计.getByText('运行 2 · 活跃 1 · 模型请求 2')).toBeTruthy();
    expect(用户责任审计.getByText('运行 run-2 · 运行操作者 · 成功')).toBeTruthy();
  });

  it('Agent 责任审计接口不可用时运行中心显示空态', async () => {
    加载Agent运行用户审计.mockRejectedValueOnce(new Error('old server'));

    render(<App />);

    const 运行中心 = await 打开运行中心();
    const 用户责任审计 = within(运行中心.getByLabelText('用户责任审计'));
    expect(用户责任审计.getByText('当前用户 user-product')).toBeTruthy();
    expect(用户责任审计.getByText('运行 0 · 活跃 0 · 模型请求 0')).toBeTruthy();
    expect(用户责任审计.getByText('暂无责任运行')).toBeTruthy();
  });

  it('运行中心可以把可重试失败 Agent 运行排队恢复', async () => {
    const retryRun: AgentRunRecord = {
      ...Agent运行记录[0],
      id: 'run-retry',
      status: 'QUEUED',
      summary: '等待从失败运行恢复',
      failureSummary: '测试命令超时',
      retryable: false,
      retryOfRunId: 'run-1',
      actorUserId: 'user-product',
      startedAt: null,
      finishedAt: null,
      createdAt: '2026-06-25T08:03:00Z',
      updatedAt: '2026-06-25T08:03:00Z'
    };
    加载Agent运行记录
      .mockResolvedValueOnce(Agent运行记录)
      .mockResolvedValueOnce([retryRun, ...Agent运行记录]);
    加载Agent运行事件.mockImplementation((_projectId, runId) => {
      if (runId === 'run-retry') {
        return Promise.resolve([]);
      }
      return Promise.resolve(runId === 'run-2' ? Agent运行事件Run2 : Agent运行事件Run1);
    });

    render(<App />);

    const 运行中心 = await 打开运行中心();
    const Agent审计详情 = within(运行中心.getByLabelText('Agent 审计详情'));
    fireEvent.click(Agent审计详情.getByRole('button', { name: '重试运行' }));

    await waitFor(() => expect(重试Agent运行).toHaveBeenCalledWith('demo', 'run-1', 'user-product'));
    await waitFor(() => expect(加载Agent运行记录).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.getAllByText(/排队中 · 修复支付失败重试/).length).toBeGreaterThan(0));
  });

  it('运行中心可以把排队 Agent 运行认领为运行中', async () => {
    const queuedRun: AgentRunRecord = {
      ...Agent运行记录[0],
      id: 'run-retry',
      status: 'QUEUED',
      summary: '等待从失败运行恢复',
      failureSummary: '测试命令超时',
      retryable: false,
      retryOfRunId: 'run-1',
      actorUserId: 'user-product',
      startedAt: null,
      finishedAt: null,
      createdAt: '2026-06-25T08:03:00Z',
      updatedAt: '2026-06-25T08:03:00Z'
    };
    const runningRun: AgentRunRecord = {
      ...queuedRun,
      status: 'RUNNING',
      summary: '运行已认领',
      startedAt: '2026-06-25T08:04:00Z',
      updatedAt: '2026-06-25T08:04:00Z'
    };
    加载Agent运行记录
      .mockResolvedValueOnce([queuedRun, ...Agent运行记录])
      .mockResolvedValueOnce([runningRun, ...Agent运行记录]);
    加载Agent运行事件.mockImplementation((_projectId, runId) => {
      if (runId === 'run-retry') {
        return Promise.resolve([]);
      }
      return Promise.resolve(runId === 'run-2' ? Agent运行事件Run2 : Agent运行事件Run1);
    });

    render(<App />);

    const 运行中心 = await 打开运行中心();
    const Agent审计详情 = within(运行中心.getByLabelText('Agent 审计详情'));
    fireEvent.click(Agent审计详情.getByRole('button', { name: '认领运行' }));

    await waitFor(() => expect(认领Agent运行).toHaveBeenCalledWith('demo', 'run-retry', 'user-product'));
    await waitFor(() => expect(加载Agent运行记录).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.getAllByText(/运行中 · 修复支付失败重试/).length).toBeGreaterThan(0));
  });

  it('运行中心可以从项目队列认领下一条 Agent 运行', async () => {
    const queuedRun: AgentRunRecord = {
      ...Agent运行记录[0],
      id: 'run-retry',
      status: 'QUEUED',
      summary: '等待从失败运行恢复',
      retryable: false,
      retryOfRunId: 'run-1',
      actorUserId: 'user-product',
      startedAt: null,
      finishedAt: null,
      createdAt: '2026-06-25T08:03:00Z',
      updatedAt: '2026-06-25T08:03:00Z'
    };
    const runningRun: AgentRunRecord = {
      ...queuedRun,
      status: 'RUNNING',
      summary: '运行已认领',
      startedAt: '2026-06-25T08:04:00Z',
      updatedAt: '2026-06-25T08:04:00Z'
    };
    加载Agent运行记录
      .mockResolvedValueOnce([queuedRun, ...Agent运行记录])
      .mockResolvedValueOnce([runningRun, ...Agent运行记录]);
    加载Agent运行事件.mockImplementation((_projectId, runId) => {
      if (runId === 'run-retry') {
        return Promise.resolve([]);
      }
      return Promise.resolve(runId === 'run-2' ? Agent运行事件Run2 : Agent运行事件Run1);
    });

    render(<App />);

    const 运行中心 = await 打开运行中心();
    fireEvent.click(运行中心.getByRole('button', { name: '认领下一条' }));

    await waitFor(() => expect(认领下一条Agent运行).toHaveBeenCalledWith('demo', 'user-product'));
    await waitFor(() => expect(加载Agent运行记录).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.getAllByText(/运行中 · 修复支付失败重试/).length).toBeGreaterThan(0));
  });

  it('Agent 运行接口不可用时仍保留工作台', async () => {
    加载Agent运行记录.mockRejectedValueOnce(new Error('old server'));

    render(<App />);

    expect(await screen.findByText('支付系统重构')).toBeTruthy();
    expect(screen.getByLabelText('Agent 运行')).toBeTruthy();
    const 输出台 = within(screen.getByLabelText('大模型输出台'));
    expect(输出台.getByText('暂无 Agent 运行')).toBeTruthy();
  });

  it('可以通过页面配置按钮按角色标签编辑开发智能体配置', async () => {
    render(<App />);

    expect(await screen.findByText('支付系统重构')).toBeTruthy();
    expect(screen.getByLabelText('工作台底部状态')).toBeTruthy();
    expect(screen.queryByLabelText('关键事件')).toBeNull();
    expect(screen.queryByLabelText('项目配置')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '配置' }));

    const 配置区 = within(await screen.findByRole('dialog', { name: '项目配置' }));
    fireEvent.click(配置区.getByRole('tab', { name: '开发' }));
    expect(配置区.getByText('开发智能体')).toBeTruthy();

    fireEvent.change(配置区.getByLabelText('开发智能体系统提示词'), {
      target: { value: '你是开发编码智能体，必须先读代码再修改。' }
    });
    fireEvent.change(配置区.getByLabelText('开发智能体主题色'), {
      target: { value: '#0f766e' }
    });
    fireEvent.change(配置区.getByLabelText('开发智能体供应商'), {
      target: { value: 'deepseek' }
    });
    fireEvent.change(配置区.getByLabelText('开发智能体缓存策略'), {
      target: { value: 'deepseek-prefix-v2' }
    });
    fireEvent.change(配置区.getByLabelText('开发智能体动态后缀策略'), {
      target: { value: 'stable-prefix-dynamic-tail' }
    });
    fireEvent.change(配置区.getByLabelText('开发智能体缓存作用域'), {
      target: { value: 'provider-role' }
    });
    fireEvent.click(配置区.getByRole('button', { name: '保存开发智能体配置' }));

    await waitFor(() => {
      expect(更新角色智能体配置).toHaveBeenCalledWith(
        'demo',
        'developer',
        expect.objectContaining({
          providerId: 'deepseek',
          systemPrompt: '你是开发编码智能体，必须先读代码再修改。',
          themeColor: '#0f766e',
          cachePolicyId: 'deepseek-prefix-v2',
          volatileSuffixStrategy: 'stable-prefix-dynamic-tail',
          cacheScopeStrategy: 'provider-role'
        }),
        'user-product'
      );
    });
    expect(加载角色智能体配置).toHaveBeenCalledWith('demo', 'user-product');
  });

  it('可以在配置中心添加成员并查看用户级审计', async () => {
    render(<App />);

    expect(await screen.findByText('支付系统重构')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '配置' }));

    const 配置区 = within(await screen.findByRole('dialog', { name: '项目配置' }));
    fireEvent.click(配置区.getByRole('tab', { name: '成员' }));

    await waitFor(() => expect(加载项目成员).toHaveBeenCalledWith('demo', 'user-product'));
    expect(await 配置区.findByText('user-dev')).toBeTruthy();
    expect(配置区.getAllByText('DEVELOPER').length).toBeGreaterThan(0);

    fireEvent.click(配置区.getByRole('button', { name: '查看 user-dev 审计' }));
    await waitFor(() => expect(加载用户级审计).toHaveBeenCalledWith('demo', 'user-dev', 'user-product'));
    expect(await 配置区.findByText(/允许执行 npm test/)).toBeTruthy();

    fireEvent.change(配置区.getByLabelText('成员用户 ID'), { target: { value: 'user-tester' } });
    fireEvent.change(配置区.getByLabelText('成员显示名'), { target: { value: '测试同学' } });
    fireEvent.change(配置区.getByLabelText('成员角色'), { target: { value: 'TESTER' } });
    fireEvent.click(配置区.getByRole('button', { name: '添加成员' }));

    await waitFor(() => {
      expect(添加项目成员).toHaveBeenCalledWith('demo', {
        userId: 'user-tester',
        displayName: '测试同学',
        roleKey: 'TESTER'
      }, 'user-product');
    });
    expect(加载项目成员.mock.calls.length).toBeGreaterThanOrEqual(3);
  });

  it('admin 可以在配置中心创建可登录用户并设置项目角色', async () => {
    window.localStorage.setItem('matrixcode.currentActorUserId', 'admin');
    render(<App />);

    expect(await screen.findByText('支付系统重构')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '配置' }));

    const 配置区 = within(await screen.findByRole('dialog', { name: '项目配置' }));
    fireEvent.click(配置区.getByRole('tab', { name: '成员' }));

    await waitFor(() => expect(加载项目成员).toHaveBeenCalledWith('demo', 'admin'));
    fireEvent.change(配置区.getByLabelText('成员用户 ID'), { target: { value: 'user-new' } });
    fireEvent.change(配置区.getByLabelText('新用户用户名'), { target: { value: 'new-user' } });
    fireEvent.change(配置区.getByLabelText('成员显示名'), { target: { value: '新用户' } });
    fireEvent.change(配置区.getByLabelText('新用户邮箱'), { target: { value: 'new@example.com' } });
    fireEvent.change(配置区.getByLabelText('新用户密码'), { target: { value: 'Secret-123' } });
    fireEvent.change(配置区.getByLabelText('成员角色'), { target: { value: 'DEVELOPER' } });
    fireEvent.click(配置区.getByRole('button', { name: '创建用户' }));

    await waitFor(() => {
      expect(创建项目用户).toHaveBeenCalledWith('demo', {
        userId: 'user-new',
        username: 'new-user',
        displayName: '新用户',
        email: 'new@example.com',
        password: 'Secret-123',
        roleKey: 'DEVELOPER'
      }, 'admin');
    });
  });

  it('admin 密码登录后首次刷新责任审计使用登录用户而不是角色默认用户', async () => {
    加载项目工作台.mockRejectedValueOnce(new Error('需要登录态'));
    登录身份.mockResolvedValueOnce({
      userId: 'admin',
      token: 'satoken-admin',
      expiresAt: '2026-06-30T12:00:00Z'
    });

    render(<App />);

    expect(await screen.findByText('欢迎使用 Matrix 智能平台')).toBeTruthy();
    fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'admin-secret' } });
    fireEvent.click(screen.getByRole('button', { name: '登录并加载工作台' }));

    await waitFor(() => expect(加载项目工作台).toHaveBeenLastCalledWith('demo', 'admin'));
    await waitFor(() => expect(加载Agent运行用户审计).toHaveBeenLastCalledWith('demo', 'admin', 50));
    expect(加载Agent运行用户审计).not.toHaveBeenCalledWith('demo', 'user-product', 50);
  });

  it('可以在配置中心治理项目邀请生命周期', async () => {
    加载项目邀请.mockResolvedValue([默认签发项目邀请.invitation]);

    render(<App />);

    expect(await screen.findByText('支付系统重构')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '配置' }));

    const 配置区 = within(await screen.findByRole('dialog', { name: '项目配置' }));
    fireEvent.click(配置区.getByRole('tab', { name: '成员' }));

    expect(await 配置区.findByText('user-tester')).toBeTruthy();
    fireEvent.click(配置区.getByRole('button', { name: '重发' }));

    await waitFor(() => {
      expect(重发项目邀请).toHaveBeenCalledWith(
        'demo',
        'invitation-tester',
        expect.objectContaining({ expiresAt: expect.any(String) }),
        'user-product'
      );
    });
    expect(await 配置区.findByText('reissued-invitation-token')).toBeTruthy();

    fireEvent.click(配置区.getByRole('button', { name: '撤销' }));
    await waitFor(() => {
      expect(撤销项目邀请).toHaveBeenCalledWith('demo', 'invitation-tester', 'user-product');
    });

    fireEvent.click(配置区.getByRole('button', { name: '清理过期邀请' }));
    await waitFor(() => {
      expect(清理过期项目邀请).toHaveBeenCalledWith('demo', 'user-product');
    });
  });

  it('可以在配置中心变更成员角色和状态', async () => {
    render(<App />);

    expect(await screen.findByText('支付系统重构')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '配置' }));

    const 配置区 = within(await screen.findByRole('dialog', { name: '项目配置' }));
    fireEvent.click(配置区.getByRole('tab', { name: '成员' }));

    await waitFor(() => expect(加载项目成员).toHaveBeenCalledWith('demo', 'user-product'));
    fireEvent.change(await 配置区.findByLabelText('user-dev 角色'), { target: { value: 'TESTER' } });

    await waitFor(() => {
      expect(更新项目成员).toHaveBeenCalledWith('demo', 'user-dev', {
        roleKey: 'TESTER',
        status: 'ACTIVE'
      }, 'user-product');
    });

    fireEvent.click(配置区.getByRole('button', { name: '禁用 user-dev' }));
    await waitFor(() => {
      expect(更新项目成员).toHaveBeenCalledWith('demo', 'user-dev', {
        status: 'DISABLED'
      }, 'user-product');
    });

    fireEvent.click(配置区.getByRole('button', { name: '移除 user-dev' }));
    await waitFor(() => {
      expect(更新项目成员).toHaveBeenCalledWith('demo', 'user-dev', {
        status: 'REMOVED'
      }, 'user-product');
    });
  });

  it('可以在配置中心登录和退出当前操作者', async () => {
    render(<App />);

    expect(await screen.findByText('支付系统重构')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '配置' }));

    const 配置区 = within(await screen.findByRole('dialog', { name: '项目配置' }));
    fireEvent.click(配置区.getByRole('tab', { name: '身份' }));

    await waitFor(() => expect(加载项目成员).toHaveBeenCalledWith('demo', 'user-product'));
    fireEvent.change(await 配置区.findByLabelText('登录用户名'), { target: { value: 'user-dev' } });
    expect(配置区.queryByLabelText('登录有效期')).toBeNull();
    fireEvent.change(配置区.getByLabelText('登录密码'), { target: { value: 'test-login-password' } });
    fireEvent.click(配置区.getByRole('button', { name: '登录' }));

    await waitFor(() => {
      expect(登录身份).toHaveBeenCalledWith('demo', {
        username: 'user-dev',
        password: 'test-login-password'
      });
    });
    expect(window.localStorage.getItem('matrixcode.actorToken')).toBe('sa-token');
    expect(window.localStorage.getItem('matrixcode.actorTokenUserId')).toBe('user-dev');
    expect(window.localStorage.getItem('matrixcode.actorTokenExpiresAt')).toBe('2026-06-27T06:00:00Z');
    expect(await 配置区.findByText('user-dev · 动态续期 · 本地估算至 2026-06-27T06:00:00Z')).toBeTruthy();

    fireEvent.click(配置区.getByRole('button', { name: '退出登录' }));
    await waitFor(() => expect(退出身份).toHaveBeenCalledWith('demo', 'user-dev'));
    expect(window.localStorage.getItem('matrixcode.actorToken')).toBeNull();
    expect(window.localStorage.getItem('matrixcode.actorTokenUserId')).toBeNull();
    expect(window.localStorage.getItem('matrixcode.actorTokenExpiresAt')).toBeNull();
  });

  it('登录后可以在配置中心修改当前用户密码', async () => {
    window.localStorage.setItem('matrixcode.actorToken', 'sa-token');
    window.localStorage.setItem('matrixcode.actorTokenUserId', 'user-dev');
    window.localStorage.setItem('matrixcode.actorTokenExpiresAt', '2026-06-27T06:00:00Z');

    render(<App />);

    expect(await screen.findByText('支付系统重构')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '配置' }));

    const 配置区 = within(await screen.findByRole('dialog', { name: '项目配置' }));
    fireEvent.click(配置区.getByRole('tab', { name: '身份' }));

    fireEvent.change(await 配置区.findByLabelText('当前密码'), { target: { value: 'old-secret' } });
    fireEvent.change(配置区.getByLabelText('新密码'), { target: { value: 'new-secret' } });
    fireEvent.change(配置区.getByLabelText('确认新密码'), { target: { value: 'new-secret' } });
    fireEvent.click(配置区.getByRole('button', { name: '修改密码' }));

    await waitFor(() => {
      expect(修改身份密码).toHaveBeenCalledWith('demo', {
        oldPassword: 'old-secret',
        newPassword: 'new-secret'
      }, 'user-dev');
    });
    expect(await 配置区.findByText('密码已更新')).toBeTruthy();
  });

  it('配置中心登录 admin 后会切换成员页为创建用户模式', async () => {
    登录身份.mockResolvedValueOnce({
      userId: 'admin',
      token: 'admin-sa-token',
      expiresAt: '2026-06-27T07:00:00Z'
    });
    加载身份会话列表.mockResolvedValue([]);

    render(<App />);

    expect(await screen.findByText('支付系统重构')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '配置' }));

    const 配置区 = within(await screen.findByRole('dialog', { name: '项目配置' }));
    fireEvent.click(配置区.getByRole('tab', { name: '身份' }));

    fireEvent.change(await 配置区.findByLabelText('登录用户名'), { target: { value: 'admin' } });
    fireEvent.change(配置区.getByLabelText('登录密码'), { target: { value: 'admin-password' } });
    fireEvent.click(配置区.getByRole('button', { name: '登录' }));

    await waitFor(() => {
      expect(登录身份).toHaveBeenCalledWith('demo', {
        username: 'admin',
        password: 'admin-password'
      });
    });
    await waitFor(() => expect(加载项目工作台).toHaveBeenCalledWith('demo', 'admin'));

    fireEvent.click(配置区.getByRole('tab', { name: '成员' }));

    await waitFor(() => expect(加载项目成员).toHaveBeenCalledWith('demo', 'admin'));
    expect(await 配置区.findByRole('button', { name: '创建用户' })).toBeTruthy();
    expect(配置区.getByLabelText('新用户密码')).toBeTruthy();
  });

  it('可以在配置中心刷新续期并踢下线会话', async () => {
    window.localStorage.setItem('matrixcode.actorToken', 'sa-token');
    window.localStorage.setItem('matrixcode.actorTokenUserId', 'user-dev');
    window.localStorage.setItem('matrixcode.actorTokenExpiresAt', '2026-06-27T06:00:00Z');
    render(<App />);

    expect(await screen.findByText('支付系统重构')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '配置' }));

    const 配置区 = within(await screen.findByRole('dialog', { name: '项目配置' }));
    fireEvent.click(配置区.getByRole('tab', { name: '身份' }));

    await waitFor(() => expect(加载身份会话列表).toHaveBeenCalledWith('demo', 'user-dev', 'user-product'));
    expect(await 配置区.findByText('fp-user-dev · WEB · 剩余 3600 秒')).toBeTruthy();

    fireEvent.click(配置区.getByRole('button', { name: '续期当前会话' }));
    await waitFor(() => expect(续期身份会话).toHaveBeenCalledWith('demo', 'user-dev'));
    expect(加载身份会话列表).toHaveBeenLastCalledWith('demo', 'user-dev', 'user-product');

    fireEvent.click(配置区.getByRole('button', { name: '踢下线 user-dev' }));
    await waitFor(() => expect(踢下线身份会话).toHaveBeenCalledWith('demo', 'user-dev', 'user-product'));
    expect(window.localStorage.getItem('matrixcode.actorToken')).toBeNull();
  });

  it('可以打开运行诊断并查看阻塞项和下一步动作', async () => {
    render(<App />);

    expect(await screen.findByText('支付系统重构')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '诊断' }));

    const 诊断 = within(await screen.findByRole('dialog', { name: '运行诊断' }));
    await waitFor(() => expect(加载运行诊断).toHaveBeenCalledWith('demo', 'user-product'));
    expect(await 诊断.findByText('整体状态：失败')).toBeTruthy();
    expect(诊断.getByText('MySQL')).toBeTruthy();
    expect(诊断.getByText(/127.0.0.1:3306 不可达/)).toBeTruthy();
    expect(诊断.getByText('阻塞')).toBeTruthy();
    expect(诊断.getByText('检查 MySQL 服务和网络')).toBeTruthy();
    expect(诊断.getByText('恢复 Milvus 后重新运行诊断')).toBeTruthy();
  });

  it('运行诊断失败时展示失败态并支持重试', async () => {
    加载运行诊断.mockRejectedValueOnce(new Error('network'));

    render(<App />);

    expect(await screen.findByText('支付系统重构')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '诊断' }));

    const 诊断 = within(await screen.findByRole('dialog', { name: '运行诊断' }));
    expect(await 诊断.findByText('运行诊断暂不可用')).toBeTruthy();

    fireEvent.click(诊断.getByRole('button', { name: '重试诊断' }));

    await waitFor(() => expect(加载运行诊断).toHaveBeenCalledTimes(2));
    expect(加载运行诊断).toHaveBeenLastCalledWith('demo', 'user-product');
    expect(await 诊断.findByText('整体状态：失败')).toBeTruthy();
  });

  it('冻结需求后主工作区直接显示交接文档并可在文档中心查看正文', async () => {
    加载项目工作台.mockResolvedValueOnce(冻结后工作台);

    render(<App />);

    fireEvent.click(await screen.findByRole('tab', { name: '文件' }));
    const 文档交接 = within(await screen.findByLabelText('文档交接'));
    expect(文档交接.getByText('产品需求草稿')).toBeTruthy();
    expect(文档交接.getByText(/产品需求文档 · 已冻结 · v1/)).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '文档' }));

    const 文档中心 = within(await screen.findByRole('dialog', { name: '文档中心' }));
    expect(文档中心.getByText('产品需求草稿')).toBeTruthy();
    expect(文档中心.getByText(/引用 ID/)).toBeTruthy();
    expect(文档中心.getByText('支付失败后允许用户重新发起支付。')).toBeTruthy();
  });

  it('待审批命令会显示运维徽标和运行态提醒列表', async () => {
    render(<App />);

    const 角色工作区 = within(await screen.findByRole('navigation', { name: '角色工作区' }));
    expect(角色工作区.getByLabelText('运维待审批 1 项')).toBeTruthy();

    const 运行中心 = await 打开运行中心();
    const 运行态提醒 = within(运行中心.getByLabelText('运行态提醒列表'));
    expect(运行态提醒.getByText('需要审批本地命令')).toBeTruthy();
    expect(运行态提醒.getByText(/ssh prod systemctl restart app/)).toBeTruthy();
  });

  it('服务端运行态提醒在运行中心列表显示未读或已读状态', async () => {
    加载项目工作台.mockResolvedValueOnce({
      ...基础工作台,
      runtimeNotifications: [服务端未读审批提醒, 服务端已读成功提醒]
    });

    render(<App />);

    const 运行中心 = await 打开运行中心();
    const 运行态提醒 = within(运行中心.getByLabelText('运行态提醒列表'));
    expect(运行态提醒.getAllByText('未读').length).toBeGreaterThan(0);
    expect(运行态提醒.getAllByText('已读').length).toBeGreaterThan(0);
  });

  it('运行中心运行态提醒支持未读数量和未读筛选', async () => {
    加载项目工作台.mockResolvedValueOnce({
      ...基础工作台,
      runtimeNotifications: [服务端未读审批提醒, 服务端已读成功提醒]
    });

    render(<App />);

    const 运行中心 = await 打开运行中心();
    const 运行态提醒 = within(运行中心.getByLabelText('运行态提醒列表'));
    expect(运行态提醒.getByText('未读 1')).toBeTruthy();
    fireEvent.click(运行态提醒.getByRole('button', { name: '未读' }));

    expect(运行态提醒.getByText('需要审批本地命令')).toBeTruthy();
    expect(运行态提醒.queryByText('本地命令执行成功')).toBeNull();
  });

  it('未读筛选没有记录时显示空状态', async () => {
    加载项目工作台.mockResolvedValueOnce({
      ...基础工作台,
      runtimeNotifications: [服务端已读审批提醒]
    });

    render(<App />);

    const 运行中心 = await 打开运行中心();
    const 运行态提醒 = within(运行中心.getByLabelText('运行态提醒列表'));
    fireEvent.click(运行态提醒.getByRole('button', { name: '未读' }));

    expect(运行态提醒.getByText('暂无未读提醒')).toBeTruthy();
  });

  it('可以在运行中心把全部运行态提醒标记为已读', async () => {
    加载项目工作台
      .mockResolvedValueOnce({
        ...基础工作台,
        runtimeNotifications: [服务端未读审批提醒, { ...服务端已读成功提醒, readAt: null }]
      })
      .mockResolvedValueOnce({
        ...基础工作台,
        runtimeNotifications: [服务端已读审批提醒, 服务端已读成功提醒]
      });

    render(<App />);

    expect(await screen.findByRole('status', { name: '运行态提醒' })).toBeTruthy();
    let 运行中心 = await 打开运行中心();
    fireEvent.click(within(运行中心.getByLabelText('运行态提醒列表')).getByRole('button', { name: '全部已读' }));

    await waitFor(() => expect(批量标记运行态提醒已读).toHaveBeenCalledWith('demo', 'user-product'));
    await waitFor(() => expect(加载项目工作台).toHaveBeenCalledTimes(2));
    expect(screen.queryByRole('status', { name: '运行态提醒' })).toBeNull();
    运行中心 = within(screen.getByRole('dialog', { name: '运行中心' }));
    expect(within(运行中心.getByLabelText('运行态提醒列表')).getAllByText('已读')).toHaveLength(2);
  });

  it('全部已读失败时保留页面并显示同步错误', async () => {
    加载项目工作台.mockResolvedValueOnce({
      ...基础工作台,
      runtimeNotifications: [服务端未读审批提醒]
    });
    批量标记运行态提醒已读.mockRejectedValueOnce(new Error('network'));

    render(<App />);

    const 运行中心 = await 打开运行中心();
    fireEvent.click(within(运行中心.getByLabelText('运行态提醒列表')).getByRole('button', { name: '全部已读' }));

    expect(await screen.findByText('同步最新工作台失败，请稍后重试')).toBeTruthy();
    expect(screen.getByRole('status', { name: '运行态提醒' })).toBeTruthy();
  });

  it('顶部运行态提醒可以关闭且不会影响审批按钮', async () => {
    render(<App />);

    const 顶部提醒 = await screen.findByRole('status', { name: '运行态提醒' });
    expect(within(顶部提醒).getByText('需要审批本地命令')).toBeTruthy();

    fireEvent.click(within(顶部提醒).getByRole('button', { name: '关闭提醒' }));

    expect(screen.queryByRole('status', { name: '运行态提醒' })).toBeNull();
    const 运行中心 = await 打开运行中心();
    expect(运行中心.getByRole('button', { name: '批准执行' })).toBeTruthy();
  });

  it('关闭顶部运行态提醒时调用已读接口并刷新工作台', async () => {
    加载项目工作台
      .mockResolvedValueOnce({
        ...基础工作台,
        runtimeNotifications: [服务端未读审批提醒]
      })
      .mockResolvedValueOnce({
        ...基础工作台,
        runtimeNotifications: [服务端已读审批提醒]
      });

    render(<App />);

    const 顶部提醒 = await screen.findByRole('status', { name: '运行态提醒' });
    fireEvent.click(within(顶部提醒).getByRole('button', { name: '关闭提醒' }));

    await waitFor(() => expect(标记运行态提醒已读).toHaveBeenCalledWith('demo', 'approval:task-1', 'user-product'));
    await waitFor(() => expect(加载项目工作台).toHaveBeenCalledTimes(2));
    expect(screen.queryByRole('status', { name: '运行态提醒' })).toBeNull();
    const 运行中心 = await 打开运行中心();
    expect(within(运行中心.getByLabelText('运行态提醒列表')).getByText('已读')).toBeTruthy();
    expect(运行中心.getByRole('button', { name: '批准执行' })).toBeTruthy();
  });

  it('切换当前操作者后运行态提醒已读使用所选用户', async () => {
    加载项目工作台
      .mockResolvedValueOnce({
        ...基础工作台,
        runtimeNotifications: [服务端未读审批提醒]
      })
      .mockResolvedValueOnce({
        ...基础工作台,
        runtimeNotifications: [服务端已读审批提醒]
      });

    render(<App />);

    expect(await screen.findByText('支付系统重构')).toBeTruthy();
    fireEvent.change(screen.getByLabelText('当前操作者'), { target: { value: 'user-dev' } });

    const 运行中心 = await 打开运行中心();
    fireEvent.click(within(运行中心.getByLabelText('运行态提醒列表')).getByRole('button', { name: '全部已读' }));

    await waitFor(() => expect(批量标记运行态提醒已读).toHaveBeenCalledWith('demo', 'user-dev'));
  });

  it('收到运行态提醒已读事件后自动刷新工作台', async () => {
    加载项目工作台
      .mockResolvedValueOnce({
        ...基础工作台,
        runtimeNotifications: [服务端未读审批提醒]
      })
      .mockResolvedValueOnce({
        ...基础工作台,
        runtimeNotifications: [服务端已读审批提醒]
      });

    render(<App />);

    expect(await screen.findByRole('status', { name: '运行态提醒' })).toBeTruthy();
    act(() => {
      项目事件处理器?.({
        id: 'runtime-event-read',
        projectId: 'demo',
        type: 'RUNTIME_NOTIFICATION_READ',
        message: '运行态提醒已读',
        occurredAt: '2026-06-25T06:32:00Z'
      });
    });

    await waitFor(() => expect(加载项目工作台).toHaveBeenCalledTimes(2));
    expect(screen.queryByRole('status', { name: '运行态提醒' })).toBeNull();
    const 运行中心 = await 打开运行中心();
    expect(within(运行中心.getByLabelText('运行态提醒列表')).getByText('已读')).toBeTruthy();
  });

  it('运行中心本地执行代理展示运行中任务日志并可以取消', async () => {
    加载项目工作台.mockResolvedValueOnce(长任务工作台).mockResolvedValueOnce({
      ...长任务工作台,
      localExecution: {
        ...长任务工作台.localExecution,
        activeTasks: [],
        recentTasks: [
          {
            ...长任务工作台.localExecution.recentTasks[0],
            status: 'CANCELED',
            canceledBy: 'user-product',
            cancelNote: '用户在运行中心取消任务',
            canceledAt: '2026-06-24T10:11:00Z'
          }
        ],
        recentTaskLogs: []
      }
    });
    取消本地任务.mockResolvedValue({
      ...长任务工作台.localExecution.recentTasks[0],
      status: 'CANCELED',
      canceledBy: 'user-product',
      cancelNote: '用户在运行中心取消任务',
      canceledAt: '2026-06-24T10:11:00Z'
    });

    render(<App />);

    const 运行中心 = await 打开运行中心();
    const 本地执行代理 = within(运行中心.getByLabelText('本地执行代理'));
    expect(本地执行代理.getByText(/最近命令 运行中 · ALLOW · sleep 5/)).toBeTruthy();
    expect(本地执行代理.getByText(/SYSTEM · 任务开始运行/)).toBeTruthy();

    fireEvent.click(本地执行代理.getByRole('button', { name: '取消任务' }));

    await waitFor(() =>
      expect(取消本地任务).toHaveBeenCalledWith('demo', 'task-long', {
        actorId: 'user-product',
        note: '用户在运行中心取消任务'
      })
    );
    await waitFor(() => expect(加载项目工作台).toHaveBeenCalledTimes(2));
  });

  it('运行中本地任务会自动刷新到终态并停止轮询', async () => {
    加载项目工作台.mockResolvedValue(完成长任务工作台).mockResolvedValueOnce(长任务工作台);

    render(<App />);

    let 运行中心 = await 打开运行中心();
    expect(运行中心.getByText(/最近命令 运行中 · ALLOW · sleep 5/)).toBeTruthy();

    await waitFor(() => expect(加载项目工作台).toHaveBeenCalledTimes(2), { timeout: 2500, interval: 100 });

    运行中心 = within(screen.getByRole('dialog', { name: '运行中心' }));
    expect(运行中心.getByText(/最近命令 成功 · ALLOW · sleep 5/)).toBeTruthy();
    expect(运行中心.getByText(/SYSTEM · 任务运行完成，退出码：0/)).toBeTruthy();
    await new Promise((resolve) => window.setTimeout(resolve, 2500));

    expect(加载项目工作台).toHaveBeenCalledTimes(2);
  }, 7000);

  it('收到本地任务运行态事件后自动刷新工作台', async () => {
    加载项目工作台.mockResolvedValueOnce(长任务工作台).mockResolvedValueOnce(完成长任务工作台);

    render(<App />);

    let 运行中心 = await 打开运行中心();
    expect(运行中心.getByText(/最近命令 运行中 · ALLOW · sleep 5/)).toBeTruthy();
    expect(订阅项目事件).toHaveBeenCalledWith(
      'demo',
      expect.objectContaining({ onEvent: expect.any(Function) }),
      'user-product'
    );

    act(() => {
      项目事件处理器?.({
        id: 'runtime-event-1',
        projectId: 'demo',
        type: 'LOCAL_COMMAND_COMPLETED',
        message: '任务运行完成',
        occurredAt: '2026-06-25T06:30:00Z'
      });
    });

    await waitFor(() => expect(加载项目工作台).toHaveBeenCalledTimes(2));
    运行中心 = within(screen.getByRole('dialog', { name: '运行中心' }));
    expect(运行中心.getByText(/最近命令 成功 · ALLOW · sleep 5/)).toBeTruthy();
    expect(运行中心.getByText(/SYSTEM · 任务运行完成，退出码：0/)).toBeTruthy();
    const 本地任务提醒列表 = within(运行中心.getByLabelText('运行态提醒列表'));
    expect(本地任务提醒列表.getByText('本地命令执行成功')).toBeTruthy();
    expect(本地任务提醒列表.getByText(/sleep 5/)).toBeTruthy();
  });

  it('收到 Compose 运行态事件后自动刷新工作台', async () => {
    加载项目工作台.mockResolvedValueOnce(Compose已配置工作台).mockResolvedValueOnce(Compose失败工作台);

    render(<App />);

    let 运行中心 = await 打开运行中心();
    expect(运行中心.getByText(/matrixcode-demo · web · 已配置/)).toBeTruthy();
    await waitFor(() =>
      expect(订阅项目事件).toHaveBeenCalledWith(
        'demo',
        expect.objectContaining({ onEvent: expect.any(Function) }),
        'user-product'
      )
    );

    act(() => {
      项目事件处理器?.({
        id: 'runtime-event-2',
        projectId: 'demo',
        type: 'COMPOSE_OPERATION_RECORDED',
        message: 'Compose 启动失败',
        occurredAt: '2026-06-25T06:31:00Z'
      });
    });

    await waitFor(() => expect(加载项目工作台).toHaveBeenCalledTimes(2));
    运行中心 = within(screen.getByRole('dialog', { name: '运行中心' }));
    const Compose运行态 = within(运行中心.getByLabelText('Compose 运行态'));
    expect(Compose运行态.getByText(/matrixcode-demo · web · 失败/)).toBeTruthy();
    expect(Compose运行态.getByText(/最近操作：失败 · Docker Compose 命令超时/)).toBeTruthy();
    expect(Compose运行态.getByText(/Image nginx:alpine Pulling/)).toBeTruthy();
    const Compose提醒列表 = within(运行中心.getByLabelText('运行态提醒列表'));
    expect(Compose提醒列表.getByText('Compose 动作失败')).toBeTruthy();
    expect(Compose提醒列表.getByText(/Docker Compose 命令超时/)).toBeTruthy();
  });

  it('可以在运行中心本地执行代理卡片拒绝待审批命令', async () => {
    加载项目工作台.mockResolvedValueOnce(基础工作台).mockResolvedValueOnce({
      ...基础工作台,
      localExecution: {
        ...基础工作台.localExecution,
        recentTasks: [
          {
            ...基础工作台.localExecution.recentTasks[0],
            approvalDecision: 'DENY',
            status: 'DENIED',
            approverId: 'user-product',
            approvalNote: '本轮不执行',
            decidedAt: '2026-06-24T10:05:00Z',
            safetyRejectionReason: ''
          }
        ]
      }
    });

    render(<App />);

    const 运行中心 = await 打开运行中心();
    const 本地执行代理 = within(运行中心.getByLabelText('本地执行代理'));
    fireEvent.click(本地执行代理.getByRole('button', { name: '拒绝' }));

    expect(审批本地命令).toHaveBeenCalledWith('demo', 'task-1', {
      actorId: 'user-product',
      decision: 'DENY',
      note: '用户在运行中心拒绝执行'
    });
    await waitFor(() => expect(加载项目工作台).toHaveBeenCalledTimes(2));
  });

  it('可以在运行中心本地执行代理卡片批准执行待审批命令', async () => {
    加载项目工作台.mockResolvedValueOnce(基础工作台).mockResolvedValueOnce({
      ...基础工作台,
      localExecution: {
        ...基础工作台.localExecution,
        recentTasks: [
          {
            ...基础工作台.localExecution.recentTasks[0],
            command: 'git status',
            approvalDecision: 'ALLOW',
            status: 'FAILED',
            exitCode: 128,
            approverId: 'user-product',
            approvalNote: '用户在运行中心批准执行',
            decidedAt: '2026-06-24T10:05:00Z',
            safetyRejectionReason: ''
          }
        ]
      }
    });

    render(<App />);

    const 运行中心 = await 打开运行中心();
    const 本地执行代理 = within(运行中心.getByLabelText('本地执行代理'));
    fireEvent.click(本地执行代理.getByRole('button', { name: '批准执行' }));

    expect(审批本地命令).toHaveBeenCalledWith('demo', 'task-1', {
      actorId: 'user-product',
      decision: 'ALLOW',
      note: '用户在运行中心批准执行'
    });
    await waitFor(() => expect(加载项目工作台).toHaveBeenCalledTimes(2));
  });

  it('运行中心按审批责任人聚合待审批命令', async () => {
    加载项目成员.mockResolvedValueOnce([
      ...默认项目成员,
      {
        id: 'member-ops',
        projectId: 'demo',
        userId: 'user-ops',
        roleKey: 'OPERATIONS',
        status: 'ACTIVE',
        joinedAt: '2026-06-25T13:10:00Z',
        createdAt: '2026-06-25T13:10:00Z',
        updatedAt: '2026-06-25T13:10:00Z'
      }
    ]);

    render(<App />);

    const 运行中心 = await 打开运行中心();
    const 审批责任 = within(运行中心.getByLabelText('审批责任人视图'));
    expect(审批责任.getByText('user-ops · 运维')).toBeTruthy();
    expect(审批责任.getAllByText('待审批 1')).toHaveLength(2);
    expect(审批责任.getByText('已处理 0')).toBeTruthy();
    expect(审批责任.getByText('拒绝/拦截 0')).toBeTruthy();
    expect(审批责任.getByText(/申请人 user-ops/)).toBeTruthy();
    expect(审批责任.getByText(/ssh prod systemctl restart app/)).toBeTruthy();
  });

  it('阶段条可以识别缺陷、验收、运维配置和上线阶段', async () => {
    加载项目工作台.mockResolvedValueOnce({
      ...基础工作台,
      currentStage: '缺陷处理中'
    });
    let view = render(<App />);
    expect(await screen.findByLabelText('测试，当前')).toBeTruthy();
    view.unmount();
    cleanup();

    加载项目工作台.mockResolvedValueOnce({
      ...基础工作台,
      currentStage: '待产品验收'
    });
    view = render(<App />);
    expect(await screen.findByLabelText('验收，当前')).toBeTruthy();
    view.unmount();
    cleanup();

    加载项目工作台.mockResolvedValueOnce({
      ...基础工作台,
      currentStage: '待运维配置'
    });
    view = render(<App />);
    expect(await screen.findByLabelText('部署，当前')).toBeTruthy();
    view.unmount();
    cleanup();

    加载项目工作台.mockResolvedValueOnce({
      ...基础工作台,
      currentStage: '上线准备'
    });
    render(<App />);
    expect(await screen.findByLabelText('上线，当前')).toBeTruthy();
  });

  it('连接失败后可以重试加载项目工作台', async () => {
    加载项目工作台.mockRejectedValueOnce(new Error('网络不可用')).mockResolvedValueOnce(基础工作台);

    render(<App />);

    expect(await screen.findByText('团队服务器暂时不可用')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '重新连接' }));

    expect(await screen.findByText('支付系统重构')).toBeTruthy();
    expect(加载项目工作台).toHaveBeenCalledTimes(2);
  });

  it('未登录启动时可以在连接页登录后加载项目工作台', async () => {
    加载项目工作台.mockRejectedValueOnce(new Error('缺少 Sa-Token 登录态')).mockResolvedValueOnce(基础工作台);

    render(<App />);

    expect(await screen.findByText('欢迎使用 Matrix 智能平台')).toBeTruthy();

    fireEvent.change(screen.getByLabelText('用户名'), { target: { value: 'user-dev' } });
    expect(screen.queryByLabelText('登录有效期')).toBeNull();
    fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'test-login-password' } });
    fireEvent.click(screen.getByRole('button', { name: '登录并加载工作台' }));

    await waitFor(() => {
      expect(登录身份).toHaveBeenCalledWith('demo', {
        username: 'user-dev',
        password: 'test-login-password'
      });
    });
    expect(window.localStorage.getItem('matrixcode.actorToken')).toBe('sa-token');
    expect(window.localStorage.getItem('matrixcode.actorTokenUserId')).toBe('user-dev');
    expect(window.localStorage.getItem('matrixcode.currentActorUserId')).toBe('user-dev');
    expect(await screen.findByText('支付系统重构')).toBeTruthy();
    expect(加载项目工作台).toHaveBeenLastCalledWith('demo', 'user-dev');
  });

  it('产品角色可以生成需求草稿并冻结当前 PRD', async () => {
    加载项目工作台
      .mockResolvedValueOnce(基础工作台)
      .mockResolvedValueOnce(草稿工作台)
      .mockResolvedValueOnce(冻结后工作台);

    render(<App />);
    await 打开角色工作流();

    fireEvent.change(await screen.findByLabelText('产品需求'), {
      target: { value: '支付失败后允许用户重新发起支付。' }
    });
    fireEvent.click(screen.getByRole('button', { name: '生成需求草稿' }));

    expect(生成需求草稿).toHaveBeenCalledWith(
      'demo',
      { requirement: '支付失败后允许用户重新发起支付。' },
      'user-product'
    );
    fireEvent.click(screen.getByLabelText('关闭角色工作流'));
    fireEvent.click(await screen.findByRole('tab', { name: '文件' }));
    const 文档交接 = within(await screen.findByLabelText('文档交接'));
    expect(await 文档交接.findByText('产品需求草稿')).toBeTruthy();

    await 打开角色工作流();
    fireEvent.click(screen.getByRole('button', { name: '冻结当前 PRD' }));

    expect(冻结文档).toHaveBeenCalledWith('demo', 'prd-1', 'user-product');
    await waitFor(() => expect(加载项目工作台).toHaveBeenCalledTimes(3));
  });

  it('多轮产品草稿会优先冻结最新 PRD', async () => {
    加载项目工作台.mockResolvedValueOnce(多轮草稿工作台).mockResolvedValueOnce(冻结后工作台);

    render(<App />);
    await 打开角色工作流();

    expect(await screen.findByText('待冻结文档：产品需求草稿')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '冻结当前 PRD' }));

    expect(冻结文档).toHaveBeenCalledWith('demo', 'prd-new', 'user-product');
  });

  it('产品可以提交验收通过或退回测试', async () => {
    加载项目工作台.mockResolvedValueOnce(测试通过后工作台).mockResolvedValueOnce(测试通过后工作台);

    render(<App />);
    await 打开角色工作流();

    fireEvent.change(await screen.findByLabelText('验收备注'), { target: { value: '验收通过，可以发布。' } });
    fireEvent.click(screen.getByRole('button', { name: '验收通过' }));

    await waitFor(() =>
      expect(提交验收).toHaveBeenCalledWith('demo', {
        accepted: true,
        note: '验收通过，可以发布。',
        returnToRole: '开发'
      }, 'user-product')
    );

    fireEvent.change(screen.getByLabelText('验收备注'), { target: { value: '重试按钮仍需补充提示文案。' } });
    fireEvent.change(screen.getByLabelText('退回角色'), { target: { value: '测试' } });
    fireEvent.click(screen.getByRole('button', { name: '验收不通过' }));

    await waitFor(() =>
      expect(提交验收).toHaveBeenLastCalledWith('demo', {
        accepted: false,
        note: '重试按钮仍需补充提示文案。',
        returnToRole: '测试'
      }, 'user-product')
    );
  });

  it('可以在产品、开发、测试和运维角色面板之间切换', async () => {
    加载项目工作台.mockResolvedValueOnce(冻结后工作台);

    render(<App />);

    const 角色工作区 = within(await screen.findByRole('navigation', { name: '角色工作区' }));
    await 打开角色工作流();
    expect(screen.getByLabelText('产品工作区')).toBeTruthy();
    fireEvent.click(screen.getByLabelText('关闭角色工作流'));
    fireEvent.click(角色工作区.getByRole('button', { name: '开发' }));
    await 打开角色工作流();
    expect(screen.getByLabelText('本地工作区路径')).toBeTruthy();

    fireEvent.click(screen.getByLabelText('关闭角色工作流'));
    fireEvent.click(角色工作区.getByRole('button', { name: '测试' }));
    await 打开角色工作流();
    expect(screen.getByLabelText(/缺陷标题（Bug 标题）/)).toBeTruthy();

    fireEvent.click(screen.getByLabelText('关闭角色工作流'));
    fireEvent.click(角色工作区.getByRole('button', { name: '运维' }));
    await 打开角色工作流();
    expect(screen.getByLabelText(/服务器地址（SSH 地址）/)).toBeTruthy();
  });

  it('产品动作失败时展示后端中文错误', async () => {
    生成需求草稿.mockRejectedValueOnce(new Error('产品需求不能为空'));

    render(<App />);
    await 打开角色工作流();

    fireEvent.change(await screen.findByLabelText('产品需求'), {
      target: { value: '支付失败后允许用户重新发起支付。' }
    });
    fireEvent.click(screen.getByRole('button', { name: '生成需求草稿' }));

    expect(await screen.findByText('产品需求不能为空')).toBeTruthy();
    expect(screen.getByText('支付系统重构')).toBeTruthy();
  });

  it('开发角色填写六项后提交完整交付内容', async () => {
    加载项目工作台.mockResolvedValueOnce(基础工作台).mockResolvedValueOnce(基础工作台);
    render(<App />);

    const 角色工作区 = within(await screen.findByRole('navigation', { name: '角色工作区' }));
    fireEvent.click(角色工作区.getByRole('button', { name: '开发' }));
    await 打开角色工作流();

    const 提交按钮 = screen.getByRole('button', { name: '提交开发交付' }) as HTMLButtonElement;
    expect(提交按钮.disabled).toBe(true);

    const 完整交付 = {
      workspacePath: '/tmp/matrixcode',
      implementationNote: '已完成支付失败重试入口。',
      selfTestResult: '单元测试和界面自测通过。',
      apiDoc: '新增支付重试接口说明。',
      databaseScript: '无需数据库变更。',
      deploymentDoc: '按常规流程部署。'
    };

    fireEvent.change(screen.getByLabelText('本地工作区路径'), { target: { value: 完整交付.workspacePath } });
    fireEvent.change(screen.getByLabelText('实现说明'), { target: { value: 完整交付.implementationNote } });
    fireEvent.change(screen.getByLabelText('自测结果'), { target: { value: 完整交付.selfTestResult } });
    expect(提交按钮.disabled).toBe(true);

    fireEvent.change(screen.getByLabelText('接口文档'), { target: { value: 完整交付.apiDoc } });
    fireEvent.change(screen.getByLabelText('数据库脚本'), { target: { value: 完整交付.databaseScript } });
    fireEvent.change(screen.getByLabelText('部署文档'), { target: { value: 完整交付.deploymentDoc } });

    expect(提交按钮.disabled).toBe(false);
    fireEvent.click(提交按钮);

    expect(提交开发交付).toHaveBeenCalledWith('demo', 完整交付, 'user-dev');
    await waitFor(() => expect(加载项目工作台).toHaveBeenCalledTimes(2));
  });

  it('开发角色可以生成编码智能体执行准备报告', async () => {
    render(<App />);

    const 角色工作区 = within(await screen.findByRole('navigation', { name: '角色工作区' }));
    fireEvent.click(角色工作区.getByRole('button', { name: '开发' }));
    await 打开角色工作流();

    fireEvent.change(screen.getByLabelText('执行目标'), {
      target: { value: '修复支付失败重试' }
    });
    fireEvent.change(screen.getByLabelText('测试命令'), {
      target: { value: 'git status' }
    });
    fireEvent.click(screen.getByRole('button', { name: '生成执行准备' }));

    await waitFor(() =>
      expect(准备编码智能体执行).toHaveBeenCalledWith('demo', 'developer', {
        goal: '修复支付失败重试',
        workspaceId: 'workspace-1',
        actorId: 'user-dev',
        testCommand: 'git status'
      })
    );
    expect(await screen.findByText('执行准备报告')).toBeTruthy();
    expect(screen.getByText('代码编辑')).toBeTruthy();
    expect(screen.getByText('需要审批')).toBeTruthy();
    expect(screen.getByText('测试命令已提交到本地执行服务')).toBeTruthy();
    expect(screen.getByText('1 file changed')).toBeTruthy();
  });

  it('开发执行准备默认使用最近访问的授权工作区', async () => {
    const 多工作区工作台: ProjectWorkbench = {
      ...基础工作台,
      localExecution: {
        ...基础工作台.localExecution,
        workspaces: [
          {
            ...基础工作台.localExecution.workspaces[0],
            id: 'workspace-old',
            rootPath: '/tmp/matrixcode-old',
            lastAccessedAt: '2026-06-24T09:00:00Z'
          },
          {
            ...基础工作台.localExecution.workspaces[0],
            id: 'workspace-current',
            rootPath: '/repo/matrixcode',
            lastAccessedAt: '2026-06-25T09:00:00Z'
          }
        ]
      }
    };
    加载项目工作台.mockResolvedValue(多工作区工作台);
    render(<App />);

    const 角色工作区 = within(await screen.findByRole('navigation', { name: '角色工作区' }));
    fireEvent.click(角色工作区.getByRole('button', { name: '开发' }));
    await 打开角色工作流();
    fireEvent.change(screen.getByLabelText('执行目标'), {
      target: { value: '验证最近工作区默认选择' }
    });
    fireEvent.click(screen.getByRole('button', { name: '生成执行准备' }));

    await waitFor(() =>
      expect(准备编码智能体执行).toHaveBeenCalledWith('demo', 'developer', {
        goal: '验证最近工作区默认选择',
        workspaceId: 'workspace-current',
        actorId: 'user-dev',
        testCommand: 'git status'
      })
    );
  });

  it('开发角色确认后可以应用受控 Patch', async () => {
    render(<App />);

    const 角色工作区 = within(await screen.findByRole('navigation', { name: '角色工作区' }));
    fireEvent.click(角色工作区.getByRole('button', { name: '开发' }));
    await 打开角色工作流();
    fireEvent.change(screen.getByLabelText('Patch 相对路径'), {
      target: { value: 'src/App.java' }
    });
    fireEvent.change(screen.getByLabelText('期望当前内容'), {
      target: { value: 'class App {}\n' }
    });
    fireEvent.change(screen.getByLabelText('目标内容'), {
      target: { value: 'class App { void run() {} }\n' }
    });
    fireEvent.change(screen.getByLabelText('变更说明'), {
      target: { value: '补充入口' }
    });
    fireEvent.click(screen.getByLabelText('确认应用 patch'));
    fireEvent.click(screen.getByRole('button', { name: '应用受控 Patch' }));

    await waitFor(() =>
      expect(应用编码智能体Patch).toHaveBeenCalledWith('demo', 'developer', {
        workspaceId: 'workspace-1',
        actorId: 'user-dev',
        relativePath: 'src/App.java',
        expectedContent: 'class App {}\n',
        nextContent: 'class App { void run() {} }\n',
        summary: '补充入口',
        approved: true
      })
    );
    expect(await screen.findByText('Patch 应用结果')).toBeTruthy();
    expect(screen.getByText('写入 28 字节')).toBeTruthy();
    expect(screen.getByText('1 file changed')).toBeTruthy();
  });

  it('开发角色可以把 Patch 和测试任务记录为交付回溯', async () => {
    render(<App />);

    const 角色工作区 = within(await screen.findByRole('navigation', { name: '角色工作区' }));
    fireEvent.click(角色工作区.getByRole('button', { name: '开发' }));
    await 打开角色工作流();
    fireEvent.change(screen.getByLabelText('执行目标'), {
      target: { value: '修复支付失败重试' }
    });
    fireEvent.click(screen.getByRole('button', { name: '生成执行准备' }));
    expect(await screen.findByText('执行准备报告')).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Patch 相对路径'), {
      target: { value: 'src/App.java' }
    });
    fireEvent.change(screen.getByLabelText('期望当前内容'), {
      target: { value: 'class App {}\n' }
    });
    fireEvent.change(screen.getByLabelText('目标内容'), {
      target: { value: 'class App { void run() {} }\n' }
    });
    fireEvent.change(screen.getByLabelText('变更说明'), {
      target: { value: '补充入口' }
    });
    fireEvent.click(screen.getByLabelText('确认应用 patch'));
    fireEvent.click(screen.getByRole('button', { name: '应用受控 Patch' }));
    expect(await screen.findByText('Patch 应用结果')).toBeTruthy();

    fireEvent.change(screen.getByLabelText('交付结论'), {
      target: { value: '测试通过，可以交付' }
    });
    fireEvent.click(screen.getByRole('button', { name: '记录交付回溯' }));

    await waitFor(() =>
      expect(记录编码智能体交付回溯).toHaveBeenCalledWith('demo', 'developer', {
        workspaceId: 'workspace-1',
        actorId: 'user-dev',
        goal: '修复支付失败重试',
        relativePath: 'src/App.java',
        patchSummary: '补充入口',
        diffSummary: '1 file changed',
        testTaskId: 'task-prepare-1',
        testTaskStatus: 'APPROVAL_PENDING',
        testCommand: 'git status',
        deliveryConclusion: '测试通过，可以交付'
      })
    );
    expect(await screen.findByText('交付回溯已记录')).toBeTruthy();
    expect(screen.getByText('doc-handoff-1')).toBeTruthy();
  });

  it('测试可以创建 Bug 并提交测试报告', async () => {
    加载项目工作台.mockResolvedValue(开发交付后工作台);
    render(<App />);

    const 角色工作区 = within(await screen.findByRole('navigation', { name: '角色工作区' }));
    fireEvent.click(角色工作区.getByRole('button', { name: '测试' }));
    await 打开角色工作流();

    const 缺陷内容 = {
      title: '支付失败后没有重试按钮',
      severity: 'HIGH',
      steps: '触发支付失败。',
      expected: '显示重试按钮。',
      actual: '只显示失败提示。'
    };

    fireEvent.change(screen.getByLabelText('缺陷标题（Bug 标题）'), { target: { value: 缺陷内容.title } });
    fireEvent.change(screen.getByLabelText('严重级别'), { target: { value: 缺陷内容.severity } });
    fireEvent.change(screen.getByLabelText('复现步骤'), { target: { value: 缺陷内容.steps } });
    fireEvent.change(screen.getByLabelText('期望结果'), { target: { value: 缺陷内容.expected } });
    fireEvent.change(screen.getByLabelText('实际结果'), { target: { value: 缺陷内容.actual } });
    fireEvent.click(screen.getByRole('button', { name: '记录 Bug' }));

    await waitFor(() =>
      expect(创建缺陷).toHaveBeenCalledWith('demo', {
        ...缺陷内容,
        createdByRole: '测试',
        currentOwnerRole: '开发'
      }, 'user-tester')
    );

    fireEvent.change(screen.getByLabelText('测试报告'), { target: { value: '测试通过，可以进入上线准备。' } });
    fireEvent.click(screen.getByRole('button', { name: '提交测试报告' }));

    await waitFor(() =>
      expect(提交测试报告).toHaveBeenCalledWith('demo', { report: '测试通过，可以进入上线准备。' }, 'user-tester')
    );
  });

  it('测试可以流转已有 Bug 状态', async () => {
    加载项目工作台.mockResolvedValue({
      ...开发交付后工作台,
      bugs: [
        {
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
        }
      ]
    });
    render(<App />);

    const 角色工作区 = within(await screen.findByRole('navigation', { name: '角色工作区' }));
    fireEvent.click(角色工作区.getByRole('button', { name: '测试' }));
    await 打开角色工作流();

    fireEvent.change(screen.getByLabelText('目标状态'), { target: { value: 'CLOSED' } });
    fireEvent.change(screen.getByLabelText('流转备注'), { target: { value: '回归通过，关闭缺陷。' } });
    fireEvent.click(screen.getByRole('button', { name: '流转 Bug 状态' }));

    await waitFor(() =>
      expect(流转缺陷).toHaveBeenCalledWith('demo', 'bug-1', {
        nextStatus: 'CLOSED',
        note: '回归通过，关闭缺陷。'
      }, 'user-tester')
    );
  });

  it('运维可以配置环境但不会触发远程执行', async () => {
    加载项目工作台.mockResolvedValue(测试通过后工作台);
    render(<App />);

    const 角色工作区 = within(await screen.findByRole('navigation', { name: '角色工作区' }));
    fireEvent.click(角色工作区.getByRole('button', { name: '运维' }));
    await 打开角色工作流();

    fireEvent.change(screen.getByLabelText('环境名称'), { target: { value: '预发环境' } });
    fireEvent.change(screen.getByLabelText('环境地址'), { target: { value: 'https://pre.example.com' } });
    fireEvent.change(screen.getByLabelText('服务器地址（SSH 地址）'), { target: { value: 'deploy@example.com' } });
    fireEvent.change(screen.getByLabelText('部署说明'), { target: { value: '按发布单执行部署。' } });
    fireEvent.change(screen.getByLabelText('健康检查地址'), { target: { value: 'https://pre.example.com/health' } });
    fireEvent.change(screen.getByLabelText('回滚说明'), { target: { value: '回滚到上一稳定版本。' } });
    fireEvent.click(screen.getByRole('button', { name: '保存部署目标' }));

    await waitFor(() =>
      expect(配置部署目标).toHaveBeenCalledWith(
        'demo',
        expect.objectContaining({
          sshAddress: 'deploy@example.com'
        }),
        'user-ops'
      )
    );
  });

  it('运维可以运行健康检查、记录部署和记录回滚', async () => {
    加载项目工作台.mockResolvedValue(部署运行工作台);
    render(<App />);

    const 角色工作区 = within(await screen.findByRole('navigation', { name: '角色工作区' }));
    fireEvent.click(角色工作区.getByRole('button', { name: '运维' }));
    await 打开角色工作流();

    fireEvent.click(screen.getByRole('button', { name: '运行健康检查' }));
    await waitFor(() =>
      expect(运行部署健康检查).toHaveBeenCalledWith('demo', 'target/1', { actorId: 'user-ops' })
    );

    fireEvent.change(screen.getByLabelText('部署结果'), { target: { value: 'SUCCEEDED' } });
    fireEvent.change(screen.getByLabelText('部署记录说明'), { target: { value: '预发部署完成。' } });
    fireEvent.click(screen.getByRole('button', { name: '记录部署' }));
    await waitFor(() =>
      expect(记录部署操作).toHaveBeenCalledWith('demo', 'target/1', {
        actorId: 'user-ops',
        type: 'DEPLOYMENT',
        status: 'SUCCEEDED',
        note: '预发部署完成。'
      })
    );

    fireEvent.change(screen.getByLabelText('回滚结果'), { target: { value: 'RECORDED' } });
    fireEvent.change(screen.getByLabelText('回滚记录说明'), { target: { value: '记录回滚方案。' } });
    fireEvent.click(screen.getByRole('button', { name: '记录回滚' }));
    await waitFor(() =>
      expect(记录部署操作).toHaveBeenLastCalledWith('demo', 'target/1', {
        actorId: 'user-ops',
        type: 'ROLLBACK',
        status: 'RECORDED',
        note: '记录回滚方案。'
      })
    );
  });

  it('运维可以配置 Compose 演示环境并触发运行态动作', async () => {
    加载项目工作台.mockResolvedValueOnce(部署运行工作台).mockResolvedValue(Compose运行工作台);
    render(<App />);

    const 角色工作区 = within(await screen.findByRole('navigation', { name: '角色工作区' }));
    fireEvent.click(角色工作区.getByRole('button', { name: '运维' }));
    await 打开角色工作流();

    expect(await screen.findByText('Compose 演示环境')).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Compose 文件'), { target: { value: 'compose.yml' } });
    fireEvent.change(screen.getByLabelText('Compose 项目名'), { target: { value: 'matrixcode-demo' } });
    fireEvent.change(screen.getByLabelText('服务名'), { target: { value: 'web' } });
    fireEvent.click(screen.getByRole('button', { name: '保存 Compose 环境' }));

    await waitFor(() =>
      expect(配置Compose环境).toHaveBeenCalledWith('demo', 'target/1', {
        workspaceId: 'workspace-1',
        composeFilePath: 'compose.yml',
        projectName: 'matrixcode-demo',
        serviceName: 'web'
      }, 'user-ops')
    );

    const Compose演示环境 = within(await screen.findByLabelText('Compose 演示环境'));
    expect(await Compose演示环境.findByText(/matrixcode-demo · web · 运行中/)).toBeTruthy();
    fireEvent.click(Compose演示环境.getByRole('button', { name: '校验配置' }));
    await waitFor(() =>
      expect(校验Compose环境).toHaveBeenCalledWith('demo', 'compose/1', { actorId: 'user-ops' })
    );

    fireEvent.click(Compose演示环境.getByRole('button', { name: '启动演示' }));
    await waitFor(() =>
      expect(启动Compose环境).toHaveBeenCalledWith('demo', 'compose/1', { actorId: 'user-ops' })
    );

    fireEvent.click(Compose演示环境.getByRole('button', { name: '采集日志' }));
    await waitFor(() =>
      expect(采集Compose日志).toHaveBeenCalledWith('demo', 'compose/1', { actorId: 'user-ops' })
    );

    fireEvent.click(Compose演示环境.getByRole('button', { name: '停止演示' }));
    await waitFor(() =>
      expect(停止Compose环境).toHaveBeenCalledWith('demo', 'compose/1', { actorId: 'user-ops' })
    );
  });

  it('运行中心部署状态展示最近健康检查、部署和回滚记录', async () => {
    加载项目工作台.mockResolvedValueOnce(部署运行工作台);
    render(<App />);

    const 运行中心 = await 打开运行中心();
    const 部署状态 = within(运行中心.getByLabelText('部署状态'));
    expect(部署状态.getByText(/健康：健康 · HTTP 200 · 18 ms/)).toBeTruthy();
    expect(部署状态.getByText(/部署：成功 · 预发部署完成。/)).toBeTruthy();
    expect(部署状态.getByText(/回滚：已记录 · 记录回滚方案。/)).toBeTruthy();
  });

  it('运行中心 Compose 运行态展示最近操作和日志摘录', async () => {
    加载项目工作台.mockResolvedValueOnce(Compose运行工作台);
    render(<App />);

    const 运行中心 = await 打开运行中心();
    const Compose运行态 = within(运行中心.getByLabelText('Compose 运行态'));
    expect(Compose运行态.getByText(/matrixcode-demo · web · 运行中/)).toBeTruthy();
    expect(Compose运行态.getByText(/最近操作：成功 · Compose 日志已采集/)).toBeTruthy();
    expect(Compose运行态.getByText(/web ready/)).toBeTruthy();
  });

  it('动作成功但刷新失败时保留当前工作台并提示同步失败', async () => {
    加载项目工作台
      .mockResolvedValueOnce(基础工作台)
      .mockRejectedValueOnce(new Error('团队服务器连接失败'));

    render(<App />);
    await 打开角色工作流();

    fireEvent.change(await screen.findByLabelText('产品需求'), {
      target: { value: '支付失败后允许用户重新发起支付。' }
    });
    fireEvent.click(screen.getByRole('button', { name: '生成需求草稿' }));

    expect(await screen.findByText('同步最新工作台失败，请稍后重试')).toBeTruthy();
    expect(screen.getByText('支付系统重构')).toBeTruthy();
    expect(screen.getByLabelText('产品需求')).toBeTruthy();
    expect(screen.queryByText('团队服务器暂时不可用')).toBeNull();
  });

  it('开发交付成功但刷新失败时保留表单内容', async () => {
    加载项目工作台
      .mockResolvedValueOnce(基础工作台)
      .mockRejectedValueOnce(new Error('团队服务器连接失败'));
    render(<App />);

    const 角色工作区 = within(await screen.findByRole('navigation', { name: '角色工作区' }));
    fireEvent.click(角色工作区.getByRole('button', { name: '开发' }));
    await 打开角色工作流();

    const 完整交付 = {
      workspacePath: '/tmp/matrixcode',
      implementationNote: '已完成支付失败重试入口。',
      selfTestResult: '单元测试和界面自测通过。',
      apiDoc: '新增支付重试接口说明。',
      databaseScript: '无需数据库变更。',
      deploymentDoc: '按常规流程部署。'
    };

    fireEvent.change(screen.getByLabelText('本地工作区路径'), { target: { value: 完整交付.workspacePath } });
    fireEvent.change(screen.getByLabelText('实现说明'), { target: { value: 完整交付.implementationNote } });
    fireEvent.change(screen.getByLabelText('自测结果'), { target: { value: 完整交付.selfTestResult } });
    fireEvent.change(screen.getByLabelText('接口文档'), { target: { value: 完整交付.apiDoc } });
    fireEvent.change(screen.getByLabelText('数据库脚本'), { target: { value: 完整交付.databaseScript } });
    fireEvent.change(screen.getByLabelText('部署文档'), { target: { value: 完整交付.deploymentDoc } });

    fireEvent.click(screen.getByRole('button', { name: '提交开发交付' }));

    expect(await screen.findByText('同步最新工作台失败，请稍后重试')).toBeTruthy();
    expect((screen.getByLabelText('本地工作区路径') as HTMLInputElement).value).toBe(完整交付.workspacePath);
    expect((screen.getByLabelText('实现说明') as HTMLTextAreaElement).value).toBe(完整交付.implementationNote);
    expect(screen.getByText('支付系统重构')).toBeTruthy();
  });
});
