import { useEffect, useMemo, useState, type FormEvent } from 'react';
import type {
  ComposeEnvironment,
  ComposeEnvironmentInput,
  ComposeRuntimeView,
  DeploymentOperationStatus,
  DeploymentOperationType,
  DeploymentRuntimeSummary,
  DeploymentTarget,
  WorkspaceAuthorization
} from '../api/client';

export type OpsDeploymentTargetInput = {
  environmentName: string;
  environmentUrl: string;
  sshAddress: string;
  deployNote: string;
  healthCheckUrl: string;
  rollbackNote: string;
};

type OpsPanelProps = {
  workspaces: WorkspaceAuthorization[];
  deploymentTargets: DeploymentTarget[];
  deploymentRuntimeSummaries: DeploymentRuntimeSummary[];
  composeEnvironments: ComposeEnvironment[];
  composeRuntimeViews: ComposeRuntimeView[];
  onConfigureTarget: (input: OpsDeploymentTargetInput) => Promise<void>;
  onRunHealthCheck: (targetId: string) => Promise<void>;
  onRecordDeploymentOperation: (
    targetId: string,
    input: { type: DeploymentOperationType; status: DeploymentOperationStatus; note: string }
  ) => Promise<void>;
  onConfigureComposeEnvironment: (targetId: string, input: ComposeEnvironmentInput) => Promise<void>;
  onValidateComposeEnvironment: (environmentId: string) => Promise<void>;
  onStartComposeEnvironment: (environmentId: string) => Promise<void>;
  onStopComposeEnvironment: (environmentId: string) => Promise<void>;
  onCaptureComposeLogs: (environmentId: string) => Promise<void>;
};

const initialDeploymentTarget: OpsDeploymentTargetInput = {
  environmentName: '',
  environmentUrl: '',
  sshAddress: '',
  deployNote: '',
  healthCheckUrl: '',
  rollbackNote: ''
};

const initialComposeEnvironment: ComposeEnvironmentInput = {
  workspaceId: '',
  composeFilePath: '',
  projectName: '',
  serviceName: ''
};

const requiredFieldMessage = '请填写环境名称、环境地址、服务器地址、部署说明、健康检查地址和回滚说明';
const composeRequiredFieldMessage = '请选择部署目标和工作区，并填写 Compose 文件、项目名和服务名';
const syncFailureMessage = '同步最新工作台失败，请稍后重试';

const composeStatusLabels = {
  CONFIGURED: '已配置',
  VALIDATED: '已校验',
  RUNNING: '运行中',
  STOPPED: '已停止',
  FAILED: '失败'
} as const;

const composeOperationStatusLabels = {
  SUCCEEDED: '成功',
  FAILED: '失败'
} as const;

function messageFromError(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback;
}

export function OpsPanel({
  workspaces,
  deploymentTargets,
  deploymentRuntimeSummaries,
  composeEnvironments,
  composeRuntimeViews,
  onConfigureTarget,
  onRunHealthCheck,
  onRecordDeploymentOperation,
  onConfigureComposeEnvironment,
  onValidateComposeEnvironment,
  onStartComposeEnvironment,
  onStopComposeEnvironment,
  onCaptureComposeLogs
}: OpsPanelProps) {
  const [target, setTarget] = useState<OpsDeploymentTargetInput>(initialDeploymentTarget);
  const [submitting, setSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState('');
  const [selectedTargetId, setSelectedTargetId] = useState(deploymentTargets[0]?.id ?? '');
  const [operationBusy, setOperationBusy] = useState<'health' | 'deployment' | 'rollback' | null>(null);
  const [operationError, setOperationError] = useState('');
  const [deploymentStatus, setDeploymentStatus] = useState<DeploymentOperationStatus>('SUCCEEDED');
  const [deploymentNote, setDeploymentNote] = useState('');
  const [rollbackStatus, setRollbackStatus] = useState<DeploymentOperationStatus>('RECORDED');
  const [rollbackNote, setRollbackNote] = useState('');
  const [composeEnvironment, setComposeEnvironment] = useState<ComposeEnvironmentInput>(initialComposeEnvironment);
  const [composeSubmitting, setComposeSubmitting] = useState(false);
  const [composeError, setComposeError] = useState('');
  const [selectedComposeEnvironmentId, setSelectedComposeEnvironmentId] = useState(composeEnvironments[0]?.id ?? '');
  const [composeBusy, setComposeBusy] = useState<'validate' | 'start' | 'stop' | 'logs' | null>(null);
  const canSubmit = Boolean(
    target.environmentName.trim() &&
      target.environmentUrl.trim() &&
      target.sshAddress.trim() &&
      target.deployNote.trim() &&
      target.healthCheckUrl.trim() &&
      target.rollbackNote.trim()
  );
  const selectedTarget = deploymentTargets.find((item) => item.id === selectedTargetId);
  const selectedRuntimeSummary = useMemo(
    () => deploymentRuntimeSummaries.find((summary) => summary.targetId === selectedTargetId),
    [deploymentRuntimeSummaries, selectedTargetId]
  );
  const targetComposeEnvironments = useMemo(
    () => composeEnvironments.filter((environment) => environment.targetId === selectedTargetId),
    [composeEnvironments, selectedTargetId]
  );
  const activeComposeEnvironmentId = selectedComposeEnvironmentId || targetComposeEnvironments[0]?.id || '';
  const selectedComposeEnvironment = targetComposeEnvironments.find((environment) => environment.id === activeComposeEnvironmentId);
  const selectedComposeRuntimeView = useMemo(
    () => composeRuntimeViews.find((view) => view.environmentId === activeComposeEnvironmentId),
    [composeRuntimeViews, activeComposeEnvironmentId]
  );
  const canSubmitCompose = Boolean(
    selectedTargetId &&
      composeEnvironment.workspaceId.trim() &&
      composeEnvironment.composeFilePath.trim() &&
      composeEnvironment.projectName.trim() &&
      composeEnvironment.serviceName.trim()
  );

  useEffect(() => {
    if (!deploymentTargets.length) {
      setSelectedTargetId('');
      return;
    }

    if (!deploymentTargets.some((item) => item.id === selectedTargetId)) {
      setSelectedTargetId(deploymentTargets[0].id);
    }
  }, [deploymentTargets, selectedTargetId]);

  useEffect(() => {
    if (!workspaces.length) {
      setComposeEnvironment((current) => ({ ...current, workspaceId: '' }));
      return;
    }

    if (!workspaces.some((workspace) => workspace.id === composeEnvironment.workspaceId)) {
      setComposeEnvironment((current) => ({ ...current, workspaceId: workspaces[0].id }));
    }
  }, [workspaces, composeEnvironment.workspaceId]);

  useEffect(() => {
    if (!targetComposeEnvironments.length) {
      setSelectedComposeEnvironmentId('');
      return;
    }

    if (!targetComposeEnvironments.some((environment) => environment.id === selectedComposeEnvironmentId)) {
      setSelectedComposeEnvironmentId(targetComposeEnvironments[0].id);
    }
  }, [targetComposeEnvironments, selectedComposeEnvironmentId]);

  function updateTarget(field: keyof OpsDeploymentTargetInput, value: string) {
    setTarget((current) => ({ ...current, [field]: value }));
  }

  function updateComposeEnvironment(field: keyof ComposeEnvironmentInput, value: string) {
    setComposeEnvironment((current) => ({ ...current, [field]: value }));
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
      await onConfigureTarget({
        environmentName: target.environmentName.trim(),
        environmentUrl: target.environmentUrl.trim(),
        sshAddress: target.sshAddress.trim(),
        deployNote: target.deployNote.trim(),
        healthCheckUrl: target.healthCheckUrl.trim(),
        rollbackNote: target.rollbackNote.trim()
      });
      setTarget(initialDeploymentTarget);
    } catch (error) {
      const message = messageFromError(error, '保存部署目标失败，请稍后重试');
      setErrorMessage(message === syncFailureMessage ? '' : message);
    } finally {
      setSubmitting(false);
    }
  }

  async function handleRunHealthCheck() {
    if (!selectedTargetId) {
      setOperationError('请先选择部署目标');
      return;
    }

    setOperationBusy('health');
    setOperationError('');
    try {
      await onRunHealthCheck(selectedTargetId);
    } catch (error) {
      const message = messageFromError(error, '运行健康检查失败，请稍后重试');
      setOperationError(message === syncFailureMessage ? '' : message);
    } finally {
      setOperationBusy(null);
    }
  }

  async function handleRecordOperation(type: DeploymentOperationType) {
    if (!selectedTargetId) {
      setOperationError('请先选择部署目标');
      return;
    }

    const note = type === 'DEPLOYMENT' ? deploymentNote.trim() : rollbackNote.trim();
    if (!note) {
      setOperationError(type === 'DEPLOYMENT' ? '请填写部署记录说明' : '请填写回滚记录说明');
      return;
    }

    const status = type === 'DEPLOYMENT' ? deploymentStatus : rollbackStatus;
    setOperationBusy(type === 'DEPLOYMENT' ? 'deployment' : 'rollback');
    setOperationError('');
    try {
      await onRecordDeploymentOperation(selectedTargetId, { type, status, note });
      if (type === 'DEPLOYMENT') {
        setDeploymentNote('');
      } else {
        setRollbackNote('');
      }
    } catch (error) {
      const message = messageFromError(error, '记录部署运行信息失败，请稍后重试');
      setOperationError(message === syncFailureMessage ? '' : message);
    } finally {
      setOperationBusy(null);
    }
  }

  async function handleSubmitComposeEnvironment(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canSubmitCompose || !selectedTargetId) {
      setComposeError(composeRequiredFieldMessage);
      return;
    }

    setComposeSubmitting(true);
    setComposeError('');
    try {
      await onConfigureComposeEnvironment(selectedTargetId, {
        workspaceId: composeEnvironment.workspaceId.trim(),
        composeFilePath: composeEnvironment.composeFilePath.trim(),
        projectName: composeEnvironment.projectName.trim(),
        serviceName: composeEnvironment.serviceName.trim()
      });
      setComposeEnvironment((current) => ({
        ...initialComposeEnvironment,
        workspaceId: current.workspaceId
      }));
    } catch (error) {
      const message = messageFromError(error, '保存 Compose 演示环境失败，请稍后重试');
      setComposeError(message === syncFailureMessage ? '' : message);
    } finally {
      setComposeSubmitting(false);
    }
  }

  async function handleComposeAction(action: 'validate' | 'start' | 'stop' | 'logs') {
    if (!activeComposeEnvironmentId) {
      setComposeError('请先选择 Compose 演示环境');
      return;
    }

    setComposeBusy(action);
    setComposeError('');
    try {
      if (action === 'validate') {
        await onValidateComposeEnvironment(activeComposeEnvironmentId);
      } else if (action === 'start') {
        await onStartComposeEnvironment(activeComposeEnvironmentId);
      } else if (action === 'stop') {
        await onStopComposeEnvironment(activeComposeEnvironmentId);
      } else {
        await onCaptureComposeLogs(activeComposeEnvironmentId);
      }
    } catch (error) {
      const message = messageFromError(error, '执行 Compose 动作失败，请稍后重试');
      setComposeError(message === syncFailureMessage ? '' : message);
    } finally {
      setComposeBusy(null);
    }
  }

  return (
    <article className="panel role-panel" aria-label="运维工作区">
      <header className="panel__header">
        <h3 className="panel-title">运维工作区</h3>
        <span className="panel-note">环境与发布准备</span>
      </header>
      <form className="role-form" onSubmit={(event) => void handleSubmit(event)}>
        <div className="field-grid">
          <label className="field" htmlFor="ops-environment-name">
            <span>环境名称</span>
            <input
              id="ops-environment-name"
              onChange={(event) => updateTarget('environmentName', event.target.value)}
              type="text"
              value={target.environmentName}
            />
          </label>
          <label className="field" htmlFor="ops-environment-url">
            <span>环境地址</span>
            <input
              id="ops-environment-url"
              onChange={(event) => updateTarget('environmentUrl', event.target.value)}
              type="url"
              value={target.environmentUrl}
            />
          </label>
        </div>
        <label className="field" htmlFor="ops-ssh-address">
          <span>服务器地址（SSH 地址）</span>
          <input
            id="ops-ssh-address"
            onChange={(event) => updateTarget('sshAddress', event.target.value)}
            type="text"
            value={target.sshAddress}
          />
        </label>
        <label className="field" htmlFor="ops-deploy-note">
          <span>部署说明</span>
          <textarea
            id="ops-deploy-note"
            onChange={(event) => updateTarget('deployNote', event.target.value)}
            rows={4}
            value={target.deployNote}
          />
        </label>
        <label className="field" htmlFor="ops-health-check-url">
          <span>健康检查地址</span>
          <input
            id="ops-health-check-url"
            onChange={(event) => updateTarget('healthCheckUrl', event.target.value)}
            type="url"
            value={target.healthCheckUrl}
          />
        </label>
        <label className="field" htmlFor="ops-rollback-note">
          <span>回滚说明</span>
          <textarea
            id="ops-rollback-note"
            onChange={(event) => updateTarget('rollbackNote', event.target.value)}
            rows={4}
            value={target.rollbackNote}
          />
        </label>
        {errorMessage ? <p className="inline-error">{errorMessage}</p> : null}
        <div className="form-actions">
          <button className="primary-button" disabled={submitting || !canSubmit} type="submit">
            {submitting ? '保存中' : '保存部署目标'}
          </button>
        </div>
      </form>
      <form className="role-form" aria-label="部署运行记录" onSubmit={(event) => event.preventDefault()}>
        <p className="form-section-title">部署运行记录</p>
        {deploymentTargets.length ? (
          <>
            <label className="field" htmlFor="ops-runtime-target">
              <span>部署目标</span>
              <select
                id="ops-runtime-target"
                onChange={(event) => {
                  setSelectedTargetId(event.target.value);
                  setOperationError('');
                }}
                value={selectedTargetId}
              >
                {deploymentTargets.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.environmentName}
                  </option>
                ))}
              </select>
            </label>
            {selectedTarget ? (
              <p className="form-hint">
                健康检查地址：{selectedTarget.healthCheckUrl || '未配置'} · 最近检查：
                {selectedRuntimeSummary?.latestHealthCheck?.summary || '暂无'}
              </p>
            ) : null}
            <div className="form-actions">
              <button
                className="secondary-button"
                disabled={operationBusy !== null}
                onClick={() => void handleRunHealthCheck()}
                type="button"
              >
                {operationBusy === 'health' ? '检查中' : '运行健康检查'}
              </button>
            </div>
            <div className="field-grid">
              <label className="field" htmlFor="ops-deployment-status">
                <span>部署结果</span>
                <select
                  id="ops-deployment-status"
                  onChange={(event) => setDeploymentStatus(event.target.value as DeploymentOperationStatus)}
                  value={deploymentStatus}
                >
                  <option value="SUCCEEDED">成功</option>
                  <option value="FAILED">失败</option>
                  <option value="RECORDED">已记录</option>
                </select>
              </label>
              <label className="field" htmlFor="ops-rollback-status">
                <span>回滚结果</span>
                <select
                  id="ops-rollback-status"
                  onChange={(event) => setRollbackStatus(event.target.value as DeploymentOperationStatus)}
                  value={rollbackStatus}
                >
                  <option value="RECORDED">已记录</option>
                  <option value="SUCCEEDED">成功</option>
                  <option value="FAILED">失败</option>
                </select>
              </label>
            </div>
            <label className="field" htmlFor="ops-deployment-note">
              <span>部署记录说明</span>
              <textarea
                id="ops-deployment-note"
                onChange={(event) => setDeploymentNote(event.target.value)}
                rows={3}
                value={deploymentNote}
              />
            </label>
            <div className="form-actions">
              <button
                className="primary-button"
                disabled={operationBusy !== null}
                onClick={() => void handleRecordOperation('DEPLOYMENT')}
                type="button"
              >
                {operationBusy === 'deployment' ? '记录中' : '记录部署'}
              </button>
            </div>
            <label className="field" htmlFor="ops-rollback-runtime-note">
              <span>回滚记录说明</span>
              <textarea
                id="ops-rollback-runtime-note"
                onChange={(event) => setRollbackNote(event.target.value)}
                rows={3}
                value={rollbackNote}
              />
            </label>
            <div className="form-actions">
              <button
                className="secondary-button"
                disabled={operationBusy !== null}
                onClick={() => void handleRecordOperation('ROLLBACK')}
                type="button"
              >
                {operationBusy === 'rollback' ? '记录中' : '记录回滚'}
              </button>
            </div>
            {operationError ? <p className="inline-error">{operationError}</p> : null}
          </>
        ) : (
          <p className="empty-state">暂无部署目标，请先保存部署目标。</p>
        )}
      </form>
      <form className="role-form" aria-label="Compose 演示环境" onSubmit={(event) => void handleSubmitComposeEnvironment(event)}>
        <p className="form-section-title">Compose 演示环境</p>
        {deploymentTargets.length && workspaces.length ? (
          <>
            <label className="field" htmlFor="ops-compose-workspace">
              <span>本地工作区</span>
              <select
                id="ops-compose-workspace"
                onChange={(event) => updateComposeEnvironment('workspaceId', event.target.value)}
                value={composeEnvironment.workspaceId}
              >
                {workspaces.map((workspace) => (
                  <option key={workspace.id} value={workspace.id}>
                    {workspace.name}
                  </option>
                ))}
              </select>
            </label>
            <div className="field-grid">
              <label className="field" htmlFor="ops-compose-file">
                <span>Compose 文件</span>
                <input
                  id="ops-compose-file"
                  onChange={(event) => updateComposeEnvironment('composeFilePath', event.target.value)}
                  type="text"
                  value={composeEnvironment.composeFilePath}
                />
              </label>
              <label className="field" htmlFor="ops-compose-project">
                <span>Compose 项目名</span>
                <input
                  id="ops-compose-project"
                  onChange={(event) => updateComposeEnvironment('projectName', event.target.value)}
                  type="text"
                  value={composeEnvironment.projectName}
                />
              </label>
            </div>
            <label className="field" htmlFor="ops-compose-service">
              <span>服务名</span>
              <input
                id="ops-compose-service"
                onChange={(event) => updateComposeEnvironment('serviceName', event.target.value)}
                type="text"
                value={composeEnvironment.serviceName}
              />
            </label>
            <div className="form-actions">
              <button className="primary-button" disabled={composeSubmitting || !canSubmitCompose} type="submit">
                {composeSubmitting ? '保存中' : '保存 Compose 环境'}
              </button>
            </div>
            {targetComposeEnvironments.length ? (
              <>
                <label className="field" htmlFor="ops-compose-environment">
                  <span>Compose 环境</span>
                  <select
                    id="ops-compose-environment"
                    onChange={(event) => {
                      setSelectedComposeEnvironmentId(event.target.value);
                      setComposeError('');
                    }}
                    value={activeComposeEnvironmentId}
                  >
                    {targetComposeEnvironments.map((environment) => (
                      <option key={environment.id} value={environment.id}>
                        {environment.projectName} · {environment.serviceName}
                      </option>
                    ))}
                  </select>
                </label>
                {selectedComposeEnvironment ? (
                  <p className="form-hint">
                    {selectedComposeEnvironment.projectName} · {selectedComposeEnvironment.serviceName} ·{' '}
                    {composeStatusLabels[selectedComposeEnvironment.status]} · {selectedComposeEnvironment.composeFilePath}
                  </p>
                ) : null}
                {selectedComposeRuntimeView?.latestOperation ? (
                  <p className="form-hint">
                    最近操作：
                    {composeOperationStatusLabels[selectedComposeRuntimeView.latestOperation.status]} ·{' '}
                    {selectedComposeRuntimeView.latestOperation.summary}
                  </p>
                ) : null}
                {selectedComposeRuntimeView?.latestOperation?.logExcerpt ? (
                  <p className="compose-log-excerpt">{selectedComposeRuntimeView.latestOperation.logExcerpt}</p>
                ) : null}
                <div className="form-actions">
                  <button
                    className="secondary-button"
                    disabled={composeBusy !== null}
                    onClick={() => void handleComposeAction('validate')}
                    type="button"
                  >
                    {composeBusy === 'validate' ? '校验中' : '校验配置'}
                  </button>
                  <button
                    className="primary-button"
                    disabled={composeBusy !== null}
                    onClick={() => void handleComposeAction('start')}
                    type="button"
                  >
                    {composeBusy === 'start' ? '启动中' : '启动演示'}
                  </button>
                  <button
                    className="secondary-button"
                    disabled={composeBusy !== null}
                    onClick={() => void handleComposeAction('logs')}
                    type="button"
                  >
                    {composeBusy === 'logs' ? '采集中' : '采集日志'}
                  </button>
                  <button
                    className="secondary-button"
                    disabled={composeBusy !== null}
                    onClick={() => void handleComposeAction('stop')}
                    type="button"
                  >
                    {composeBusy === 'stop' ? '停止中' : '停止演示'}
                  </button>
                </div>
              </>
            ) : (
              <p className="empty-state">暂无 Compose 演示环境。</p>
            )}
            {composeError ? <p className="inline-error">{composeError}</p> : null}
          </>
        ) : (
          <p className="empty-state">请先保存部署目标并授权本地工作区。</p>
        )}
      </form>
    </article>
  );
}
