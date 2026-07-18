import React from "react";
import { createPortal } from "react-dom";
import "./NewClusterModal.css";

interface Props {
  onClose: () => void;
  onCreate: () => void;
  onExplore: () => void;
}

export const NewClusterModal: React.FC<Props> = ({ onClose, onCreate, onExplore }) => {
  return createPortal(
    <div className="cd-modal-overlay" onClick={onClose}>
      <div className="cd-deployment-modal" onClick={e => e.stopPropagation()} role="dialog" aria-modal="true">
        <div className="cd-deployment-modal-header">
          <div className="cd-deployment-modal-header-content">
            <h2>Cluster Development</h2>
            <p>Create a managed Kafka cluster or connect an existing external cluster.</p>
          </div>
          <button className="cd-icon-btn close-btn" onClick={onClose} title="Close">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M18 6L6 18M6 6l12 12"/></svg>
          </button>
        </div>
        <div className="cd-deployment-cards-wrapper">
          <div className="cd-deployment-choice-grid">
            <div className="cd-deployment-card" onClick={onCreate}>
              <div className="cd-deployment-card-content">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor"><path d="M17 16l-4-4V8.82C14.16 8.4 15 7.3 15 6c0-1.66-1.34-3-3-3S9 4.34 9 6c0 1.3.84 2.4 2 2.82V12l-4 4H3v5h5v-3.05l4-4.2 4 4.2V21h5v-5h-4zM12 5c.55 0 1 .45 1 1s-.45 1-1 1-1-.45-1-1 .45-1 1-1zm-7 14v-1h1.79l4-4.2 4 4.2H17v1H5z"/></svg>
                <h3>Create your Cluster</h3>
                <p>Build a new KRaft or ZooKeeper cluster on selected Tantor host</p>
              </div>
              <button className="cd-deployment-btn outline" onClick={onCreate}>Create</button>
            </div>
            <div className="cd-deployment-card" onClick={onExplore}>
              <div className="cd-deployment-card-content">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor"><path d="M22 11V3h-7v3H9V3H2v8h7V8h2v10h4v3h7v-8h-7v3h-2V8h2v3h7v-8zM7 9H4V5h3v4zm13-4h-3V5h3v4zm0 14h-3v-4h3v4z"/></svg>
                <h3>Existing Cluster</h3>
                <p>Connect or discover an external Kafka cluster</p>
              </div>
              <button className="cd-deployment-btn outline" onClick={onExplore}>Explorer</button>
            </div>
          </div>
        </div>
      </div>
    </div>,
    document.body
  );
};
