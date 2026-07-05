import paramiko
import sys

def run_command(ssh, command):
    print(f"Running: {command}")
    stdin, stdout, stderr = ssh.exec_command(command)
    exit_status = stdout.channel.recv_exit_status()
    out = stdout.read().decode('utf-8', errors='replace')
    err = stderr.read().decode('utf-8', errors='replace')
    if out: print(f"STDOUT:\n{out}")
    if err: print(f"STDERR:\n{err}")
    if exit_status != 0:
        print(f"Command failed with exit status {exit_status}")
        sys.exit(1)
    return out

try:
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect('192.168.3.191', username='root', password='Admin@123')

    print("Installing Java 21...")
    ssh.exec_command("yum install -y java-21-openjdk-devel")

    print("Stopping old services if any...")
    ssh.exec_command("systemctl stop tantor-server tantor-artifact-repository tantor-artifact tantor-ui nginx")

    print("Cloning repository...")
    run_command(ssh, "rm -rf /opt/Tantor_kafka")
    run_command(ssh, "cd /opt && git clone https://github.com/divyatranslab/Tantor_kafka.git")

    print("Building server...")
    run_command(ssh, "export JAVA_HOME=/usr/lib/jvm/java-21-openjdk && cd /opt/Tantor_kafka/tantor-server && mvn clean package -DskipTests")

    print("Building artifact repository...")
    run_command(ssh, "export JAVA_HOME=/usr/lib/jvm/java-21-openjdk && cd /opt/Tantor_kafka/tantor-artifact-repository && mvn clean package -DskipTests")

    print("Building frontend...")
    run_command(ssh, "cd /opt/Tantor_kafka/tantor-ui && npm install && npm run build")

    print("Setting up Nginx...")
    run_command(ssh, "rm -rf /usr/share/nginx/html/*")
    run_command(ssh, "cp -r /opt/Tantor_kafka/tantor-ui/dist/* /usr/share/nginx/html/")

    nginx_conf = """
server {
    listen 80;
    server_name _;
    root /usr/share/nginx/html;

    location / {
        index index.html;
        try_files $uri $uri/ /index.html;
    }

    location /api/v1/artifacts/ {
        proxy_pass http://127.0.0.1:8081/api/v1/artifacts/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        client_max_body_size 1000M;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8443/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
"""
    # Write conf to /etc/nginx/conf.d/tantor.conf
    import base64
    b64_conf = base64.b64encode(nginx_conf.encode()).decode()
    run_command(ssh, f"echo '{b64_conf}' | base64 -d > /etc/nginx/conf.d/tantor.conf")

    # Remove default_server from main nginx.conf if present
    run_command(ssh, "sed -i 's/listen       80 default_server;/listen       80;/' /etc/nginx/nginx.conf")
    run_command(ssh, "sed -i 's/listen       \\[::\\]:80 default_server;/listen       \\[::\\]:80;/' /etc/nginx/nginx.conf")

    print("Setting SELinux booleans for Nginx proxy...")
    ssh.exec_command("setsebool -P httpd_can_network_connect 1")

    print("Setting up systemd services...")
    server_service = """[Unit]
Description=Tantor Management Server
After=network.target postgresql.service

[Service]
Type=simple
User=root
WorkingDirectory=/opt/Tantor_kafka/tantor-server
Environment="JAVA_HOME=/usr/lib/jvm/java-21-openjdk" "TANTOR_REPO_URL=http://192.168.3.191"
ExecStart=/usr/lib/jvm/java-21-openjdk/bin/java -jar /opt/Tantor_kafka/tantor-server/target/tantor-server-1.0.0.jar
Restart=always

[Install]
WantedBy=multi-user.target
"""
    b64_server = base64.b64encode(server_service.encode()).decode()
    run_command(ssh, f"echo '{b64_server}' | base64 -d > /etc/systemd/system/tantor-server.service")

    artifact_service = """[Unit]
Description=Tantor Artifact Repository
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/Tantor_kafka/tantor-artifact-repository
Environment="JAVA_HOME=/usr/lib/jvm/java-21-openjdk"
ExecStart=/usr/lib/jvm/java-21-openjdk/bin/java -jar /opt/Tantor_kafka/tantor-artifact-repository/target/tantor-artifact-repository-1.0.0.jar
Restart=always

[Install]
WantedBy=multi-user.target
"""
    b64_artifact = base64.b64encode(artifact_service.encode()).decode()
    run_command(ssh, f"echo '{b64_artifact}' | base64 -d > /etc/systemd/system/tantor-artifact.service")

    run_command(ssh, "systemctl daemon-reload")
    run_command(ssh, "systemctl enable --now tantor-server tantor-artifact nginx")
    run_command(ssh, "systemctl restart tantor-server tantor-artifact nginx")

    ssh.close()
    print("Deployment script finished successfully!")
except Exception as e:
    print(f"Error: {e}")
