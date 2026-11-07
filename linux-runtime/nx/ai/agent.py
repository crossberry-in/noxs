'''
noxs-ai agent — the Noxs AI Agent Terminal runtime (original Noxs implementation).

    User -> AI model -> agent decision -> tool call(s) -> Noxs Tool Executor
         -> tool result -> AI model -> next tool call or final answer -> User

Boundaries enforced here (never in the model):
  - every tool call is validated against the registry before execution
  - CONFIRM-level tools ask the user: Allow? [y/N] — never simulated by the model
  - independent READ tools may run in parallel (bounded); everything else is
    sequential, so dependent or conflicting actions can never race (sections 7-8)
  - agent limits (steps, tool calls, runtime) stop runaway loops safely (section 17)
  - tool output is truncated, secrets are redacted, context is compacted (section 15)
  - Ctrl+C cancels the current request/tool and returns to nx@ai>
  - Ctrl+D / exit ends the session and releases everything it owns (section 20)
  - /provider manages AI gateways (Kilo, OpenCode Zen, OpenRouter, custom
    OpenAI-compatible endpoints): add with a y/N confirmation, switch, remove;
    answering yes enables full session chat (the whole conversation is kept)
'''
import argparse
import difflib
import json
import os
import re
import signal
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlparse

try:
    from provider import AIProviderError, CancelFlag, ChatClient, RetryPolicy
    import tools as tools_mod
    from tools import (ACTIVE_PROCESSES, PERMISSION_CONFIRM, PERMISSION_READ,
                       ToolResult, build_default_registry, redact_secrets,
                       truncate_output)
except ImportError:  # running from the repository test suite
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from provider import AIProviderError, CancelFlag, ChatClient, RetryPolicy
    import tools as tools_mod
    from tools import (ACTIVE_PROCESSES, PERMISSION_CONFIRM, PERMISSION_READ,
                       ToolResult, build_default_registry, redact_secrets,
                       truncate_output)


# ------------------------------------------------------------------ config

CONFIG_DEFAULTS = {
    'enabled': True,
    'provider': 'kilo',
    'model': 'kilo-auto/free',
    'base_url': 'https://api.kilo.ai/api/gateway',
    'api_key_env': 'KILO_API_KEY',
    'stream': True,
    'request_timeout_sec': 90,
    'max_retries': 3,
    'max_steps': 30,
    'max_tool_calls': 50,
    'max_parallel_tools': 4,
    'max_tool_output': 12000,
    'max_runtime_sec': 900,
}

CONFIG_ENV_OVERRIDES = {
    'model': 'NOXS_AI_MODEL',
    'base_url': 'NOXS_AI_BASE_URL',
    'provider': 'NOXS_AI_PROVIDER',
    'api_key_env': 'NOXS_AI_API_KEY_ENV',
    'stream': 'NOXS_AI_STREAM',
}

PROVIDER_PRESETS = {
    'kilo': {
        'label': 'Kilo',
        'base_url': 'https://api.kilo.ai/api/gateway',
        'model': 'kilo-auto/free',
        'api_key_env': 'KILO_API_KEY',
        'key_required': False,
    },
    'opencode-zen': {
        'label': 'OpenCode Zen',
        'base_url': 'https://opencode.ai/zen/v1',
        'model': 'big-pickle',
        'api_key_env': 'OPENCODE_API_KEY',
        'key_required': True,
    },
    'openrouter': {
        'label': 'OpenRouter',
        'base_url': 'https://openrouter.ai/api/v1',
        'model': 'openrouter/auto',
        'api_key_env': 'OPENROUTER_API_KEY',
        'key_required': True,
    },
}

ENV_NAME_PATTERN = re.compile(r'^[A-Za-z_][A-Za-z0-9_]*$')


def provider_store_path():
    '''Custom providers, the active choice and chat mode live here.
    Only the environment-variable NAME is stored — never the key itself.'''
    return os.path.join(os.path.expanduser('~'), '.noxs', 'ai', 'providers.json')


def load_provider_store(path=None):
    '''Missing or broken store files fall back to the empty store (built-ins only).'''
    store = {'active': None, 'full_session': True, 'providers': {}}
    try:
        with open(path or provider_store_path()) as handle:
            data = json.load(handle)
    except (OSError, ValueError):
        return store
    if not isinstance(data, dict):
        return store
    providers = data.get('providers')
    if isinstance(providers, dict):
        store['providers'] = {str(name): entry for name, entry in providers.items()
                              if isinstance(entry, dict)}
    if isinstance(data.get('active'), str) and data['active']:
        store['active'] = data['active']
    if isinstance(data.get('full_session'), bool):
        store['full_session'] = data['full_session']
    return store


def save_provider_store(store, path=None):
    target = path or provider_store_path()
    directory = os.path.dirname(target)
    if directory:
        os.makedirs(directory, exist_ok=True)
    payload = {
        'active': store.get('active'),
        'full_session': bool(store.get('full_session', True)),
        'providers': store.get('providers') or {},
    }
    with open(target, 'w') as handle:
        json.dump(payload, handle, indent=2, sort_keys=True)
        handle.write('\n')
    try:
        os.chmod(target, 0o600)
    except OSError:
        pass


def resolve_provider_definition(name, store=None):
    '''Preset or custom definition for a provider name, or None when unknown.
    A custom entry sharing a preset name overrides preset fields one by one.'''
    store = store or {}
    base = PROVIDER_PRESETS.get(name)
    entry = (store.get('providers') or {}).get(name)
    if base is None and entry is None:
        return None
    definition = dict(base) if base else {}
    if entry:
        definition['label'] = entry.get('label') or definition.get('label') or name
        definition['key_required'] = bool(entry.get(
            'key_required', definition.get('key_required', True)))
        definition['source'] = 'preset' if base else 'custom'
        for key in ('base_url', 'model', 'api_key_env'):
            value = entry.get(key)
            if value:
                definition[key] = value
    else:
        definition['source'] = 'preset'
    return definition


def provider_display_label(name, definition=None):
    if definition and definition.get('label'):
        return definition['label']
    preset = PROVIDER_PRESETS.get(name)
    return preset['label'] if preset else (name or 'kilo')


def slugify_provider_name(raw):
    slug = re.sub(r'[^a-z0-9._-]+', '-', (raw or '').strip().lower()).strip('-.')
    return slug[:24]


def load_config(path=None, overrides=None, store=None):
    config = dict(CONFIG_DEFAULTS)
    candidates = [
        path,
        os.path.join(os.path.expanduser('~'), '.noxs', 'ai', 'config.json'),
    ]
    for candidate in candidates:
        if not candidate or not os.path.isfile(candidate):
            continue
        try:
            with open(candidate) as handle:
                data = json.load(handle)
            if isinstance(data, dict):
                for key in CONFIG_DEFAULTS:
                    if key in data:
                        config[key] = data[key]
            break
        except (OSError, ValueError):
            continue
    env_set = set()
    for key, env_name in CONFIG_ENV_OVERRIDES.items():
        value = os.environ.get(env_name)
        if value is None or value == '':
            continue
        env_set.add(key)
        if isinstance(CONFIG_DEFAULTS[key], bool):
            config[key] = value.lower() in ('1', 'true', 'yes', 'on')
        elif isinstance(CONFIG_DEFAULTS[key], int):
            try:
                config[key] = int(value)
            except ValueError:
                pass
        else:
            config[key] = value
    if store is None:
        store = load_provider_store()
    active = store.get('active') or config['provider'] or 'kilo'
    definition = None
    if active != 'kilo':
        definition = resolve_provider_definition(active, store)
    elif (store.get('providers') or {}).get('kilo'):
        definition = resolve_provider_definition('kilo', store)
    if definition:
        config['provider'] = active
        for key in ('base_url', 'model', 'api_key_env'):
            if key not in env_set and definition.get(key):
                config[key] = definition[key]
    config['full_session'] = bool(store.get('full_session', True))
    if overrides:
        for key, value in overrides.items():
            if value is not None:
                config[key] = value
    for key in ('max_steps', 'max_tool_calls', 'max_parallel_tools'):
        config[key] = max(1, int(config[key]))
    config['max_tool_output'] = max(1000, int(config['max_tool_output']))
    return config


def resolve_api_key(config):
    '''The key never appears in logs, output, or errors — only in memory.'''
    for env_name in (config.get('api_key_env'), 'NOXS_AI_API_KEY'):
        if env_name and os.environ.get(env_name):
            return os.environ[env_name]
    return None


# ------------------------------------------------------------------ context

SYSTEM_PROMPT_TEMPLATE = '''You are the Noxs AI Agent, running inside the Noxs Linux environment on Android.

Environment:
- working directory: {cwd}
- architecture: {arch}
- operating system: {os_name}
- package manager: apt (Ubuntu 24.04 LTS)
- session id: {session_id}

Rules:
1. You may answer directly for greetings, explanations, arithmetic and questions that need no environment access.
2. Use tools whenever real state is needed (files, commands, packages, processes, services, system info).
3. Only use tools from the provided tool list. If something is unavailable, say so honestly.
4. Prefer READ-level tools; destructive or install actions happen only when the user confirms them.
5. Never claim a tool ran when it did not, and never fabricate file contents or command output.
6. When you have enough information, answer concisely in plain text.

You speak to the user with the "_>" prefix handled by the terminal; write plain text without that prefix.'''


class ContextManager:
    '''Conversation + tool results, bounded (spec section 15).'''

    def __init__(self, system_prompt, max_tool_output=12000, compaction_threshold=60):
        self.system = {'role': 'system', 'content': system_prompt}
        self.messages = []
        self.max_tool_output = max_tool_output
        self.compaction_threshold = compaction_threshold

    def add(self, message):
        self.messages.append(message)

    def add_tool_result(self, tool_name, result):
        payload = dict(result)
        if payload.get('stdout') and len(payload['stdout']) > self.max_tool_output:
            payload['stdout'] = truncate_output(payload['stdout'], self.max_tool_output)
        self.messages.append({
            'role': 'tool',
            'tool_call_id': payload.pop('tool_call_id', 'unknown'),
            'content': json.dumps(payload),
        })

    def compact(self):
        '''Keep the system prompt + recent turns; older tool payloads are
        summarized to a marker so the model keeps the shape but not the bulk.'''
        keep = 16
        if len(self.messages) <= self.compaction_threshold:
            return
        head = self.messages[:1]
        body = self.messages[1:]
        for message in body[:-keep]:
            if message.get('role') == 'tool':
                message['content'] = json.dumps({
                    'success': True,
                    'note': '[older tool result summarized away by Noxs AI context management]',
                })
        self.messages = head + body

    def forget(self):
        '''Full session chat off: nothing is carried into the next turn.'''
        self.messages = []

    def snapshot(self):
        return [self.system] + self.messages


# ------------------------------------------------------------------ session

class AgentSession:
    def __init__(self, cwd):
        self.id = uuid.uuid4().hex
        self.started = time.monotonic()
        self.cwd = os.path.realpath(cwd)
        self.cancel_flag = CancelFlag()
        self.owned_pids = set()
        self.tool_calls = 0
        self.steps = 0
        self.tool_history = []
        self.recent_terminal = []
        self.current_task = None

    def elapsed(self):
        return time.monotonic() - self.started


# ------------------------------------------------------------------ logging

def safe_log(log_path, session_id, event, **fields):
    '''One line, no content, no secrets (spec section 28).'''
    try:
        directory = os.path.dirname(log_path)
        if directory:
            os.makedirs(directory, exist_ok=True)
        parts = ['%s' % time.strftime('%Y-%m-%dT%H:%M:%S'), session_id[:12], event]
        for key in sorted(fields):
            parts.append('%s=%s' % (key, fields[key]))
        with open(log_path, 'a') as handle:
            handle.write(' '.join(str(part) for part in parts) + '\n')
    except OSError:
        pass


# ------------------------------------------------------------------ runtime

# ANSI colors render on a TTY; NOXS_AI_COLOR=0/1 forces the mode either way.
_color_mode = os.environ.get('NOXS_AI_COLOR', '').strip().lower()
if _color_mode in ('1', 'true', 'yes', 'on'):
    COLOR_ENABLED = True
elif _color_mode in ('0', 'false', 'no', 'off'):
    COLOR_ENABLED = False
else:
    COLOR_ENABLED = bool(getattr(sys.stdout, 'isatty', lambda: False)())

COLOR_GREEN = '32'
COLOR_RED = '31'
COLOR_YELLOW = '33'
COLOR_CYAN = '36'
COLOR_DIM = '2'

RUNNING = '{-_-}'
OK = '{✓}'
FAILED = '{×}'
WAITING = '{…}'
CANCELLED = '{■}'

SYMBOL_COLORS = {
    OK: COLOR_GREEN,
    FAILED: COLOR_RED,
    CANCELLED: COLOR_YELLOW,
    WAITING: COLOR_YELLOW,
    RUNNING: COLOR_CYAN,
}

SPINNER_FRAMES = ('{-_-}', '{o_o}', '{0_o}', '{o_0}', '{-_-}', '{o.o}', '{-.-}')

EXIT_WORDS = ('exit', 'quit')


def colorize(text, code=''):
    if not code or not COLOR_ENABLED:
        return text
    return '\x1b[%sm%s\x1b[0m' % (code, text)


def tool_badge(permission):
    '''READ tools wear a cyan [read] badge; anything that mutates state is
    a yellow [edit] badge — the same green/red language as a git diff.'''
    if permission == PERMISSION_READ:
        return colorize('[read]', COLOR_CYAN)
    return colorize('[edit]', COLOR_YELLOW)


def tool_line(symbol, name, detail='', badge=''):
    suffix = ' ' + detail if detail else ''
    prefix = badge + ' ' if badge else ''
    print('   %s %s%s%s' % (colorize(symbol, SYMBOL_COLORS.get(symbol, '')),
                            prefix, name, suffix), flush=True)


class Spinner:
    '''Single-line progress animation for one foreground action (a tool run
    or a model turn). Animates only on a TTY; every stop leaves a clean line
    so the result line replaces the animation in place.'''

    INTERVAL = 0.12

    def __init__(self, enabled=None):
        self._stop = threading.Event()
        self._thread = None
        self._label = ''
        self.enabled = COLOR_ENABLED if enabled is None else bool(enabled)

    def _animate(self):
        index = 0
        while not self._stop.wait(self.INTERVAL):
            frame = colorize(SPINNER_FRAMES[index % len(SPINNER_FRAMES)], COLOR_CYAN)
            sys.stdout.write('\r\x1b[2K   %s %s' % (frame, self._label))
            sys.stdout.flush()
            index += 1

    def start(self, label):
        self._label = label
        if not self.enabled:
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._animate, daemon=True)
        self._thread.start()

    def stop(self):
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=1.0)
            self._thread = None
        if self.enabled:
            sys.stdout.write('\r\x1b[2K')
            sys.stdout.flush()


DIFF_MAX_BYTES = 262144
DIFF_ROW_LIMIT = 8


def diff_lines(old_text, new_text, limit=DIFF_ROW_LIMIT):
    '''Git-style accounting for a file overwrite: returns (added, removed, rows)
    where rows are (sign, line) pairs, '+' = added (green) and '-' = removed
    (red). old_text None means the file did not exist before.'''
    new_lines = (new_text or '').splitlines()
    if old_text is None:
        return len(new_lines), 0, [('+', line) for line in new_lines[:limit]]
    old_lines = old_text.splitlines()
    added = removed = 0
    rows = []
    matcher = difflib.SequenceMatcher(a=old_lines, b=new_lines, autojunk=False)
    for tag, i1, i2, j1, j2 in matcher.get_opcodes():
        if tag in ('delete', 'replace'):
            for line in old_lines[i1:i2]:
                removed += 1
                if len(rows) < limit:
                    rows.append(('-', line))
        if tag in ('insert', 'replace'):
            for line in new_lines[j1:j2]:
                added += 1
                if len(rows) < limit:
                    rows.append(('+', line))
    return added, removed, rows


def assistant_prefix():
    print('_> ', end='', flush=True)


class AgentRuntime:
    def __init__(self, config, session, log_path=None, one_shot=False,
                 auto_confirm=False, on_delta=None):
        self.config = config
        self.session = session
        self.log_path = log_path or os.path.join(
            os.path.expanduser('~'), '.noxs', 'ai', 'agent.log')
        self.one_shot = one_shot
        self.auto_confirm = auto_confirm
        self.registry = build_default_registry(session.cwd, session=session)
        self.client = ChatClient(
            base_url=config['base_url'],
            model=config['model'],
            api_key=resolve_api_key(config),
            timeout=config['request_timeout_sec'],
            retry_policy=RetryPolicy(max_retries=config['max_retries']),
            cancel_flag=session.cancel_flag,
        )
        self.context = ContextManager(
            self._system_prompt(), max_tool_output=config['max_tool_output'])
        self.busy = False
        self.active_futures = []
        self._stream_state = {'open': False}

    # ------------------------------------------------------------ prompts

    def _system_prompt(self):
        arch = 'unknown'
        os_name = 'Linux'
        try:
            import platform
            arch = platform.machine() or arch
        except Exception:
            pass
        try:
            with open('/etc/os-release') as handle:
                for line in handle:
                    if line.startswith('PRETTY_NAME='):
                        os_name = line.split('=', 1)[1].strip().strip('"')
        except OSError:
            pass
        return SYSTEM_PROMPT_TEMPLATE.format(
            cwd=self.session.cwd, arch=arch, os_name=os_name,
            session_id=self.session.id[:12],
        )

    # -------------------------------------------------------------- output

    def emit_text(self, text):
        print('\n_> %s' % text.strip() if not self._stream_state['open'] else '', flush=True)

    def _on_delta(self, piece):
        if not self._stream_state['open']:
            assistant_prefix()
            self._stream_state['open'] = True
        print(piece, end='', flush=True)

    def _close_stream_line(self):
        if self._stream_state['open']:
            print(flush=True)
            self._stream_state['open'] = False

    def emit_note(self, text):
        self._close_stream_line()
        print('_> %s' % text, flush=True)

    def _provider_host(self):
        '''Human-readable host of the configured AI gateway, for failure hints.'''
        try:
            host = urlparse(self.config.get('base_url') or '').netloc
        except (ValueError, AttributeError):
            host = ''
        return host or 'the AI gateway'

    def apply_config(self, config):
        '''Hot-apply a provider configuration (the conversation is kept).'''
        self.config = config
        self.client = ChatClient(
            base_url=config['base_url'],
            model=config['model'],
            api_key=resolve_api_key(config),
            timeout=config['request_timeout_sec'],
            retry_policy=RetryPolicy(max_retries=config['max_retries']),
            cancel_flag=self.session.cancel_flag,
        )
        self.context.system = {'role': 'system', 'content': self._system_prompt()}

    def provider_label(self):
        name = self.config.get('provider', 'kilo')
        return provider_display_label(
            name, resolve_provider_definition(name, load_provider_store()))

    # --------------------------------------------------------- permissions

    def confirm(self, tool, target_summary):
        '''Confirmation is controlled by Noxs only (spec section 9).'''
        if self.auto_confirm:
            safe_log(self.log_path, self.session.id, 'confirm', tool=tool, result='auto')
            return True
        if self.one_shot:
            return False
        self._close_stream_line()
        print('\n⚠ Action requires permission', flush=True)
        print('Tool: %s' % tool, flush=True)
        print('Target: %s' % (target_summary or '(unspecified)'), flush=True)
        try:
            answer = input('Allow? [y/N] ').strip().lower()
        except (EOFError, KeyboardInterrupt):
            print(flush=True)
            return False
        allowed = answer in ('y', 'yes')
        safe_log(self.log_path, self.session.id, 'confirm', tool=tool,
                 result='allow' if allowed else 'deny')
        return allowed

    # ----------------------------------------------------------- execution

    def _validate_call(self, call):
        '''Every tool call is validated before execution (spec section 6).'''
        name = call.get('function', {}).get('name', '')
        tool = self.registry.get(name)
        if tool is None:
            return None, ToolResult.failure(name or '(unknown)', 'unknown tool')
        raw_arguments = call.get('function', {}).get('arguments') or '{}'
        try:
            arguments = json.loads(raw_arguments)
        except ValueError:
            return tool, ToolResult.failure(name, 'invalid arguments: not valid JSON')
        if not isinstance(arguments, dict):
            return tool, ToolResult.failure(name, 'invalid arguments: expected an object')
        schema = tool.parameters or {}
        for required in schema.get('required', []) or []:
            if required not in arguments:
                return tool, ToolResult.failure(name, 'missing required argument: %s' % required)
        properties = schema.get('properties', {}) or {}
        for key, value in arguments.items():
            expected = properties.get(key, {}).get('type')
            if expected == 'string' and not isinstance(value, str):
                return tool, ToolResult.failure(name, 'argument %s must be a string' % key)
            if expected == 'integer' and not isinstance(value, int):
                return tool, ToolResult.failure(name, 'argument %s must be an integer' % key)
            if expected == 'boolean' and not isinstance(value, bool):
                return tool, ToolResult.failure(name, 'argument %s must be a boolean' % key)
        return tool, None

    def _execute_one(self, call_id, call, animated=True):
        self.session.cancel_flag.check()
        tool, invalid = self._validate_call(call)
        if invalid is not None:
            tool_line(CANCELLED if 'unknown' in (invalid.get('error') or '') else FAILED,
                      call.get('function', {}).get('name', '(unknown)'),
                      (invalid.get('error') or '')[:80])
            invalid['tool_call_id'] = call_id
            return invalid
        name = tool.name
        arguments = json.loads(call.get('function', {}).get('arguments') or '{}')
        target = tool.target_summary(arguments)
        if tool.permission == PERMISSION_CONFIRM and not self.confirm(name, target):
            tool_line(CANCELLED, name, 'denied by user')
            result = ToolResult.failure(name, 'the user denied this action')
        else:
            badge = tool_badge(tool.permission)
            target_text = target if tool.permission == PERMISSION_READ else ''
            tool_line(RUNNING, name, target_text, badge=badge)
            spinner = Spinner() if animated else None
            if spinner is not None:
                spinner.start('%s %s%s' % (badge, name,
                                           (' ' + target_text) if target_text else ''))
            started = time.monotonic()
            old_text = None
            if name == 'file.write':
                old_text = self._capture_previous_file(arguments.get('path'))
            try:
                result = tool.executor(arguments)
            except AIProviderError:
                raise
            except KeyboardInterrupt:
                result = ToolResult.failure(name, 'cancelled')
            except PermissionError as error:
                # Scope/credential refusals carry safe, user-facing reasons.
                result = ToolResult.failure(name, str(error) or 'permission denied')
            except ValueError as error:
                result = ToolResult.failure(name, str(error) or 'invalid path or input')
            except Exception as error:
                result = ToolResult.failure(name, 'tool failed: %s' % error.__class__.__name__)
            finally:
                if spinner is not None:
                    spinner.stop()
            symbol = OK if result.get('success') else FAILED
            detail = 'exit code: %s' % result.get('exit_code') if not result.get('success') else target
            tool_line(symbol, name, detail if not result.get('success') else '', badge=badge)
            if name == 'file.write' and result.get('success'):
                self._print_write_diff(old_text, arguments.get('content'))
            if name == 'terminal.run' and result.get('success'):
                self.session.recent_terminal.append((result.get('stdout') or '')[-2000:])
                self.session.recent_terminal = self.session.recent_terminal[-5:]
            safe_log(self.log_path, self.session.id, 'tool', tool=name,
                     ms=int((time.monotonic() - started) * 1000),
                     ok=1 if result.get('success') else 0)
        result['tool_call_id'] = call_id
        self.session.tool_history.append({'tool': name, 'ok': bool(result.get('success'))})
        self.session.tool_calls += 1
        return result

    def _capture_previous_file(self, path):
        '''Best-effort snapshot of a file before file.write overwrites it,
        taken through the read tool so scope guards apply. None = no diff.'''
        if not isinstance(path, str) or not path:
            return None
        reader = self.registry.get('file.read')
        if reader is None:
            return None
        try:
            snapshot = reader.executor({'path': path})
        except Exception:
            return None
        if not snapshot.get('success'):
            return None
        data = snapshot.get('stdout') or ''
        if not data or len(data) > DIFF_MAX_BYTES or '[output truncated' in data:
            return None
        return data

    def _print_write_diff(self, old_text, new_text):
        '''Git-style colored diff preview after a successful file.write:
        added lines render green, removed lines red.'''
        if not isinstance(new_text, str):
            return
        if len(new_text) > DIFF_MAX_BYTES:
            self.emit_note('(diff skipped: the written file is very large)')
            return
        added, removed, rows = diff_lines(old_text, new_text)
        if not added and not removed:
            return
        for sign, text in rows:
            color = COLOR_GREEN if sign == '+' else COLOR_RED
            print('        %s' % colorize('%s %s' % (sign, text), color), flush=True)
        summary = '+%d -%d' % (added, removed)
        if old_text is None:
            summary += ' (new file)'
        print('        %s' % colorize(summary, COLOR_DIM), flush=True)

    def execute_calls(self, calls):
        '''Parallel only when every call in the batch is independent READ-level
        work; anything else runs sequentially so results can never race (section 7).'''
        batch = [(call.get('id') or 'call_%d' % index, call)
                 for index, call in enumerate(calls)]
        permissions = []
        for _, call in batch:
            tool = self.registry.get(call.get('function', {}).get('name', ''))
            permissions.append(tool.permission if tool else PERMISSION_READ)
        parallel = (
            len(batch) > 1
            and all(permission == PERMISSION_READ for permission in permissions)
            and len(batch) <= self.config['max_parallel_tools']
        )
        results = {}
        if parallel:
            self.emit_note('Running %d independent checks in parallel...' % len(batch))
            with ThreadPoolExecutor(max_workers=self.config['max_parallel_tools']) as pool:
                futures = {pool.submit(self._execute_one, call_id, call, False): call_id
                           for call_id, call in batch}
                self.active_futures = list(futures)
                for future, call_id in futures.items():
                    try:
                        results[call_id] = future.result()
                    except AIProviderError as error:
                        if error.code == 'cancelled':
                            for pending in futures:
                                pending.cancel()
                            results[call_id] = ToolResult.failure(
                                'parallel', 'cancelled by user')
                        else:
                            raise
                self.active_futures = []
        else:
            for call_id, call in batch:
                results[call_id] = self._execute_one(call_id, call)
        return results

    # ---------------------------------------------------------- agent turn

    def agent_turn(self, user_input):
        self.session.cancel_flag.reset()
        self.session.current_task = user_input[:120]
        self.context.add({'role': 'user', 'content': user_input})
        deadline = self.session.started + self.config['max_runtime_sec']
        try:
            for _ in range(self.config['max_steps']):
                if self.session.elapsed() > (deadline - self.session.started):
                    self.emit_note('Agent runtime limit reached. The current task was stopped safely.')
                    return False
                self.session.steps += 1
                self.context.compact()
                thinking = None
                if not self.config['stream']:
                    thinking = Spinner()
                    thinking.start('[think] %s' % self.config['model'])
                try:
                    message = self.client.complete(
                        self.context.snapshot(),
                        tools=self.registry.schemas(),
                        stream=self.config['stream'],
                        on_delta=self._on_delta,
                    )
                finally:
                    if thinking is not None:
                        thinking.stop()
                self._close_stream_line()
                if message.get('content') and not self.config['stream']:
                    # Stream mode printed the text live; non-stream gateways
                    # need the final answer rendered here.
                    self.emit_text(message['content'])
                if message.get('content'):
                    self.context.add({'role': 'assistant', 'content': message['content']})
                calls = message.get('tool_calls') or []
                if not calls:
                    if not message.get('content'):
                        self.emit_note('(empty response from the model)')
                    return True
                self.context.add({
                    'role': 'assistant',
                    'content': message.get('content'),
                    'tool_calls': [
                        {'id': call.get('id'), 'type': 'function',
                         'function': call.get('function')}
                        for call in calls
                    ],
                })
                if self.session.tool_calls + len(calls) > self.config['max_tool_calls']:
                    self.emit_note('Agent tool-call limit reached. The current task was stopped safely.')
                    return False
                results = self.execute_calls(calls)
                for call in calls:
                    call_id = call.get('id') or 'call_0'
                    result = results.get(call_id) or ToolResult.failure('tool', 'no result')
                    self.context.add_tool_result(
                        call.get('function', {}).get('name', 'tool'), result)
                if self.session.cancel_flag.cancelled:
                    self.emit_note('Stopped. Back to the prompt.')
                    return False
            self.emit_note('Agent step limit reached. The current task was stopped safely.')
            return False
        except AIProviderError as error:
            self._close_stream_line()
            if error.code == 'cancelled':
                self.emit_note('Request cancelled. Back to the prompt.')
                return False
            if error.code == 'rate_limit':
                self.emit_note('The AI provider is rate limiting requests. Try again shortly.')
            elif error.code == 'auth':
                self.emit_note('The AI provider rejected the credentials. Check the Noxs AI configuration key.')
            else:
                self.emit_note('The AI provider is temporarily unavailable. (%s)' % error.code)
                self.emit_note('The gateway %s could not be reached or is failing right now. '
                               'Check your connection and retry — or configure another '
                               'OpenAI-compatible gateway with NOXS_AI_BASE_URL and NOXS_AI_MODEL.'
                               % self._provider_host())
            return False
        finally:
            self.session.cancel_flag.reset()
            self.session.current_task = None
            if not self.config.get('full_session', True):
                self.context.forget()

    # ------------------------------------------------------ slash commands

    def slash_command(self, line):
        command = line.split()[0].lower()
        if command in ('/exit', '/quit'):
            return False
        if command == '/help':
            print('''Noxs AI Agent commands:
  /help        this help
  /tools       list available tools
  /model       show the configured model
  /provider    AI providers: list, add [name], use <name>, remove <name>, show [name]
  /status      session status
  /context     conversation size
  /permissions show tool permission levels
  /stop        stop the active agent operation
  /clear       clear the conversation
  /reset       new session (keeps the terminal)
  /exit        leave the AI Agent (same as Ctrl+D)''', flush=True)
        elif command == '/tools':
            print('Available tools:', flush=True)
            for name in self.registry.available_names():
                tool = self.registry.get(name)
                print('- %s (%s)' % (name, tool.permission), flush=True)
        elif command == '/model':
            print('Model: %s' % self.config['model'], flush=True)
            print('Provider: %s' % self.provider_label(), flush=True)
            print('Gateway: %s' % self.config.get('base_url', ''), flush=True)
            print('Full session chat: %s' % (
                'on' if self.config.get('full_session', True) else 'off'), flush=True)
        elif command == '/provider':
            self._provider_command(line)
        elif command == '/status':
            print('Session: %s' % self.session.id[:12], flush=True)
            print('Runtime: %d s' % int(self.session.elapsed()), flush=True)
            print('Tool calls: %d' % self.session.tool_calls, flush=True)
            print('Working directory: %s' % self.session.cwd, flush=True)
        elif command == '/context':
            print('Messages in context: %d' % len(self.context.messages), flush=True)
        elif command == '/permissions':
            print('Permission levels:', flush=True)
            for name in self.registry.available_names():
                tool = self.registry.get(name)
                print('- %s: %s' % (name, tool.permission), flush=True)
        elif command == '/stop':
            self.session.cancel_flag.cancel()
            print('Stopping the active operation...', flush=True)
        elif command == '/clear':
            self.context.messages = []
            print('Conversation cleared.', flush=True)
        elif command == '/reset':
            self.context.messages = []
            self.session.tool_calls = 0
            self.session.steps = 0
            self.session.tool_history = []
            print('Session reset.', flush=True)
        else:
            print('Unknown command: %s (try /help)' % command, flush=True)
        return True

    # ------------------------------------------------------- /provider

    @staticmethod
    def _ask(prompt, default=None):
        shown = '%s [%s]: ' % (prompt, default) if default else '%s: ' % prompt
        try:
            answer = input(shown).strip()
        except (EOFError, KeyboardInterrupt):
            print(flush=True)
            raise
        return answer or (default or '')

    @staticmethod
    def _ask_yes_no(prompt, default=False):
        suffix = '[Y/n]' if default else '[y/N]'
        try:
            answer = input('%s %s ' % (prompt, suffix)).strip().lower()
        except (EOFError, KeyboardInterrupt):
            print(flush=True)
            raise
        if not answer:
            return default
        return answer in ('y', 'yes')

    @staticmethod
    def _valid_base_url(url):
        if not url or ' ' in url:
            return False
        try:
            parsed = urlparse(url)
        except ValueError:
            return False
        return parsed.scheme in ('http', 'https') and bool(parsed.netloc)

    def _provider_command(self, line):
        parts = line.split()
        sub = parts[1].lower() if len(parts) > 1 else 'list'
        argument = parts[2] if len(parts) > 2 else None
        try:
            if sub in ('list', 'ls'):
                self._provider_list()
            elif sub == 'add':
                self._provider_add(argument)
            elif sub == 'use':
                self._provider_use(argument)
            elif sub in ('remove', 'rm'):
                self._provider_remove(argument)
            elif sub == 'show':
                self._provider_show(argument)
            else:
                print('Unknown /provider action: %s (try list, add, use, remove, show)'
                      % sub, flush=True)
        except (EOFError, KeyboardInterrupt):
            print('Cancelled. Back to the prompt.', flush=True)

    def _provider_list(self):
        store = load_provider_store()
        active = self.config.get('provider', 'kilo')
        names = sorted(set(PROVIDER_PRESETS) | set(store.get('providers') or {}))
        print('AI providers (* = active):', flush=True)
        for name in names:
            definition = resolve_provider_definition(name, store) or {}
            marker = '*' if name == active else ' '
            key_env = definition.get('api_key_env') or ''
            if definition.get('key_required'):
                state = 'set' if os.environ.get(key_env) else 'not set'
                key_info = '%s (%s)' % (key_env, state) if key_env else 'key required'
            else:
                key_info = 'keyless'
            print('%s %-16s %-16s %-22s %s' % (
                marker, name, provider_display_label(name, definition)[:16],
                (definition.get('model') or '?')[:22], key_info), flush=True)
        print('Full session chat: %s' % (
            'on' if self.config.get('full_session', True) else 'off'), flush=True)
        print('Manage: /provider add [name] | use <name> | remove <name> | show [name]',
              flush=True)

    def _provider_show(self, name):
        store = load_provider_store()
        name = name or self.config.get('provider', 'kilo')
        definition = resolve_provider_definition(name, store)
        if definition is None:
            print('Unknown provider: %s (see /provider list)' % name, flush=True)
            return
        key_env = definition.get('api_key_env') or ''
        print('Provider: %s (%s)' % (name, provider_display_label(name, definition)),
              flush=True)
        print('Source: %s' % definition.get('source', 'preset'), flush=True)
        print('Gateway: %s' % definition.get('base_url', '?'), flush=True)
        print('Model: %s' % definition.get('model', '?'), flush=True)
        if definition.get('key_required'):
            state = 'set' if os.environ.get(key_env) else 'not set'
            print('API key: read from %s (%s; the value is never shown or stored)'
                  % (key_env, state), flush=True)
        else:
            print('API key: not required (works keyless)', flush=True)
        print('Active: %s' % ('yes' if name == self.config.get('provider', 'kilo')
                              else 'no'), flush=True)

    def _provider_use(self, name):
        if not name:
            print('Usage: /provider use <name> (see /provider list)', flush=True)
            return
        store = load_provider_store()
        definition = resolve_provider_definition(name, store)
        if definition is None:
            print('Unknown provider: %s (see /provider list)' % name, flush=True)
            return
        store['active'] = name
        save_provider_store(store)
        self.apply_config(load_config(store=store))
        print('Active provider: %s (%s, model: %s)' % (
            name, self.provider_label(), self.config['model']), flush=True)
        key_env = definition.get('api_key_env') or ''
        if definition.get('key_required') and not os.environ.get(key_env):
            print('Note: %s is not set — export it before chatting.' % key_env, flush=True)

    def _provider_remove(self, name):
        if not name:
            print('Usage: /provider remove <name>', flush=True)
            return
        store = load_provider_store()
        if name not in (store.get('providers') or {}):
            print('%s is not a custom provider (built-in presets cannot be removed).'
                  % name, flush=True)
            return
        if name == store.get('active'):
            print('%s is active — switch first with /provider use <other-name>.'
                  % name, flush=True)
            return
        if self._ask_yes_no('Remove provider %s?' % name, default=False):
            del store['providers'][name]
            save_provider_store(store)
            print('Removed provider: %s' % name, flush=True)
        else:
            print('Cancelled. Nothing was removed.', flush=True)

    def _provider_add(self, name_arg=None):
        preset = None
        name = name_arg
        definition = {}
        if name and name.lower() in PROVIDER_PRESETS:
            preset = name.lower()
            definition = dict(PROVIDER_PRESETS[preset])
            name = preset
        try:
            if not preset:
                suggested = slugify_provider_name(name) or 'custom'
                name = slugify_provider_name(self._ask('Provider name', suggested)) or 'custom'
                if name in PROVIDER_PRESETS:
                    print('%s is a built-in preset — pick another name, or run '
                          '/provider use %s.' % (name, name), flush=True)
                    return
            base_url = self._ask('Gateway base URL (OpenAI-compatible /v1)',
                                 definition.get('base_url', ''))
            if not self._valid_base_url(base_url):
                print('That does not look like an http(s) URL. Cancelled.', flush=True)
                return
            model = self._ask('Model', definition.get('model', ''))
            if not model or ' ' in model:
                print('A model id is required (no spaces). Cancelled.', flush=True)
                return
            api_key_env = self._ask('API key environment variable',
                                    definition.get('api_key_env', 'NOXS_AI_API_KEY'))
            if not ENV_NAME_PATTERN.match(api_key_env):
                print('That is not a valid environment variable name. Cancelled.',
                      flush=True)
                return
        except (EOFError, KeyboardInterrupt):
            print('Cancelled. Nothing was saved.', flush=True)
            return
        key_present = bool(os.environ.get(api_key_env))
        print('About to add provider:', flush=True)
        print('  name:     %s' % name, flush=True)
        print('  gateway:  %s' % base_url, flush=True)
        print('  model:    %s' % model, flush=True)
        print('  api key:  %s (%s)' % (api_key_env,
                                     'set' if key_present else 'not set'), flush=True)
        if not self._ask_yes_no('Save this provider?', default=False):
            print('Cancelled. Nothing was saved.', flush=True)
            return
        store = load_provider_store()
        if name in (store.get('providers') or {}):
            print('Overwriting existing custom provider: %s' % name, flush=True)
        store.setdefault('providers', {})[name] = {
            'base_url': base_url,
            'model': model,
            'api_key_env': api_key_env,
            'preset': preset,
        }
        full_session = self._ask_yes_no(
            'Allow full session chat (the agent remembers the whole conversation)?',
            default=True)
        store['full_session'] = full_session
        store['active'] = name
        try:
            save_provider_store(store)
        except OSError as error:
            print('Could not save the provider store: %s' % error.__class__.__name__,
                  flush=True)
            return
        self.apply_config(load_config(store=store))
        print('Saved. Active provider: %s (%s, model: %s)' % (
            name, self.provider_label(), self.config['model']), flush=True)
        if full_session:
            print('Full session chat: on — the conversation is remembered across turns.',
                  flush=True)
        else:
            print('Full session chat: off — each message starts fresh.', flush=True)
        if not key_present and preset != 'kilo':
            print('Note: export %s=<your key> before chatting.' % api_key_env, flush=True)

    # ---------------------------------------------------------------- REPL

    def run_interactive(self):
        print('Noxs AI Agent — model: %s (provider: %s)' % (
            self.config['model'], self.provider_label()), flush=True)
        print('Type /help for commands. /provider switches gateways. '
              'Ctrl+D or /exit leaves the agent.', flush=True)
        while True:
            try:
                line = input('nx@ai> ')
            except EOFError:
                print(flush=True)
                break
            except KeyboardInterrupt:
                print('^C', flush=True)
                continue
            text = line.strip()
            if not text:
                continue
            if text.lower() in EXIT_WORDS:
                break
            if text.startswith('/'):
                try:
                    if not self.slash_command(text):
                        break
                except KeyboardInterrupt:
                    print(flush=True)
                continue
            try:
                self.busy = True
                self.agent_turn(text)
            finally:
                self.busy = False
                self._close_stream_line()
        self.shutdown()

    def run_one_shot(self, task):
        try:
            self.busy = True
            self.agent_turn(task)
        finally:
            self.busy = False
            self._close_stream_line()
            self.shutdown()

    def shutdown(self):
        '''Release everything the session owns (spec section 20).'''
        self.session.cancel_flag.cancel()
        for process in list(ACTIVE_PROCESSES):
            tools_mod.kill_process_group(process)
        for pid in list(self.session.owned_pids):
            try:
                os.killpg(pid, signal.SIGTERM)
            except (ProcessLookupError, PermissionError, OSError):
                pass
        safe_log(self.log_path, self.session.id, 'session_end',
                 seconds=int(self.session.elapsed()),
                 tool_calls=self.session.tool_calls)


# -------------------------------------------------------------------- main

def parse_args(argv):
    parser = argparse.ArgumentParser(prog='nx ai', add_help=False,
                                     description='Noxs AI Agent Terminal')
    parser.add_argument('task', nargs='*', help='one-shot task (omit for interactive mode)')
    parser.add_argument('--model')
    parser.add_argument('--no-stream', action='store_true')
    parser.add_argument('--yes', action='store_true',
                        help='auto-approve CONFIRM tools (one-shot only)')
    parser.add_argument('--config')
    parser.add_argument('-h', '--help', action='store_true')
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(sys.argv[1:] if argv is None else argv)
    if args.help:
        print(__doc__)
        return 0
    overrides = {'model': args.model}
    if args.no_stream:
        overrides['stream'] = False
    config = load_config(path=args.config, overrides=overrides)
    if not config.get('enabled', True):
        print('_> The Noxs AI Agent is disabled in the configuration.', flush=True)
        return 1
    session = AgentSession(cwd=os.getcwd())
    runtime = AgentRuntime(config, session, one_shot=bool(args.task),
                           auto_confirm=bool(args.yes and args.task))
    if args.task:
        ok = False
        try:
            runtime.run_one_shot(' '.join(args.task))
            ok = True
        except KeyboardInterrupt:
            print(flush=True)
        return 0 if ok else 1
    signal.signal(signal.SIGINT, handle_sigint)
    runtime.run_interactive()
    return 0


def handle_sigint(signum, frame):
    '''Ctrl+C: cancel the current request/tool, return to nx@ai> (spec section 1).
    KeyboardInterrupt inside input() behaves like the default handler; this
    hook covers agent turns by cancelling the cooperative flag and killing
    agent-owned subprocesses.'''
    raise KeyboardInterrupt


if __name__ == '__main__':
    sys.exit(main())
