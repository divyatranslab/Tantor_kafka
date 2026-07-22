import { Navigate, useLocation } from 'react-router-dom';
import { usePermissions } from '../hooks/usePermissions';

interface ProtectedRouteProps {
  children: React.ReactNode;
  requireAdmin?: boolean;
}

export function ProtectedRoute({ children, requireAdmin = true }: ProtectedRouteProps) {
  const { isAdmin } = usePermissions();
  const location = useLocation();

  if (requireAdmin && !isAdmin) {
    // Redirect non-admins to dashboard and preserve the attempted path if needed later
    return <Navigate to="/dashboard" state={{ from: location }} replace />;
  }

  return <>{children}</>;
}
