import paramiko

def run_command(ssh, command):
    print(f"Running: {command}")
    stdin, stdout, stderr = ssh.exec_command(command)
    exit_status = stdout.channel.recv_exit_status()
    out = stdout.read().decode('utf-8', errors='replace')
    err = stderr.read().decode('utf-8', errors='replace')
    if out: print(f"STDOUT: {out}")
    if err: print(f"STDERR: {err}")

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.3.191', username='root', password='Admin@123')

run_command(ssh, "systemctl cat tantor-server")
run_command(ssh, "systemctl cat tantor-artifact-repository")

ssh.close()
