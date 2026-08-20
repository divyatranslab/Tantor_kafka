import re

with open('src/pages/DeploymentLogs.tsx', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Remove useNavigate from router import
content = content.replace(
    "import { useParams, useNavigate } from 'react-router-dom';",
    "import { useParams } from 'react-router-dom';"
)

# 2. Remove Server from lucide import
content = content.replace(
    'CheckCircle2, Clock, Copy, Loader2, RefreshCw, Server, Terminal,',
    'CheckCircle2, Clock, Copy, Loader2, RefreshCw, Terminal,'
)

# 3. Add useCallback to react import
content = content.replace(
    "import { useEffect, useRef, useState } from 'react';",
    "import { useEffect, useRef, useState, useCallback } from 'react';"
)

# 4. Remove navigate variable line
content = content.replace('  const navigate = useNavigate();\n', '')

# 5. Wrap fetchTasks in useCallback and fix closure
old_fetch_sig = '  const fetchTasks = async (manual = false) => {'
new_fetch_sig = '  const fetchTasks = useCallback(async (manual = false) => {'
content = content.replace(old_fetch_sig, new_fetch_sig)

# 6. Close useCallback and fix first useEffect
old_close = '  };\n\n  useEffect(() => {\n    fetchTasks();\n  }, [id]);\n'
new_close = '  }, [id]);\n\n  useEffect(() => {\n    fetchTasks();\n  }, [fetchTasks]);\n'
content = content.replace(old_close, new_close)

# 7. Fix polling interval and its deps
content = content.replace(
    '    const interval = window.setInterval(fetchTasks, 3000);\n    return () => window.clearInterval(interval);\n  }, [id, shouldPoll]);',
    '    const interval = window.setInterval(() => { fetchTasks(); }, 3000);\n    return () => window.clearInterval(interval);\n  }, [fetchTasks, shouldPoll]);'
)

# 8. Remove stepLogsObj block (dead code - result is never read)
stepblock = (
    '  let stepLogsObj: Record<string, string> = {};\n'
    '  try {\n'
    '    if (selectedTask.stepLogs) {\n'
    '      stepLogsObj = JSON.parse(selectedTask.stepLogs);\n'
    '    }\n'
    '  } catch (e) {\n'
    '    console.error("Failed to parse step logs", e);\n'
    '  }\n'
    '\n'
    '  const handleRetry'
)
content = content.replace(stepblock, '  const handleRetry')

# 9. Remove activeStepIndex line (dead code - value is never read)
content = re.sub(
    r"  const activeStepIndex = DEPLOYMENT_STEPS\.indexOf\(selectedTask\.currentStep \|\| ''\);\n",
    '',
    content
)

with open('src/pages/DeploymentLogs.tsx', 'w', encoding='utf-8') as f:
    f.write(content)

print('DONE')
