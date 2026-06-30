import { useEffect, useMemo, useState, type FormEvent } from 'react';
import type {
  CodingAgentExecutionPlan,
  CodingAgentExecutionStatus,
  CodingAgentPatchResult,
  CodingAgentStepType,
  DocumentSummary,
  ExecutionTaskStatus,
  WorkspaceAuthorization
} from '../api/client';

export type DeveloperDeliveryInput = {
  workspacePath: string;
  implementationNote: string;
  selfTestResult: string;
  apiDoc: string;
  databaseScript: string;
  deploymentDoc: string;
};

export type DeveloperExecutionInput = {
  goal: string;
  workspaceId: string;
  testCommand: string;
};

export type DeveloperPatchInput = {
  workspaceId: string;
  relativePath: string;
  expectedContent: string;
  nextContent: string;
  summary: string;
  approved: boolean;
};

export type DeveloperHandoffInput = {
  workspaceId: string;
  goal: string;
  relativePath: string;
  patchSummary: string;
  diffSummary: string;
  testTaskId: string;
  testTaskStatus: string;
  testCommand: string;
  deliveryConclusion: string;
};

type DeveloperPanelProps = {
  workspaces: WorkspaceAuthorization[];
  onPrepareExecution: (input: DeveloperExecutionInput) => Promise<CodingAgentExecutionPlan>;
  onApplyPatch: (input: DeveloperPatchInput) => Promise<CodingAgentPatchResult>;
  onRecordHandoff: (input: DeveloperHandoffInput) => Promise<DocumentSummary>;
  onSubmitDelivery: (input: DeveloperDeliveryInput) => Promise<void>;
};

const initialDelivery: DeveloperDeliveryInput = {
  workspacePath: '',
  implementationNote: '',
  selfTestResult: '',
  apiDoc: '',
  databaseScript: '',
  deploymentDoc: ''
};
const initialPatchInput: DeveloperPatchInput = {
  workspaceId: '',
  relativePath: '',
  expectedContent: '',
  nextContent: '',
  summary: '',
  approved: false
};

const requiredFieldMessage = '请填写本地工作区路径、实现说明、自测结果、接口文档、数据库脚本和部署文档';
const executionRequiredFieldMessage = '请选择授权工作区，并填写执行目标和测试命令';
const patchRequiredFieldMessage = '请选择授权工作区，填写相对路径、内容和变更说明，并确认应用 patch';
const handoffRequiredFieldMessage = '请先生成执行准备、应用 patch，并填写交付结论';
const syncFailureMessage = '同步最新工作台失败，请稍后重试';
const executionStatusLabels: Record<CodingAgentExecutionStatus, string> = {
  READY: '就绪',
  REVIEW_REQUIRED: '需审查',
  APPROVAL_REQUIRED: '需要审批',
  SUBMITTED: '已提交',
  CAPTURED: '已采集'
};
const executionStepTypeLabels: Record<CodingAgentStepType, string> = {
  CONTEXT_RECALL: '上下文召回',
  PLAN_REVIEW: '计划审查',
  FILE_REVIEW: '文件阅读',
  CODE_EDIT: '代码编辑',
  TEST_COMMAND: '测试命令',
  DIFF_REVIEW: 'Diff 审查',
  HANDOFF: '交付回溯'
};
const localTaskStatusLabels: Record<ExecutionTaskStatus, string> = {
  APPROVAL_PENDING: '审批待处理',
  DENIED: '已拒绝',
  QUEUED: '排队中',
  RUNNING: '运行中',
  SUCCESS: '成功',
  FAILED: '失败',
  CANCELED: '已取消'
};

function messageFromError(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback;
}

function workspaceAccessTime(workspace: WorkspaceAuthorization) {
  const accessedAt = Date.parse(workspace.lastAccessedAt);
  return Number.isNaN(accessedAt) ? 0 : accessedAt;
}

export function DeveloperPanel({
  workspaces,
  onPrepareExecution,
  onApplyPatch,
  onRecordHandoff,
  onSubmitDelivery
}: DeveloperPanelProps) {
  const [delivery, setDelivery] = useState<DeveloperDeliveryInput>(initialDelivery);
  const [executionInput, setExecutionInput] = useState<DeveloperExecutionInput>({
    goal: '',
    workspaceId: '',
    testCommand: 'git status'
  });
  const [executionPlan, setExecutionPlan] = useState<CodingAgentExecutionPlan | null>(null);
  const [patchInput, setPatchInput] = useState<DeveloperPatchInput>(initialPatchInput);
  const [patchResult, setPatchResult] = useState<CodingAgentPatchResult | null>(null);
  const [handoffConclusion, setHandoffConclusion] = useState('');
  const [handoffDocument, setHandoffDocument] = useState<DocumentSummary | null>(null);
  const [preparing, setPreparing] = useState(false);
  const [patching, setPatching] = useState(false);
  const [recordingHandoff, setRecordingHandoff] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState('');
  const [executionErrorMessage, setExecutionErrorMessage] = useState('');
  const [patchErrorMessage, setPatchErrorMessage] = useState('');
  const [handoffErrorMessage, setHandoffErrorMessage] = useState('');
  const authorizedWorkspaces = useMemo(
    () =>
      [...workspaces.filter((workspace) => workspace.status === 'AUTHORIZED')].sort(
        (left, right) => workspaceAccessTime(right) - workspaceAccessTime(left)
      ),
    [workspaces]
  );
  const canPrepare = Boolean(
    executionInput.goal.trim() && executionInput.workspaceId.trim() && executionInput.testCommand.trim()
  );
  const canApplyPatch = Boolean(
    patchInput.workspaceId.trim() &&
      patchInput.relativePath.trim() &&
      patchInput.expectedContent &&
      patchInput.nextContent &&
      patchInput.summary.trim() &&
      patchInput.approved
  );
  const canRecordHandoff = Boolean(executionPlan && patchResult && handoffConclusion.trim());
  const canSubmit = Boolean(
    delivery.workspacePath.trim() &&
      delivery.implementationNote.trim() &&
      delivery.selfTestResult.trim() &&
      delivery.apiDoc.trim() &&
      delivery.databaseScript.trim() &&
      delivery.deploymentDoc.trim()
  );

  useEffect(() => {
    if (!authorizedWorkspaces.length) {
      if (executionInput.workspaceId) {
        setExecutionInput((current) => ({ ...current, workspaceId: '' }));
      }
      if (patchInput.workspaceId) {
        setPatchInput((current) => ({ ...current, workspaceId: '' }));
      }
      return;
    }

    if (!authorizedWorkspaces.some((workspace) => workspace.id === executionInput.workspaceId)) {
      setExecutionInput((current) => ({ ...current, workspaceId: authorizedWorkspaces[0].id }));
    }
    if (!authorizedWorkspaces.some((workspace) => workspace.id === patchInput.workspaceId)) {
      setPatchInput((current) => ({ ...current, workspaceId: authorizedWorkspaces[0].id }));
    }
  }, [authorizedWorkspaces, executionInput.workspaceId, patchInput.workspaceId]);

  function updateExecutionInput(field: keyof DeveloperExecutionInput, value: string) {
    setExecutionInput((current) => ({ ...current, [field]: value }));
  }

  function updatePatchInput(field: keyof DeveloperPatchInput, value: string | boolean) {
    setPatchInput((current) => ({ ...current, [field]: value }));
  }

  function updateDelivery(field: keyof DeveloperDeliveryInput, value: string) {
    setDelivery((current) => ({ ...current, [field]: value }));
  }

  async function handlePrepareExecution(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canPrepare) {
      setExecutionErrorMessage(executionRequiredFieldMessage);
      return;
    }

    setPreparing(true);
    setExecutionErrorMessage('');
    try {
      const plan = await onPrepareExecution({
        goal: executionInput.goal.trim(),
        workspaceId: executionInput.workspaceId,
        testCommand: executionInput.testCommand.trim()
      });
      setExecutionPlan(plan);
    } catch (error) {
      setExecutionErrorMessage(messageFromError(error, '生成执行准备失败，请稍后重试'));
    } finally {
      setPreparing(false);
    }
  }

  async function handleApplyPatch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canApplyPatch) {
      setPatchErrorMessage(patchRequiredFieldMessage);
      return;
    }

    setPatching(true);
    setPatchErrorMessage('');
    try {
      const result = await onApplyPatch({
        workspaceId: patchInput.workspaceId,
        relativePath: patchInput.relativePath.trim(),
        expectedContent: patchInput.expectedContent,
        nextContent: patchInput.nextContent,
        summary: patchInput.summary.trim(),
        approved: patchInput.approved
      });
      setPatchResult(result);
      setHandoffDocument(null);
    } catch (error) {
      setPatchErrorMessage(messageFromError(error, '应用受控 Patch 失败，请重新检查内容'));
    } finally {
      setPatching(false);
    }
  }

  async function handleRecordHandoff(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canRecordHandoff || !executionPlan || !patchResult) {
      setHandoffErrorMessage(handoffRequiredFieldMessage);
      return;
    }

    setRecordingHandoff(true);
    setHandoffErrorMessage('');
    try {
      const document = await onRecordHandoff({
        workspaceId: patchResult.workspaceId,
        goal: executionPlan.task.goal,
        relativePath: patchResult.relativePath,
        patchSummary: patchResult.summary,
        diffSummary: patchResult.gitDiffSummary.stat || `${patchResult.gitDiffSummary.changedFiles.length} 个文件变更`,
        testTaskId: executionPlan.testCommandTask.taskId,
        testTaskStatus: executionPlan.testCommandTask.status,
        testCommand: executionPlan.testCommandTask.command,
        deliveryConclusion: handoffConclusion.trim()
      });
      setHandoffDocument(document);
    } catch (error) {
      setHandoffErrorMessage(messageFromError(error, '记录交付回溯失败，请稍后重试'));
    } finally {
      setRecordingHandoff(false);
    }
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canSubmit) {
      setErrorMessage(requiredFieldMessage);
      return;
    }

    setSubmitting(true);
    setErrorMessage('');
    try {
      await onSubmitDelivery(delivery);
      setDelivery(initialDelivery);
    } catch (error) {
      const message = messageFromError(error, '提交开发交付失败，请稍后重试');
      setErrorMessage(message === syncFailureMessage ? '' : message);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <article className="panel role-panel" aria-label="开发工作区">
      <header className="panel__header">
        <h3 className="panel-title">开发工作区</h3>
        <span className="panel-note">交付说明与自测</span>
      </header>
      <form
        aria-label="编码智能体执行准备"
        className="role-form coding-agent-form"
        onSubmit={(event) => void handlePrepareExecution(event)}
      >
        <h4 className="form-section-title">编码智能体执行准备</h4>
        <div className="field-grid">
          <label className="field" htmlFor="developer-execution-workspace">
            <span>授权工作区</span>
            <select
              disabled={!authorizedWorkspaces.length}
              id="developer-execution-workspace"
              onChange={(event) => updateExecutionInput('workspaceId', event.target.value)}
              value={executionInput.workspaceId}
            >
              {authorizedWorkspaces.map((workspace) => (
                <option key={workspace.id} value={workspace.id}>
                  {workspace.name} · {workspace.rootPath}
                </option>
              ))}
            </select>
          </label>
          <label className="field" htmlFor="developer-execution-test-command">
            <span>测试命令</span>
            <input
              disabled={!authorizedWorkspaces.length}
              id="developer-execution-test-command"
              onChange={(event) => updateExecutionInput('testCommand', event.target.value)}
              type="text"
              value={executionInput.testCommand}
            />
          </label>
        </div>
        <label className="field" htmlFor="developer-execution-goal">
          <span>执行目标</span>
          <textarea
            disabled={!authorizedWorkspaces.length}
            id="developer-execution-goal"
            onChange={(event) => updateExecutionInput('goal', event.target.value)}
            placeholder="例如：修复支付失败后的重试入口，并补齐自测"
            rows={3}
            value={executionInput.goal}
          />
        </label>
        {!authorizedWorkspaces.length ? <p className="form-hint">请先在运行中心授权本地工作区</p> : null}
        {executionErrorMessage ? <p className="inline-error">{executionErrorMessage}</p> : null}
        <div className="form-actions">
          <button className="primary-button" disabled={preparing || !canPrepare} type="submit">
            {preparing ? '生成中' : '生成执行准备'}
          </button>
        </div>
      </form>
      {executionPlan ? (
        <section className="coding-agent-result" aria-label="执行准备报告">
          <header className="coding-agent-result__header">
            <div>
              <h4>执行准备报告</h4>
              <span>{executionPlan.task.goal}</span>
            </div>
            <strong>{executionPlan.task.status}</strong>
          </header>
          <div className="coding-agent-summary">
            <article>
              <span>测试命令</span>
              <strong>{localTaskStatusLabels[executionPlan.testCommandTask.status]}</strong>
              <small>{executionPlan.testCommandTask.command}</small>
            </article>
            <article>
              <span>Diff 基线</span>
              <strong>{executionPlan.gitDiffSummary.repository ? 'Git 仓库' : '非 Git 仓库'}</strong>
              <small>{executionPlan.gitDiffSummary.stat || `${executionPlan.gitDiffSummary.changedFiles.length} 个文件变更`}</small>
            </article>
          </div>
          <ol className="coding-agent-steps">
            {executionPlan.executionSteps.map((step) => (
              <li className={`coding-agent-step coding-agent-step--${step.status.toLowerCase()}`} key={`${step.order}-${step.type}`}>
                <span>{String(step.order).padStart(2, '0')}</span>
                <div>
                  <strong>{step.title || executionStepTypeLabels[step.type]}</strong>
                  {step.title !== executionStepTypeLabels[step.type] ? <small>{executionStepTypeLabels[step.type]}</small> : null}
                  <p>{step.summary}</p>
                </div>
                <em>{executionStatusLabels[step.status]}</em>
              </li>
            ))}
          </ol>
        </section>
      ) : null}
      <form
        aria-label="受控 Patch 应用"
        className="role-form coding-agent-form"
        onSubmit={(event) => void handleApplyPatch(event)}
      >
        <h4 className="form-section-title">受控 Patch 应用</h4>
        <div className="field-grid">
          <label className="field" htmlFor="developer-patch-workspace">
            <span>Patch 工作区</span>
            <select
              disabled={!authorizedWorkspaces.length}
              id="developer-patch-workspace"
              onChange={(event) => updatePatchInput('workspaceId', event.target.value)}
              value={patchInput.workspaceId}
            >
              {authorizedWorkspaces.map((workspace) => (
                <option key={workspace.id} value={workspace.id}>
                  {workspace.name} · {workspace.rootPath}
                </option>
              ))}
            </select>
          </label>
          <label className="field" htmlFor="developer-patch-relative-path">
            <span>Patch 相对路径</span>
            <input
              disabled={!authorizedWorkspaces.length}
              id="developer-patch-relative-path"
              onChange={(event) => updatePatchInput('relativePath', event.target.value)}
              placeholder="例如：src/App.java"
              type="text"
              value={patchInput.relativePath}
            />
          </label>
        </div>
        <label className="field" htmlFor="developer-patch-summary">
          <span>变更说明</span>
          <input
            disabled={!authorizedWorkspaces.length}
            id="developer-patch-summary"
            onChange={(event) => updatePatchInput('summary', event.target.value)}
            type="text"
            value={patchInput.summary}
          />
        </label>
        <div className="field-grid">
          <label className="field" htmlFor="developer-patch-expected-content">
            <span>期望当前内容</span>
            <textarea
              disabled={!authorizedWorkspaces.length}
              id="developer-patch-expected-content"
              onChange={(event) => updatePatchInput('expectedContent', event.target.value)}
              rows={5}
              value={patchInput.expectedContent}
            />
          </label>
          <label className="field" htmlFor="developer-patch-next-content">
            <span>目标内容</span>
            <textarea
              disabled={!authorizedWorkspaces.length}
              id="developer-patch-next-content"
              onChange={(event) => updatePatchInput('nextContent', event.target.value)}
              rows={5}
              value={patchInput.nextContent}
            />
          </label>
        </div>
        <label className="checkbox-field" htmlFor="developer-patch-approved">
          <input
            checked={patchInput.approved}
            disabled={!authorizedWorkspaces.length}
            id="developer-patch-approved"
            onChange={(event) => updatePatchInput('approved', event.target.checked)}
            type="checkbox"
          />
          <span>确认应用 patch</span>
        </label>
        {patchErrorMessage ? <p className="inline-error">{patchErrorMessage}</p> : null}
        <div className="form-actions">
          <button className="primary-button" disabled={patching || !canApplyPatch} type="submit">
            {patching ? '应用中' : '应用受控 Patch'}
          </button>
        </div>
      </form>
      {patchResult ? (
        <section className="coding-agent-result" aria-label="Patch 应用结果">
          <header className="coding-agent-result__header">
            <div>
              <h4>Patch 应用结果</h4>
              <span>{patchResult.relativePath}</span>
            </div>
            <strong>{patchResult.role}</strong>
          </header>
          <div className="coding-agent-summary">
            <article>
              <span>写入结果</span>
              <strong>写入 {patchResult.bytesWritten} 字节</strong>
              <small>{patchResult.summary}</small>
            </article>
            <article>
              <span>Diff 摘要</span>
              <strong>{patchResult.gitDiffSummary.repository ? 'Git 仓库' : '非 Git 仓库'}</strong>
              <small>
                {patchResult.gitDiffSummary.stat || `${patchResult.gitDiffSummary.changedFiles.length} 个文件变更`}
              </small>
            </article>
          </div>
        </section>
      ) : null}
      {patchResult ? (
        <form
          aria-label="编码智能体交付回溯"
          className="role-form coding-agent-form"
          onSubmit={(event) => void handleRecordHandoff(event)}
        >
          <h4 className="form-section-title">交付回溯</h4>
          <label className="field" htmlFor="developer-handoff-conclusion">
            <span>交付结论</span>
            <textarea
              id="developer-handoff-conclusion"
              onChange={(event) => setHandoffConclusion(event.target.value)}
              rows={3}
              value={handoffConclusion}
            />
          </label>
          {handoffErrorMessage ? <p className="inline-error">{handoffErrorMessage}</p> : null}
          <div className="form-actions">
            <button className="primary-button" disabled={recordingHandoff || !canRecordHandoff} type="submit">
              {recordingHandoff ? '记录中' : '记录交付回溯'}
            </button>
          </div>
        </form>
      ) : null}
      {handoffDocument ? (
        <section className="coding-agent-result" aria-label="交付回溯已记录">
          <header className="coding-agent-result__header">
            <div>
              <h4>交付回溯已记录</h4>
              <span>{handoffDocument.id}</span>
            </div>
            <strong>{handoffDocument.title}</strong>
          </header>
        </section>
      ) : null}
      <form className="role-form" onSubmit={(event) => void handleSubmit(event)}>
        <label className="field" htmlFor="developer-workspace-path">
          <span>本地工作区路径</span>
          <input
            id="developer-workspace-path"
            onChange={(event) => updateDelivery('workspacePath', event.target.value)}
            placeholder="请输入本地工作区路径"
            type="text"
            value={delivery.workspacePath}
          />
        </label>
        <label className="field" htmlFor="developer-implementation-note">
          <span>实现说明</span>
          <textarea
            id="developer-implementation-note"
            onChange={(event) => updateDelivery('implementationNote', event.target.value)}
            rows={4}
            value={delivery.implementationNote}
          />
        </label>
        <label className="field" htmlFor="developer-self-test-result">
          <span>自测结果</span>
          <textarea
            id="developer-self-test-result"
            onChange={(event) => updateDelivery('selfTestResult', event.target.value)}
            rows={3}
            value={delivery.selfTestResult}
          />
        </label>
        <div className="field-grid">
          <label className="field" htmlFor="developer-api-doc">
            <span>接口文档</span>
            <textarea
              id="developer-api-doc"
              onChange={(event) => updateDelivery('apiDoc', event.target.value)}
              rows={3}
              value={delivery.apiDoc}
            />
          </label>
          <label className="field" htmlFor="developer-database-script">
            <span>数据库脚本</span>
            <textarea
              id="developer-database-script"
              onChange={(event) => updateDelivery('databaseScript', event.target.value)}
              rows={3}
              value={delivery.databaseScript}
            />
          </label>
        </div>
        <label className="field" htmlFor="developer-deployment-doc">
          <span>部署文档</span>
          <textarea
            id="developer-deployment-doc"
            onChange={(event) => updateDelivery('deploymentDoc', event.target.value)}
            rows={3}
            value={delivery.deploymentDoc}
          />
        </label>
        {errorMessage ? <p className="inline-error">{errorMessage}</p> : null}
        <div className="form-actions">
          <button className="primary-button" disabled={submitting || !canSubmit} type="submit">
            {submitting ? '提交中' : '提交开发交付'}
          </button>
        </div>
      </form>
    </article>
  );
}
