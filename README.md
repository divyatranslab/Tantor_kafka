# Tantor Kafka Platform

Tantor is an enterprise-grade, agent-pull architecture management platform for Apache Kafka (KRaft mode). It features a central Java Spring Boot management server, a high-performance React frontend, and lightweight Go agents for fully remote, air-gapped deployments without SSH.

## 🚀 Quick Start Guide

If you just received this repository, follow these exact steps to run the complete Tantor platform on your local machine.

### Prerequisites
Before you begin, ensure you have the following installed on your machine:
1. **Java 21** (`jdk-21`) 
2. **PostgreSQL** (Running locally on port 5432)
3. **Node.js & npm** (For the UI)
4. **Go 1.21+** (Only needed if compiling the agent from source)

---

### Step 1: Database Setup
Tantor uses PostgreSQL to store cluster configurations and host metadata.
1. Open pgAdmin or your terminal and connect to your local PostgreSQL instance.
2. Create a new database named exactly: `tantor`
3. Ensure the password for the `postgres` user matches the `.env` file (default is `Yawar@#123`).

### Step 2: Build the Backend Services
The backend is split into two microservices: the Management Server and the Artifact Repository.
1. Open PowerShell.
2. Navigate to the project root directory.
3. Run the automated build script (this will download Maven and compile both `.jar` files):
   ```powershell
   .\build.ps1
   ```

### Step 3: Start the Backend Services
Once the build is complete, you can launch both backend services simultaneously.
1. In the same PowerShell window, run:
   ```powershell
   .\start-backend.ps1
   ```
2. Wait about **15 seconds** for Spring Boot to fully initialize.
3. The backend services keep running in the background. To stop them, run:
   ```powershell
   .\stop-backend.ps1
   ```
4. To restart cleanly without creating duplicate Java processes, run:
   ```powershell
   .\start-backend.ps1 -Restart
   ```
5. Backend logs are written under `.runtime\logs`.

### Step 4: Start the Frontend UI
The frontend is built with React and Vite.
1. Open a **new** terminal or PowerShell window.
2. Navigate into the UI directory:
   ```powershell
   cd tantor-ui
   ```
3. Install the dependencies (only needed the first time):
   ```powershell
   npm install
   ```
4. Start the development server:
   ```powershell
   npm run dev
   ```
5. Open your browser and navigate to the URL provided in the terminal (usually `http://localhost:5173`).

---

### Step 5: (Optional) Running a Local Agent
If you want to simulate a VM and test deployments locally on your Windows machine:
1. Open a new terminal.
2. Navigate into the agent directory:
   ```powershell
   cd tantor-agent
   ```
3. Run the Go agent and point it to your local backend:
   ```powershell
   go run main.go --server http://localhost:8443
   ```
4. The agent will now appear in your Tantor UI under the **Hosts** dashboard!
