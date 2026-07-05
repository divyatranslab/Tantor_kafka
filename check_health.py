import paramiko

def run_command(ssh, command):
    print(f"--- Running: {command} ---")
    stdin, stdout, stderr = ssh.exec_command(command)
    exit_status = stdout.channel.recv_exit_status()
    out = stdout.read().decode('utf-8').strip()
    err = stderr.read().decode('utf-8').strip()
    if out:
        print(f"{out}\n")
    if err:
        print(f"ERROR: {err}\n")
    return exit_status

try:
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect('192.168.3.191', username='root', password='Admin@123')
    
    print("=== VM Health Check ===")
    
    # 1. Check storage (Disk Space)
    run_command(ssh, "df -h")
    
    # 2. Check Memory (RAM)
    run_command(ssh, "free -h")
    
    # 3. Check CPU / Load Average
    run_command(ssh, "uptime")
    
    # 4. Check Top CPU consuming processes
    run_command(ssh, "ps -eo pid,ppid,cmd,%mem,%cpu --sort=-%cpu | head -n 10")
    
    ssh.close()
except Exception as e:
    print(f"Error: {e}")
