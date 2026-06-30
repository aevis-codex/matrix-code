import type {
  ExecutionTask,
  ExecutionTaskStatus,
  ProjectWorkbench,
  RuntimeNotification,
  RuntimeNotificationLevel
} from './api/client';

export type { RuntimeNotification, RuntimeNotificationLevel };

const terminalTaskStatuses = new Set<ExecutionTaskStatus>(['SUCCESS', 'FAILED', 'CANCELED']);

function taskOccurredAt(task: ExecutionTask): string {
  return task.canceledAt ?? task.createdAt;
}

function notificationWeight(level: RuntimeNotificationLevel): number {
  if (level === 'ACTION') {
    return 4;
  }
  if (level === 'ERROR') {
    return 3;
  }
  if (level === 'WARNING') {
    return 2;
  }
  return 1;
}

function compareNotifications(left: RuntimeNotification, right: RuntimeNotification): number {
  const leftTime = new Date(left.occurredAt).getTime();
  const rightTime = new Date(right.occurredAt).getTime();

  if (Number.isFinite(leftTime) && Number.isFinite(rightTime) && leftTime !== rightTime) {
    return rightTime - leftTime;
  }

  return notificationWeight(right.level) - notificationWeight(left.level);
}

function taskNotification(task: ExecutionTask): RuntimeNotification | null {
  if (task.status === 'APPROVAL_PENDING') {
    return {
      id: `approval:${task.taskId}`,
      level: 'ACTION',
      title: '需要审批本地命令',
      message: task.command,
      occurredAt: task.createdAt
    };
  }

  if (!terminalTaskStatuses.has(task.status)) {
    return null;
  }

  if (task.status === 'FAILED') {
    return {
      id: `local-task:${task.taskId}:FAILED`,
      level: 'ERROR',
      title: '本地命令执行失败',
      message: task.command,
      occurredAt: taskOccurredAt(task)
    };
  }

  if (task.status === 'CANCELED') {
    return {
      id: `local-task:${task.taskId}:CANCELED`,
      level: 'WARNING',
      title: '本地命令已取消',
      message: task.command,
      occurredAt: taskOccurredAt(task)
    };
  }

  return {
    id: `local-task:${task.taskId}:SUCCESS`,
    level: 'SUCCESS',
    title: '本地命令执行成功',
    message: task.command,
    occurredAt: taskOccurredAt(task)
  };
}

export function countPendingApprovals(workbench: ProjectWorkbench): number {
  return workbench.localExecution.recentTasks.filter((task) => task.status === 'APPROVAL_PENDING').length;
}

export function deriveRuntimeNotifications(workbench: ProjectWorkbench): RuntimeNotification[] {
  const taskNotifications = workbench.localExecution.recentTasks
    .map(taskNotification)
    .filter((notification): notification is RuntimeNotification => Boolean(notification));

  const composeNotifications = workbench.composeRuntimeViews
    .map((view): RuntimeNotification | null => {
      const operation = view.latestOperation;
      if (!operation) {
        return null;
      }

      return {
        id: `compose:${operation.id}:${operation.status}`,
        level: operation.status === 'FAILED' ? 'ERROR' : 'SUCCESS',
        title: operation.status === 'FAILED' ? 'Compose 动作失败' : 'Compose 动作成功',
        message: operation.summary,
        occurredAt: operation.createdAt
      };
    })
    .filter((notification): notification is RuntimeNotification => Boolean(notification));

  return [...taskNotifications, ...composeNotifications].sort(compareNotifications).slice(0, 5);
}

export function runtimeNotificationsFromWorkbench(workbench: ProjectWorkbench): RuntimeNotification[] {
  if (Array.isArray(workbench.runtimeNotifications)) {
    return workbench.runtimeNotifications;
  }

  return deriveRuntimeNotifications(workbench);
}

export function latestVisibleNotification(
  notifications: RuntimeNotification[],
  dismissedIds: Set<string>
): RuntimeNotification | null {
  return notifications.find((notification) => !notification.readAt && !dismissedIds.has(notification.id)) ?? null;
}
