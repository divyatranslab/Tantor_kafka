import React, { createContext, useContext, useState, useEffect } from 'react';

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
  loading: boolean;
}

const ClusterContext = createContext<ClusterContextProps>({
  clusters: [],
  activeClusterId: null,
  setActiveClusterId: () => {},
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

  return (
    <ClusterContext.Provider value={{ clusters, activeClusterId, setActiveClusterId, loading }}>
      {children}
    </ClusterContext.Provider>
  );
};
