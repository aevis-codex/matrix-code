import { describe, expect, it } from 'vitest';
import type { ExecutionTask, ProjectWorkbench } from './api/client';
import {
  countPendingApprovals,
  deriveRuntimeNotifications,
  latestVisibleNotification,
  runtimeNotificationsFromWorkbench
} from './runtimeNotifications';

const baseWorkbench: ProjectWorkbench = {
  projectId: 'demo',
  projectName: '支付系统重构',
  currentStage: '上线准备',
  roles: [],
  documents: [],
  bugs: [],
  deploymentTargets: [],
  deploymentRuntimeSummaries: [],
  composeEnvironments: [],
  composeRuntimeViews: [],
  metrics: { cacheHitRate: 0, sessionTokens: 0, eventCount: 0, documentCount: 0, openBugCount: 0 },
  modelGateway: {
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
  },
  localExecution: {
    workspaces: [],
    recentFileOperations: [],
    recentTasks: [],
    activeTasks: [],
    recentTaskLogs: [],
    recentGitDiff: null,
    recentAuditRecords: []
  },
  events: []
};

const baseTask: ExecutionTask = {
  taskId: 'task-1',
  projectId: 'demo',
  workspaceId: 'workspace-1',
  actorId: 'user-dev',
  toolType: 'SHELL',
  command: 'git status',
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
  createdAt: '2026-06-25T06:00:00Z'
};

function serverNotification(
  id: string,
  level: 'ACTION' | 'SUCCESS' | 'WARNING' | 'ERROR',
  title: string,
  readAt: string | null
) {
  return {
    id,
    projectId: 'demo',
    level,
    title,
    message: 'git status',
    sourceType: 'APPROVAL' as const,
    sourceId: 'task-1',
    occurredAt: '2026-06-25T06:00:00Z',
    readAt
  };
}

describe('运行态提醒派生', () => {
  it('从待审批任务派生操作提醒并统计待审批数量', () => {
    const workbench: ProjectWorkbench = {
      ...baseWorkbench,
      localExecution: {
        ...baseWorkbench.localExecution,
        recentTasks: [baseTask]
      }
    };

    const notifications = deriveRuntimeNotifications(workbench);

    expect(countPendingApprovals(workbench)).toBe(1);
    expect(notifications[0]).toMatchObject({
      id: 'approval:task-1',
      level: 'ACTION',
      title: '需要审批本地命令',
      message: 'git status'
    });
  });

  it('按时间倒序保留最近五条本地任务和 Compose 提醒', () => {
    const workbench: ProjectWorkbench = {
      ...baseWorkbench,
      localExecution: {
        ...baseWorkbench.localExecution,
        recentTasks: Array.from({ length: 6 }, (_, index) => ({
          ...baseTask,
          taskId: `task-${index}`,
          command: `echo ${index}`,
          approvalDecision: 'ALLOW',
          approverId: 'user-reviewer',
          decidedAt: '2026-06-25T06:00:00Z',
          status: index % 2 === 0 ? 'SUCCESS' : 'FAILED',
          exitCode: index % 2 === 0 ? 0 : 1,
          createdAt: `2026-06-25T06:0${index}:00Z`
        }))
      },
      composeRuntimeViews: [
        {
          environmentId: 'compose-1',
          targetId: 'target-1',
          status: 'FAILED',
          composeFilePath: 'compose.yaml',
          projectName: 'matrixcode-demo',
          serviceName: 'web',
          latestOperation: {
            id: 'compose-op-1',
            projectId: 'demo',
            environmentId: 'compose-1',
            actorId: 'user-ops',
            type: 'START',
            status: 'FAILED',
            summary: 'Docker Compose 命令超时',
            logExcerpt: 'Image nginx:alpine Pulling',
            createdAt: '2026-06-25T06:06:30Z'
          }
        }
      ]
    };

    const notifications = deriveRuntimeNotifications(workbench);

    expect(notifications).toHaveLength(5);
    expect(notifications[0]).toMatchObject({
      id: 'compose:compose-op-1:FAILED',
      level: 'ERROR',
      title: 'Compose 动作失败',
      message: 'Docker Compose 命令超时'
    });
    expect(notifications[1].id).toBe('local-task:task-5:FAILED');
  });

  it('可以跳过当前会话已关闭的顶部提醒', () => {
    const notifications = [
      {
        id: 'n-1',
        level: 'ERROR' as const,
        title: '失败',
        message: '命令失败',
        occurredAt: '2026-06-25T06:00:00Z'
      },
      {
        id: 'n-2',
        level: 'SUCCESS' as const,
        title: '成功',
        message: '命令成功',
        occurredAt: '2026-06-25T05:00:00Z'
      }
    ];

    expect(latestVisibleNotification(notifications, new Set(['n-1']))?.id).toBe('n-2');
  });

  it('优先使用服务端运行态提醒', () => {
    const workbench: ProjectWorkbench = {
      ...baseWorkbench,
      localExecution: {
        ...baseWorkbench.localExecution,
        recentTasks: [baseTask]
      },
      runtimeNotifications: [serverNotification('server-1', 'ACTION', '服务端提醒', null)]
    };

    const notifications = runtimeNotificationsFromWorkbench(workbench);

    expect(notifications).toHaveLength(1);
    expect(notifications[0]).toMatchObject({
      id: 'server-1',
      title: '服务端提醒',
      readAt: null
    });
  });

  it('旧工作台响应缺少服务端提醒时继续使用本地派生提醒', () => {
    const workbench: ProjectWorkbench = {
      ...baseWorkbench,
      localExecution: {
        ...baseWorkbench.localExecution,
        recentTasks: [baseTask]
      }
    };

    expect(runtimeNotificationsFromWorkbench(workbench)[0].id).toBe('approval:task-1');
  });

  it('跳过服务端已读提醒选择下一条未读提醒', () => {
    const notifications = [
      serverNotification('n-1', 'ERROR', '已读失败', '2026-06-25T06:01:00Z'),
      serverNotification('n-2', 'SUCCESS', '未读成功', null)
    ];

    expect(latestVisibleNotification(notifications, new Set())?.id).toBe('n-2');
  });
});
