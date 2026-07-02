import { type FormEvent, useEffect, useState } from 'react';
import {
  applyCodingAgentPatch,
  cancelLocalExecutionTask,
  captureComposeLogs,
  claimAgentRun,
  claimNextAgentRun,
  configureComposeEnvironment,
  configureDeploymentTarget,
  createRoleModelRequestStream,
  createBug,
  createProductDrafts,
  decideLocalCommandApproval,
  freezeDocument,
  loadAgentRunEvents,
  loadAgentRunUserAudit,
  loadAgentRuns,
  loadRoleAgentConfigs,
  loadProjectMembers,
  loadProjectWorkbench,
  loadRuntimeDiagnostics,
  loginActorSession,
  markAllRuntimeNotificationsRead,
  markRuntimeNotificationRead,
  prepareCodingAgentExecution,
  recordCodingAgentHandoff,
  recordDeploymentOperation,
  renewActorSession,
  retryAgentRun,
  runDeploymentHealthCheck,
  startComposeEnvironment,
  stopComposeEnvironment,
  subscribeProjectEvents,
  submitAcceptance,
  submitDeveloperDelivery,
  submitTestReport,
  transitionBug,
  updateRoleAgentConfig,
  validateComposeEnvironment,
  type ComposeEnvironmentInput,
  type AgentRunEventRecord,
  type AgentRunRecord,
  type AgentRunUserAuditReport,
  type ContextBlock,
  type DocumentSummary,
  type DocumentState,
  type DocumentType,
  type DeploymentOperationInput,
  type CodingAgentExecutionPlan,
  type CodingAgentPatchResult,
  type ModelResponse,
  type ModelRole,
  type ProjectEvent,
  type ProjectMember,
  type ProjectWorkbench,
  type RoleAgentConfig,
  type RoleAgentConfigInput,
  type RuntimeCheckStatus,
  type RuntimeDiagnosticsReport
} from './api/client';
import {
  DeveloperPanel,
  type DeveloperDeliveryInput,
  type DeveloperExecutionInput,
  type DeveloperHandoffInput,
  type DeveloperPatchInput
} from './components/DeveloperPanel';
import { OperationsCenterDialog, WorkbenchStatusBar } from './components/InspectorPanel';
import { OpsPanel, type OpsDeploymentTargetInput } from './components/OpsPanel';
import { ProductPanel, type ProductAcceptanceInput } from './components/ProductPanel';
import { RoleAgentConfigDialog } from './components/RoleAgentConfigDialog';
import { RoleSwitcher } from './components/RoleSwitcher';
import { TesterPanel, type TesterBugInput, type TesterBugTransitionInput, type TesterReportInput } from './components/TesterPanel';
import { countPendingApprovals, latestVisibleNotification, runtimeNotificationsFromWorkbench } from './runtimeNotifications';
import './App.css';

type StageView = {
  name: string;
  number: string;
  status: 'done' | 'active' | 'pending';
  statusLabel: '已完成' | '当前' | '待处理';
};

type WorkbenchState =
  | { type: 'loading' }
  | {
      type: 'ready';
      workbench: ProjectWorkbench;
      agentRuns: AgentRunRecord[];
      agentRunEvents: AgentRunEventRecord[];
      agentRunUserAudit: AgentRunUserAuditReport;
      roleAgentConfigs: RoleAgentConfig[];
      projectMembers: ProjectMember[];
      refreshing: boolean;
      syncError?: string;
    }
  | { type: 'error'; message: string };

type RuntimeNotificationFilter = 'all' | 'unread';
type WorkspaceContextTab = 'overview' | 'files' | 'changes';
type ComposerApprovalMode = 'ask' | 'auto' | 'yolo';
type ComposerReasoningEffort = 'auto' | 'high' | 'max';
type ComposerIntentState = {
  plan: boolean;
  goal: boolean;
  tokenEconomy: boolean;
};
type ComposerSubmitState =
  | { status: 'idle' }
  | { status: 'sending'; submittedInstruction: string; submittedAt: string }
  | { status: 'streaming'; submittedInstruction: string; submittedAt: string; partialAnswer: string }
  | { status: 'answered'; submittedInstruction: string; submittedAt: string; response: ModelResponse }
  | { status: 'error'; submittedInstruction: string; submittedAt: string; message: string };

type RuntimeDiagnosticsState =
  | { type: 'idle' }
  | { type: 'loading' }
  | { type: 'ready'; report: RuntimeDiagnosticsReport }
  | { type: 'error' };

const workflowStages = [
  { name: '需求', aliases: ['需求录入', '需求草稿', '需求冻结'] },
  { name: '开发', aliases: ['开发中', '开发', '验收退回开发'] },
  { name: '测试', aliases: ['测试中', '测试执行', '测试', '缺陷处理中', '验收退回测试'] },
  { name: '验收', aliases: ['待产品验收', '产品验收', '验收'] },
  { name: '部署', aliases: ['待运维配置', '运维配置', '部署'] },
  { name: '上线', aliases: ['上线准备', '上线'] }
];
const syncFailureMessage = '同步最新工作台失败，请稍后重试';
const currentActorStorageKey = 'matrixcode.currentActorUserId';
const actorTokenStorageKey = 'matrixcode.actorToken';
const actorTokenUserIdStorageKey = 'matrixcode.actorTokenUserId';
const actorTokenExpiresAtStorageKey = 'matrixcode.actorTokenExpiresAt';
const localTaskRefreshIntervalMillis = 2000;
const runtimeEventRefreshDelayMillis = 0;
const sessionRenewCheckIntervalMillis = 60_000;
const sessionRenewLeadMillis = 15 * 60_000;
const modelAnswerPreviewMaxLength = 360;
const rolePathByModelRole: Record<RoleAgentConfig['role'], string> = {
  PRODUCT: 'product',
  DEVELOPER: 'developer',
  TESTER: 'tester',
  OPERATIONS: 'operations'
};
const roleKeyByDisplayRole: Record<string, ModelRole> = {
  产品: 'PRODUCT',
  开发: 'DEVELOPER',
  测试: 'TESTER',
  运维: 'OPERATIONS'
};
const fallbackActorIdByDisplayRole: Record<string, string> = {
  产品: 'user-product',
  开发: 'user-dev',
  测试: 'user-tester',
  运维: 'user-ops'
};
const roleLabelByKey: Record<string, string> = {
  OWNER: '负责人',
  PRODUCT: '产品',
  DEVELOPER: '开发',
  TESTER: '测试',
  OPERATIONS: '运维'
};

const documentTypeLabels: Record<DocumentType, string> = {
  PRD: '产品需求文档',
  ACCEPTANCE_CRITERIA: '验收标准',
  UI_BRIEF: '界面说明',
  IMPLEMENTATION_NOTE: '实现说明',
  API_DOC: '接口文档',
  DATABASE_SCRIPT: '数据库脚本',
  DEPLOYMENT_DOC: '部署文档',
  CODING_AGENT_HANDOFF: '编码智能体交付回溯',
  QA_REPORT: '测试报告',
  ACCEPTANCE_RECORD: '验收记录',
  DEPLOYMENT_RECORD: '部署记录'
};

const documentStateLabels: Record<DocumentState, string> = {
  DRAFT: '草稿',
  REVIEW_PENDING: '待审核',
  FROZEN: '已冻结'
};

const runtimeCheckStatusLabels: Record<RuntimeCheckStatus, string> = {
  PASS: '通过',
  WARN: '警告',
  FAIL: '失败',
  SKIPPED: '跳过'
};
const composerApprovalModeLabels: Record<ComposerApprovalMode, string> = {
  ask: '问询',
  auto: '自动',
  yolo: 'Yolo'
};
const composerReasoningEffortLabels: Record<ComposerReasoningEffort, string> = {
  auto: 'auto',
  high: 'high',
  max: 'max'
};

function buildStageViews(currentStage: string): StageView[] {
  const activeIndex = workflowStages.findIndex((stage) =>
    stage.aliases.some((alias) => currentStage.includes(alias))
  );

  return workflowStages.map((stage, index) => {
    const status =
      activeIndex === -1
        ? 'pending'
        : index < activeIndex
          ? 'done'
          : index === activeIndex
            ? 'active'
            : 'pending';
    const statusLabel = status === 'done' ? '已完成' : status === 'active' ? '当前' : '待处理';

    return {
      name: stage.name,
      number: String(index + 1).padStart(2, '0'),
      status,
      statusLabel
    };
  });
}

/**
 * 生成前端模型下拉框的稳定引用值。
 * 作用域：Agent Composer；场景：把用户选择的供应商和模型映射回后端已绑定模型。
 */
function formatModelBindingRef(binding: { providerId: string; model: string } | undefined) {
  return binding ? `${binding.providerId}/${binding.model}` : '未绑定模型';
}

/**
 * 去重模型引用，保持后端绑定顺序中的第一个有效项。
 * 作用域：Agent Composer；场景：多个角色绑定同一供应商模型时，前端下拉只展示一次，避免 React key 冲突。
 */
function uniqueModelRefs(modelRefs: string[]) {
  return Array.from(new Set(modelRefs.filter(Boolean)));
}

/**
 * 压缩实时输入摘要。
 * 作用域：大模型输出台；场景：用户在底部输入时，上方实时预览只展示低敏短摘要。
 */
function summarizeComposerDraft(draft: string) {
  const compact = draft.trim().replace(/\s+/g, ' ');
  if (!compact) {
    return '';
  }
  return compact.length > 120 ? `${compact.slice(0, 120)}...` : compact;
}

/**
 * 压缩模型回复预览。
 * 作用域：大模型输出台；场景：真实模型返回长内容时，默认展示摘要，避免挤压工作台主操作区。
 */
function summarizeModelAnswer(answer: string) {
  const compact = answer.trim();
  if (compact.length <= modelAnswerPreviewMaxLength) {
    return compact;
  }
  return `${compact.slice(0, modelAnswerPreviewMaxLength).trimEnd()}...`;
}

/**
 * 构建随 Agent Composer 请求发送的工作台上下文。
 * 作用域：模型网关前端调用；场景：只发送阶段、最近文档和关键事件摘要，运行选项由后端统一补充。
 */
function buildComposerContextBlocks(workbench: ProjectWorkbench, selectedRole: string, tokenEconomy: boolean): ContextBlock[] {
  const recentDocuments = workbench.documents
    .slice(0, 3)
    .map((document) => `${document.title}(${documentStateLabels[document.state]} v${document.version})`)
    .join('；');
  const recentEvents = workbench.events.slice(0, 3).map((event) => event.message).join('；');
  const blocks: ContextBlock[] = [
    {
      type: 'WORKBENCH_STAGE',
      summary: `项目 ${workbench.projectName} 当前阶段：${workbench.currentStage}；当前角色：${selectedRole}`,
      allowedByGate: true
    }
  ];

  if (!tokenEconomy && recentDocuments) {
    blocks.push({
      type: 'RECENT_DOCUMENTS',
      summary: recentDocuments,
      allowedByGate: true
    });
  }
  if (!tokenEconomy && recentEvents) {
    blocks.push({
      type: 'RECENT_EVENTS',
      summary: recentEvents,
      allowedByGate: true
    });
  }

  return blocks;
}

function formatEventTime(event: ProjectEvent) {
  const date = new Date(event.occurredAt);
  if (Number.isNaN(date.getTime())) {
    return '时间待同步';
  }

  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  const hour = String(date.getHours()).padStart(2, '0');
  const minute = String(date.getMinutes()).padStart(2, '0');

  return `${month}月${day}日 ${hour}:${minute}`;
}

function hasLiveLocalExecutionTask(workbench: ProjectWorkbench): boolean {
  return workbench.localExecution.activeTasks.some((task) => task.status === 'QUEUED' || task.status === 'RUNNING');
}

function readStoredActorUserId(): string | null {
  try {
    return window.localStorage.getItem(currentActorStorageKey);
  } catch {
    return null;
  }
}

function storeCurrentActorUserId(actorUserId: string) {
  try {
    window.localStorage.setItem(currentActorStorageKey, actorUserId);
  } catch {
    // localStorage may be unavailable in restricted browser contexts.
  }
}

function storeActorToken(response: { userId: string; token: string; expiresAt: string }) {
  try {
    window.localStorage.setItem(actorTokenStorageKey, response.token);
    window.localStorage.setItem(actorTokenUserIdStorageKey, response.userId);
    window.localStorage.setItem(actorTokenExpiresAtStorageKey, response.expiresAt);
  } catch {
    // localStorage may be unavailable in restricted browser contexts.
  }
}

function readStoredActorTokenStatus(): { userId: string; expiresAt: string } | null {
  try {
    const userId = window.localStorage.getItem(actorTokenUserIdStorageKey);
    const expiresAt = window.localStorage.getItem(actorTokenExpiresAtStorageKey);
    const token = window.localStorage.getItem(actorTokenStorageKey);
    if (!userId || !expiresAt || !token) {
      return null;
    }
    return { userId, expiresAt };
  } catch {
    return null;
  }
}

function updateStoredActorTokenExpiresAt(expiresAt: string) {
  try {
    window.localStorage.setItem(actorTokenExpiresAtStorageKey, expiresAt);
  } catch {
    // localStorage may be unavailable in restricted browser contexts.
  }
}

function isAuthRequiredMessage(message: string) {
  return message.includes('Sa-Token') || message.includes('登录态') || message.includes('身份令牌');
}

function resolveCurrentActorId(
  selectedRole: string,
  projectMembers: ProjectMember[],
  selectedActorUserId: string | null
): string {
  if (selectedActorUserId) {
    return selectedActorUserId;
  }

  const roleKey = roleKeyByDisplayRole[selectedRole];
  const roleMember = projectMembers.find((member) => member.status === 'ACTIVE' && member.roleKey === roleKey);
  if (roleMember) {
    return roleMember.userId;
  }

  return fallbackActorIdByDisplayRole[selectedRole] ?? 'user-product';
}

function buildActorOptions(selectedRole: string, projectMembers: ProjectMember[], currentActorId: string) {
  const options = new Map<string, string>();
  projectMembers
    .filter((member) => member.status === 'ACTIVE')
    .forEach((member) => {
      options.set(member.userId, `${member.userId} · ${roleLabelByKey[member.roleKey] ?? member.roleKey}`);
    });

  if (!options.has(currentActorId)) {
    options.set(currentActorId, `${currentActorId} · ${roleLabelByKey[roleKeyByDisplayRole[selectedRole]] ?? '默认'}`);
  }

  return Array.from(options, ([value, label]) => ({ value, label }));
}

function emptyAgentRunUserAudit(projectId: string, userId: string): AgentRunUserAuditReport {
  return {
    projectId,
    userId,
    totalRuns: 0,
    activeResponsibilities: 0,
    modelRequestCount: 0,
    entries: []
  };
}

function DocumentHandoffList({ documents }: { documents: DocumentSummary[] }) {
  const visibleDocuments = documents.slice(0, 6);

  return (
    <article className="panel" aria-label="文档交接">
      <header className="panel__header">
        <h3 className="panel-title">文档交接</h3>
        <span className="panel-note">最近 {documents.length} 份</span>
      </header>
      {visibleDocuments.length ? (
        <ul className="handoff-list">
          {visibleDocuments.map((document) => (
            <li className="handoff-item" key={document.id}>
              <strong>{document.title}</strong>
              <span>
                {documentTypeLabels[document.type]} · {documentStateLabels[document.state]} · v{document.version}
              </span>
            </li>
          ))}
        </ul>
      ) : (
        <p className="empty-state">暂无文档交接</p>
      )}
    </article>
  );
}

function DocumentCenterDialog({
  documents,
  open,
  onClose
}: {
  documents: DocumentSummary[];
  open: boolean;
  onClose: () => void;
}) {
  if (!open) {
    return null;
  }

  return (
    <div className="config-dialog-backdrop">
      <section aria-label="文档中心" className="config-dialog document-center" role="dialog">
        <header className="config-dialog__header">
          <div>
            <p className="eyebrow">上下文资产</p>
            <h2>文档中心</h2>
          </div>
          <button aria-label="关闭文档中心" className="config-dialog__close" onClick={onClose} type="button">
            ×
          </button>
        </header>
        {documents.length ? (
          <ul className="document-center__list">
            {documents.map((document) => (
              <li className="document-center__item" key={document.id}>
                <div>
                  <strong>{document.title}</strong>
                  <span>
                    {documentTypeLabels[document.type]} · {documentStateLabels[document.state]} · v{document.version}
                  </span>
                </div>
                <code>引用 ID：{document.id}</code>
                <div className="document-center__content" aria-label={`${document.title} 正文`}>
                  <span>正文</span>
                  <pre>{document.content}</pre>
                </div>
              </li>
            ))}
          </ul>
        ) : (
          <p className="empty-state">暂无文档</p>
        )}
      </section>
    </div>
  );
}

function RuntimeDiagnosticsDialog({
  actorUserId,
  open,
  onClose,
  projectId
}: {
  actorUserId: string;
  open: boolean;
  onClose: () => void;
  projectId: string;
}) {
  const [diagnosticsState, setDiagnosticsState] = useState<RuntimeDiagnosticsState>({ type: 'idle' });

  async function runDiagnostics() {
    setDiagnosticsState({ type: 'loading' });
    try {
      const report = await loadRuntimeDiagnostics(projectId, actorUserId);
      setDiagnosticsState({ type: 'ready', report });
    } catch {
      setDiagnosticsState({ type: 'error' });
    }
  }

  useEffect(() => {
    if (open) {
      void runDiagnostics();
    }
  }, [open, projectId, actorUserId]);

  if (!open) {
    return null;
  }

  const report = diagnosticsState.type === 'ready' ? diagnosticsState.report : null;
  const blockedCount = report?.items.filter((item) => item.blocking || item.status === 'FAIL').length ?? 0;
  const warningCount = report?.items.filter((item) => item.status === 'WARN').length ?? 0;
  const passCount = report?.items.filter((item) => item.status === 'PASS').length ?? 0;
  const generatedAt = report ? new Date(report.generatedAt) : null;
  const generatedAtText =
    generatedAt && !Number.isNaN(generatedAt.getTime())
      ? generatedAt.toLocaleString('zh-CN', { hour12: false })
      : '时间待同步';

  return (
    <div className="config-dialog-backdrop">
      <section aria-label="运行诊断" className="config-dialog diagnostics-dialog" role="dialog">
        <header className="config-dialog__header">
          <div>
            <p className="eyebrow">真实运行检查</p>
            <h2>运行诊断</h2>
          </div>
          <button aria-label="关闭运行诊断" className="config-dialog__close" onClick={onClose} type="button">
            ×
          </button>
        </header>

        {diagnosticsState.type === 'loading' || diagnosticsState.type === 'idle' ? (
          <p className="empty-state">正在运行诊断...</p>
        ) : null}

        {diagnosticsState.type === 'error' ? (
          <article className="diagnostics-error">
            <strong>运行诊断暂不可用</strong>
            <span>请确认团队服务器仍在运行，或稍后重新诊断。</span>
            <button className="primary-button diagnostics-error__button" onClick={() => void runDiagnostics()} type="button">
              重试诊断
            </button>
          </article>
        ) : null}

        {report ? (
          <>
            <section className="diagnostics-summary" aria-label="诊断摘要">
              <article className={`diagnostics-summary__status diagnostics-summary__status--${report.status.toLowerCase()}`}>
                <span>整体状态：{runtimeCheckStatusLabels[report.status]}</span>
                <small>生成时间 {generatedAtText}</small>
              </article>
              <article>
                <strong>{blockedCount}</strong>
                <span>阻塞项</span>
              </article>
              <article>
                <strong>{warningCount}</strong>
                <span>警告项</span>
              </article>
              <article>
                <strong>{passCount}</strong>
                <span>通过项</span>
              </article>
            </section>

            <section aria-label="诊断检查项">
              <h3 className="diagnostics-section-title">检查项</h3>
              <ul className="diagnostics-list">
                {report.items.map((item) => (
                  <li
                    className={`diagnostics-item diagnostics-item--${item.status.toLowerCase()} ${
                      item.blocking ? 'diagnostics-item--blocking' : ''
                    }`}
                    key={item.key}
                  >
                    <header className="diagnostics-item__header">
                      <strong>{item.label}</strong>
                      <span>{runtimeCheckStatusLabels[item.status]}</span>
                      {item.blocking ? <span>阻塞</span> : null}
                    </header>
                    <p>{item.detail}</p>
                  </li>
                ))}
              </ul>
            </section>

            <section aria-label="诊断下一步">
              <h3 className="diagnostics-section-title">下一步动作</h3>
              {report.nextActions.length ? (
                <ul className="diagnostics-actions">
                  {report.nextActions.map((action) => (
                    <li key={action}>{action}</li>
                  ))}
                </ul>
              ) : (
                <p className="empty-state">暂无额外动作</p>
              )}
            </section>
          </>
        ) : null}
      </section>
    </div>
  );
}

function EventList({ events }: { events: ProjectEvent[] }) {
  return (
    <article className="panel" aria-label="角色动态">
      <header className="panel__header">
        <h3 className="panel-title">角色动态</h3>
        <span className="panel-note">最近同步</span>
      </header>
      {events.length ? (
        <ul className="activity-list">
          {events.map((event) => (
            <li className="activity-item" key={event.id}>
              <strong>{formatEventTime(event)}</strong>
              <span>{event.message}</span>
            </li>
          ))}
        </ul>
      ) : (
        <p className="empty-state">暂无角色动态</p>
      )}
    </article>
  );
}

function formatTimelineTime(occurredAt: string) {
  const date = new Date(occurredAt);
  if (Number.isNaN(date.getTime())) {
    return '时间待同步';
  }

  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  const hour = String(date.getHours()).padStart(2, '0');
  const minute = String(date.getMinutes()).padStart(2, '0');
  return `${month}月${day}日 ${hour}:${minute}`;
}

/**
 * 展示角色智能体的本次请求预览和最近运行摘要。
 * 作用域：工作台中间主区域；场景：底部对话输入时即时预览，模型返回后展示真实响应摘要。
 */
function AgentOutputConsole({
  agentRunEvents,
  agentRuns,
  approvalMode,
  composerDraft,
  composerSubmitState,
  currentModelRef,
  reasoningEffort,
  selectedRole
}: {
  agentRunEvents: AgentRunEventRecord[];
  agentRuns: AgentRunRecord[];
  approvalMode: ComposerApprovalMode;
  composerDraft: string;
  composerSubmitState: ComposerSubmitState;
  currentModelRef: string;
  reasoningEffort: ComposerReasoningEffort;
  selectedRole: string;
}) {
  const [answerExpanded, setAnswerExpanded] = useState(false);
  const liveDraft = summarizeComposerDraft(composerDraft);
  const answeredRequestId =
    composerSubmitState.status === 'answered'
      ? composerSubmitState.response.requestId
      : composerSubmitState.status === 'streaming'
        ? composerSubmitState.submittedAt
        : composerSubmitState.status;
  const modelAnswer =
    composerSubmitState.status === 'answered'
      ? composerSubmitState.response.answer
      : composerSubmitState.status === 'streaming'
        ? composerSubmitState.partialAnswer
        : '';
  const modelAnswerPreview = summarizeModelAnswer(modelAnswer);
  const modelAnswerIsLong = composerSubmitState.status === 'answered' && modelAnswerPreview !== modelAnswer.trim();
  const displayedModelAnswer = answerExpanded || !modelAnswerIsLong ? modelAnswer.trim() : modelAnswerPreview;
  const submitStatusLabel =
    composerSubmitState.status === 'streaming'
      ? '流式输出中'
      : composerSubmitState.status === 'sending'
      ? '模型请求中'
      : composerSubmitState.status === 'answered'
        ? '已返回'
        : composerSubmitState.status === 'error'
          ? '请求失败'
          : liveDraft
            ? '草稿同步'
            : '等待输入';

  useEffect(() => {
    setAnswerExpanded(false);
  }, [answeredRequestId]);

  return (
    <section className="agent-output-console" aria-label="大模型输出台">
      <header className="agent-console-header">
        <div>
          <p className="eyebrow">Output</p>
          <h3>{selectedRole}智能体</h3>
        </div>
        <span>{submitStatusLabel}</span>
      </header>
      <section className={`agent-live-preview agent-live-preview--${composerSubmitState.status}`} aria-label="本次请求预览">
        <header>
          <div>
            <strong>
              {composerSubmitState.status === 'answered'
                ? '模型回复'
                : composerSubmitState.status === 'streaming'
                  ? '模型流式回复'
                  : composerSubmitState.status === 'error'
                    ? '请求失败'
                  : '本次请求预览'}
            </strong>
            <span>{currentModelRef}</span>
          </div>
          <em>{composerApprovalModeLabels[approvalMode]} · effort {composerReasoningEffortLabels[reasoningEffort]}</em>
        </header>
        {composerSubmitState.status === 'answered' || composerSubmitState.status === 'streaming' ? (
          <div className="agent-live-preview__answer">
            <pre
              aria-label={
                composerSubmitState.status === 'streaming'
                  ? '模型流式回复'
                  : modelAnswerIsLong && answerExpanded
                    ? '模型回复全文'
                    : '模型回复摘要'
              }
            >
              {composerSubmitState.status === 'streaming'
                ? displayedModelAnswer || '正在建立流式响应...'
                : displayedModelAnswer}
            </pre>
            {composerSubmitState.status === 'answered' && modelAnswerIsLong ? (
              <button
                aria-expanded={answerExpanded}
                className="agent-live-preview__expand"
                onClick={() => setAnswerExpanded((expanded) => !expanded)}
                type="button"
              >
                {answerExpanded ? '收起回复' : '展开完整回复'}
              </button>
            ) : null}
          </div>
        ) : composerSubmitState.status === 'sending' ? (
          <pre>正在调用模型，等待 {currentModelRef} 返回结果...{'\n\n'}{composerSubmitState.submittedInstruction}</pre>
        ) : composerSubmitState.status === 'error' ? (
          <pre>{composerSubmitState.message}</pre>
        ) : (
          <pre>{liveDraft || `等待输入。这里会预览将发送给${selectedRole}智能体的任务。`}</pre>
        )}
      </section>
      {agentRuns.length ? (
        <ol className="agent-output-list">
          {agentRuns.slice(0, 3).map((run) => (
            <li className="agent-output-item" key={run.id}>
              <div>
                <strong>{run.goal}</strong>
                <span>
                  {run.status} · {run.providerId}/{run.modelName} · {formatTimelineTime(run.updatedAt)}
                </span>
              </div>
              {run.summary ? <p>{run.summary}</p> : null}
            </li>
          ))}
        </ol>
      ) : (
        <div className="agent-output-empty">
          <strong>暂无 Agent 运行</strong>
          <span>在下方发送消息后，这里会显示模型输出。</span>
        </div>
      )}
    </section>
  );
}

/**
 * 底部 Agent 对话台。
 * 作用域：工作台模型交互入口；场景：选择协作方式、权限模式、模型和推理力度后发起真实后端模型请求。
 */
function AgentComposer({
  approvalMode,
  currentModelRef,
  draft,
  intentState,
  modelOptions,
  onApprovalModeChange,
  onCurrentModelRefChange,
  onDraftChange,
  onIntentStateChange,
  onReasoningEffortChange,
  onSubmit,
  reasoningEffort,
  selectedRole,
  submitting
}: {
  approvalMode: ComposerApprovalMode;
  currentModelRef: string;
  draft: string;
  intentState: ComposerIntentState;
  modelOptions: string[];
  onApprovalModeChange: (mode: ComposerApprovalMode) => void;
  onCurrentModelRefChange: (modelRef: string) => void;
  onDraftChange: (draft: string) => void;
  onIntentStateChange: (state: ComposerIntentState) => void;
  onReasoningEffortChange: (effort: ComposerReasoningEffort) => void;
  onSubmit: (instruction: string) => Promise<void>;
  reasoningEffort: ComposerReasoningEffort;
  selectedRole: string;
  submitting: boolean;
}) {
  const [intentMenuOpen, setIntentMenuOpen] = useState(false);
  const [effortMenuOpen, setEffortMenuOpen] = useState(false);
  const [moreMenuOpen, setMoreMenuOpen] = useState(false);
  const canSubmit = Boolean(draft.trim()) && !submitting;

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canSubmit) {
      return;
    }
    await onSubmit(draft.trim());
  }

  function toggleIntent(key: keyof ComposerIntentState) {
    onIntentStateChange({ ...intentState, [key]: !intentState[key] });
  }

  return (
    <form
      aria-busy={submitting}
      className="agent-composer"
      aria-label="Agent 对话 Composer"
      onSubmit={(event) => void handleSubmit(event)}
    >
      <label className="agent-composer__input-wrap" htmlFor="agent-composer-input">
        <span className="agent-composer__prompt">›</span>
        <textarea
          aria-label="Agent 对话输入"
          id="agent-composer-input"
          onChange={(event) => onDraftChange(event.target.value)}
          placeholder={`给${selectedRole}智能体输入任务或问题...`}
          readOnly={submitting}
          rows={3}
          value={draft}
        />
        <button
          aria-label={submitting ? '正在发送给角色智能体' : '发送给角色智能体'}
          className="agent-composer__send"
          disabled={!canSubmit}
          type="submit"
        >
          {submitting ? '…' : '↑'}
        </button>
      </label>

      <div className="agent-composer__toolbar" aria-label="Agent 对话控制条">
        <div className="agent-composer__control agent-composer__control--intent">
          <button
            aria-expanded={intentMenuOpen}
            aria-haspopup="menu"
            aria-label="协作方式"
            className={`agent-composer__icon-button ${intentMenuOpen ? 'agent-composer__icon-button--open' : ''}`}
            onClick={() => {
              setIntentMenuOpen((open) => !open);
              setEffortMenuOpen(false);
              setMoreMenuOpen(false);
            }}
            title="协作方式"
            type="button"
          >
            ☷
          </button>
          {intentMenuOpen ? (
            <div className="agent-composer-menu agent-composer-menu--intent" role="menu" aria-label="协作方式">
              <p className="agent-composer-menu__label">协作方式</p>
              <button
                className={`agent-composer-menu__item ${intentState.plan ? 'agent-composer-menu__item--active' : ''}`}
                onClick={() => toggleIntent('plan')}
                role="menuitemcheckbox"
                aria-checked={intentState.plan}
                type="button"
              >
                <span className="agent-composer-menu__mark">☰</span>
                <span>
                  <strong>计划</strong>
                  <small>先只读产出计划，确认后再写入。</small>
                </span>
                <i aria-hidden="true" />
              </button>
              <button
                className={`agent-composer-menu__item ${intentState.goal ? 'agent-composer-menu__item--active' : ''}`}
                onClick={() => toggleIntent('goal')}
                role="menuitemcheckbox"
                aria-checked={intentState.goal}
                type="button"
              >
                <span className="agent-composer-menu__mark">◎</span>
                <span>
                  <strong>目标</strong>
                  <small>输入目标后，让角色智能体持续推进。</small>
                </span>
                <i aria-hidden="true" />
              </button>
              <button
                className={`agent-composer-menu__item ${intentState.tokenEconomy ? 'agent-composer-menu__item--active' : ''}`}
                onClick={() => toggleIntent('tokenEconomy')}
                role="menuitemcheckbox"
                aria-checked={intentState.tokenEconomy}
                type="button"
              >
                <span className="agent-composer-menu__mark">◔</span>
                <span>
                  <strong>省 token</strong>
                  <small>精简动态上下文，保留稳定前缀缓存命中。</small>
                </span>
                <i aria-hidden="true" />
              </button>
            </div>
          ) : null}
        </div>

        <div className="agent-composer-modebar" data-mode={approvalMode} aria-label="工具权限">
          <span className="agent-composer-modebar__thumb" aria-hidden="true" />
          {(['ask', 'auto', 'yolo'] as ComposerApprovalMode[]).map((mode) => (
            <button
              aria-pressed={approvalMode === mode}
              className={`agent-composer-modebar__item ${
                approvalMode === mode ? 'agent-composer-modebar__item--active' : ''
              }`}
              key={mode}
              onClick={() => onApprovalModeChange(mode)}
              type="button"
            >
              {composerApprovalModeLabels[mode]}
            </button>
          ))}
        </div>

        <label className="agent-composer-select">
          <span>模型</span>
          <select aria-label="当前模型" onChange={(event) => onCurrentModelRefChange(event.target.value)} value={currentModelRef}>
            {modelOptions.map((modelRef) => (
              <option key={modelRef} value={modelRef}>
                {modelRef}
              </option>
            ))}
          </select>
        </label>

        <div className="agent-composer__control agent-composer__control--effort">
          <button
            aria-expanded={effortMenuOpen}
            aria-haspopup="menu"
            aria-label={`推理力度：${reasoningEffort}`}
            className="agent-composer-effort"
            onClick={() => {
              setEffortMenuOpen((open) => !open);
              setIntentMenuOpen(false);
              setMoreMenuOpen(false);
            }}
            type="button"
          >
            <span>推理力度</span>
            <strong>{reasoningEffort}</strong>
          </button>
          {effortMenuOpen ? (
            <div className="agent-composer-menu agent-composer-menu--effort" role="menu" aria-label="推理力度">
              {(['auto', 'high', 'max'] as ComposerReasoningEffort[]).map((effort) => (
                <button
                  className={`agent-composer-menu__choice ${
                    reasoningEffort === effort ? 'agent-composer-menu__choice--active' : ''
                  }`}
                  key={effort}
                  onClick={() => {
                    onReasoningEffortChange(effort);
                    setEffortMenuOpen(false);
                  }}
                  role="menuitemradio"
                  aria-checked={reasoningEffort === effort}
                  type="button"
                >
                  <span>◜</span>
                  <strong>{effort}</strong>
                  {reasoningEffort === effort ? <em>✓</em> : null}
                </button>
              ))}
            </div>
          ) : null}
        </div>

        <div className="agent-composer__control agent-composer__control--more">
          <button
            aria-expanded={moreMenuOpen}
            aria-haspopup="menu"
            aria-label="更多控制"
            className="agent-composer-more"
            onClick={() => {
              setMoreMenuOpen((open) => !open);
              setIntentMenuOpen(false);
              setEffortMenuOpen(false);
            }}
            type="button"
          >
            <span>•••</span>
            更多
          </button>
          {moreMenuOpen ? (
            <div className="agent-composer-menu agent-composer-menu--more" role="menu" aria-label="更多控制">
              <p className="agent-composer-menu__label">当前会话</p>
              <span className="agent-composer-menu__note">角色：{selectedRole}</span>
              <span className="agent-composer-menu__note">模型：{currentModelRef}</span>
              <span className="agent-composer-menu__note">发送后会写入模型请求 trace。</span>
            </div>
          ) : null}
        </div>
      </div>
    </form>
  );
}

function WorkspaceContextPanel({
  onTabChange,
  tab,
  workbench
}: {
  onTabChange: (tab: WorkspaceContextTab) => void;
  tab: WorkspaceContextTab;
  workbench: ProjectWorkbench;
}) {
  const currentBinding = workbench.modelGateway.bindings[0];
  const metrics = workbench.metrics;
  const gatewayMetrics = workbench.modelGateway.metrics;
  const recentDiff = workbench.localExecution.recentGitDiff;
  const changedFiles = recentDiff?.changedFiles ?? [];

  return (
    <aside className="workspace-context" aria-label="输出与预览">
      <div className="workspace-context__tabs" aria-label="上下文面板" role="tablist">
        {[
          ['overview', '概览'],
          ['files', '文件'],
          ['changes', '改动']
        ].map(([value, label]) => (
          <button
            aria-selected={tab === value}
            className={`workspace-context__tab ${tab === value ? 'workspace-context__tab--active' : ''}`}
            key={value}
            onClick={() => onTabChange(value as WorkspaceContextTab)}
            role="tab"
            type="button"
          >
            {label}
          </button>
        ))}
      </div>

      {tab === 'overview' ? (
        <div className="workspace-context__body">
          <section className="context-card" aria-label="上下文窗口">
            <header>
              <strong>上下文窗口</strong>
              <span>当前模型上下文占用</span>
            </header>
            <div className="context-ring">
              <strong>{Math.round(metrics.cacheHitRate * 100)}%</strong>
              <span>缓存命中</span>
            </div>
            <ul className="context-list">
              <li>
                <span>当前模型</span>
                <strong>{currentBinding ? `${currentBinding.providerId}/${currentBinding.model}` : '未绑定'}</strong>
              </li>
              <li>
                <span>请求数</span>
                <strong>{gatewayMetrics.requestCount.toLocaleString('zh-CN')}</strong>
              </li>
              <li>
                <span>会话 tokens</span>
                <strong>{metrics.sessionTokens.toLocaleString('zh-CN')}</strong>
              </li>
            </ul>
          </section>
          <section className="context-card" aria-label="成本">
            <header>
              <strong>成本</strong>
              <span>模型调用成本</span>
            </header>
            <div className="context-metric-grid">
              <span>
                <small>会话费用</small>
                <strong>{gatewayMetrics.estimatedCost.toLocaleString('zh-CN')} {gatewayMetrics.currency}</strong>
              </span>
              <span>
                <small>文档</small>
                <strong>{metrics.documentCount.toLocaleString('zh-CN')}</strong>
              </span>
              <span>
                <small>未关 Bug</small>
                <strong>{metrics.openBugCount.toLocaleString('zh-CN')}</strong>
              </span>
            </div>
          </section>
        </div>
      ) : null}

      {tab === 'files' ? (
        <div className="workspace-context__body">
          <DocumentHandoffList documents={workbench.documents} />
        </div>
      ) : null}

      {tab === 'changes' ? (
        <div className="workspace-context__body">
          <section className="context-card" aria-label="Git 改动">
            <header>
              <strong>Git 改动</strong>
              <span>{recentDiff?.stat || '暂无改动摘要'}</span>
            </header>
            {changedFiles.length ? (
              <ul className="context-file-list">
                {changedFiles.map((file) => (
                  <li key={file}>{file}</li>
                ))}
              </ul>
            ) : (
              <p className="empty-state">暂无文件改动</p>
            )}
          </section>
          <EventList events={workbench.events} />
        </div>
      ) : null}
    </aside>
  );
}

async function loadAgentRuntimeSnapshot(projectId: string, actorUserId: string): Promise<{
  agentRuns: AgentRunRecord[];
  agentRunEvents: AgentRunEventRecord[];
}> {
  try {
    const agentRuns = await loadAgentRuns(projectId, actorUserId);
    const recentRunIds = agentRuns.slice(0, 3).map((run) => run.id);
    const agentRunEvents = (
      await Promise.all(recentRunIds.map((runId) => loadAgentRunEvents(projectId, runId, actorUserId).catch(() => [])))
    ).flat();
    return { agentRuns, agentRunEvents };
  } catch {
    return { agentRuns: [], agentRunEvents: [] };
  }
}

function App() {
  const [workbenchState, setWorkbenchState] = useState<WorkbenchState>({ type: 'loading' });
  const [activeRole, setActiveRole] = useState('产品');
  const [approvalBusyTaskId, setApprovalBusyTaskId] = useState<string | null>(null);
  const [cancelBusyTaskId, setCancelBusyTaskId] = useState<string | null>(null);
  const [retryBusyRunId, setRetryBusyRunId] = useState<string | null>(null);
  const [claimBusyRunId, setClaimBusyRunId] = useState<string | null>(null);
  const [claimNextBusy, setClaimNextBusy] = useState(false);
  const [dismissedNotificationIds, setDismissedNotificationIds] = useState<Set<string>>(() => new Set());
  const [runtimeNotificationFilter, setRuntimeNotificationFilter] = useState<RuntimeNotificationFilter>('all');
  const [runtimeNotificationActionBusy, setRuntimeNotificationActionBusy] = useState(false);
  const [configDialogOpen, setConfigDialogOpen] = useState(false);
  const [documentCenterOpen, setDocumentCenterOpen] = useState(false);
  const [runtimeDiagnosticsOpen, setRuntimeDiagnosticsOpen] = useState(false);
  const [operationsCenterOpen, setOperationsCenterOpen] = useState(false);
  const [workflowPanelOpen, setWorkflowPanelOpen] = useState(false);
  const [contextPanelTab, setContextPanelTab] = useState<WorkspaceContextTab>('overview');
  const [selectedActorUserId, setSelectedActorUserId] = useState<string | null>(() => readStoredActorUserId());
  const [loginUsername, setLoginUsername] = useState(() => readStoredActorUserId() ?? 'admin');
  const [loginPassword, setLoginPassword] = useState('');
  const [loginBusy, setLoginBusy] = useState(false);
  const [loginErrorMessage, setLoginErrorMessage] = useState('');
  const [composerDraft, setComposerDraft] = useState('');
  const [composerIntentState, setComposerIntentState] = useState<ComposerIntentState>({
    plan: false,
    goal: false,
    tokenEconomy: true
  });
  const [composerApprovalMode, setComposerApprovalMode] = useState<ComposerApprovalMode>('auto');
  const [composerReasoningEffort, setComposerReasoningEffort] = useState<ComposerReasoningEffort>('auto');
  const [composerModelRef, setComposerModelRef] = useState('');
  const [composerSubmitState, setComposerSubmitState] = useState<ComposerSubmitState>({ status: 'idle' });

  async function refreshWorkbench(options: { keepCurrent?: boolean; actorUserId?: string } = {}) {
    const currentReadyState = workbenchState.type === 'ready' ? workbenchState : null;

    setWorkbenchState((current) =>
      options.keepCurrent && current.type === 'ready'
        ? { ...current, refreshing: true, syncError: undefined }
        : { type: 'loading' }
    );

    try {
      const projectId = currentReadyState?.workbench.projectId ?? 'demo';
      const workbenchActorId =
        options.actorUserId ??
        selectedActorUserId ??
        (currentReadyState ? resolveCurrentActorId(activeRole, currentReadyState.projectMembers, null) : undefined);
      const requestActorId = workbenchActorId ?? fallbackActorIdByDisplayRole[activeRole] ?? 'user-product';
      const workbench = await loadProjectWorkbench(projectId, requestActorId);
      const [roleAgentConfigs, projectMembers, agentRuntime] = await Promise.all([
        loadRoleAgentConfigs(workbench.projectId, requestActorId),
        loadProjectMembers(workbench.projectId, requestActorId).catch(() => []),
        loadAgentRuntimeSnapshot(workbench.projectId, requestActorId)
      ]);
      const auditActorId = resolveCurrentActorId(activeRole, projectMembers, workbenchActorId ?? selectedActorUserId);
      const agentRunUserAudit = await loadAgentRunUserAudit(workbench.projectId, auditActorId, 50).catch(() =>
        emptyAgentRunUserAudit(workbench.projectId, auditActorId)
      );
      setWorkbenchState({
        type: 'ready',
        workbench,
        roleAgentConfigs,
        projectMembers,
        agentRuns: agentRuntime.agentRuns,
        agentRunEvents: agentRuntime.agentRunEvents,
        agentRunUserAudit,
        refreshing: false
      });
    } catch (error) {
      if (options.keepCurrent && currentReadyState) {
        setWorkbenchState({
          type: 'ready',
          workbench: currentReadyState.workbench,
          roleAgentConfigs: currentReadyState.roleAgentConfigs,
          projectMembers: currentReadyState.projectMembers,
          agentRuns: currentReadyState.agentRuns,
          agentRunEvents: currentReadyState.agentRunEvents,
          agentRunUserAudit: currentReadyState.agentRunUserAudit,
          refreshing: false,
          syncError: syncFailureMessage
        });
        throw new Error(syncFailureMessage);
      }

      const errorMessage = error instanceof Error ? error.message : '';
      setWorkbenchState({
        type: 'error',
        message: isAuthRequiredMessage(errorMessage) ? '需要登录 MatrixCode' : '团队服务器暂时不可用'
      });
    }
  }

  async function handleLoginFromErrorPage(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (loginBusy) {
      return;
    }

    const username = loginUsername.trim();
    const password = loginPassword.trim();
    if (!username || !password) {
      setLoginErrorMessage('请输入用户名和密码');
      return;
    }

    setLoginBusy(true);
    setLoginErrorMessage('');
    try {
      const response = await loginActorSession('demo', {
        username,
        password,
      });
      storeActorToken(response);
      storeCurrentActorUserId(response.userId);
      setSelectedActorUserId(response.userId);
      setLoginUsername(response.userId);
      setLoginPassword('');
      await refreshWorkbench({ actorUserId: response.userId });
    } catch {
      setLoginErrorMessage('登录失败，请检查用户名、密码和项目成员状态');
    } finally {
      setLoginBusy(false);
    }
  }

  async function refreshAgentRunUserAudit(actorUserId: string) {
    if (workbenchState.type !== 'ready') {
      return;
    }

    const projectId = workbenchState.workbench.projectId;
    const report = await loadAgentRunUserAudit(projectId, actorUserId, 50).catch(() =>
      emptyAgentRunUserAudit(projectId, actorUserId)
    );
    setWorkbenchState((current) =>
      current.type === 'ready' && current.workbench.projectId === projectId
        ? { ...current, agentRunUserAudit: report }
        : current
    );
  }

  useEffect(() => {
    void refreshWorkbench();
  }, []);

  useEffect(() => {
    if (workbenchState.type !== 'ready') {
      return undefined;
    }
    let renewing = false;
    const projectId = workbenchState.workbench.projectId;
    const maybeRenewSession = () => {
      const storedStatus = readStoredActorTokenStatus();
      if (!storedStatus || renewing) {
        return;
      }
      const expiresAt = Date.parse(storedStatus.expiresAt);
      if (!Number.isFinite(expiresAt) || expiresAt - Date.now() > sessionRenewLeadMillis) {
        return;
      }
      renewing = true;
      void renewActorSession(projectId, storedStatus.userId)
        .then((session) => {
          updateStoredActorTokenExpiresAt(new Date(Date.now() + session.timeoutSeconds * 1000).toISOString());
        })
        .catch(() => undefined)
        .finally(() => {
          renewing = false;
        });
    };

    maybeRenewSession();
    const intervalId = window.setInterval(maybeRenewSession, sessionRenewCheckIntervalMillis);
    return () => window.clearInterval(intervalId);
  }, [workbenchState.type === 'ready' ? workbenchState.workbench.projectId : null, workbenchState.type]);

  const readyProjectId = workbenchState.type === 'ready' ? workbenchState.workbench.projectId : null;
  const readySelectedRole =
    workbenchState.type === 'ready' && workbenchState.workbench.roles.some((role) => role.name === activeRole)
      ? activeRole
      : '产品';
  const currentActorId =
    workbenchState.type === 'ready'
      ? resolveCurrentActorId(readySelectedRole, workbenchState.projectMembers, selectedActorUserId)
      : fallbackActorIdByDisplayRole[readySelectedRole] ?? 'user-product';
  const shouldRefreshLocalTasks =
    workbenchState.type === 'ready' && hasLiveLocalExecutionTask(workbenchState.workbench);
  const readyModelRefs =
    workbenchState.type === 'ready'
      ? uniqueModelRefs(workbenchState.workbench.modelGateway.bindings.map((binding) => formatModelBindingRef(binding)))
      : [];
  const readyModelRefsKey = readyModelRefs.join('|');

  useEffect(() => {
    if (!shouldRefreshLocalTasks) {
      return undefined;
    }

    const intervalId = window.setInterval(() => {
      void refreshWorkbench({ keepCurrent: true }).catch(() => undefined);
    }, localTaskRefreshIntervalMillis);

    return () => window.clearInterval(intervalId);
  }, [shouldRefreshLocalTasks]);

  useEffect(() => {
    if (workbenchState.type !== 'ready') {
      return;
    }

    const roleKey = roleKeyByDisplayRole[readySelectedRole];
    const roleBinding = workbenchState.workbench.modelGateway.bindings.find((binding) => binding.role === roleKey);
    const fallbackBinding = workbenchState.workbench.modelGateway.bindings[0];
    const preferredModelRef = formatModelBindingRef(roleBinding ?? fallbackBinding);
    setComposerModelRef((current) =>
      current && readyModelRefs.includes(current) ? current : preferredModelRef
    );
  }, [workbenchState.type, readySelectedRole, readyModelRefsKey]);

  useEffect(() => {
    if (!readyProjectId) {
      return undefined;
    }

    let refreshScheduled = false;
    let refreshTimerId: number | null = null;
    const scheduleRefresh = () => {
      if (refreshScheduled) {
        return;
      }

      refreshScheduled = true;
      refreshTimerId = window.setTimeout(() => {
        refreshScheduled = false;
        refreshTimerId = null;
        void refreshWorkbench({ keepCurrent: true }).catch(() => undefined);
      }, runtimeEventRefreshDelayMillis);
    };
    const subscription = subscribeProjectEvents(
      readyProjectId,
      {
        onEvent: (event) => {
          if (event.projectId === readyProjectId) {
            scheduleRefresh();
          }
        }
      },
      currentActorId
    );

    return () => {
      if (refreshTimerId !== null) {
        window.clearTimeout(refreshTimerId);
      }
      subscription.close();
    };
  }, [readyProjectId, currentActorId]);

  async function handleCreateProductDrafts(requirement: string) {
    if (workbenchState.type !== 'ready') {
      return;
    }

    await createProductDrafts(workbenchState.workbench.projectId, { requirement }, currentActorId);
    await refreshWorkbench({ keepCurrent: true });
  }

  async function handleFreezeDocument(documentId: string) {
    if (workbenchState.type !== 'ready') {
      return;
    }

    await freezeDocument(workbenchState.workbench.projectId, documentId, currentActorId);
    await refreshWorkbench({ keepCurrent: true });
  }

  async function handleSubmitAcceptance(input: ProductAcceptanceInput) {
    if (workbenchState.type !== 'ready') {
      return;
    }

    await submitAcceptance(workbenchState.workbench.projectId, input, currentActorId);
    await refreshWorkbench({ keepCurrent: true });
  }

  async function handleSubmitDeveloperDelivery(input: DeveloperDeliveryInput) {
    if (workbenchState.type !== 'ready') {
      return;
    }

    await submitDeveloperDelivery(workbenchState.workbench.projectId, input, currentActorId);
    await refreshWorkbench({ keepCurrent: true });
  }

  async function handlePrepareCodingAgentExecution(input: DeveloperExecutionInput): Promise<CodingAgentExecutionPlan> {
    if (workbenchState.type !== 'ready') {
      throw new Error('团队服务器暂时不可用');
    }

    const plan = await prepareCodingAgentExecution(workbenchState.workbench.projectId, 'developer', {
      ...input,
      actorId: currentActorId
    });
    void refreshWorkbench({ keepCurrent: true }).catch(() => undefined);
    return plan;
  }

  async function handleApplyCodingAgentPatch(input: DeveloperPatchInput): Promise<CodingAgentPatchResult> {
    if (workbenchState.type !== 'ready') {
      throw new Error('团队服务器暂时不可用');
    }

    const result = await applyCodingAgentPatch(workbenchState.workbench.projectId, 'developer', {
      ...input,
      actorId: currentActorId
    });
    void refreshWorkbench({ keepCurrent: true }).catch(() => undefined);
    return result;
  }

  async function handleRecordCodingAgentHandoff(input: DeveloperHandoffInput): Promise<DocumentSummary> {
    if (workbenchState.type !== 'ready') {
      throw new Error('团队服务器暂时不可用');
    }

    const document = await recordCodingAgentHandoff(workbenchState.workbench.projectId, 'developer', {
      ...input,
      actorId: currentActorId
    });
    void refreshWorkbench({ keepCurrent: true }).catch(() => undefined);
    return document;
  }

  async function handleCreateBug(input: TesterBugInput) {
    if (workbenchState.type !== 'ready') {
      return;
    }

    await createBug(
      workbenchState.workbench.projectId,
      {
        ...input,
        createdByRole: '测试',
        currentOwnerRole: '开发'
      },
      currentActorId
    );
    await refreshWorkbench({ keepCurrent: true });
  }

  async function handleSubmitTestReport(input: TesterReportInput) {
    if (workbenchState.type !== 'ready') {
      return;
    }

    await submitTestReport(workbenchState.workbench.projectId, input, currentActorId);
    await refreshWorkbench({ keepCurrent: true });
  }

  async function handleTransitionBug(bugId: string, input: TesterBugTransitionInput) {
    if (workbenchState.type !== 'ready') {
      return;
    }

    await transitionBug(workbenchState.workbench.projectId, bugId, input, currentActorId);
    await refreshWorkbench({ keepCurrent: true });
  }

  async function handleConfigureDeploymentTarget(input: OpsDeploymentTargetInput) {
    if (workbenchState.type !== 'ready') {
      return;
    }

    await configureDeploymentTarget(workbenchState.workbench.projectId, input, currentActorId);
    await refreshWorkbench({ keepCurrent: true });
  }

  async function handleRunDeploymentHealthCheck(targetId: string) {
    if (workbenchState.type !== 'ready') {
      return;
    }

    await runDeploymentHealthCheck(workbenchState.workbench.projectId, targetId, { actorId: currentActorId });
    await refreshWorkbench({ keepCurrent: true });
  }

  async function handleRecordDeploymentOperation(
    targetId: string,
    input: Omit<DeploymentOperationInput, 'actorId'>
  ) {
    if (workbenchState.type !== 'ready') {
      return;
    }

    await recordDeploymentOperation(workbenchState.workbench.projectId, targetId, {
      actorId: currentActorId,
      ...input
    });
    await refreshWorkbench({ keepCurrent: true });
  }

  async function handleConfigureComposeEnvironment(targetId: string, input: ComposeEnvironmentInput) {
    if (workbenchState.type !== 'ready') {
      return;
    }

    await configureComposeEnvironment(workbenchState.workbench.projectId, targetId, input, currentActorId);
    await refreshWorkbench({ keepCurrent: true });
  }

  async function handleValidateComposeEnvironment(environmentId: string) {
    if (workbenchState.type !== 'ready') {
      return;
    }

    await validateComposeEnvironment(workbenchState.workbench.projectId, environmentId, { actorId: currentActorId });
    await refreshWorkbench({ keepCurrent: true });
  }

  async function handleStartComposeEnvironment(environmentId: string) {
    if (workbenchState.type !== 'ready') {
      return;
    }

    await startComposeEnvironment(workbenchState.workbench.projectId, environmentId, { actorId: currentActorId });
    await refreshWorkbench({ keepCurrent: true });
  }

  async function handleStopComposeEnvironment(environmentId: string) {
    if (workbenchState.type !== 'ready') {
      return;
    }

    await stopComposeEnvironment(workbenchState.workbench.projectId, environmentId, { actorId: currentActorId });
    await refreshWorkbench({ keepCurrent: true });
  }

  async function handleCaptureComposeLogs(environmentId: string) {
    if (workbenchState.type !== 'ready') {
      return;
    }

    await captureComposeLogs(workbenchState.workbench.projectId, environmentId, { actorId: currentActorId });
    await refreshWorkbench({ keepCurrent: true });
  }

  async function handleDecideLocalCommandApproval(taskId: string, decision: 'ALLOW' | 'DENY') {
    if (workbenchState.type !== 'ready') {
      return;
    }

    setApprovalBusyTaskId(taskId);
    try {
      await decideLocalCommandApproval(workbenchState.workbench.projectId, taskId, {
        actorId: currentActorId,
        decision,
        note: decision === 'ALLOW' ? '用户在运行中心批准执行' : '用户在运行中心拒绝执行'
      });
      await refreshWorkbench({ keepCurrent: true });
    } finally {
      setApprovalBusyTaskId(null);
    }
  }

  async function handleCancelLocalExecutionTask(taskId: string) {
    if (workbenchState.type !== 'ready') {
      return;
    }

    setCancelBusyTaskId(taskId);
    try {
      await cancelLocalExecutionTask(workbenchState.workbench.projectId, taskId, {
        actorId: currentActorId,
        note: '用户在运行中心取消任务'
      });
      await refreshWorkbench({ keepCurrent: true });
    } finally {
      setCancelBusyTaskId(null);
    }
  }

  async function handleRetryAgentRun(runId: string) {
    if (workbenchState.type !== 'ready') {
      return;
    }

    setRetryBusyRunId(runId);
    try {
      await retryAgentRun(workbenchState.workbench.projectId, runId, currentActorId);
      const agentRuntime = await loadAgentRuntimeSnapshot(workbenchState.workbench.projectId, currentActorId);
      setWorkbenchState((current) =>
        current.type === 'ready'
          ? {
              ...current,
              agentRuns: agentRuntime.agentRuns,
              agentRunEvents: agentRuntime.agentRunEvents,
              refreshing: false,
              syncError: undefined
            }
          : current
      );
    } catch {
      setWorkbenchState((current) =>
        current.type === 'ready' ? { ...current, refreshing: false, syncError: syncFailureMessage } : current
      );
    } finally {
      setRetryBusyRunId(null);
    }
  }

  async function handleClaimAgentRun(runId: string) {
    if (workbenchState.type !== 'ready') {
      return;
    }

    setClaimBusyRunId(runId);
    try {
      await claimAgentRun(workbenchState.workbench.projectId, runId, currentActorId);
      const agentRuntime = await loadAgentRuntimeSnapshot(workbenchState.workbench.projectId, currentActorId);
      setWorkbenchState((current) =>
        current.type === 'ready'
          ? {
              ...current,
              agentRuns: agentRuntime.agentRuns,
              agentRunEvents: agentRuntime.agentRunEvents,
              refreshing: false,
              syncError: undefined
            }
          : current
      );
    } catch {
      setWorkbenchState((current) =>
        current.type === 'ready' ? { ...current, refreshing: false, syncError: syncFailureMessage } : current
      );
    } finally {
      setClaimBusyRunId(null);
    }
  }

  async function handleClaimNextAgentRun() {
    if (workbenchState.type !== 'ready') {
      return;
    }

    setClaimNextBusy(true);
    try {
      await claimNextAgentRun(workbenchState.workbench.projectId, currentActorId);
      const agentRuntime = await loadAgentRuntimeSnapshot(workbenchState.workbench.projectId, currentActorId);
      setWorkbenchState((current) =>
        current.type === 'ready'
          ? {
              ...current,
              agentRuns: agentRuntime.agentRuns,
              agentRunEvents: agentRuntime.agentRunEvents,
              refreshing: false,
              syncError: undefined
            }
          : current
      );
    } catch {
      setWorkbenchState((current) =>
        current.type === 'ready' ? { ...current, refreshing: false, syncError: syncFailureMessage } : current
      );
    } finally {
      setClaimNextBusy(false);
    }
  }

  async function handleDismissTopNotification(notificationId: string) {
    if (workbenchState.type !== 'ready') {
      return;
    }

    setDismissedNotificationIds((current) => new Set([...current, notificationId]));
    try {
      await markRuntimeNotificationRead(workbenchState.workbench.projectId, notificationId, currentActorId);
      await refreshWorkbench({ keepCurrent: true });
    } catch {
      setWorkbenchState((current) =>
        current.type === 'ready' ? { ...current, refreshing: false, syncError: syncFailureMessage } : current
      );
    }
  }

  async function handleMarkAllRuntimeNotificationsRead() {
    if (workbenchState.type !== 'ready' || runtimeNotificationActionBusy) {
      return;
    }

    setRuntimeNotificationActionBusy(true);
    try {
      await markAllRuntimeNotificationsRead(workbenchState.workbench.projectId, currentActorId);
      setDismissedNotificationIds((current) => {
        const next = new Set(current);
        runtimeNotificationsFromWorkbench(workbenchState.workbench).forEach((notification) => {
          next.add(notification.id);
        });
        return next;
      });
      await refreshWorkbench({ keepCurrent: true });
    } catch {
      setWorkbenchState((current) =>
        current.type === 'ready' ? { ...current, refreshing: false, syncError: syncFailureMessage } : current
      );
    } finally {
      setRuntimeNotificationActionBusy(false);
    }
  }

  async function handleUpdateRoleAgentConfig(role: RoleAgentConfig['role'], input: RoleAgentConfigInput) {
    if (workbenchState.type !== 'ready') {
      return;
    }

    const projectId = workbenchState.workbench.projectId;
    await updateRoleAgentConfig(projectId, rolePathByModelRole[role], input, currentActorId);
    const roleAgentConfigs = await loadRoleAgentConfigs(projectId, currentActorId);
    setWorkbenchState((current) =>
      current.type === 'ready'
        ? { ...current, roleAgentConfigs, refreshing: false, syncError: undefined }
        : current
    );
  }

  /**
   * 提交底部 Agent Composer 指令。
   * 作用域：工作台模型交互；场景：把当前角色、上下文摘要、模型覆盖和运行选项提交到后端模型网关。
   */
  async function handleSubmitAgentComposer(instruction: string) {
    if (workbenchState.type !== 'ready') {
      return;
    }

    const currentWorkbench = workbenchState.workbench;
    const currentSelectedRole = readySelectedRole;
    const roleKey = roleKeyByDisplayRole[currentSelectedRole];
    const rolePath = rolePathByModelRole[roleKey];
    const selectedModel = currentWorkbench.modelGateway.bindings.find((binding) => formatModelBindingRef(binding) === composerModelRef);
    const submittedAt = new Date().toISOString();
    setComposerSubmitState({ status: 'sending', submittedInstruction: instruction, submittedAt });
    try {
      const response = await createRoleModelRequestStream(
        currentWorkbench.projectId,
        rolePath,
        {
          actorUserId: currentActorId,
          instruction,
          contextBlocks: buildComposerContextBlocks(currentWorkbench, currentSelectedRole, composerIntentState.tokenEconomy),
          providerId: selectedModel?.providerId,
          model: selectedModel?.model,
          approvalMode: composerApprovalMode,
          reasoningEffort: composerReasoningEffort,
          planMode: composerIntentState.plan,
          goalMode: composerIntentState.goal,
          tokenEconomy: composerIntentState.tokenEconomy
        },
        (delta) => {
          setComposerSubmitState((current) => {
            if (
              (current.status === 'sending' || current.status === 'streaming') &&
              current.submittedAt === submittedAt
            ) {
              return {
                status: 'streaming',
                submittedInstruction: instruction,
                submittedAt,
                partialAnswer: (current.status === 'streaming' ? current.partialAnswer : '') + delta
              };
            }
            return current;
          });
        },
        currentActorId
      );
      setComposerDraft('');
      setComposerSubmitState({ status: 'answered', submittedInstruction: instruction, submittedAt, response });
      void refreshWorkbench({ keepCurrent: true }).catch(() => undefined);
    } catch (error) {
      setComposerSubmitState({
        status: 'error',
        submittedInstruction: instruction,
        submittedAt,
        message: error instanceof Error ? error.message : '模型请求失败，请稍后重试'
      });
    }
  }

  if (workbenchState.type === 'loading') {
    return (
      <main className="loading">
        <section className="loading__panel" aria-label="连接状态">
          <strong>正在连接团队服务器...</strong>
          <div className="loading__bar" />
        </section>
      </main>
    );
  }

  if (workbenchState.type === 'error') {
    const authRequired = workbenchState.message === '需要登录 MatrixCode';

    return (
      <main className="loading">
        <section className="loading__panel" aria-label="连接错误">
          <strong>{authRequired ? '欢迎使用 Matrix 智能平台' : workbenchState.message}</strong>
          {authRequired ? (
            <>
              <form aria-label="本地登录" className="auth-login-form" onSubmit={handleLoginFromErrorPage}>
                <label className="auth-login-form__field">
                  <span>用户名</span>
                  <input
                    aria-label="用户名"
                    autoComplete="username"
                    onChange={(event) => setLoginUsername(event.target.value)}
                    value={loginUsername}
                  />
                </label>
                <label className="auth-login-form__field">
                  <span>密码</span>
                  <input
                    aria-label="密码"
                    autoComplete="current-password"
                    onChange={(event) => setLoginPassword(event.target.value)}
                    type="password"
                    value={loginPassword}
                  />
                </label>
                {loginErrorMessage ? (
                  <p className="auth-login-form__error" role="alert">
                    {loginErrorMessage}
                  </p>
                ) : null}
                <button className="retry-button" disabled={loginBusy} type="submit">
                  {loginBusy ? '登录中' : '登录并加载工作台'}
                </button>
              </form>
            </>
          ) : (
            <>
              <p className="loading__message">请检查团队服务器是否启动，或稍后重新连接。</p>
              <button className="retry-button" onClick={() => void refreshWorkbench()} type="button">
                重新连接
              </button>
            </>
          )}
        </section>
      </main>
    );
  }

  const { workbench, roleAgentConfigs, refreshing, syncError } = workbenchState;
  const stageViews = buildStageViews(workbench.currentStage);
  const selectedRole = readySelectedRole;
  const actorOptions = buildActorOptions(selectedRole, workbenchState.projectMembers, currentActorId);
  const runtimeNotifications = runtimeNotificationsFromWorkbench(workbench);
  const unreadRuntimeNotificationCount = runtimeNotifications.filter((notification) => !notification.readAt).length;
  const visibleRuntimeNotifications =
    runtimeNotificationFilter === 'unread'
      ? runtimeNotifications.filter((notification) => !notification.readAt)
      : runtimeNotifications;
  const pendingApprovalCount = countPendingApprovals(workbench);
  const topNotification = latestVisibleNotification(runtimeNotifications, dismissedNotificationIds);
  const hasServerRuntimeNotifications = Array.isArray(workbench.runtimeNotifications);
  const modelOptions = readyModelRefs.length ? readyModelRefs : uniqueModelRefs([formatModelBindingRef(workbench.modelGateway.bindings[0])]);
  const currentComposerModelRef = composerModelRef || modelOptions[0];
  const composerSubmitting = composerSubmitState.status === 'sending' || composerSubmitState.status === 'streaming';
  const composerIntentLabels = [
    composerIntentState.plan ? '计划' : '',
    composerIntentState.goal ? '目标' : '',
    composerIntentState.tokenEconomy ? '省 token' : ''
  ].filter(Boolean);
  const composerContextSummary = `${workbench.documents.length.toLocaleString('zh-CN')} 份文档 · ${workbench.events.length.toLocaleString('zh-CN')} 条事件`;
  const roleWorkflowPanel = (
    <>
      {selectedRole === '产品' ? (
        <ProductPanel
          documents={workbench.documents}
          onCreateDrafts={handleCreateProductDrafts}
          onFreezeDocument={handleFreezeDocument}
          onSubmitAcceptance={handleSubmitAcceptance}
        />
      ) : null}
      {selectedRole === '开发' ? (
        <DeveloperPanel
          workspaces={workbench.localExecution.workspaces}
          onPrepareExecution={handlePrepareCodingAgentExecution}
          onApplyPatch={handleApplyCodingAgentPatch}
          onRecordHandoff={handleRecordCodingAgentHandoff}
          onSubmitDelivery={handleSubmitDeveloperDelivery}
        />
      ) : null}
      {selectedRole === '测试' ? (
        <TesterPanel
          bugs={workbench.bugs}
          onCreateBug={handleCreateBug}
          onSubmitReport={handleSubmitTestReport}
          onTransitionBug={handleTransitionBug}
        />
      ) : null}
      {selectedRole === '运维' ? (
        <OpsPanel
          workspaces={workbench.localExecution.workspaces}
          deploymentTargets={workbench.deploymentTargets}
          deploymentRuntimeSummaries={workbench.deploymentRuntimeSummaries}
          composeEnvironments={workbench.composeEnvironments}
          composeRuntimeViews={workbench.composeRuntimeViews}
          onConfigureTarget={handleConfigureDeploymentTarget}
          onRunHealthCheck={handleRunDeploymentHealthCheck}
          onRecordDeploymentOperation={handleRecordDeploymentOperation}
          onConfigureComposeEnvironment={handleConfigureComposeEnvironment}
          onValidateComposeEnvironment={handleValidateComposeEnvironment}
          onStartComposeEnvironment={handleStartComposeEnvironment}
          onStopComposeEnvironment={handleStopComposeEnvironment}
          onCaptureComposeLogs={handleCaptureComposeLogs}
        />
      ) : null}
    </>
  );

  return (
    <main className="workspace">
      <aside className="sidebar" aria-label="项目导航">
        <header className="brand">
          <h1 className="brand__name">MatrixCode</h1>
          <span className="brand__status">在线</span>
        </header>
        <RoleSwitcher
          activeRole={selectedRole}
          onRoleChange={setActiveRole}
          pendingApprovalCount={pendingApprovalCount}
          roles={workbench.roles}
        />
      </aside>

      <section className="workspace-main" aria-label="项目工作台">
        <header className="stage-header">
          <div className="stage-header__title">
            <p className="eyebrow">{workbench.projectName}</p>
            <h2>{workbench.currentStage}</h2>
          </div>
          <div className="stage-header__actions">
            <label className="actor-compact">
              <span>操作者</span>
              <select
                aria-label="当前操作者"
                value={currentActorId}
                onChange={(event) => {
                  const nextActorUserId = event.target.value;
                  setSelectedActorUserId(nextActorUserId);
                  storeCurrentActorUserId(nextActorUserId);
                  void refreshAgentRunUserAudit(nextActorUserId);
                }}
              >
                {actorOptions.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </label>
            <button
              aria-label="诊断"
              className="icon-button"
              onClick={() => setRuntimeDiagnosticsOpen(true)}
              title="诊断"
              type="button"
            >
              ⌁
            </button>
            <button
              aria-label="文档"
              className="icon-button"
              onClick={() => setDocumentCenterOpen(true)}
              title="文档"
              type="button"
            >
              ◰
            </button>
            <button
              aria-label="运行"
              className="icon-button"
              onClick={() => setOperationsCenterOpen(true)}
              title="运行"
              type="button"
            >
              ▶
            </button>
            <button
              aria-label="工作流"
              className="icon-button"
              onClick={() => setWorkflowPanelOpen(true)}
              title="工作流"
              type="button"
            >
              ⧉
            </button>
            <button
              aria-label="配置"
              className="icon-button"
              onClick={() => setConfigDialogOpen(true)}
              title="配置"
              type="button"
            >
              ⚙
            </button>
            <span className={`stage-badge ${refreshing ? 'stage-badge--muted' : ''}`} title={refreshing ? '同步中' : '自动同步'}>
              {refreshing ? '刷新中' : '自动同步'}
            </span>
          </div>
        </header>

        <div className="stage-rail">
          {syncError ? (
            <p className="sync-alert" role="status">
              {syncError}
            </p>
          ) : null}
          {topNotification ? (
            <section
              className={`runtime-alert runtime-alert--${topNotification.level.toLowerCase()}`}
              role="status"
              aria-label="运行态提醒"
            >
              <div className="runtime-alert__body">
                <strong>{topNotification.title}</strong>
                <span>{topNotification.message}</span>
              </div>
              <button
                aria-label="关闭提醒"
                className="runtime-alert__close"
                onClick={() => void handleDismissTopNotification(topNotification.id)}
                type="button"
              >
                ×
              </button>
            </section>
          ) : null}
          <section className="stage-track" aria-label="文档流转阶段">
            {stageViews.map((stage) => (
              <article
                aria-label={`${stage.name}，${stage.statusLabel}`}
                className={`stage-step stage-step--${stage.status}`}
                key={stage.name}
              >
                <span className="stage-step__number">{stage.number}</span>
                <strong className="stage-step__label">{stage.name}</strong>
              </article>
            ))}
          </section>
        </div>

        <AgentOutputConsole
          agentRunEvents={workbenchState.agentRunEvents}
          agentRuns={workbenchState.agentRuns}
          approvalMode={composerApprovalMode}
          composerDraft={composerDraft}
          composerSubmitState={composerSubmitState}
          currentModelRef={currentComposerModelRef}
          reasoningEffort={composerReasoningEffort}
          selectedRole={selectedRole}
        />

        <section className="agent-dialog-console agent-dialog-console--composer" aria-label="大模型对话台">
          <div className="agent-dialog-console__header">
            <div>
              <p className="eyebrow">大模型对话台</p>
              <h3>{selectedRole}智能体</h3>
            </div>
            <span>{currentComposerModelRef}</span>
          </div>
          <AgentComposer
            approvalMode={composerApprovalMode}
            currentModelRef={currentComposerModelRef}
            draft={composerDraft}
            intentState={composerIntentState}
            modelOptions={modelOptions}
            onApprovalModeChange={setComposerApprovalMode}
            onCurrentModelRefChange={setComposerModelRef}
            onDraftChange={setComposerDraft}
            onIntentStateChange={setComposerIntentState}
            onReasoningEffortChange={setComposerReasoningEffort}
            onSubmit={handleSubmitAgentComposer}
            reasoningEffort={composerReasoningEffort}
            selectedRole={selectedRole}
            submitting={composerSubmitting}
          />
        </section>
      </section>

      <WorkspaceContextPanel tab={contextPanelTab} onTabChange={setContextPanelTab} workbench={workbench} />

      <WorkbenchStatusBar
        agentRunEvents={workbenchState.agentRunEvents}
        agentRuns={workbenchState.agentRuns}
        composerContextSummary={composerContextSummary}
        composerIntentLabels={composerIntentLabels}
        metrics={workbench.metrics}
        modelGateway={workbench.modelGateway}
        runtimeNotificationUnreadCount={unreadRuntimeNotificationCount}
        selectedRole={selectedRole}
      />
      {workflowPanelOpen ? (
        <div className="config-dialog-backdrop">
          <section aria-label="角色工作流" className="config-dialog workflow-dialog" role="dialog">
            <header className="config-dialog__header">
              <div>
                <p className="eyebrow">角色工作流</p>
                <h2>{selectedRole}工作台</h2>
              </div>
              <button
                aria-label="关闭角色工作流"
                className="config-dialog__close"
                onClick={() => setWorkflowPanelOpen(false)}
                type="button"
              >
                ×
              </button>
            </header>
            <div className="workflow-dialog__body">{roleWorkflowPanel}</div>
          </section>
        </div>
      ) : null}
      <RoleAgentConfigDialog
        actorUserId={currentActorId}
        configs={roleAgentConfigs}
        open={configDialogOpen}
        projectId={workbench.projectId}
        onClose={() => setConfigDialogOpen(false)}
        onActorSessionChange={(actorUserId) => {
          storeCurrentActorUserId(actorUserId);
          setSelectedActorUserId(actorUserId);
          void refreshWorkbench({ keepCurrent: true, actorUserId }).catch(() => undefined);
        }}
        onUpdateRoleAgentConfig={handleUpdateRoleAgentConfig}
      />
      <DocumentCenterDialog
        documents={workbench.documents}
        open={documentCenterOpen}
        onClose={() => setDocumentCenterOpen(false)}
      />
      <RuntimeDiagnosticsDialog
        actorUserId={currentActorId}
        open={runtimeDiagnosticsOpen}
        onClose={() => setRuntimeDiagnosticsOpen(false)}
        projectId={workbench.projectId}
      />
      <OperationsCenterDialog
        projectId={workbench.projectId}
        actorUserId={currentActorId}
        projectMembers={workbenchState.projectMembers}
        agentRunUserAudit={workbenchState.agentRunUserAudit}
        agentRunEvents={workbenchState.agentRunEvents}
        agentRuns={workbenchState.agentRuns}
        bugs={workbench.bugs}
        deploymentTargets={workbench.deploymentTargets}
        deploymentRuntimeSummaries={workbench.deploymentRuntimeSummaries}
        composeEnvironments={workbench.composeEnvironments}
        composeRuntimeViews={workbench.composeRuntimeViews}
        events={workbench.events}
        metrics={workbench.metrics}
        modelGateway={workbench.modelGateway}
        localExecution={workbench.localExecution}
        runtimeNotifications={visibleRuntimeNotifications}
        runtimeNotificationFilter={runtimeNotificationFilter}
        runtimeNotificationUnreadCount={unreadRuntimeNotificationCount}
        runtimeNotificationActionBusy={runtimeNotificationActionBusy}
        runtimeNotificationActionsEnabled={hasServerRuntimeNotifications}
        approvalBusyTaskId={approvalBusyTaskId}
        cancelBusyTaskId={cancelBusyTaskId}
        retryBusyRunId={retryBusyRunId}
        claimBusyRunId={claimBusyRunId}
        claimNextBusy={claimNextBusy}
        onRuntimeNotificationFilterChange={setRuntimeNotificationFilter}
        onMarkAllRuntimeNotificationsRead={handleMarkAllRuntimeNotificationsRead}
        onDecideLocalCommandApproval={handleDecideLocalCommandApproval}
        onCancelLocalExecutionTask={handleCancelLocalExecutionTask}
        onRetryAgentRun={handleRetryAgentRun}
        onClaimAgentRun={handleClaimAgentRun}
        onClaimNextAgentRun={handleClaimNextAgentRun}
        open={operationsCenterOpen}
        onClose={() => setOperationsCenterOpen(false)}
      />
    </main>
  );
}

export default App;
