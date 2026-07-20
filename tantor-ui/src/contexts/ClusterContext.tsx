import React, { createContext, useContext, useState, useEffect, useMemo } from 'react';

export interface Cluster {
  id: string;
  name: string;
  status: string;
  kafkaVersion: string;
  mode: string;
  [key: string]: any;
}

interface ClusterContextProps {
  clusters: Cluster[];
  activeClusterId: string | null;
  setActiveClusterId: (id: string | null) => void;
  /** The mode of the currently active cluster ('EXTERNAL' or other values like 'kraft') */
  activeClusterMode: string | null;
  /** Convenience boolean: true when the active cluster is External */
  isExternalCluster: boolean;
  loading: boolean;
}

const ClusterContext = createContext<ClusterContextProps>({
  clusters: [],
  activeClusterId: null,
  setActiveClusterId: () => {},
  activeClusterMode: null,
  isExternalCluster: false,
  loading: true,
});

export const useCluster = () => useContext(ClusterContext);

export const ClusterProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [clusters, setClusters] = useState<Cluster[]>([]);
  const [activeClusterId, setActiveClusterId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch('/api/v1/ui/clusters')
      .then(res => res.json())
      .then(data => {
        setClusters(data);
        if (data.length > 0) {
          const defaultCluster = data.find((c: Cluster) => c.status !== 'DELETED') || data[0];
          setActiveClusterId(defaultCluster.id);
        }
      })
      .catch(err => console.error("Failed to load clusters for dropdown", err))
      .finally(() => setLoading(false));
  }, []);

  const activeClusterMode = useMemo(() => {
    if (!activeClusterId) return null;
    const found = clusters.find(c => c.id === activeClusterId);
    return found?.mode ?? null;
  }, [activeClusterId, clusters]);

  const isExternalCluster = activeClusterMode === 'EXTERNAL';

  return (
    <ClusterContext.Provider value={{ clusters, activeClusterId, setActiveClusterId, activeClusterMode, isExternalCluster, loading }}>
      {children}
    </ClusterContext.Provider>
  );
};
