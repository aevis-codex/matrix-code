import type { RoleSummary } from '../api/client';

type RoleSwitcherProps = {
  roles: RoleSummary[];
  activeRole: string;
  pendingApprovalCount?: number;
  onRoleChange: (role: string) => void;
};

export function RoleSwitcher({ roles, activeRole, pendingApprovalCount = 0, onRoleChange }: RoleSwitcherProps) {
  return (
    <nav className="role-list" aria-label="角色工作区">
      {roles.map((role) => {
        const active = role.name === activeRole;
        const showApprovalBadge = role.name === '运维' && pendingApprovalCount > 0;

        return (
          <button
            aria-label={role.name}
            aria-pressed={active}
            className={`role-item ${active ? 'role-item--active' : ''}`}
            key={role.name}
            onClick={() => onRoleChange(role.name)}
            type="button"
          >
            <span className="role-item__mark">{role.name.slice(0, 1)}</span>
            <span className="role-item__body">
              <strong className="role-item__name">{role.name}</strong>
              <span className="role-item__state">{role.state}</span>
            </span>
            <span className="role-item__meta">
              {showApprovalBadge ? (
                <span className="role-item__badge" aria-label={`运维待审批 ${pendingApprovalCount} 项`}>
                  {pendingApprovalCount > 9 ? '9+' : pendingApprovalCount}
                </span>
              ) : null}
              <span className="role-item__count">{role.todoCount}</span>
            </span>
          </button>
        );
      })}
    </nav>
  );
}
