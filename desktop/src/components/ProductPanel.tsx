import { useState, type FormEvent } from 'react';
import type { DocumentSummary } from '../api/client';

export type ProductAcceptanceInput = {
  accepted: boolean;
  note: string;
  returnToRole: '开发' | '测试';
};

type ProductPanelProps = {
  documents: DocumentSummary[];
  onCreateDrafts: (requirement: string) => Promise<void>;
  onFreezeDocument: (documentId: string) => Promise<void>;
  onSubmitAcceptance: (input: ProductAcceptanceInput) => Promise<void>;
};

const syncFailureMessage = '同步最新工作台失败，请稍后重试';

function messageFromError(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback;
}

function createdAtTime(document: DocumentSummary) {
  const timestamp = Date.parse(document.createdAt);
  return Number.isNaN(timestamp) ? 0 : timestamp;
}

function latestDraftPrd(documents: DocumentSummary[]) {
  return [...documents]
    .filter((document) => document.type === 'PRD' && document.state !== 'FROZEN')
    .sort((left, right) => createdAtTime(right) - createdAtTime(left))[0];
}

export function ProductPanel({ documents, onCreateDrafts, onFreezeDocument, onSubmitAcceptance }: ProductPanelProps) {
  const [requirement, setRequirement] = useState('');
  const [acceptanceNote, setAcceptanceNote] = useState('');
  const [returnToRole, setReturnToRole] = useState<'开发' | '测试'>('开发');
  const [busyAction, setBusyAction] = useState<'draft' | 'freeze' | 'accept' | 'reject' | null>(null);
  const [errorMessage, setErrorMessage] = useState('');
  const draftPrd = latestDraftPrd(documents);

  async function handleCreateDrafts(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const trimmedRequirement = requirement.trim();
    if (!trimmedRequirement) {
      setErrorMessage('产品需求不能为空');
      return;
    }

    setBusyAction('draft');
    setErrorMessage('');
    try {
      await onCreateDrafts(trimmedRequirement);
    } catch (error) {
      const message = messageFromError(error, '生成需求草稿失败，请稍后重试');
      setErrorMessage(message === syncFailureMessage ? '' : message);
    } finally {
      setBusyAction(null);
    }
  }

  async function handleFreezeDocument() {
    if (!draftPrd) {
      return;
    }

    setBusyAction('freeze');
    setErrorMessage('');
    try {
      await onFreezeDocument(draftPrd.id);
    } catch (error) {
      const message = messageFromError(error, '冻结当前 PRD 失败，请稍后重试');
      setErrorMessage(message === syncFailureMessage ? '' : message);
    } finally {
      setBusyAction(null);
    }
  }

  async function handleSubmitAcceptance(accepted: boolean) {
    const trimmedNote = acceptanceNote.trim();
    if (!trimmedNote) {
      setErrorMessage('请填写验收备注');
      return;
    }

    setBusyAction(accepted ? 'accept' : 'reject');
    setErrorMessage('');
    try {
      await onSubmitAcceptance({
        accepted,
        note: trimmedNote,
        returnToRole
      });
      setAcceptanceNote('');
    } catch (error) {
      const message = messageFromError(error, '提交验收结论失败，请稍后重试');
      setErrorMessage(message === syncFailureMessage ? '' : message);
    } finally {
      setBusyAction(null);
    }
  }

  return (
    <article className="panel role-panel" aria-label="产品工作区">
      <header className="panel__header">
        <h3 className="panel-title">产品工作区</h3>
        <span className="panel-note">需求、冻结与验收</span>
      </header>
      <form className="role-form" onSubmit={(event) => void handleCreateDrafts(event)}>
        <p className="form-section-title">需求产出</p>
        <label className="field" htmlFor="product-requirement">
          <span>产品需求</span>
          <textarea
            aria-label="产品需求"
            id="product-requirement"
            onChange={(event) => setRequirement(event.target.value)}
            placeholder="输入本轮要转成 PRD 的需求"
            rows={6}
            value={requirement}
          />
        </label>
        <div className="form-actions">
          <button className="primary-button" disabled={busyAction !== null || !requirement.trim()} type="submit">
            {busyAction === 'draft' ? '生成中' : '生成需求草稿'}
          </button>
          <button
            className="secondary-button"
            disabled={busyAction !== null || !draftPrd}
            onClick={() => void handleFreezeDocument()}
            type="button"
          >
            {busyAction === 'freeze' ? '冻结中' : '冻结当前 PRD'}
          </button>
        </div>
        <p className="form-hint">{draftPrd ? `待冻结文档：${draftPrd.title}` : '暂无可冻结 PRD'}</p>
      </form>
      <form className="role-form" onSubmit={(event) => event.preventDefault()}>
        <p className="form-section-title">产品验收</p>
        <label className="field" htmlFor="product-acceptance-note">
          <span>验收备注</span>
          <textarea
            id="product-acceptance-note"
            onChange={(event) => setAcceptanceNote(event.target.value)}
            placeholder="记录验收结论、问题或发布授权"
            rows={4}
            value={acceptanceNote}
          />
        </label>
        <label className="field" htmlFor="product-return-role">
          <span>退回角色</span>
          <select
            id="product-return-role"
            onChange={(event) => setReturnToRole(event.target.value as '开发' | '测试')}
            value={returnToRole}
          >
            <option value="开发">开发</option>
            <option value="测试">测试</option>
          </select>
        </label>
        <div className="form-actions">
          <button
            className="primary-button"
            disabled={busyAction !== null || !acceptanceNote.trim()}
            onClick={() => void handleSubmitAcceptance(true)}
            type="button"
          >
            {busyAction === 'accept' ? '提交中' : '验收通过'}
          </button>
          <button
            className="secondary-button"
            disabled={busyAction !== null || !acceptanceNote.trim()}
            onClick={() => void handleSubmitAcceptance(false)}
            type="button"
          >
            {busyAction === 'reject' ? '退回中' : '验收不通过'}
          </button>
        </div>
      </form>
      {errorMessage ? <p className="inline-error role-panel__error">{errorMessage}</p> : null}
    </article>
  );
}
