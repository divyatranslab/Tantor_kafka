import paramiko

def run_command(ssh, command):
    print(f"Running: {command}")
    stdin, stdout, stderr = ssh.exec_command(command)
    exit_status = stdout.channel.recv_exit_status()
    out = stdout.read().decode('utf-8')
    err = stderr.read().decode('utf-8')
    if out:
        print(f"STDOUT:\n{out}")
    if err:
        print(f"STDERR:\n{err}")
    return exit_status

try:
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect('192.168.3.191', username='root', password='Admin@123')
    
    print("--- System Info ---")
    run_command(ssh, "cat /etc/os-release")
    
    print("--- Checking tools ---")
    run_command(ssh, "git --version")
    run_command(ssh, "mvn --version")
    run_command(ssh, "node --version")
    run_command(ssh, "npm --version")
    run_command(ssh, "java -version")
    run_command(ssh, "go version")
    
    ssh.close()
except Exception as e:
    print(f"Error: {e}")
