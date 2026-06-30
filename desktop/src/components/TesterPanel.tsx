import { useState, type FormEvent } from 'react';
import type { BugSeverity, BugStatus, ProjectBug } from '../api/client';

export type TesterBugInput = {
  title: string;
  severity: BugSeverity;
  steps: string;
  expected: string;
  actual: string;
};

export type TesterReportInput = {
  report: string;
};

export type TesterBugTransitionInput = {
  nextStatus: BugStatus;
  note: string;
};

type TesterPanelProps = {
  bugs: ProjectBug[];
  onCreateBug: (input: TesterBugInput) => Promise<void>;
  onSubmitReport: (input: TesterReportInput) => Promise<void>;
  onTransitionBug: (bugId: string, input: TesterBugTransitionInput) => Promise<void>;
};

const initialBug: TesterBugInput = {
  title: '',
  severity: 'MEDIUM',
  steps: '',
  expected: '',
  actual: ''
};

const initialReport: TesterReportInput = {
  report: ''
};

const initialTransition: TesterBugTransitionInput = {
  nextStatus: 'REGRESSION_PENDING',
  note: ''
};

const bugRequiredFieldMessage = '请填写缺陷标题、复现步骤、期望结果和实际结果';
const reportRequiredFieldMessage = '请填写测试报告';
const transitionRequiredFieldMessage = '请选择 Bug 并填写流转备注';
const syncFailureMessage = '同步最新工作台失败，请稍后重试';

const bugStatusLabels: Record<BugStatus, string> = {
  NEW: '新建',
  CONFIRMED: '已确认',
  FIXING: '修复中',
  REGRESSION_PENDING: '待回归',
  CLOSED: '已关闭',
  REOPENED: '重新打开'
};

function messageFromError(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback;
}

export function TesterPanel({ bugs, onCreateBug, onSubmitReport, onTransitionBug }: TesterPanelProps) {
  const [bug, setBug] = useState<TesterBugInput>(initialBug);
  const [report, setReport] = useState<TesterReportInput>(initialReport);
  const [selectedBugId, setSelectedBugId] = useState('');
  const [transition, setTransition] = useState<TesterBugTransitionInput>(initialTransition);
  const [busyAction, setBusyAction] = useState<'bug' | 'report' | 'transition' | null>(null);
  const [errorMessage, setErrorMessage] = useState('');
  const canCreateBug = Boolean(bug.title.trim() && bug.steps.trim() && bug.expected.trim() && bug.actual.trim());
  const canSubmitReport = Boolean(report.report.trim());
  const activeBugId = selectedBugId || bugs[0]?.id || '';
  const canTransitionBug = Boolean(activeBugId && transition.note.trim());

  function updateBug(field: keyof TesterBugInput, value: string) {
    setBug((current) => ({
      ...current,
      [field]: field === 'severity' ? (value as BugSeverity) : value
    }));
  }

  async function handleCreateBug(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canCreateBug) {
      setErrorMessage(bugRequiredFieldMessage);
      return;
    }

    setBusyAction('bug');
    setErrorMessage('');
    try {
      await onCreateBug({
        title: bug.title.trim(),
        severity: bug.severity,
        steps: bug.steps.trim(),
        expected: bug.expected.trim(),
        actual: bug.actual.trim()
      });
      setBug(initialBug);
    } catch (error) {
      const message = messageFromError(error, '记录 Bug 失败，请稍后重试');
      setErrorMessage(message === syncFailureMessage ? '' : message);
    } finally {
      setBusyAction(null);
    }
  }

  async function handleSubmitReport() {
    if (!canSubmitReport) {
      setErrorMessage(reportRequiredFieldMessage);
      return;
    }

    setBusyAction('report');
    setErrorMessage('');
    try {
      await onSubmitReport({ report: report.report.trim() });
      setReport(initialReport);
    } catch (error) {
      const message = messageFromError(error, '提交测试报告失败，请稍后重试');
      setErrorMessage(message === syncFailureMessage ? '' : message);
    } finally {
      setBusyAction(null);
    }
  }

  async function handleTransitionBug() {
    if (!canTransitionBug) {
      setErrorMessage(transitionRequiredFieldMessage);
      return;
    }

    setBusyAction('transition');
    setErrorMessage('');
    try {
      await onTransitionBug(activeBugId, {
        nextStatus: transition.nextStatus,
        note: transition.note.trim()
      });
      setTransition(initialTransition);
    } catch (error) {
      const message = messageFromError(error, '流转 Bug 状态失败，请稍后重试');
      setErrorMessage(message === syncFailureMessage ? '' : message);
    } finally {
      setBusyAction(null);
    }
  }

  return (
    <article className="panel role-panel" aria-label="测试工作区">
      <header className="panel__header">
        <h3 className="panel-title">测试工作区</h3>
        <span className="panel-note">缺陷、流转与报告</span>
      </header>
      <form className="role-form" onSubmit={(event) => void handleCreateBug(event)}>
        <p className="form-section-title">记录缺陷</p>
        <label className="field" htmlFor="tester-bug-title">
          <span>缺陷标题（Bug 标题）</span>
          <input
            id="tester-bug-title"
            onChange={(event) => updateBug('title', event.target.value)}
            type="text"
            value={bug.title}
          />
        </label>
        <label className="field" htmlFor="tester-severity">
          <span>严重级别</span>
          <select id="tester-severity" onChange={(event) => updateBug('severity', event.target.value)} value={bug.severity}>
            <option value="LOW">低</option>
            <option value="MEDIUM">中</option>
            <option value="HIGH">高</option>
            <option value="BLOCKER">阻塞</option>
          </select>
        </label>
        <label className="field" htmlFor="tester-steps">
          <span>复现步骤</span>
          <textarea id="tester-steps" onChange={(event) => updateBug('steps', event.target.value)} rows={4} value={bug.steps} />
        </label>
        <div className="field-grid">
          <label className="field" htmlFor="tester-expected">
            <span>期望结果</span>
            <textarea
              id="tester-expected"
              onChange={(event) => updateBug('expected', event.target.value)}
              rows={3}
              value={bug.expected}
            />
          </label>
          <label className="field" htmlFor="tester-actual">
            <span>实际结果</span>
            <textarea id="tester-actual" onChange={(event) => updateBug('actual', event.target.value)} rows={3} value={bug.actual} />
          </label>
        </div>
        <label className="field" htmlFor="tester-report">
          <span>测试报告</span>
          <textarea
            id="tester-report"
            onChange={(event) => setReport({ report: event.target.value })}
            rows={4}
            value={report.report}
          />
        </label>
        <div className="form-actions">
          <button className="primary-button" disabled={busyAction !== null || !canCreateBug} type="submit">
            {busyAction === 'bug' ? '记录中' : '记录 Bug'}
          </button>
          <button
            className="secondary-button"
            disabled={busyAction !== null || !canSubmitReport}
            onClick={() => void handleSubmitReport()}
            type="button"
          >
            {busyAction === 'report' ? '提交中' : '提交测试报告'}
          </button>
        </div>
      </form>
      <form className="role-form" onSubmit={(event) => event.preventDefault()}>
        <p className="form-section-title">流转 Bug</p>
        {bugs.length ? (
          <>
            <label className="field" htmlFor="tester-transition-bug">
              <span>选择要流转的 Bug</span>
              <select
                id="tester-transition-bug"
                onChange={(event) => setSelectedBugId(event.target.value)}
                value={activeBugId}
              >
                {bugs.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.title} · {bugStatusLabels[item.status]}
                  </option>
                ))}
              </select>
            </label>
            <label className="field" htmlFor="tester-transition-status">
              <span>目标状态</span>
              <select
                id="tester-transition-status"
                onChange={(event) =>
                  setTransition((current) => ({ ...current, nextStatus: event.target.value as BugStatus }))
                }
                value={transition.nextStatus}
              >
                <option value="CONFIRMED">已确认</option>
                <option value="FIXING">修复中</option>
                <option value="REGRESSION_PENDING">待回归</option>
                <option value="CLOSED">已关闭</option>
                <option value="REOPENED">重新打开</option>
              </select>
            </label>
            <label className="field" htmlFor="tester-transition-note">
              <span>流转备注</span>
              <textarea
                id="tester-transition-note"
                onChange={(event) => setTransition((current) => ({ ...current, note: event.target.value }))}
                rows={3}
                value={transition.note}
              />
            </label>
            <div className="form-actions">
              <button
                className="secondary-button"
                disabled={busyAction !== null || !canTransitionBug}
                onClick={() => void handleTransitionBug()}
                type="button"
              >
                {busyAction === 'transition' ? '流转中' : '流转 Bug 状态'}
              </button>
            </div>
          </>
        ) : (
          <p className="form-hint">暂无可流转 Bug</p>
        )}
      </form>
      {errorMessage ? <p className="inline-error role-panel__error">{errorMessage}</p> : null}
    </article>
  );
}
