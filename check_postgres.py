import paramiko

def run_command(ssh, command):
    print(f"Running: {command}")
    stdin, stdout, stderr = ssh.exec_command(command)
    exit_status = stdout.channel.recv_exit_status()
    out = stdout.read().decode('utf-8', errors='replace')
    err = stderr.read().decode('utf-8', errors='replace')
    if out:
        print(f"STDOUT:\n{out.encode('ascii', errors='replace').decode('ascii')}")
    if err:
        print(f"STDERR:\n{err.encode('ascii', errors='replace').decode('ascii')}")
    return exit_status

try:
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect('192.168.3.191', username='root', password='Admin@123')
    
    run_command(ssh, "systemctl status postgresql")
    run_command(ssh, "psql -U postgres -c '\\l'")
    run_command(ssh, "netstat -tulnp | grep 5432")
    
    ssh.close()
except Exception as e:
    print(f"Error: {e}")
