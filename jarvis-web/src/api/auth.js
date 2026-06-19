function defaultApiBase() {
  if (typeof window === 'undefined') {
    return 'http://localhost:8082';
  }
  return `${window.location.protocol}//${window.location.hostname}:8082`;
}

const API_BASE = (import.meta.env.VITE_JARVIS_API_BASE || defaultApiBase()).replace(/\/$/, '');

async function request(path, { method = 'POST', body, token } = {}) {
  let response;
  const headers = {
    'Content-Type': 'application/json',
  };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  try {
    response = await fetch(`${API_BASE}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (error) {
    throw new Error(`无法连接到 Jarvis 服务：${error.message}`);
  }

  const text = await response.text();
  const data = text ? JSON.parse(text) : {};
  if (!response.ok) {
    const error = new Error(data.msg || data.message || `请求失败：${response.status}`);
    error.status = response.status;
    throw error;
  }
  return data;
}

export function login({ username, password }) {
  return request('/api/v1/auth/login', { body: { username, password } });
}

export function register({ username, password, displayName }) {
  return request('/api/v1/auth/register', { body: { username, password, displayName } });
}

export function me(token) {
  return request('/api/v1/auth/me', { method: 'GET', token });
}

export function logout(token) {
  return request('/api/v1/auth/logout', { token });
}

export function changePassword(token, { oldPassword, newPassword }) {
  return request('/api/v1/auth/change-password', {
    token,
    body: { oldPassword, newPassword },
  });
}

export function sendChat(token, { sessionId, message }) {
  return request('/api/v1/chat', {
    token,
    body: { sessionId, message },
  });
}

export function confirmTool(token, { confirmId }) {
  return request('/api/v1/tools/confirm', {
    token,
    body: { confirm_id: confirmId },
  });
}

export function listChatSessions(token) {
  return request('/api/v1/chat/sessions', { method: 'GET', token });
}

export function createChatSession(token) {
  return request('/api/v1/chat/sessions', { token });
}

export function getChatSessionMessages(token, sessionId) {
  return request(`/api/v1/chat/sessions/${encodeURIComponent(sessionId)}/messages`, {
    method: 'GET',
    token,
  });
}

export function listWorktrees(token) {
  return request('/api/v1/git/worktrees', { method: 'GET', token });
}

export function createWorktree(token, { name, baseRef, taskId }) {
  return request('/api/v1/git/worktrees', {
    token,
    body: {
      name,
      base_ref: baseRef,
      task_id: taskId,
    },
  });
}

export function keepWorktree(token, name) {
  return request(`/api/v1/git/worktrees/${encodeURIComponent(name)}/keep`, { token });
}

export function deleteWorktree(token, name) {
  return request(`/api/v1/git/worktrees/${encodeURIComponent(name)}`, {
    method: 'DELETE',
    token,
  });
}

function parseSseBlock(block) {
  let event = 'message';
  const data = [];
  for (const rawLine of block.split(/\r?\n/)) {
    const line = rawLine.trimEnd();
    if (!line || line.startsWith(':')) continue;
    if (line.startsWith('event:')) {
      event = line.slice(6).trim();
      continue;
    }
    if (line.startsWith('data:')) {
      data.push(line.slice(5).trimStart());
    }
  }
  return { event, data: data.join('\n') };
}

export async function streamChat(
  token,
  { sessionId, message, onToken, onReasoning, onToolCall, onToolResult, onDone, signal },
) {
  let response;
  try {
    response = await fetch(`${API_BASE}/api/v1/chat/stream`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: 'text/event-stream',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ sessionId, message }),
      signal,
    });
  } catch (error) {
    throw new Error(`无法连接到 Jarvis 服务：${error.message}`);
  }

  if (!response.ok) {
    const text = await response.text();
    let data = {};
    try {
      data = text ? JSON.parse(text) : {};
    } catch {
      data = {};
    }
    const error = new Error(data.msg || data.message || text || `请求失败：${response.status}`);
    error.status = response.status;
    throw error;
  }

  if (!response.body) {
    throw new Error('当前浏览器不支持流式响应');
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let finalData = null;

  function handleEvent(event, rawData) {
    if (!rawData) return;
    let data;
    try {
      data = JSON.parse(rawData);
    } catch {
      data = { content: rawData };
    }

    if (event === 'token') {
      onToken?.(data.content || '', data);
      return;
    }
    if (event === 'reasoning') {
      onReasoning?.(data.content || '', data);
      return;
    }
    if (event === 'tool_call') {
      onToolCall?.(data);
      return;
    }
    if (event === 'tool_result') {
      onToolResult?.(data);
      return;
    }
    if (event === 'done') {
      finalData = data;
      onDone?.(data);
      return;
    }
    if (event === 'error') {
      const error = new Error(data.content || data.message || '流式请求失败');
      error.data = data;
      throw error;
    }
  }

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const blocks = buffer.split(/\r?\n\r?\n/);
    buffer = blocks.pop() || '';
    for (const block of blocks) {
      const { event, data } = parseSseBlock(block);
      handleEvent(event, data);
    }
  }

  buffer += decoder.decode();
  if (buffer.trim()) {
    const { event, data } = parseSseBlock(buffer);
    handleEvent(event, data);
  }

  return finalData || {};
}

export function apiBase() {
  return API_BASE;
}
