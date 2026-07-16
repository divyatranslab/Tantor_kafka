Write-Host "Building images with Podman..."

# Build UI
Write-Host "Building tantor-ui..."
podman build -t tantor-ui:latest ./tantor-ui

# Build Server
Write-Host "Building tantor-server..."
podman build -t tantor-server:latest ./tantor-server

# Build Artifact Repo
Write-Host "Building tantor-artifact-repository..."
podman build -t tantor-artifact-repository:latest ./tantor-artifact-repository

# Pull Postgres image for offline deployment
Write-Host "Pulling postgres:13..."
podman pull postgres:13

Write-Host "Saving images to tarballs..."
podman save -o tantor-ui.tar tantor-ui:latest
podman save -o tantor-server.tar tantor-server:latest
podman save -o tantor-artifact-repository.tar tantor-artifact-repository:latest
podman save -o postgres.tar postgres:13

Write-Host "Packaging deployment bundle..."
# Create deployment directory
New-Item -ItemType Directory -Force -Path deployment-bundle

# Move tars
Move-Item *.tar deployment-bundle\

# Copy required files
Copy-Item podman-compose.yml deployment-bundle\
Copy-Item .env.example deployment-bundle\.env

# Create a start script for the target machine
$startScript = @"
#!/bin/bash
echo "Loading images..."
podman load -i tantor-ui.tar
podman load -i tantor-server.tar
podman load -i tantor-artifact-repository.tar
podman load -i postgres.tar

echo "Starting services..."
podman-compose up -d
echo "Deployment complete."
"@
Set-Content -Path deployment-bundle\start.sh -Value $startScript

# Tar the bundle
tar -czvf deployment-bundle.tar.gz deployment-bundle

Write-Host "Deployment bundle created: deployment-bundle.tar.gz"
Write-Host "You can now transfer deployment-bundle.tar.gz to your client machine."
