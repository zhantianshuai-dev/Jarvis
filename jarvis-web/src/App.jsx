import React, { useEffect, useMemo, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {
  ArrowUp,
  Check,
  Bot,
  CircleArrowUp,
  CircleHelp,
  TriangleAlert,
  ChevronDown,
  Folder,
  GitBranch,
  Globe,
  Grid2X2,
  Image as ImageIcon,
  Library,
  Lock,
  LogOut,
  Menu,
  MessageSquarePlus,
  Mic,
  MoreHorizontal,
  PanelLeftClose,
  Pencil,
  Plus,
  RefreshCw,
  Search,
  Settings,
  Sparkles,
  Palette,
  Trash2,
  UserRound,
  X,
} from 'lucide-react';
import {
  apiBase,
  confirmTool,
  createChatSession,
  createWorktree,
  deleteChatSession,
  deleteWorktree,
  getChatSessionMessages,
  keepWorktree,
  listWorkspaces,
  listWorktrees,
  listChatSessions,
  login,
  logout,
  me,
  register,
  streamChat,
} from './api/auth.js';

const TOKEN_KEY = 'jarvis.access_token';
const USER_KEY = 'jarvis.user';
const SESSION_KEY = 'jarvis.chat_session_id';
const WORKSPACE_KEY = 'jarvis.workspace_id';
const DEEPSEEK_V4_FLASH_CONTEXT_WINDOW = 1_048_576;
const JARVIS_ICON = '/favicon.svg';
const RUN_MODES = [
  { value: 'chat', label: 'Chat', command: '/chat' },
  { value: 'agent', label: 'Agent', command: '/agent' },
  { value: 'super_agent', label: 'Super Agent', command: '/super-agent' },
];

function createSessionId() {
  return `web_${Date.now()}_${Math.random().toString(16).slice(2, 8)}`;
}

function createClientId(prefix = 'msg') {
  if (globalThis.crypto?.randomUUID) {
    return globalThis.crypto.randomUUID();
  }
  return `${prefix}_${Date.now()}_${Math.random().toString(16).slice(2, 10)}`;
}

function readStoredSession() {
  const token = localStorage.getItem(TOKEN_KEY);
  const rawUser = localStorage.getItem(USER_KEY);
  if (!token) return null;
  try {
    return { token, user: rawUser ? JSON.parse(rawUser) : null };
  } catch {
    clearStoredSession();
    return null;
  }
}

function writeStoredSession(session) {
  localStorage.setItem(TOKEN_KEY, session.token);
  localStorage.setItem(USER_KEY, JSON.stringify(session.user));
}

function clearStoredSession() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
}

function normalizeSession(result) {
  const user = result.user || {
    userId: result.userId,
    username: result.username,
    displayName: result.username,
    role: 'user',
    enabled: true,
    lastLoginAt: null,
  };
  return {
    token: result.accessToken,
    tokenType: result.tokenType || 'Bearer',
    expiresIn: result.expiresIn,
    user,
  };
}

function normalizeWorkspaces(items = []) {
  return (Array.isArray(items) ? items : []).map((item) => ({
    id: item.id || '',
    label: item.label || item.id || 'Workspace',
    path: item.path || '',
    default: Boolean(item.default),
  })).filter((item) => item.id);
}

function firstName(user) {
  return user?.displayName || user?.username || 'User';
}

function MarkdownMessage({ content }) {
  return (
    <div className="markdown-body">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          a({ children, ...props }) {
            return (
              <a {...props} target="_blank" rel="noreferrer">
                {children}
              </a>
            );
          },
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  );
}

function extractToolConfirmation(content, data = {}) {
  if (!data?.requires_confirmation && !content?.includes('confirm_id:')) {
    return null;
  }
  const confirmId = data.confirm_id || content.match(/confirm_id:\s*([^\s]+)/)?.[1] || '';
  if (!confirmId) {
    return null;
  }
  return {
    confirmId,
    tool: data.tool || content.match(/工具:\s*([^\n]+)/)?.[1]?.trim() || 'tool',
    action: data.action || content.match(/操作:\s*([^\n]+)/)?.[1]?.trim() || 'confirm',
    summary: data.summary || content.match(/摘要:\s*([^\n]+)/)?.[1]?.trim() || '',
    command: data.command || content.match(/命令:\s*([^\n]+)/)?.[1]?.trim() || '',
    expiresAt: data.expires_at || content.match(/过期时间:\s*([^\n]+)/)?.[1]?.trim() || '',
    status: 'pending',
  };
}

function formatConfirmResult(result) {
  const raw = typeof result?.result === 'string' ? result.result : JSON.stringify(result, null, 2);
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

function normalizeContextUsage(value = {}) {
  const raw = value.token_usage || value.tokenUsage || value.usage || value;
  const context = Number(raw?.context_tokens ?? raw?.contextTokens ?? raw?.prompt_tokens ?? raw?.promptTokens ?? 0);
  if (!context) {
    return null;
  }
  return {
    contextTokens: context,
    contextWindow: DEEPSEEK_V4_FLASH_CONTEXT_WINDOW,
  };
}

function formatContextTokens(tokens) {
  if (tokens >= 1_048_576) return `${Math.round(tokens / 1_048_576)}M`;
  return `${Math.round(tokens / 1_024)}K`;
}

function ContextUsageLine({ usage }) {
  if (!usage) return null;
  return (
    <div
      className="context-usage-line"
      title={`当前上下文 ${usage.contextTokens.toLocaleString()} / ${usage.contextWindow.toLocaleString()} tokens`}
    >
      {formatContextTokens(usage.contextTokens)}/{formatContextTokens(usage.contextWindow)}
    </div>
  );
}

function ProviderEventList({ events }) {
  if (!events?.length) return null;
  return (
    <div className="provider-events">
      {events.slice(-4).map((event) => (
        <div className={`provider-event ${event.type}`} key={event.id}>
          {event.text}
        </div>
      ))}
    </div>
  );
}

function selectedRunModeLabel(value) {
  return RUN_MODES.find((item) => item.value === value)?.label || 'Agent';
}

function normalizeChatMessages(items = []) {
  return items
    .filter((item) => item.role === 'user' || item.role === 'assistant')
    .map((item) => {
      if (item.role === 'assistant' && Boolean(item.subagent_status || item.subagentStatus)) {
        return normalizeStoredSubagentStatus(item);
      }
      return {
        id: item.id || createClientId(),
        role: item.role,
        content: item.content || '',
        createdAt: item.createdAt,
        tokenUsage: item.role === 'assistant' ? normalizeContextUsage(item) : null,
        confirmation: item.role === 'assistant' ? extractToolConfirmation(item.content || '', item) : null,
      };
    });
}

function normalizeConversations(items = []) {
  return items.map((item) => ({
    sessionId: item.sessionId || item.session_id,
    title: item.title || '新的对话',
    messageCount: item.messageCount ?? item.message_count ?? 0,
    updatedAt: item.updatedAt || item.updated_at || '',
  })).filter((item) => item.sessionId);
}

function normalizeWorktrees(items = []) {
  return items.map((item) => ({
    name: item.name || '',
    path: item.path || '',
    absolutePath: item.absolute_path || item.absolutePath || '',
    branch: item.branch || '',
    baseRef: item.base_ref || item.baseRef || '',
    taskId: item.task_id || item.taskId || '',
    status: item.status || '',
    kept: Boolean(item.kept),
    createdAt: item.created_at || item.createdAt || '',
    updatedAt: item.updated_at || item.updatedAt || '',
  })).filter((item) => item.name);
}

function subagentStatusContent(status, task, result, error) {
  if (status === 'running') {
    return `子 Agent 正在运行${task ? `：${task}` : ''}`;
  }
  if (status === 'completed') {
    return `子 Agent 执行成功${task ? `：${task}` : ''}${result ? `\n\n${result}` : ''}`;
  }
  if (status === 'failed') {
    return `子 Agent 执行失败${task ? `：${task}` : ''}${error ? `\n\n${error}` : ''}`;
  }
  return `子 Agent 状态更新${task ? `：${task}` : ''}`;
}

function normalizeSubagentStatus(data = {}) {
  const taskId = data.task_id || data.taskId || '';
  const status = data.status || 'running';
  const task = data.task || '';
  const result = data.result || '';
  const error = data.error || '';
  return {
    id: `subagent_${taskId || createClientId('subagent')}`,
    role: 'assistant',
    kind: 'subagent_status',
    content: subagentStatusContent(status, task, result, error),
    createdAt: data.created_at || new Date().toISOString(),
    subagent: {
      taskId,
      status,
      task,
      worktree: data.worktree || '',
      worktreePath: data.worktree_path || data.worktreePath || '',
      result,
      error,
    },
  };
}

function normalizeStoredSubagentStatus(item = {}) {
  const content = item.content || '';
  const taskId = item.task_id || item.taskId || content.match(/任务 ID:\s*([^\n]+)/)?.[1]?.trim() || '';
  const status = item.status || (content.includes('执行失败') ? 'failed' : 'completed');
  const task = item.task || content.match(/任务:\s*([^\n]+)/)?.[1]?.trim() || '';
  const result = status === 'completed'
    ? content.split(/\n结果:\n/).slice(1).join('\n结果:\n').trim()
    : '';
  const error = status === 'failed'
    ? item.error || content.match(/错误:\s*([\s\S]+)$/)?.[1]?.trim() || ''
    : '';
  return {
    ...normalizeSubagentStatus({
      task_id: taskId,
      status,
      task,
      worktree: item.worktree || '',
      result,
      error,
      created_at: item.createdAt,
    }),
    id: item.id || `subagent_${taskId || createClientId('subagent')}`,
  };
}

function subagentStatusFromToolResult(data = {}) {
  const toolName = data.tool_name || data.toolName || '';
  const content = data.content || '';
  if (toolName !== 'spawn' || !content.includes('子 Agent 已启动')) {
    return null;
  }
  const taskId = content.match(/任务 ID:\s*([^\n]+)/)?.[1]?.trim() || '';
  if (!taskId) {
    return null;
  }
  return {
    task_id: taskId,
    status: 'running',
    task: content.match(/任务:\s*([^\n]+)/)?.[1]?.trim() || '',
    worktree: content.match(/worktree:\s*([^\n]+)/)?.[1]?.trim() || '',
    worktree_path: content.match(/worktree_path:\s*([^\n]+)/)?.[1]?.trim() || '',
  };
}

function normalizeProviderEvent(type, data = {}) {
  const provider = data.provider || '';
  const nextProvider = data.next_provider || data.nextProvider || '';
  const attempt = Number(data.attempt || 0);
  const maxAttempts = Number(data.max_attempts || data.maxAttempts || 0);
  let text = data.message || '';
  if (!text) {
    if (type === 'llm_retry') {
      text = `${provider} 调用失败，正在重试 ${attempt}/${maxAttempts}`;
    } else if (type === 'llm_fallback') {
      text = nextProvider ? `${provider} 调用失败，切换到 ${nextProvider}` : `${provider} 调用失败`;
    } else if (type === 'llm_circuit_open') {
      text = `${provider} 熔断中，跳过该模型服务`;
    }
  }
  return {
    id: createClientId('provider_event'),
    type,
    provider,
    nextProvider,
    text,
  };
}

export default function App() {
  const [authState, setAuthState] = useState('checking');
  const [mode, setMode] = useState('login');
  const [session, setSession] = useState(null);
  const [authForm, setAuthForm] = useState({ username: '', password: '', displayName: '' });
  const [sidebarOpen, setSidebarOpen] = useState(true);
  const [accountOpen, setAccountOpen] = useState(false);
  const [theme, setTheme] = useState(() => localStorage.getItem('jarvis.theme') || 'light');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [sessionId, setSessionId] = useState(() => localStorage.getItem(SESSION_KEY) || createSessionId());
  const [messages, setMessages] = useState([]);
  const [conversations, setConversations] = useState([]);
  const [historyBusy, setHistoryBusy] = useState(false);
  const [deletingConversationId, setDeletingConversationId] = useState('');
  const [conversationPendingDelete, setConversationPendingDelete] = useState(null);
  const [draft, setDraft] = useState('');
  const [chatError, setChatError] = useState('');
  const [chatBusy, setChatBusy] = useState(false);
  const [runMode, setRunMode] = useState('agent');
  const [runModeOpen, setRunModeOpen] = useState(false);
  const [workspaces, setWorkspaces] = useState([]);
  const [selectedWorkspace, setSelectedWorkspace] = useState(() => localStorage.getItem(WORKSPACE_KEY) || '');
  const [workspaceOpen, setWorkspaceOpen] = useState(false);
  const [workspaceQuery, setWorkspaceQuery] = useState('');
  const [activeView, setActiveView] = useState('chat');
  const [worktrees, setWorktrees] = useState([]);
  const [worktreeBusy, setWorktreeBusy] = useState(false);
  const [worktreeError, setWorktreeError] = useState('');
  const [worktreeNotice, setWorktreeNotice] = useState('');
  const [worktreeForm, setWorktreeForm] = useState({ name: '', baseRef: 'HEAD', taskId: '' });
  const scrollRef = useRef(null);
  const runModeRef = useRef(null);
  const workspaceRef = useRef(null);
  const selectedWorkspaceItem = useMemo(
    () => workspaces.find((item) => item.id === selectedWorkspace) || null,
    [selectedWorkspace, workspaces],
  );
  const filteredWorkspaces = useMemo(() => {
    const query = workspaceQuery.trim().toLowerCase();
    if (!query) return workspaces;
    return workspaces.filter((item) => {
      return item.label.toLowerCase().includes(query) || item.path.toLowerCase().includes(query);
    });
  }, [workspaceQuery, workspaces]);

  useEffect(() => {
    let cancelled = false;
    async function restore() {
      const stored = readStoredSession();
      if (!stored) {
        setAuthState('anonymous');
        return;
      }
      try {
        const user = await me(stored.token);
        if (cancelled) return;
        const next = { ...stored, user };
        setSession(next);
        writeStoredSession(next);
        setAuthState('authenticated');
        await bootstrapChat(next);
      } catch {
        clearStoredSession();
        localStorage.removeItem(SESSION_KEY);
        if (!cancelled) {
          setSession(null);
          setSessionId('');
          setConversations([]);
          setMessages([]);
          setAuthState('anonymous');
        }
      }
    }
    restore();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    function closeFloatingMenus(event) {
      if (!runModeRef.current?.contains(event.target)) {
        setRunModeOpen(false);
      }
      if (!workspaceRef.current?.contains(event.target)) {
        setWorkspaceOpen(false);
      }
    }
    document.addEventListener('mousedown', closeFloatingMenus);
    return () => document.removeEventListener('mousedown', closeFloatingMenus);
  }, []);

  useEffect(() => {
    if (sessionId) {
      localStorage.setItem(SESSION_KEY, sessionId);
    } else {
      localStorage.removeItem(SESSION_KEY);
    }
  }, [sessionId]);

  useEffect(() => {
    if (selectedWorkspace) {
      localStorage.setItem(WORKSPACE_KEY, selectedWorkspace);
    }
  }, [selectedWorkspace]);

  useEffect(() => {
    if (authState !== 'authenticated' || !session?.token) {
      return;
    }
    let cancelled = false;
    async function loadWorkspaces() {
      try {
        const result = await listWorkspaces(session.token);
        if (cancelled) return;
        const next = normalizeWorkspaces(result.workspaces);
        setWorkspaces(next);
        const stored = localStorage.getItem(WORKSPACE_KEY);
        const fallback = result.defaultWorkspaceId || next.find((item) => item.default)?.id || next[0]?.id || '';
        setSelectedWorkspace(next.some((item) => item.id === stored) ? stored : fallback);
      } catch {
        if (!cancelled) {
          setWorkspaces([]);
        }
      }
    }
    loadWorkspaces();
    return () => {
      cancelled = true;
    };
  }, [authState, session?.token]);

  useEffect(() => {
    scrollRef.current?.scrollIntoView({ block: 'end' });
  }, [messages, chatBusy]);

  useEffect(() => {
    if (authState !== 'authenticated' || !session?.token || !sessionId) {
      return undefined;
    }
    const url = new URL(`${apiBase()}/api/v1/events`);
    url.searchParams.set('session_id', sessionId);
    url.searchParams.set('access_token', session.token);
    const events = new EventSource(url.toString());
    events.addEventListener('subagent_status', (event) => {
      try {
        upsertSubagentStatus(JSON.parse(event.data));
      } catch {
        // 忽略无法解析的状态事件。
      }
    });
    return () => events.close();
  }, [authState, session?.token, sessionId]);

  useEffect(() => {
    if (authState === 'authenticated' && session?.token && activeView === 'worktrees') {
      refreshWorktrees().catch(() => {});
    }
  }, [authState, session?.token, activeView]);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem('jarvis.theme', theme);
  }, [theme]);

  useEffect(() => {
    if (!conversationPendingDelete) return undefined;
    function closeOnEscape(event) {
      if (event.key === 'Escape') setConversationPendingDelete(null);
    }
    document.addEventListener('keydown', closeOnEscape);
    return () => document.removeEventListener('keydown', closeOnEscape);
  }, [conversationPendingDelete]);

  const isRegister = mode === 'register';
  const submitText = useMemo(() => {
    if (busy) return isRegister ? '正在创建...' : '正在登录...';
    return isRegister ? '创建账号' : '继续';
  }, [busy, isRegister]);

  async function refreshConversationList(token) {
    const result = await listChatSessions(token);
    const next = normalizeConversations(result.sessions);
    setConversations(next);
    return next;
  }

  async function loadConversation(token, nextSessionId) {
    if (!nextSessionId) return false;
    setActiveView('chat');
    setHistoryBusy(true);
    setChatError('');
    try {
      const result = await getChatSessionMessages(token, nextSessionId);
      setSessionId(result.sessionId || nextSessionId);
      setMessages(normalizeChatMessages(result.messages));
      return true;
    } catch (err) {
      setChatError(err.message || '加载历史会话失败');
      return false;
    } finally {
      setHistoryBusy(false);
    }
  }

  async function createAndOpenConversation(token) {
    const result = await createChatSession(token);
    const nextSessionId = result.sessionId || result.session_id || createSessionId();
    const item = {
      sessionId: nextSessionId,
      title: result.title || '新的对话',
      messageCount: 0,
      updatedAt: new Date().toISOString(),
    };
    setConversations((current) => [item, ...current.filter((conv) => conv.sessionId !== nextSessionId)]);
    setSessionId(nextSessionId);
    setMessages([]);
    setChatError('');
    return nextSessionId;
  }

  async function bootstrapChat(nextSession) {
    setHistoryBusy(true);
    setChatError('');
    try {
      const conversations = await refreshConversationList(nextSession.token);
      const storedSessionId = localStorage.getItem(SESSION_KEY);
      const selectedSessionId = storedSessionId || conversations[0]?.sessionId;
      if (selectedSessionId) {
        const loaded = await loadConversation(nextSession.token, selectedSessionId);
        if (!loaded && conversations[0]?.sessionId && conversations[0].sessionId !== selectedSessionId) {
          await loadConversation(nextSession.token, conversations[0].sessionId);
        } else if (!loaded) {
          await createAndOpenConversation(nextSession.token);
        }
      } else {
        await createAndOpenConversation(nextSession.token);
      }
    } catch (err) {
      setMessages([]);
      setChatError(err.message || '加载会话历史失败');
    } finally {
      setHistoryBusy(false);
    }
  }

  function updateAuthField(event) {
    setAuthForm((current) => ({ ...current, [event.target.name]: event.target.value }));
  }

  function updateWorktreeField(event) {
    setWorktreeForm((current) => ({ ...current, [event.target.name]: event.target.value }));
  }

  async function submitAuth(event) {
    event.preventDefault();
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const result = isRegister
        ? await register(authForm)
        : await login({ username: authForm.username, password: authForm.password });
      const nextSession = normalizeSession(result);
      writeStoredSession(nextSession);
      setSession(nextSession);
      setAuthState('authenticated');
      await bootstrapChat(nextSession);
    } catch (err) {
      setError(err.message || '请求失败');
    } finally {
      setBusy(false);
    }
  }

  async function handleSend(event) {
    event.preventDefault();
    const content = draft.trim();
    if (!content || chatBusy) return;

    let activeSessionId = sessionId;
    if (!activeSessionId) {
      activeSessionId = await createAndOpenConversation(session.token);
    }

    const userMessage = { id: createClientId(), role: 'user', content };
    const assistantId = createClientId();
    const assistantMessage = { id: assistantId, role: 'assistant', content: '' };
    setMessages((current) => [...current, userMessage, assistantMessage]);
    setConversations((current) => {
      const existing = current.find((item) => item.sessionId === activeSessionId);
      const title = existing && existing.messageCount > 0 ? existing.title : content.slice(0, 30);
      return [
        {
          sessionId: activeSessionId,
          title,
          messageCount: (existing?.messageCount || 0) + 1,
          updatedAt: new Date().toISOString(),
        },
        ...current.filter((item) => item.sessionId !== activeSessionId),
      ];
    });
    setDraft('');
    setChatBusy(true);
    setChatError('');
    try {
      await streamChat(session.token, {
        sessionId: activeSessionId,
        message: content,
        mode: runMode,
        workspace: selectedWorkspace,
        onToken: (token) => {
          if (!token) return;
          setMessages((current) =>
            current.map((message) =>
              message.id === assistantId
                ? { ...message, content: `${message.content}${token}` }
                : message,
            ),
          );
        },
        onDone: (data) => {
          if (!data?.content) return;
          const tokenUsage = normalizeContextUsage(data);
          setMessages((current) =>
            current.map((message) =>
              message.id === assistantId
                ? {
                    ...message,
                    content: data.content,
                    tokenUsage,
                    confirmation: extractToolConfirmation(data.content, data),
                  }
                : message,
            ),
          );
        },
        onToolResult: (data) => {
          const status = subagentStatusFromToolResult(data);
          if (status) {
            upsertSubagentStatus(status);
          }
        },
        onProviderEvent: (type, data) => {
          const providerEvent = normalizeProviderEvent(type, data);
          setMessages((current) =>
            current.map((message) =>
              message.id === assistantId
                ? {
                    ...message,
                    providerEvents: [...(message.providerEvents || []), providerEvent],
                  }
                : message,
            ),
          );
        },
      });
    } catch (err) {
      if (err.status === 401) {
        clearStoredSession();
        localStorage.removeItem(SESSION_KEY);
        setSession(null);
        setSessionId('');
        setConversations([]);
        setAuthState('anonymous');
      }
      setMessages((current) => current.filter((message) => message.id !== assistantId || message.content));
      setChatError(err.message || '发送失败');
    } finally {
      setChatBusy(false);
      refreshConversationList(session.token).catch(() => {});
    }
  }

  async function handleToolConfirm(messageId, confirmId) {
    if (!confirmId || chatBusy) return;
    setChatError('');
    setMessages((current) =>
      current.map((message) =>
        message.id === messageId && message.confirmation
          ? { ...message, confirmation: { ...message.confirmation, status: 'running', error: '' } }
          : message,
      ),
    );
    try {
      const result = await confirmTool(session.token, { confirmId });
      const reply = typeof result?.reply === 'string' ? result.reply.trim() : '';
      const resultText = formatConfirmResult(result);
      setMessages((current) =>
        current
          .map((message) =>
            message.id === messageId && message.confirmation
              ? { ...message, confirmation: { ...message.confirmation, status: 'confirmed' } }
              : message,
          )
          .concat({
            id: createClientId(),
            role: 'assistant',
            content: reply || `工具确认操作已执行。\n\n\`\`\`json\n${resultText}\n\`\`\``,
          }),
      );
    } catch (err) {
      if (err.status === 401) {
        clearStoredSession();
        localStorage.removeItem(SESSION_KEY);
        setSession(null);
        setSessionId('');
        setConversations([]);
        setAuthState('anonymous');
      }
      setMessages((current) =>
        current.map((message) =>
          message.id === messageId && message.confirmation
            ? {
                ...message,
                confirmation: {
                  ...message.confirmation,
                  status: 'pending',
                  error: err.message || '确认失败',
                },
              }
            : message,
        ),
      );
    }
  }

  function upsertSubagentStatus(data) {
    const next = normalizeSubagentStatus(data);
    setMessages((current) => {
      const index = current.findIndex((message) =>
        message.kind === 'subagent_status' && message.subagent?.taskId === next.subagent.taskId
      );
      if (index < 0 || !next.subagent.taskId) {
        return [...current, next];
      }
      return current.map((message, i) => (i === index ? { ...message, ...next } : message));
    });
  }

  async function newChat() {
    if (!session?.token || chatBusy) return;
    setActiveView('chat');
    setDraft('');
    await createAndOpenConversation(session.token);
  }

  function requestConversationDelete(conversation) {
    if (!session?.token || chatBusy || historyBusy || deletingConversationId) return;
    setConversationPendingDelete(conversation);
  }

  async function deleteConversation(conversation) {
    if (!session?.token || !conversation) return;

    const deletingActiveConversation = conversation.sessionId === sessionId;
    setDeletingConversationId(conversation.sessionId);
    setChatError('');
    try {
      await deleteChatSession(session.token, conversation.sessionId);
      const remaining = conversations.filter((item) => item.sessionId !== conversation.sessionId);
      setConversations(remaining);

      if (deletingActiveConversation) {
        const nextConversation = remaining[0];
        if (nextConversation) {
          await loadConversation(session.token, nextConversation.sessionId);
        } else {
          await createAndOpenConversation(session.token);
        }
      }
    } catch (err) {
      if (err.status === 401) {
        clearStoredSession();
        localStorage.removeItem(SESSION_KEY);
        setSession(null);
        setSessionId('');
        setConversations([]);
        setAuthState('anonymous');
      } else {
        setChatError(err.message || '删除会话失败');
      }
    } finally {
      setDeletingConversationId('');
    }
  }

  async function openWorktrees() {
    if (!session?.token) return;
    setActiveView('worktrees');
    setAccountOpen(false);
  }

  async function refreshWorktrees() {
    if (!session?.token) return;
    setWorktreeBusy(true);
    setWorktreeError('');
    try {
      const result = await listWorktrees(session.token);
      setWorktrees(normalizeWorktrees(result.worktrees));
    } catch (err) {
      if (err.status === 401) {
        clearStoredSession();
        localStorage.removeItem(SESSION_KEY);
        setSession(null);
        setSessionId('');
        setConversations([]);
        setAuthState('anonymous');
      }
      setWorktreeError(err.message || '加载 worktree 失败');
    } finally {
      setWorktreeBusy(false);
    }
  }

  async function submitWorktree(event) {
    event.preventDefault();
    if (!session?.token || worktreeBusy) return;
    setWorktreeBusy(true);
    setWorktreeError('');
    setWorktreeNotice('');
    try {
      await createWorktree(session.token, {
        name: worktreeForm.name.trim(),
        baseRef: worktreeForm.baseRef.trim() || 'HEAD',
        taskId: worktreeForm.taskId.trim(),
      });
      setWorktreeForm({ name: '', baseRef: 'HEAD', taskId: '' });
      setWorktreeNotice('Worktree 已创建。');
      const result = await listWorktrees(session.token);
      setWorktrees(normalizeWorktrees(result.worktrees));
    } catch (err) {
      setWorktreeError(err.message || '创建 worktree 失败');
    } finally {
      setWorktreeBusy(false);
    }
  }

  async function handleKeepWorktree(name) {
    if (!session?.token || worktreeBusy) return;
    setWorktreeBusy(true);
    setWorktreeError('');
    setWorktreeNotice('');
    try {
      await keepWorktree(session.token, name);
      setWorktreeNotice(`已标记保留：${name}`);
      const result = await listWorktrees(session.token);
      setWorktrees(normalizeWorktrees(result.worktrees));
    } catch (err) {
      setWorktreeError(err.message || '标记保留失败');
    } finally {
      setWorktreeBusy(false);
    }
  }

  async function handleDeleteWorktree(name) {
    if (!session?.token || worktreeBusy) return;
    const ok = window.confirm(`确认删除 worktree：${name}？`);
    if (!ok) return;
    setWorktreeBusy(true);
    setWorktreeError('');
    setWorktreeNotice('');
    try {
      await deleteWorktree(session.token, name);
      setWorktreeNotice(`已删除：${name}`);
      const result = await listWorktrees(session.token);
      setWorktrees(normalizeWorktrees(result.worktrees));
    } catch (err) {
      setWorktreeError(err.message || '删除 worktree 失败');
    } finally {
      setWorktreeBusy(false);
    }
  }

  async function handleLogout() {
    setBusy(true);
    try {
      await logout(session.token);
    } catch {
      // 本地退出优先。
    } finally {
      clearStoredSession();
      localStorage.removeItem(SESSION_KEY);
      setSession(null);
      setAuthState('anonymous');
      setBusy(false);
      setNotice('');
      setError('');
      setMessages([]);
      setConversations([]);
      setSessionId('');
    }
  }

  if (authState === 'checking') {
    return (
      <main className="auth-page">
        <section className="auth-card">
          <div className="auth-logo"><img src={JARVIS_ICON} alt="Jarvis" /></div>
          <h1>正在检查登录状态</h1>
          <p>请稍候...</p>
        </section>
      </main>
    );
  }

  if (authState === 'authenticated' && session) {
    return (
      <main className={`chat-shell ${sidebarOpen ? '' : 'sidebar-collapsed'}`}>
        <aside className={`chat-sidebar ${sidebarOpen ? '' : 'collapsed'}`}>
          <div className="sidebar-top">
            <button className="brand-button" type="button" aria-label="Jarvis">
              <img className="jarvis-brand-icon" src={JARVIS_ICON} alt="" />
            </button>
            <button className="icon-button" type="button" onClick={() => setSidebarOpen(!sidebarOpen)} aria-label="折叠侧栏">
              {sidebarOpen ? <PanelLeftClose size={20} /> : <Menu size={20} />}
            </button>
          </div>

          {sidebarOpen && (
            <>
              <nav className="sidebar-nav" aria-label="主导航">
                <button className={`nav-item ${activeView === 'chat' ? 'active' : ''}`} type="button" onClick={newChat}>
                  <MessageSquarePlus size={20} />
                  新聊天
                </button>
                <button className="nav-item" type="button">
                  <Search size={20} />
                  搜索聊天
                </button>
                <button className="nav-item" type="button">
                  <Library size={20} />
                  库
                </button>
                <button className="nav-item" type="button">
                  <Folder size={20} />
                  项目
                </button>
                <button className="nav-item" type="button">
                  <Grid2X2 size={20} />
                  应用
                </button>
                <button className={`nav-item ${activeView === 'worktrees' ? 'active' : ''}`} type="button" onClick={openWorktrees}>
                  <GitBranch size={20} />
                  Worktrees
                </button>
                <button className="nav-item" type="button">
                  <MoreHorizontal size={20} />
                  更多
                </button>
              </nav>

              <div className="sidebar-section">
                <div className="sidebar-label">最近</div>
                {conversations.length === 0 ? (
                  <button className="conversation-item active" type="button" onClick={newChat}>
                    <span>新的对话</span>
                    <MoreHorizontal size={16} />
                  </button>
                ) : (
                  conversations.map((conversation) => (
                    <div className="conversation-row" key={conversation.sessionId}>
                      <button
                        className={`conversation-item ${conversation.sessionId === sessionId ? 'active' : ''}`}
                        type="button"
                        disabled={historyBusy || chatBusy || Boolean(deletingConversationId)}
                        onClick={() => loadConversation(session.token, conversation.sessionId)}
                      >
                        <span>{conversation.title || '新的对话'}</span>
                      </button>
                      <button
                        className="conversation-delete"
                        type="button"
                        aria-label={`删除会话：${conversation.title || '新的对话'}`}
                        title="删除会话"
                        disabled={historyBusy || chatBusy || Boolean(deletingConversationId)}
                        onClick={() => requestConversationDelete(conversation)}
                      >
                        <Trash2 size={15} />
                      </button>
                    </div>
                  ))
                )}
              </div>

              <div className="sidebar-account">
                <button className="account-button" type="button" onClick={() => setAccountOpen(!accountOpen)}>
                  <span className="avatar">{firstName(session.user).slice(0, 1).toUpperCase()}</span>
                  <span className="account-meta">
                    <span className="account-name">{firstName(session.user)}</span>
                    <span className="account-plan">免费版</span>
                  </span>
                  <span className="upgrade-chip">升级</span>
                </button>
              </div>
            </>
          )}
        </aside>

        <section className="chat-main">
          <header className="chat-header">
            <button className="mobile-menu" type="button" onClick={() => setSidebarOpen(!sidebarOpen)}>
              <Menu size={20} />
            </button>
            <button className="model-pill" type="button">
              {activeView === 'worktrees' ? 'Worktrees' : 'Jarvis'}
              <ChevronDown size={16} />
            </button>
            <div className="header-spacer" />
            <button className="top-upgrade" type="button">
              <Sparkles size={17} />
              升级
            </button>
          </header>

          {activeView === 'worktrees' ? (
            <section className="worktree-view">
              <div className="worktree-head">
                <div>
                  <h1>Worktrees</h1>
                  <p>管理 Jarvis 的隔离工作区。</p>
                </div>
                <button className="secondary-button" type="button" onClick={refreshWorktrees} disabled={worktreeBusy}>
                  <RefreshCw size={16} />
                  刷新
                </button>
              </div>

              <form className="worktree-form" onSubmit={submitWorktree}>
                <label>
                  名称
                  <input
                    name="name"
                    value={worktreeForm.name}
                    onChange={updateWorktreeField}
                    placeholder="feature-login"
                    required
                  />
                </label>
                <label>
                  Base Ref
                  <input
                    name="baseRef"
                    value={worktreeForm.baseRef}
                    onChange={updateWorktreeField}
                    placeholder="HEAD"
                  />
                </label>
                <label>
                  Task ID
                  <input
                    name="taskId"
                    value={worktreeForm.taskId}
                    onChange={updateWorktreeField}
                    placeholder="可选"
                  />
                </label>
                <button className="dark-button" type="submit" disabled={worktreeBusy || !worktreeForm.name.trim()}>
                  创建
                </button>
              </form>

              {worktreeError && <div className="inline-error">{worktreeError}</div>}
              {worktreeNotice && <div className="success-box compact">{worktreeNotice}</div>}

              <div className="worktree-table-wrap">
                <table className="worktree-table">
                  <thead>
                    <tr>
                      <th>名称</th>
                      <th>状态</th>
                      <th>分支</th>
                      <th>路径</th>
                      <th>Task</th>
                      <th>保留</th>
                      <th>更新</th>
                      <th>操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {worktrees.length === 0 ? (
                      <tr>
                        <td colSpan="8" className="empty-cell">
                          {worktreeBusy ? '正在加载...' : '暂无 worktree'}
                        </td>
                      </tr>
                    ) : (
                      worktrees.map((item) => (
                        <tr key={item.name}>
                          <td className="strong-cell">{item.name}</td>
                          <td><span className={`status-pill ${item.status}`}>{item.status || '-'}</span></td>
                          <td><code>{item.branch || '-'}</code></td>
                          <td className="path-cell" title={item.absolutePath || item.path}>{item.path || '-'}</td>
                          <td>{item.taskId || '-'}</td>
                          <td>{item.kept ? '是' : '否'}</td>
                          <td>{item.updatedAt ? new Date(item.updatedAt).toLocaleString() : '-'}</td>
                          <td>
                            <div className="row-actions">
                              <button
                                type="button"
                                disabled={worktreeBusy || item.status !== 'active' || item.kept}
                                onClick={() => handleKeepWorktree(item.name)}
                              >
                                保留
                              </button>
                              <button
                                className="danger-action"
                                type="button"
                                disabled={worktreeBusy || item.status !== 'active'}
                                onClick={() => handleDeleteWorktree(item.name)}
                              >
                                <Trash2 size={14} />
                                删除
                              </button>
                            </div>
                          </td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>
            </section>
          ) : (
          <div className="messages">
            {historyBusy ? (
              <div className="empty-state">
                <h1>正在加载会话</h1>
              </div>
            ) : messages.length === 0 ? (
              <div className="empty-state">
                <h1>我们先从哪里开始呢？</h1>
              </div>
            ) : (
              messages.map((message) => (
                <article className={`message-row ${message.role}`} key={message.id}>
                  <div className={`message-avatar ${message.role === 'assistant' ? 'jarvis-message-avatar' : ''}`}>
                    {message.role === 'user' ? firstName(session.user).slice(0, 1).toUpperCase() : <img src={JARVIS_ICON} alt="Jarvis" />}
                  </div>
                  <div className="message-body">
                    {message.role === 'assistant' ? (
                      message.kind === 'subagent_status' ? (
                        <div className={`subagent-status ${message.subagent?.status || 'running'}`}>
                          <div className="subagent-status-line">
                            <span className="subagent-dot" />
                            <span>
                              {message.subagent?.status === 'completed'
                                ? '子 Agent 执行成功'
                                : message.subagent?.status === 'failed'
                                  ? '子 Agent 执行失败'
                                  : '子 Agent 正在运行'}
                            </span>
                          </div>
                          {message.subagent?.task && (
                            <div className="subagent-task">{message.subagent.task}</div>
                          )}
                          {message.subagent?.worktree && (
                            <div className="subagent-meta">worktree: {message.subagent.worktree}</div>
                          )}
                          {message.subagent?.status === 'completed' && message.subagent?.result && (
                            <details className="subagent-details">
                              <summary>查看结果</summary>
                              <MarkdownMessage content={message.subagent.result} />
                            </details>
                          )}
                          {message.subagent?.status === 'failed' && message.subagent?.error && (
                            <div className="subagent-error">{message.subagent.error}</div>
                          )}
                        </div>
                      ) : message.content || message.providerEvents?.length ? (
                        <>
                          {message.content ? <MarkdownMessage content={message.content} /> : null}
                          <ProviderEventList events={message.providerEvents} />
                          {message.confirmation && (
                            <div className="tool-confirm-panel">
                              <div className="tool-confirm-head">
                                <Lock size={17} />
                                <span>工具操作等待确认</span>
                              </div>
                              <div className="tool-confirm-meta">
                                <span>工具：{message.confirmation.tool}</span>
                                <span>操作：{message.confirmation.action}</span>
                                {message.confirmation.expiresAt && <span>过期：{message.confirmation.expiresAt}</span>}
                              </div>
                              {message.confirmation.summary && (
                                <div className="tool-confirm-summary">{message.confirmation.summary}</div>
                              )}
                              {message.confirmation.command && (
                                <code className="tool-confirm-command">{message.confirmation.command}</code>
                              )}
                              {message.confirmation.error && (
                                <div className="tool-confirm-error">{message.confirmation.error}</div>
                              )}
                              <button
                                className="tool-confirm-button"
                                type="button"
                                disabled={message.confirmation.status !== 'pending' || chatBusy}
                                onClick={() => handleToolConfirm(message.id, message.confirmation.confirmId)}
                              >
                                {message.confirmation.status === 'running' ? (
                                  '正在执行...'
                                ) : message.confirmation.status === 'confirmed' ? (
                                  <>
                                    <Check size={16} />
                                    已执行
                                  </>
                                ) : (
                                  '确认执行'
                                )}
                              </button>
                            </div>
                          )}
                          <ContextUsageLine usage={message.tokenUsage} />
                        </>
                      ) : (
                        <div className="thinking">正在思考</div>
                      )
                    ) : (
                      message.content
                    )}
                  </div>
                </article>
              ))
            )}
            {chatBusy && messages[messages.length - 1]?.role !== 'assistant' && (
              <article className="message-row assistant">
                <div className="message-avatar jarvis-message-avatar"><img src={JARVIS_ICON} alt="Jarvis" /></div>
                <div className="message-body thinking">正在思考</div>
              </article>
            )}
            <div ref={scrollRef} />
          </div>
          )}

          {activeView === 'chat' && (
            <>
              <div className="composer-wrap">
                {chatError && <div className="chat-error">{chatError}</div>}
                <form className="composer" onSubmit={handleSend}>
                  <textarea
                    value={draft}
                    onChange={(event) => setDraft(event.target.value)}
                    onKeyDown={(event) => {
                      if (event.nativeEvent.isComposing || event.keyCode === 229) {
                        return;
                      }
                      if (event.key === 'Enter' && !event.shiftKey) {
                        event.preventDefault();
                        handleSend(event);
                      }
                    }}
                    placeholder="今天帮你做些什么？ @ 引用对话文件，/ 调用技能与指令"
                    rows={1}
                  />
                  <div className="composer-toolbar">
                    <div className="composer-left-tools">
                      <div className="mode-picker-wrap" ref={runModeRef}>
                        <button
                          className="mode-picker toolbar-pill"
                          type="button"
                          disabled={chatBusy}
                          aria-haspopup="menu"
                          aria-expanded={runModeOpen}
                          onClick={() => setRunModeOpen((open) => !open)}
                        >
                          <Sparkles size={18} />
                          <span>{selectedRunModeLabel(runMode)}</span>
                          <ChevronDown size={16} />
                        </button>
                        {runModeOpen && (
                          <div className="mode-menu" role="menu">
                            {RUN_MODES.map((item) => {
                              const active = item.value === runMode;
                              return (
                                <button
                                  key={item.value}
                                  className={`mode-menu-item${active ? ' active' : ''}`}
                                  type="button"
                                  role="menuitemradio"
                                  aria-checked={active}
                                  onClick={() => {
                                    setRunMode(item.value);
                                    setRunModeOpen(false);
                                  }}
                                >
                                  <span className="mode-menu-icon">
                                    {item.value === 'chat' && <MessageSquarePlus size={18} />}
                                    {item.value === 'agent' && <Sparkles size={18} />}
                                    {item.value === 'super_agent' && <Bot size={18} />}
                                  </span>
                                  <span className="mode-menu-text">
                                    <span>{item.label}</span>
                                    <span>{item.command}</span>
                                  </span>
                                  <span className="mode-menu-check">
                                    {active && <Check size={17} />}
                                  </span>
                                </button>
                              );
                            })}
                          </div>
                        )}
                      </div>
                      <button className="toolbar-pill" type="button">
                        <Library size={20} />
                        <span>Auto</span>
                        <ChevronDown size={16} />
                      </button>
                      <button className="toolbar-pill" type="button">
                        <Library size={20} />
                        <span>技能</span>
                        <ChevronDown size={16} />
                      </button>
                      <button className="toolbar-pill" type="button">
                        <Globe size={20} />
                        <span>连接器</span>
                        <ChevronDown size={16} />
                      </button>
                      <button className="toolbar-pill" type="button">
                        <Lock size={19} />
                        <span>默认权限</span>
                        <ChevronDown size={16} />
                      </button>
                    </div>
                    <div className="composer-right-tools">
                      <button className="composer-tool" type="button" aria-label="添加">
                        <Plus size={24} />
                      </button>
                      <button className="composer-tool soft" type="button" aria-label="技能">
                        <Sparkles size={22} />
                      </button>
                      <button className="composer-tool soft" type="button" aria-label="语音输入">
                        <Mic size={21} />
                      </button>
                      <button className="send-button" type="submit" disabled={!draft.trim() || chatBusy}>
                        <ArrowUp size={20} />
                      </button>
                    </div>
                  </div>
                </form>
                <div className="composer-workspace-row">
                  <div className="workspace-picker-wrap" ref={workspaceRef}>
                    <button
                      className="workspace-picker"
                      type="button"
                      disabled={chatBusy || workspaces.length === 0}
                      title={selectedWorkspaceItem?.path || ''}
                      aria-haspopup="menu"
                      aria-expanded={workspaceOpen}
                      onClick={() => {
                        setWorkspaceQuery('');
                        setWorkspaceOpen((open) => !open);
                      }}
                    >
                      <Folder size={18} />
                      <span>{selectedWorkspaceItem?.label || '选择工作空间'}</span>
                      <ChevronDown size={16} />
                    </button>
                    {workspaceOpen && (
                      <div className="workspace-menu" role="menu">
                        <label className="workspace-search">
                          <input
                            value={workspaceQuery}
                            onChange={(event) => setWorkspaceQuery(event.target.value)}
                            placeholder="搜索工作空间"
                            autoFocus
                          />
                          <Search size={19} />
                        </label>
                        <div className="workspace-menu-list">
                          {filteredWorkspaces.length === 0 ? (
                            <div className="workspace-empty">没有匹配的工作空间</div>
                          ) : (
                            filteredWorkspaces.map((item) => {
                              const active = item.id === selectedWorkspace;
                              return (
                                <button
                                  key={item.id}
                                  className={`workspace-menu-item${active ? ' active' : ''}`}
                                  type="button"
                                  role="menuitemradio"
                                  aria-checked={active}
                                  onClick={() => {
                                    setSelectedWorkspace(item.id);
                                    setWorkspaceOpen(false);
                                  }}
                                >
                                  <span className="workspace-menu-main">
                                    <span>{item.label}</span>
                                    {item.path && <span>{item.path}</span>}
                                  </span>
                                  <span className="workspace-menu-check">
                                    {active && <Check size={18} />}
                                  </span>
                                </button>
                              );
                            })
                          )}
                        </div>
                      </div>
                    )}
                  </div>
                </div>
                {messages.length === 0 && (
                  <div className="quick-actions">
                    <button type="button">
                      <ImageIcon size={18} />
                      生成图片
                    </button>
                    <button type="button">
                      <Pencil size={18} />
                      撰写或编辑
                    </button>
                    <button type="button">
                      <Globe size={18} />
                      查找资料
                    </button>
                  </div>
                )}
              </div>
              <p className="composer-note">Jarvis 可能会出错。请核查重要信息。</p>
            </>
          )}
        </section>

        {conversationPendingDelete && (
          <div
            className="confirm-dialog-backdrop"
            role="presentation"
            onMouseDown={(event) => {
              if (event.target === event.currentTarget) setConversationPendingDelete(null);
            }}
          >
            <section
              className="confirm-dialog"
              role="alertdialog"
              aria-modal="true"
              aria-labelledby="delete-conversation-title"
              aria-describedby="delete-conversation-description"
            >
              <div className="confirm-dialog-head">
                <span className="confirm-dialog-icon"><TriangleAlert size={24} /></span>
                <h2 id="delete-conversation-title">删除会话</h2>
              </div>
              <p id="delete-conversation-description">
                确认后将永久删除“{conversationPendingDelete.title || '新的对话'}”及其聊天记录，且无法恢复。
              </p>
              <div className="confirm-dialog-actions">
                <button
                  className="confirm-dialog-cancel"
                  type="button"
                  autoFocus
                  disabled={Boolean(deletingConversationId)}
                  onClick={() => setConversationPendingDelete(null)}
                >
                  取消
                </button>
                <button
                  className="confirm-dialog-delete"
                  type="button"
                  disabled={Boolean(deletingConversationId)}
                  onClick={async () => {
                    const conversation = conversationPendingDelete;
                    setConversationPendingDelete(null);
                    await deleteConversation(conversation);
                  }}
                >
                  {deletingConversationId ? '正在删除...' : '确认删除'}
                </button>
              </div>
            </section>
          </div>
        )}

        {accountOpen && (
          <div className="account-popover" role="dialog" aria-label="账户菜单">
            <div className="popover-head">
              <div>
                <strong>{firstName(session.user)}</strong>
                <span>{session.user.username}</span>
              </div>
              <button className="icon-button" type="button" onClick={() => setAccountOpen(false)}>
                <X size={18} />
              </button>
            </div>

            <div className="account-menu">
              <button className="account-menu-row" type="button">
                <Settings size={18} />
                <span>设置</span>
              </button>
              <div className="account-menu-row appearance-row">
                <span className="account-menu-label"><Palette size={18} />外观</span>
                <span className="theme-switch" role="group" aria-label="选择外观">
                  <button className={theme === 'light' ? 'active' : ''} type="button" onClick={() => setTheme('light')}>浅色</button>
                  <button className={theme === 'dark' ? 'active' : ''} type="button" onClick={() => setTheme('dark')}>深色</button>
                </span>
              </div>
              <button className="account-menu-row" type="button">
                <CircleHelp size={18} />
                <span>帮助与反馈</span>
              </button>
              <button className="account-menu-row" type="button">
                <CircleArrowUp size={18} />
                <span>检查更新</span>
              </button>
            </div>

            <button className="logout-row account-menu-row" type="button" onClick={handleLogout} disabled={busy}>
              <LogOut size={17} />
              退出登录
            </button>
          </div>
        )}
      </main>
    );
  }

  return (
    <main className="auth-page">
      <section className="auth-card">
        <div className="auth-logo"><img src={JARVIS_ICON} alt="Jarvis" /></div>
        <h1>{isRegister ? '创建你的账号' : '欢迎回来'}</h1>

        {notice && <div className="success-box">{notice}</div>}

        <form onSubmit={submitAuth} className="auth-form">
          {isRegister && (
            <label className="field">
              <span>显示名称</span>
              <input
                name="displayName"
                value={authForm.displayName}
                onChange={updateAuthField}
                placeholder="你的名字"
                autoComplete="name"
              />
            </label>
          )}

          <label className="field">
            <span>用户名</span>
            <div className="input-wrap">
              <UserRound size={18} />
              <input
                name="username"
                value={authForm.username}
                onChange={updateAuthField}
                placeholder="username"
                autoComplete="username"
                required
              />
            </div>
          </label>

          <label className="field">
            <span>密码</span>
            <div className="input-wrap">
              <Lock size={18} />
              <input
                name="password"
                type="password"
                value={authForm.password}
                onChange={updateAuthField}
                placeholder={isRegister ? '至少 8 位' : '输入密码'}
                autoComplete={isRegister ? 'new-password' : 'current-password'}
                minLength={isRegister ? 8 : undefined}
                required
              />
            </div>
          </label>

          {error && <div className="error-box">{error}</div>}

          <button className="primary-button" type="submit" disabled={busy}>
            {submitText}
          </button>
        </form>

        <p className="switch-line">
          {isRegister ? '已经有账号？' : '还没有账号？'}
          <button
            type="button"
            onClick={() => {
              setMode(isRegister ? 'login' : 'register');
              setError('');
              setNotice('');
            }}
          >
            {isRegister ? '登录' : '注册'}
          </button>
        </p>

        <p className="api-line">API: {apiBase()}</p>
      </section>
    </main>
  );
}
