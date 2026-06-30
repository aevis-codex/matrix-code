import { useEffect, useMemo, useState, type FormEvent } from 'react';
import {
  addProjectMember,
  batchUpdateProjectMembers,
  createProjectInvitation,
  createProjectUser,
  expireProjectInvitations,
  kickoutActorSessions,
  loadActorSession,
  loadActorSessions,
  loadProjectInvitations,
  loadProjectMembers,
  loadUserAuditRecords,
  loginActorSession,
  logoutActorSession,
  renewActorSession,
  reissueProjectInvitation,
  revokeProjectInvitation,
  updateProjectMember,
  type ActorSessionInfo,
  type ActorTokenIssueResponse,
  type ModelRole,
  type ProjectInvitation,
  type ProjectMember,
  type ProjectMemberInput,
  type ProjectMemberUpdateInput,
  type ProjectUserInput,
  type RoleAgentConfig,
  type RoleAgentConfigInput,
  type UserAuditRecord
} from '../api/client';

type RoleAgentConfigDialogProps = {
  actorUserId: string;
  configs: RoleAgentConfig[];
  open: boolean;
  projectId: string;
  onClose: () => void;
  onActorSessionChange: (actorUserId: string) => void;
  onUpdateRoleAgentConfig: (role: ModelRole, input: RoleAgentConfigInput) => Promise<void>;
};

type ConfigSection = 'agents' | 'members' | 'identity';

const actorTokenStorageKey = 'matrixcode.actorToken';
const actorTokenUserIdStorageKey = 'matrixcode.actorTokenUserId';
const actorTokenExpiresAtStorageKey = 'matrixcode.actorTokenExpiresAt';

const roleLabels: Record<ModelRole, string> = {
  PRODUCT: '产品',
  DEVELOPER: '开发',
  TESTER: '测试',
  OPERATIONS: '运维'
};

const memberRoles = ['OWNER', 'PRODUCT', 'DEVELOPER', 'TESTER', 'OPERATIONS'];

const memberRoleLabels: Record<string, string> = {
  OWNER: '负责人',
  PRODUCT: '产品',
  DEVELOPER: '开发',
  TESTER: '测试',
  OPERATIONS: '运维'
};

function defaultInvitationExpiresAt() {
  const expiresAt = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000);
  return expiresAt.toISOString().slice(0, 16);
}

const cacheScopeStrategyLabels: Record<string, string> = {
  'provider-model': '供应商 + 模型',
  'provider-role': '同供应商复用',
  'project-role': '角色级复用'
};

type ActorTokenStatus = {
  userId: string;
  expiresAt: string;
};

function readStoredActorTokenStatus(): ActorTokenStatus | null {
  try {
    const token = window.localStorage.getItem(actorTokenStorageKey);
    const userId = window.localStorage.getItem(actorTokenUserIdStorageKey);
    const expiresAt = window.localStorage.getItem(actorTokenExpiresAtStorageKey);
    if (!token || !userId || !expiresAt) {
      return null;
    }
    return { userId, expiresAt };
  } catch {
    return null;
  }
}

function storeActorToken(response: ActorTokenIssueResponse) {
  window.localStorage.setItem(actorTokenStorageKey, response.token);
  window.localStorage.setItem(actorTokenUserIdStorageKey, response.userId);
  window.localStorage.setItem(actorTokenExpiresAtStorageKey, response.expiresAt);
}

function clearStoredActorToken() {
  window.localStorage.removeItem(actorTokenStorageKey);
  window.localStorage.removeItem(actorTokenUserIdStorageKey);
  window.localStorage.removeItem(actorTokenExpiresAtStorageKey);
}

type RoleAgentConfigEditorProps = {
  config: RoleAgentConfig;
  onUpdate: (role: ModelRole, input: RoleAgentConfigInput) => Promise<void>;
};

function RoleAgentConfigEditor({ config, onUpdate }: RoleAgentConfigEditorProps) {
  const initialCacheScopeStrategy = config.cacheScopeStrategy ?? 'provider-model';
  const [displayName, setDisplayName] = useState(config.displayName);
  const [providerId, setProviderId] = useState(config.providerId);
  const [model, setModel] = useState(config.model);
  const [toolContractVersion, setToolContractVersion] = useState(config.toolContractVersion);
  const [cachePolicyId, setCachePolicyId] = useState(config.cachePolicyId);
  const [volatileSuffixStrategy, setVolatileSuffixStrategy] = useState(config.volatileSuffixStrategy);
  const [cacheScopeStrategy, setCacheScopeStrategy] = useState(initialCacheScopeStrategy);
  const [systemPrompt, setSystemPrompt] = useState(config.systemPrompt);
  const [userPromptTemplate, setUserPromptTemplate] = useState(config.userPromptTemplate);
  const [themeColor, setThemeColor] = useState(config.themeColor);
  const [fontFamily, setFontFamily] = useState(config.fontFamily);
  const [fontSize, setFontSize] = useState(String(config.fontSize));
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setDisplayName(config.displayName);
    setProviderId(config.providerId);
    setModel(config.model);
    setToolContractVersion(config.toolContractVersion);
    setCachePolicyId(config.cachePolicyId);
    setVolatileSuffixStrategy(config.volatileSuffixStrategy);
    setCacheScopeStrategy(config.cacheScopeStrategy ?? 'provider-model');
    setSystemPrompt(config.systemPrompt);
    setUserPromptTemplate(config.userPromptTemplate);
    setThemeColor(config.themeColor);
    setFontFamily(config.fontFamily);
    setFontSize(String(config.fontSize));
  }, [config]);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) {
      return;
    }

    setBusy(true);
    try {
      await onUpdate(config.role, {
        displayName,
        agentKind: config.agentKind,
        providerId,
        model,
        toolContractVersion,
        cachePolicyId,
        volatileSuffixStrategy,
        cacheScopeStrategy,
        systemPrompt,
        userPromptTemplate,
        themeColor,
        fontFamily,
        fontSize: Number(fontSize),
        sortOrder: config.sortOrder,
        enabled: config.enabled
      });
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="role-agent-editor" onSubmit={handleSubmit}>
      <header className="role-agent-editor__header">
        <strong>{config.displayName}</strong>
        <span className="role-agent-editor__swatch" style={{ backgroundColor: themeColor }} />
      </header>
      <label className="compact-field">
        <span>名称</span>
        <input
          aria-label={`${config.displayName}名称`}
          value={displayName}
          onChange={(event) => setDisplayName(event.target.value)}
        />
      </label>
      <label className="compact-field">
        <span>供应商</span>
        <input
          aria-label={`${config.displayName}供应商`}
          value={providerId}
          onChange={(event) => setProviderId(event.target.value)}
        />
      </label>
      <label className="compact-field">
        <span>模型</span>
        <input
          aria-label={`${config.displayName}模型`}
          value={model}
          onChange={(event) => setModel(event.target.value)}
        />
      </label>
      <label className="compact-field">
        <span>工具契约</span>
        <input
          aria-label={`${config.displayName}工具契约`}
          value={toolContractVersion}
          onChange={(event) => setToolContractVersion(event.target.value)}
        />
      </label>
      <div className="role-agent-editor__cache-grid">
        <label className="compact-field">
          <span>缓存策略</span>
          <input
            aria-label={`${config.displayName}缓存策略`}
            value={cachePolicyId}
            onChange={(event) => setCachePolicyId(event.target.value)}
          />
        </label>
        <label className="compact-field">
          <span>动态后缀策略</span>
          <input
            aria-label={`${config.displayName}动态后缀策略`}
            value={volatileSuffixStrategy}
            onChange={(event) => setVolatileSuffixStrategy(event.target.value)}
          />
        </label>
        <label className="compact-field">
          <span>缓存作用域</span>
          <select
            aria-label={`${config.displayName}缓存作用域`}
            value={cacheScopeStrategy}
            onChange={(event) => setCacheScopeStrategy(event.target.value)}
          >
            {Object.entries(cacheScopeStrategyLabels).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
      </div>
      <label className="compact-field">
        <span>主题色</span>
        <input
          aria-label={`${config.displayName}主题色`}
          value={themeColor}
          onChange={(event) => setThemeColor(event.target.value)}
        />
      </label>
      <div className="role-agent-editor__font-grid">
        <label className="compact-field">
          <span>字体</span>
          <input
            aria-label={`${config.displayName}字体`}
            value={fontFamily}
            onChange={(event) => setFontFamily(event.target.value)}
          />
        </label>
        <label className="compact-field">
          <span>字号</span>
          <input
            aria-label={`${config.displayName}字号`}
            min={10}
            max={28}
            type="number"
            value={fontSize}
            onChange={(event) => setFontSize(event.target.value)}
          />
        </label>
      </div>
      <label className="compact-field">
        <span>系统提示词</span>
        <textarea
          aria-label={`${config.displayName}系统提示词`}
          rows={4}
          value={systemPrompt}
          onChange={(event) => setSystemPrompt(event.target.value)}
        />
      </label>
      <label className="compact-field">
        <span>用户提示词模板</span>
        <textarea
          aria-label={`${config.displayName}用户提示词模板`}
          rows={3}
          value={userPromptTemplate}
          onChange={(event) => setUserPromptTemplate(event.target.value)}
        />
      </label>
      <button className="secondary-button role-agent-editor__save" disabled={busy} type="submit">
        保存{config.displayName}配置
      </button>
    </form>
  );
}

type ProjectMembersPanelProps = {
  actorUserId: string;
  projectId: string;
  open: boolean;
};

function ProjectMembersPanel({ actorUserId, projectId, open }: ProjectMembersPanelProps) {
  const [members, setMembers] = useState<ProjectMember[]>([]);
  const [invitations, setInvitations] = useState<ProjectInvitation[]>([]);
  const [activeRole, setActiveRole] = useState('ALL');
  const [loading, setLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState('');
  const [userId, setUserId] = useState('');
  const [username, setUsername] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [roleKey, setRoleKey] = useState('DEVELOPER');
  const [invitationExpiresAt, setInvitationExpiresAt] = useState(defaultInvitationExpiresAt);
  const [issuedInvitationToken, setIssuedInvitationToken] = useState('');
  const [issuedInvitationUserId, setIssuedInvitationUserId] = useState('');
  const [addBusy, setAddBusy] = useState(false);
  const [invitationBusy, setInvitationBusy] = useState(false);
  const [selectedMemberIds, setSelectedMemberIds] = useState<string[]>([]);
  const [selectedAuditUserId, setSelectedAuditUserId] = useState('');
  const [auditRecords, setAuditRecords] = useState<UserAuditRecord[]>([]);
  const [auditLoading, setAuditLoading] = useState(false);
  const [auditError, setAuditError] = useState('');
  const [memberActionBusy, setMemberActionBusy] = useState('');
  const [invitationActionBusy, setInvitationActionBusy] = useState('');

  async function refreshMembers() {
    setLoading(true);
    setErrorMessage('');
    try {
      const [nextMembers, nextInvitations] = await Promise.all([
        loadProjectMembers(projectId, actorUserId),
        loadProjectInvitations(projectId, actorUserId)
      ]);
      setMembers(nextMembers);
      setInvitations(nextInvitations);
      setSelectedMemberIds((current) =>
        current.filter((memberId) => nextMembers.some((member) => member.id === memberId && member.status !== 'REMOVED'))
      );
    } catch {
      setErrorMessage('项目成员暂不可用');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    if (open) {
      void refreshMembers();
    }
  }, [actorUserId, open, projectId]);

  const filteredMembers = useMemo(
    () => (activeRole === 'ALL' ? members : members.filter((member) => member.roleKey === activeRole)),
    [activeRole, members]
  );
  const selectedMembers = members.filter((member) => selectedMemberIds.includes(member.id));
  const pendingInvitations = invitations.filter((invitation) => invitation.status === 'PENDING');
  const isSuperAdminActor = actorUserId === 'admin';

  async function handleAddMember(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (addBusy) {
      return;
    }

    setAddBusy(true);
    setErrorMessage('');
    try {
      if (isSuperAdminActor) {
        const input: ProjectUserInput = {
          userId: userId.trim(),
          username: username.trim() || userId.trim(),
          displayName: displayName.trim(),
          email: email.trim(),
          password: password.trim(),
          roleKey
        };
        await createProjectUser(projectId, input, actorUserId);
      } else {
        const input: ProjectMemberInput = {
          userId: userId.trim(),
          displayName: displayName.trim(),
          roleKey
        };
        await addProjectMember(projectId, input, actorUserId);
      }
      setUserId('');
      setUsername('');
      setDisplayName('');
      setEmail('');
      setPassword('');
      setRoleKey('DEVELOPER');
      await refreshMembers();
    } catch {
      setErrorMessage(isSuperAdminActor ? '用户创建失败' : '成员添加失败');
    } finally {
      setAddBusy(false);
    }
  }

  async function handleCreateInvitation(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (invitationBusy) {
      return;
    }

    setInvitationBusy(true);
    setErrorMessage('');
    setIssuedInvitationToken('');
    setIssuedInvitationUserId('');
    try {
      const issued = await createProjectInvitation(
        projectId,
        {
          userId: userId.trim(),
          displayName: displayName.trim(),
          roleKey,
          expiresAt: new Date(invitationExpiresAt).toISOString()
        },
        actorUserId
      );
      setIssuedInvitationToken(issued.token);
      setIssuedInvitationUserId(issued.invitation.inviteeUserId);
      setUserId('');
      setDisplayName('');
      setRoleKey('DEVELOPER');
      setInvitationExpiresAt(defaultInvitationExpiresAt());
      await refreshMembers();
    } catch {
      setErrorMessage('邀请创建失败');
    } finally {
      setInvitationBusy(false);
    }
  }

  async function handleLoadAudit(member: ProjectMember) {
    setSelectedAuditUserId(member.userId);
    setAuditLoading(true);
    setAuditError('');
    try {
      setAuditRecords(await loadUserAuditRecords(projectId, member.userId, actorUserId));
    } catch {
      setAuditRecords([]);
      setAuditError('用户审计暂不可用');
    } finally {
      setAuditLoading(false);
    }
  }

  async function handleUpdateMember(member: ProjectMember, input: ProjectMemberUpdateInput) {
    const busyKey = `${member.userId}:${input.roleKey ?? input.status ?? 'update'}`;
    if (memberActionBusy) {
      return;
    }
    setMemberActionBusy(busyKey);
    setErrorMessage('');
    try {
      await updateProjectMember(projectId, member.userId, input, actorUserId);
      await refreshMembers();
    } catch {
      setErrorMessage('成员更新失败');
    } finally {
      setMemberActionBusy('');
    }
  }

  async function handleBatchMemberStatus(status: string) {
    if (memberActionBusy || !selectedMembers.length) {
      return;
    }
    setMemberActionBusy(`batch:${status}`);
    setErrorMessage('');
    try {
      await batchUpdateProjectMembers(
        projectId,
        {
          updates: selectedMembers.map((member) => ({
            userId: member.userId,
            roleKey: member.roleKey,
            status
          }))
        },
        actorUserId
      );
      setSelectedMemberIds([]);
      await refreshMembers();
    } catch {
      setErrorMessage('成员批量更新失败');
    } finally {
      setMemberActionBusy('');
    }
  }

  async function handleRevokeInvitation(invitation: ProjectInvitation) {
    if (invitationActionBusy) {
      return;
    }
    setInvitationActionBusy(`${invitation.id}:revoke`);
    setErrorMessage('');
    try {
      await revokeProjectInvitation(projectId, invitation.id, actorUserId);
      await refreshMembers();
    } catch {
      setErrorMessage('邀请撤销失败');
    } finally {
      setInvitationActionBusy('');
    }
  }

  async function handleReissueInvitation(invitation: ProjectInvitation) {
    if (invitationActionBusy) {
      return;
    }
    setInvitationActionBusy(`${invitation.id}:reissue`);
    setErrorMessage('');
    setIssuedInvitationToken('');
    setIssuedInvitationUserId('');
    try {
      const issued = await reissueProjectInvitation(
        projectId,
        invitation.id,
        { expiresAt: new Date(invitationExpiresAt).toISOString() },
        actorUserId
      );
      setIssuedInvitationToken(issued.token);
      setIssuedInvitationUserId(issued.invitation.inviteeUserId);
      await refreshMembers();
    } catch {
      setErrorMessage('邀请重发失败');
    } finally {
      setInvitationActionBusy('');
    }
  }

  async function handleExpireInvitations() {
    if (invitationActionBusy) {
      return;
    }
    setInvitationActionBusy('expire');
    setErrorMessage('');
    try {
      await expireProjectInvitations(projectId, actorUserId);
      await refreshMembers();
    } catch {
      setErrorMessage('过期邀请清理失败');
    } finally {
      setInvitationActionBusy('');
    }
  }

  function toggleSelectedMember(memberId: string) {
    setSelectedMemberIds((current) =>
      current.includes(memberId) ? current.filter((id) => id !== memberId) : [...current, memberId]
    );
  }

  return (
    <div className="project-members-config">
      <form className="project-member-form" onSubmit={handleAddMember}>
        <label className="compact-field">
          <span>用户 ID</span>
          <input
            aria-label="成员用户 ID"
            value={userId}
            onChange={(event) => setUserId(event.target.value)}
            placeholder="user-dev"
          />
        </label>
        <label className="compact-field">
          <span>显示名</span>
          <input
            aria-label="成员显示名"
            value={displayName}
            onChange={(event) => setDisplayName(event.target.value)}
            placeholder="开发同学"
          />
        </label>
        {isSuperAdminActor ? (
          <>
            <label className="compact-field">
              <span>用户名</span>
              <input
                aria-label="新用户用户名"
                autoComplete="username"
                value={username}
                onChange={(event) => setUsername(event.target.value)}
                placeholder="dev"
              />
            </label>
            <label className="compact-field">
              <span>邮箱</span>
              <input
                aria-label="新用户邮箱"
                autoComplete="email"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                placeholder="dev@example.com"
              />
            </label>
            <label className="compact-field">
              <span>密码</span>
              <input
                aria-label="新用户密码"
                autoComplete="new-password"
                type="password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
              />
            </label>
          </>
        ) : null}
        <label className="compact-field">
          <span>角色</span>
          <select aria-label="成员角色" value={roleKey} onChange={(event) => setRoleKey(event.target.value)}>
            {memberRoles.map((role) => (
              <option key={role} value={role}>
                {role}
              </option>
            ))}
          </select>
        </label>
        <button className="secondary-button project-member-form__submit" disabled={addBusy} type="submit">
          {addBusy ? (isSuperAdminActor ? '创建中' : '添加中') : isSuperAdminActor ? '创建用户' : '添加成员'}
        </button>
      </form>

      <form className="project-member-form project-member-form--invitation" onSubmit={handleCreateInvitation}>
        <label className="compact-field">
          <span>过期时间</span>
          <input
            aria-label="邀请过期时间"
            type="datetime-local"
            value={invitationExpiresAt}
            onChange={(event) => setInvitationExpiresAt(event.target.value)}
          />
        </label>
        <button className="secondary-button project-member-form__submit" disabled={invitationBusy} type="submit">
          {invitationBusy ? '创建中' : '创建邀请'}
        </button>
      </form>

      {issuedInvitationToken ? (
        <section className="project-member-invitation-token" aria-label="最新邀请令牌">
          <strong>{issuedInvitationUserId} 邀请令牌</strong>
          <code>{issuedInvitationToken}</code>
        </section>
      ) : null}

      {pendingInvitations.length ? (
        <section className="project-member-invitations" aria-label="待处理邀请">
          <div className="project-member-invitations__header">
            <strong>待处理邀请</strong>
            <button
              className="secondary-button"
              disabled={Boolean(invitationActionBusy)}
              onClick={() => void handleExpireInvitations()}
              type="button"
            >
              清理过期邀请
            </button>
          </div>
          <ul>
            {pendingInvitations.map((invitation) => (
              <li key={invitation.id}>
                <span>{invitation.inviteeUserId}</span>
                <span>{memberRoleLabels[invitation.roleKey] ?? invitation.roleKey}</span>
                <time dateTime={invitation.expiresAt}>{new Date(invitation.expiresAt).toLocaleString()}</time>
                <div className="project-member-invitations__actions">
                  <button
                    className="secondary-button"
                    disabled={Boolean(invitationActionBusy)}
                    onClick={() => void handleReissueInvitation(invitation)}
                    type="button"
                  >
                    重发
                  </button>
                  <button
                    className="secondary-button"
                    disabled={Boolean(invitationActionBusy)}
                    onClick={() => void handleRevokeInvitation(invitation)}
                    type="button"
                  >
                    撤销
                  </button>
                </div>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      <div aria-label="成员角色标签" className="role-agent-tabs role-agent-tabs--members" role="tablist">
        <button
          aria-selected={activeRole === 'ALL'}
          className={`role-agent-tabs__button ${activeRole === 'ALL' ? 'role-agent-tabs__button--active' : ''}`}
          onClick={() => setActiveRole('ALL')}
          role="tab"
          type="button"
        >
          全部
        </button>
        {memberRoles.map((role) => (
          <button
            aria-selected={activeRole === role}
            className={`role-agent-tabs__button ${activeRole === role ? 'role-agent-tabs__button--active' : ''}`}
            key={role}
            onClick={() => setActiveRole(role)}
            role="tab"
            type="button"
          >
            {memberRoleLabels[role]}
          </button>
        ))}
      </div>

      {loading ? <p className="empty-state">正在加载项目成员</p> : null}
      {errorMessage ? <p className="inline-error">{errorMessage}</p> : null}
      {!loading && !filteredMembers.length ? <p className="empty-state">暂无项目成员</p> : null}

      {members.length ? (
        <div className="project-member-batch-actions" aria-label="成员批量治理">
          <span>{selectedMembers.length ? `已选 ${selectedMembers.length} 人` : '选择成员后批量处理'}</span>
          <button
            className="secondary-button"
            disabled={!selectedMembers.length || Boolean(memberActionBusy)}
            onClick={() => void handleBatchMemberStatus('ACTIVE')}
            type="button"
          >
            批量恢复
          </button>
          <button
            className="secondary-button"
            disabled={!selectedMembers.length || Boolean(memberActionBusy)}
            onClick={() => void handleBatchMemberStatus('DISABLED')}
            type="button"
          >
            批量禁用
          </button>
          <button
            className="secondary-button"
            disabled={!selectedMembers.length || Boolean(memberActionBusy)}
            onClick={() => void handleBatchMemberStatus('REMOVED')}
            type="button"
          >
            批量移除
          </button>
        </div>
      ) : null}

      {filteredMembers.length ? (
        <ul className="project-member-list" aria-label="项目成员">
          {filteredMembers.map((member) => (
            <li className="project-member-list__item" key={member.id}>
              <label className="project-member-list__select">
                <input
                  aria-label={`选择 ${member.userId}`}
                  checked={selectedMemberIds.includes(member.id)}
                  onChange={() => toggleSelectedMember(member.id)}
                  type="checkbox"
                />
              </label>
              <div className="project-member-list__meta">
                <strong>{member.userId}</strong>
                <span className={member.status === 'ACTIVE' ? '' : 'project-member-list__status--inactive'}>
                  {member.status}
                </span>
              </div>
              <label className="project-member-list__role">
                <span>角色</span>
                <select
                  aria-label={`${member.userId} 角色`}
                  disabled={Boolean(memberActionBusy)}
                  value={member.roleKey}
                  onChange={(event) =>
                    void handleUpdateMember(member, {
                      roleKey: event.target.value,
                      status: member.status || 'ACTIVE'
                    })
                  }
                >
                  {memberRoles.map((role) => (
                    <option key={role} value={role}>
                      {role}
                    </option>
                  ))}
                </select>
              </label>
              <div className="project-member-list__actions">
                <button
                  className="secondary-button project-member-list__audit"
                  disabled={Boolean(memberActionBusy)}
                  onClick={() => void handleLoadAudit(member)}
                  type="button"
                >
                  查看 {member.userId} 审计
                </button>
                <button
                  className="secondary-button project-member-list__audit"
                  disabled={Boolean(memberActionBusy)}
                  onClick={() =>
                    void handleUpdateMember(member, {
                      status: member.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
                    })
                  }
                  type="button"
                >
                  {member.status === 'ACTIVE' ? '禁用' : '恢复'} {member.userId}
                </button>
                <button
                  className="secondary-button project-member-list__audit"
                  disabled={Boolean(memberActionBusy)}
                  onClick={() => void handleUpdateMember(member, { status: 'REMOVED' })}
                  type="button"
                >
                  移除 {member.userId}
                </button>
              </div>
            </li>
          ))}
        </ul>
      ) : null}

      <section className="project-member-audit" aria-label="用户级审计">
        <header>
          <strong>{selectedAuditUserId ? `${selectedAuditUserId} 审计` : '用户级审计'}</strong>
          {auditLoading ? <span>加载中</span> : null}
        </header>
        {auditError ? <p className="inline-error">{auditError}</p> : null}
        {!auditLoading && selectedAuditUserId && !auditRecords.length && !auditError ? (
          <p className="empty-state">该成员暂无审计记录</p>
        ) : null}
        {auditRecords.length ? (
          <ul>
            {auditRecords.map((record) => (
              <li key={record.id}>
                <strong>{record.actionKey}</strong>
                <span>{record.summary}</span>
              </li>
            ))}
          </ul>
        ) : null}
      </section>
    </div>
  );
}

type IdentityTokenPanelProps = {
  actorUserId: string;
  projectId: string;
  open: boolean;
  onActorSessionChange: (actorUserId: string) => void;
};

function IdentityTokenPanel({ actorUserId, projectId, open, onActorSessionChange }: IdentityTokenPanelProps) {
  const [members, setMembers] = useState<ProjectMember[]>([]);
  const [username, setUsername] = useState(readStoredActorTokenStatus()?.userId ?? 'admin');
  const [selectedUserId, setSelectedUserId] = useState(readStoredActorTokenStatus()?.userId ?? '');
  const [ttlSeconds, setTtlSeconds] = useState('86400');
  const [password, setPassword] = useState('');
  const [status, setStatus] = useState<ActorTokenStatus | null>(() => readStoredActorTokenStatus());
  const [sessions, setSessions] = useState<ActorSessionInfo[]>([]);
  const [sessionsLoading, setSessionsLoading] = useState(false);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [errorMessage, setErrorMessage] = useState('');

  async function refreshMembers() {
    setLoading(true);
    setErrorMessage('');
    try {
      const nextMembers = await loadProjectMembers(projectId, actorUserId);
      setMembers(nextMembers);
      const activeMember = nextMembers.find((member) => member.status === 'ACTIVE');
      if (!selectedUserId && activeMember) {
        setSelectedUserId(activeMember.userId);
      }
    } catch {
      setErrorMessage('项目成员暂不可用');
    } finally {
      setLoading(false);
    }
  }

  async function refreshStoredSession(storedStatus: ActorTokenStatus | null) {
    if (!storedStatus) {
      setSessions([]);
      return;
    }
    try {
      const session = await loadActorSession(projectId, storedStatus.userId);
      setStatus({ userId: session.userId, expiresAt: storedStatus.expiresAt });
      setSelectedUserId(session.userId);
      setUsername(session.userId);
      await refreshSessions(session.userId);
    } catch {
      clearStoredActorToken();
      setStatus(null);
      setSessions([]);
    }
  }

  /**
   * 刷新身份页展示的会话治理列表。
   *
   * 目标用户默认取当前登录用户，治理操作者使用工作台当前操作者；
   * 服务端只返回 token 指纹和设备低敏信息，前端不读取 token 明文。
   */
  async function refreshSessions(targetUserId = (status?.userId || selectedUserId).trim(), requestActorUserId = actorUserId) {
    if (!targetUserId) {
      setSessions([]);
      return;
    }
    setSessionsLoading(true);
    setErrorMessage('');
    try {
      setSessions(await loadActorSessions(projectId, targetUserId, requestActorUserId));
    } catch {
      setSessions([]);
      setErrorMessage('会话列表暂不可用');
    } finally {
      setSessionsLoading(false);
    }
  }

  useEffect(() => {
    if (open) {
      const storedStatus = readStoredActorTokenStatus();
      setStatus(storedStatus);
      void refreshStoredSession(storedStatus);
      void refreshMembers();
    }
  }, [actorUserId, open, projectId]);

  async function handleLogin(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) {
      return;
    }

    setBusy(true);
    setErrorMessage('');
    try {
      const response = await loginActorSession(projectId, {
        username: username.trim(),
        password: password.trim(),
        ttlSeconds: Number(ttlSeconds),
      });
      storeActorToken(response);
      onActorSessionChange(response.userId);
      setStatus({ userId: response.userId, expiresAt: response.expiresAt });
      setSelectedUserId(response.userId);
      setUsername(response.userId);
      setPassword('');
      await refreshSessions(response.userId, response.userId);
    } catch {
      setErrorMessage('登录失败');
    } finally {
      setBusy(false);
    }
  }

  async function handleLogout() {
    if (busy) {
      return;
    }
    setBusy(true);
    setErrorMessage('');
    try {
      if (status?.userId) {
        await logoutActorSession(projectId, status.userId);
      }
      clearStoredActorToken();
      setStatus(null);
      setPassword('');
      setSessions([]);
    } catch {
      setErrorMessage('退出登录失败');
    } finally {
      setBusy(false);
    }
  }

  /**
   * 续期当前登录会话并同步本地过期时间。
   *
   * 服务端返回剩余秒数后，前端只更新本地展示用 expiresAt 和会话列表；
   * 真实会话有效期以 Sa-Token 服务端状态为准。
   */
  async function handleRenewSession() {
    if (busy || !status?.userId) {
      return;
    }
    setBusy(true);
    setErrorMessage('');
    try {
      const renewed = await renewActorSession(projectId, status.userId, Number(ttlSeconds));
      const expiresAt = new Date(Date.now() + renewed.timeoutSeconds * 1000).toISOString();
      window.localStorage.setItem(actorTokenExpiresAtStorageKey, expiresAt);
      setStatus({ userId: status.userId, expiresAt });
      await refreshSessions(status.userId);
    } catch {
      setErrorMessage('会话续期失败');
    } finally {
      setBusy(false);
    }
  }

  /**
   * 踢下线目标用户的会话。
   *
   * 当目标就是当前本地登录用户时，必须同步清理本地 token，避免界面
   * 继续携带已经失效的 Bearer token 访问后端。
   */
  async function handleKickoutSessions() {
    const targetUserId = (status?.userId || selectedUserId).trim();
    if (busy || !targetUserId) {
      return;
    }
    setBusy(true);
    setErrorMessage('');
    try {
      await kickoutActorSessions(projectId, targetUserId, actorUserId);
      if (status?.userId === targetUserId) {
        clearStoredActorToken();
        setStatus(null);
        setSessions([]);
      } else {
        await refreshSessions(targetUserId);
      }
    } catch {
      setErrorMessage('踢下线失败');
    } finally {
      setBusy(false);
    }
  }

  const sessionTargetUserId = (status?.userId || selectedUserId).trim();

  return (
    <section className="identity-token-config" aria-label="登录配置">
      <form className="identity-token-form" onSubmit={handleLogin}>
        <label className="compact-field">
          <span>用户名</span>
          <input
            aria-label="登录用户名"
            autoComplete="username"
            value={username}
            onChange={(event) => setUsername(event.target.value)}
          />
        </label>
        <label className="compact-field">
          <span>有效期</span>
          <input
            aria-label="登录有效期"
            min={60}
            max={604800}
            type="number"
            value={ttlSeconds}
            onChange={(event) => setTtlSeconds(event.target.value)}
          />
        </label>
        <label className="compact-field">
          <span>密码</span>
          <input
            aria-label="登录密码"
            autoComplete="current-password"
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
        </label>
        <button className="secondary-button identity-token-form__submit" disabled={busy || loading} type="submit">
          {busy ? '登录中' : '登录'}
        </button>
      </form>

      {errorMessage ? <p className="inline-error">{errorMessage}</p> : null}
      {loading ? <p className="empty-state">正在加载项目成员</p> : null}
      <div className="identity-token-status">
        <strong>当前登录态</strong>
        <span>{status ? `${status.userId} · 有效至 ${status.expiresAt}` : '未登录'}</span>
        <button className="secondary-button" disabled={busy || !status} onClick={() => void handleLogout()} type="button">
          退出登录
        </button>
      </div>
      <div className="identity-session-panel">
        <div className="identity-session-panel__header">
          <strong>会话治理</strong>
          <div className="identity-session-panel__actions">
            <button
              className="secondary-button"
              disabled={busy || sessionsLoading || !sessionTargetUserId}
              onClick={() => void refreshSessions(sessionTargetUserId)}
              type="button"
            >
              刷新会话
            </button>
            <button
              className="secondary-button"
              disabled={busy || !status}
              onClick={() => void handleRenewSession()}
              type="button"
            >
              续期当前会话
            </button>
            <button
              aria-label={sessionTargetUserId ? `踢下线 ${sessionTargetUserId}` : '踢下线'}
              className="secondary-button"
              disabled={busy || !sessionTargetUserId}
              onClick={() => void handleKickoutSessions()}
              type="button"
            >
              踢下线
            </button>
          </div>
        </div>
        {sessionsLoading ? <p className="empty-state">正在刷新会话</p> : null}
        {!sessionsLoading && sessions.length ? (
          <ul className="identity-session-list">
            {sessions.map((session) => (
              <li key={session.tokenFingerprint}>
                <span>{`${session.tokenFingerprint} · ${session.deviceType || '未知设备'} · 剩余 ${session.timeoutSeconds} 秒`}</span>
                <small>{session.deviceId || '未记录设备'} · {session.createdAt}</small>
              </li>
            ))}
          </ul>
        ) : null}
        {!sessionsLoading && !sessions.length ? <p className="empty-state">暂无可见会话</p> : null}
      </div>
    </section>
  );
}

export function RoleAgentConfigDialog({
  actorUserId,
  configs,
  open,
  projectId,
  onClose,
  onActorSessionChange,
  onUpdateRoleAgentConfig
}: RoleAgentConfigDialogProps) {
  const [activeSection, setActiveSection] = useState<ConfigSection>('agents');
  const [activeRole, setActiveRole] = useState<ModelRole>('PRODUCT');

  useEffect(() => {
    if (open && configs.length && !configs.some((config) => config.role === activeRole)) {
      setActiveRole(configs[0].role);
    }
  }, [activeRole, configs, open]);

  if (!open) {
    return null;
  }

  const activeConfig = configs.find((config) => config.role === activeRole) ?? configs[0];

  return (
    <div className="config-dialog-backdrop">
      <section aria-label="项目配置" className="config-dialog" role="dialog">
        <header className="config-dialog__header">
          <div>
            <p className="eyebrow">配置中心</p>
            <h2>项目配置</h2>
          </div>
          <button aria-label="关闭配置" className="config-dialog__close" onClick={onClose} type="button">
            ×
          </button>
        </header>

        <div aria-label="配置分类" className="config-section-tabs" role="tablist">
          <button
            aria-selected={activeSection === 'agents'}
            className={`config-section-tabs__button ${
              activeSection === 'agents' ? 'config-section-tabs__button--active' : ''
            }`}
            onClick={() => setActiveSection('agents')}
            role="tab"
            type="button"
          >
            智能体
          </button>
          <button
            aria-selected={activeSection === 'members'}
            className={`config-section-tabs__button ${
              activeSection === 'members' ? 'config-section-tabs__button--active' : ''
            }`}
            onClick={() => setActiveSection('members')}
            role="tab"
            type="button"
          >
            成员
          </button>
          <button
            aria-selected={activeSection === 'identity'}
            className={`config-section-tabs__button ${
              activeSection === 'identity' ? 'config-section-tabs__button--active' : ''
            }`}
            onClick={() => setActiveSection('identity')}
            role="tab"
            type="button"
          >
            身份
          </button>
        </div>

        {activeSection === 'agents' ? (
          configs.length ? (
            <>
              <div aria-label="角色标签" className="role-agent-tabs" role="tablist">
                {configs.map((config) => (
                  <button
                    aria-selected={activeRole === config.role}
                    className={`role-agent-tabs__button ${
                      activeRole === config.role ? 'role-agent-tabs__button--active' : ''
                    }`}
                    key={config.role}
                    onClick={() => setActiveRole(config.role)}
                    role="tab"
                    type="button"
                  >
                    {roleLabels[config.role]}
                  </button>
                ))}
              </div>
              {activeConfig ? (
                <RoleAgentConfigEditor config={activeConfig} onUpdate={onUpdateRoleAgentConfig} />
              ) : null}
            </>
          ) : (
            <p className="empty-state">暂无角色智能体配置</p>
          )
        ) : activeSection === 'members' ? (
          <ProjectMembersPanel actorUserId={actorUserId} open={activeSection === 'members'} projectId={projectId} />
        ) : (
          <IdentityTokenPanel
            actorUserId={actorUserId}
            open={activeSection === 'identity'}
            projectId={projectId}
            onActorSessionChange={onActorSessionChange}
          />
        )}
      </section>
    </div>
  );
}
