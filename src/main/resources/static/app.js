/**
 * FlowAI 前端逻辑（Vue 3 CDN 版）
 *
 * Vue 3 核心概念速查：
 * - ref()          把普通变量变成"响应式"——改了值页面自动更新
 * - computed()     计算属性——依赖的 ref 变了它自动重算
 * - v-model        双向绑定——输入框 ↔ 变量同步
 * - v-if / v-html  条件渲染 / 把字符串当 HTML 插入
 * - @event         事件绑定（@click, @wheel, @mousedown...）
 * - :style         动态绑定样式
 */
const { createApp, ref, computed, onMounted, nextTick, watch } = Vue;

createApp({
    setup() {
        // ===== 响应式数据 =====
        const textInput = ref('');           // 输入框内容
        const loading = ref(false);          // 生成中？
        // 任务40：中断生成——AbortController 提到 setup 作用域，stopGenerate 才能 abort 它
        let abortController = null;          // 当前请求的控制器（generate 里赋值，stopGenerate 里 abort）
        let userAborted = false;             // 区分"用户主动停止" vs "超时/网络错误"
        const svgContent = ref('');          // 后端返回的 SVG 字符串
        const lastRawSvg = ref('');          // 任务39+：未去白底的原始 SVG，主题切换时重新处理
        const plantUmlSource = ref('');      // 后端返回的 PlantUML 源码（下载用）
        const errorMessage = ref('');        // 错误信息
        const theme = ref('light');          // 当前主题
        const chartType = ref('flowchart');  // 图表类型（flowchart / mindmap / architecture）
        const selectedModel = ref('mimo');   // 模型选择（mimo 主 / kimi 备）
        const format = ref('svg');            // 输出格式：svg（后端 PlantUML 渲染）/ mermaid（前端 mermaid.js 渲染，任务37）
        const mermaidSource = ref('');        // 后端返回的 Mermaid 源码（任务37，下载/复制用）
        const aiSummary = ref('');            // LLM 原始回复截短摘要（≤200 字 + 句子完整）；校验失败时折叠区展示"AI 实际说了什么"
        const aiSummaryExpanded = ref(false); // 折叠区展开状态，默认收起（避免一开始吓到用户）
        // 任务38+B/C：RAG 检索增强的开关与展示
        const useRag = ref(false);            // 是否启用知识库（前端勾选框，任务38-C）
        const useTool = ref(false);           // 任务39：是否让 LLM 自己决定调工具（read_file / web_search / code_execute / RAG）
        const ragSources = ref([]);           // 命中来源文件列表（任务38-B 面板展示用）
        const ragScores = ref([]);            // 各来源相似度（0~1）
        const ragContext = ref('');           // 拼给 prompt 的全文（调试用）
        const ragHits = ref([]);              // 命中详情：source + score + content（任务38-B 优化）
        const ragDegraded = ref(false);       // RAG 检索失败降级标志（任务38 容错）

        // 进度面板状态（任务37：SSE 实时进度）
        const progressLogs = ref([]);        // 进度日志列表
        const tokenInfo = ref({ promptTokens: 0, completionTokens: 0, totalTokens: 0 });
        const tokenReady = ref(false);        // 是否已拿到 token 数据（区分"未开始"与"缓存命中=0"）
        const currentProvider = ref('');      // 后端实际调用的模型名（mimo/kimi），由 SSE 事件实时更新

        // Toast 状态
        const toastMsg = ref('');
        const toastVisible = ref(false);
        let toastTimer = null;

        // SVG 缩放 / 拖拽状态
        const scale = ref(1);
        const translateX = ref(0);
        const translateY = ref(0);
        const isDragging = ref(false);
        let dragStart = { x: 0, y: 0, tx: 0, ty: 0 };

        // 任务39 UI 改版：思考折叠区 + 工具标签 + 搜索面板
        const thinkingSteps = ref([]);      // 离散任务步骤（构建 prompt / 调工具 / 命中结果等）
        const streamingThinking = ref('');  // 任务39+：模型推理实时流（逐字追加，delta:true）
        const toolCalls = ref([]);          // 工具调用标签 [{toolKey, callKey, input, label, icon, iconClass, status, resultCount}]
        const searchResults = ref({});      // 多轮搜索结果：{ [callKey]: items[] }
        const activeSearchKey = ref('');    // 当前展开的搜索 callKey
        const searchPanelOpen = ref(false); // 搜索面板是否展开
        const thinkingOpen = ref(false);    // 思考折叠区是否展开
        const thinkingRef = ref(null);      // 任务39：思考区外层容器锚点，用于自动滚到底部
        const thinkingBodyRef = ref(null);  // 任务39+：思考卡正文锚点（流式推理在这里增长）
        const inputRef = ref(null);         // 任务39：输入框锚点，用于多行自动增高

        // ===== 计算属性 =====
        // hasResult：有 SVG 内容就显示画布，否则显示空状态
        const hasResult = computed(() => svgContent.value !== '');

        // SVG 缩放 / 拖拽的 transform：scale=1 且未平移时返回 'none'
        // 关键：避免 Chrome 把元素丢进合成层（will-change/恒等 transform）导致文字发虚
        const svgTransform = computed(() => {
            if (scale.value === 1 && translateX.value === 0 && translateY.value === 0) {
                return 'none';
            }
            return 'translate(' + translateX.value + 'px, ' + translateY.value + 'px) scale(' + scale.value + ')';
        });

        // 图表类型的中文名（按钮、空状态文案都用）
        const typeNames = { flowchart: '流程图', mindmap: '思维导图', architecture: '架构图' };

        // 按钮文字：点击"思维导图"后，按钮自动变成"生成思维导图"
        const buttonText = computed(() => '生成' + (typeNames[chartType.value] || '流程图'));

        // 当前选中的搜索结果列表（支持多轮搜索：每个 callKey 一份结果）
        const currentSearchResults = computed(() => searchResults.value[activeSearchKey.value] || []);
        // 当前选中的搜索查询词（面板标题显示）
        const currentSearchInput = computed(() => {
            const tc = toolCalls.value.find(t => t.callKey === activeSearchKey.value);
            return tc && tc.input ? tc.input : '';
        });

        // 输入框占位符：按图表类型给不同的示例
        const placeholderText = computed(() => {
            if (chartType.value === 'mindmap') {
                return '例如：电商系统的组成，包括前端、后端、数据库、基础设施';
            }
            if (chartType.value === 'architecture') {
                return '例如：电商系统架构，Web前端通过网关访问订单服务和用户服务，订单服务依赖数据库和消息队列';
            }
            return '例如：用户输入账号密码，系统验证，验证通过则进入首页，验证失败则提示错误并重新输入';
        });

        // 示例文案（键 = mini-tag 的名字）
        const examples = {
            '登录': '用户输入账号密码 → 系统验证 → 验证通过则进入首页，验证失败则提示错误并重新输入',
            '审批': '员工提交请假申请 → 主管审批 → 审批通过则通知员工并记录，审批驳回则通知员工修改重新提交',
            '电商': '电商系统组成：前端包括Web商城、小程序、App，后端包括订单服务、用户服务、支付服务，基础设施包括数据库、缓存、消息队列',
            '系统架构': '电商系统架构：Web前端通过HTTP访问API网关，网关路由到订单服务和用户服务，订单服务依赖MySQL数据库和Redis缓存，支付服务对接第三方支付系统'
        };

        // ===== 方法 =====

        /** 填入示例 */
        function fillExample(type) {
            textInput.value = examples[type] || '';
            autosize();
        }

        /** 输入框多行自动增高（模仿 Kimi：文字多时撑高，超出上限才滚动）；至少保持两行 */
        function autosize() {
            const t = inputRef.value;
            if (!t) return;
            t.style.height = 'auto';
            const minH = 54;   // 两行文字的高度下限
            t.style.height = Math.min(Math.max(t.scrollHeight, minH), 240) + 'px';
        }

        /** 切换图表类型：只切换类型，不清屏幕（用户约定：点发送才清空上次内容，切类型保留当前画面） */
        function setChartType(type) {
            chartType.value = type;
        }

        /** 切换输出格式：svg / mermaid（任务37） */
        function setFormat(f) {
            format.value = f;
        }

        /** 切换搜索面板：点同一个标签收起，点别的标签切换过去（web_search 结果展开/收起） */
        function toggleSearch(tc) {
            if (tc.toolKey !== 'web_search' || !tc.resultCount) return; // 非搜索或无结果：不响应
            if (searchPanelOpen.value && activeSearchKey.value === tc.callKey) {
                searchPanelOpen.value = false;                    // 已展开同一个 → 收起
            } else {
                activeSearchKey.value = tc.callKey;               // 切到该标签并展开
                searchPanelOpen.value = true;
            }
        }

        /** 显示 Toast 轻提示 */
        function showToast(msg) {
            toastMsg.value = msg;
            toastVisible.value = true;
            clearTimeout(toastTimer);
            toastTimer = setTimeout(() => { toastVisible.value = false; }, 2000);
        }

        /** 切换明暗主题 */
        function toggleTheme() {
            theme.value = theme.value === 'dark' ? 'light' : 'dark';
            document.body.setAttribute('data-theme', theme.value);
            localStorage.setItem('flowai-theme', theme.value);
        }

        /** 模型切换：选中的模型会作为主模型优先调用（其余自动成为 fallback），真正生效了 */
        function onModelChange() {
            const label = selectedModel.value === 'mimo' ? 'mimo（主模型）' : 'kimi（备用入口，需配置 API key 才生效）';
            showToast('已选择 ' + label + '。将优先调用该模型；若它不可用会自动切换到另一个');
        }

        /** 工具调用唯一 key：同工具多次调用（如两次 web_search 不同关键词）用 input 区分 */
        function toolCallKey(provider, input) {
            const i = String(input || '').trim();
            return i ? `${provider}:${i}` : provider;
        }

        /** 进入生成前清空旧结果与进度面板（generate 调用） */
        function resetState() {
            errorMessage.value = '';
            svgContent.value = '';
            lastRawSvg.value = '';
            plantUmlSource.value = '';
            mermaidSource.value = '';
            ragSources.value = [];
            ragScores.value = [];
            ragContext.value = '';
            ragHits.value = [];
            ragDegraded.value = false;
            progressLogs.value = [];
            tokenInfo.value = { promptTokens: 0, completionTokens: 0, totalTokens: 0 };
            tokenReady.value = false;
            currentProvider.value = '';
            thinkingSteps.value = [];
            streamingThinking.value = '';
            toolCalls.value = [];
            searchResults.value = {};
            activeSearchKey.value = '';
            searchPanelOpen.value = false;
            thinkingOpen.value = false;
            aiSummary.value = '';            // 清掉"查看 AI 实际说了什么"折叠区（点发送才清，切类型不清）
            aiSummaryExpanded.value = false;
            resetZoom();
        }

        /** 核心：发起生成请求（任务37：改用 SSE 流式接口，实时显示进度与 Token） */
        async function generate() {
            const text = textInput.value.trim();

            // 边界检查
            if (!text) { errorMessage.value = '请输入流程描述'; return; }
            if (text.length > 2000) { errorMessage.value = '输入内容过长，请精简到 2000 字以内'; return; }

            // 进入 loading：清空旧内容 + 进度面板
            loading.value = true;
            resetState();

            // 250 秒超时（后端 SSE 超时 260s，前端略短留余量；调到 250s 避免慢模型推理被前端提前掐断）
            // model：把下拉框选中的模型（mimo/kimi）传给后端，实现"选哪个就优先打哪个"
            // format：输出格式（svg / mermaid，任务37）
            const params = new URLSearchParams({ text, type: chartType.value, model: selectedModel.value, format: format.value });
            if (useRag.value) params.set('useRag', 'true'); // 任务38-C：勾选了才开启知识库检索
            if (useTool.value) params.set('useTool', 'true'); // 任务39：勾选了才让 LLM 调工具
            abortController = new AbortController();
            userAborted = false;
            const timeoutId = setTimeout(() => abortController.abort(), 250000);

            try {
                const res = await fetch('/api/generate/stream?' + params.toString(), {
                    signal: abortController.signal
                });
                if (!res.ok) {
                    throw new Error('HTTP ' + res.status);
                }

                // 逐块读取 SSE 流
                const reader = res.body.getReader();
                const decoder = new TextDecoder();
                let buffer = '';
                while (true) {
                    const { done, value } = await reader.read();
                    if (done) break;
                    buffer += decoder.decode(value, { stream: true });
                    // SSE 以空行（\n\n）分隔每一帧
                    let idx;
                    while ((idx = buffer.indexOf('\n\n')) >= 0) {
                        const frame = buffer.slice(0, idx);
                        buffer = buffer.slice(idx + 2);
                        await handleSseFrame(frame);
                        if (!loading.value) break; // 收到 done/error 后停止解析
                    }
                }
            } catch (err) {
                if (err.name === 'AbortError') {
                    if (userAborted) {
                        // 用户主动点了停止：不弹"超时"错误，只给一句中性提示
                        errorMessage.value = '已停止生成';
                    } else {
                        errorMessage.value = '请求超时（250秒），请检查网络或后端是否正常';
                    }
                } else {
                    console.error('请求失败:', err);
                    errorMessage.value = '生成失败: ' + err.message;
                }
            } finally {
                clearTimeout(timeoutId);
                loading.value = false;
                abortController = null;
                userAborted = false;
            }
        }

        /** 任务40：中断当前生成。点击停止按钮时调用，abort 掉进行中的 fetch 流 */
        function stopGenerate() {
            if (!loading.value) return;
            userAborted = true;
            if (abortController) abortController.abort();
            // 立即恢复 UI：不等 catch/finally，让按钮秒回"发送"态
            loading.value = false;
            errorMessage.value = '已停止生成';
        }

        /** 解析一帧 SSE（event: xxx / data: {...}），分发到进度面板或最终结果 */
        async function handleSseFrame(frame) {
            let eventName = 'message';
            const dataLines = [];
            frame.split('\n').forEach(line => {
                if (line.startsWith('event:')) eventName = line.slice(6).trim();
                else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
            });
            if (dataLines.length === 0) return;
            let data;
            try { data = JSON.parse(dataLines.join('\n')); }
            catch { return; }

            if (eventName === 'progress') {
                // 后端实际调用的模型名（mimo/kimi）随事件实时更新，区分"我选的"与"真正在跑的"
                if (data.provider) currentProvider.value = data.provider;
                const type = data.type || 'info';
                if (type === 'token_usage') {
                    // 实时大数字：把 token 用量写进 tokenInfo
                    tokenInfo.value = {
                        promptTokens: data.promptTokens || 0,
                        completionTokens: data.completionTokens || 0,
                        totalTokens: data.totalTokens || 0
                    };
                    tokenReady.value = true;
                } else if (type === 'cache_hit') {
                    // 命中缓存：没有真实消耗，显示 0，并记一条日志
                    tokenInfo.value = {
                        promptTokens: data.promptTokens || 0,
                        completionTokens: data.completionTokens || 0,
                        totalTokens: data.totalTokens || 0
                    };
                    tokenReady.value = true;
                    progressLogs.value.push({ type, message: data.message || '命中缓存' });
                } else if (type === 'tool_call') {
                    // 任务39 UI 改版：工具调用以"中文标签 + 图标"展示，读起来更像"任务执行步骤"
                    // 任务39 补充：同工具多次调用（如两次 web_search）要带 input 区分，避免显示成重复标签
                    const toolKey = data.provider || data.tool || '';
                    const input = String(data.input || '').trim();
                    const callKey = toolCallKey(toolKey, input);
                    const metaMap = {
                        'read_file':         { label: '读取本地文件', icon: '📄', iconClass: 'read' },
                        'web_search':        { label: '搜索网页',     icon: '🔍', iconClass: 'search' },
                        'code_execute':      { label: '执行代码',     icon: '⚙️', iconClass: 'code' },
                        'retrieve_document': { label: '检索知识库',   icon: '📚', iconClass: 'rag' },
                    };
                    const meta = metaMap[toolKey] || { label: toolKey, icon: '🔧', iconClass: '' };
                    // 把查询词摘要拼到标签里，让用户一眼看出两次搜索的区别
                    const inputShort = input.length > 18 ? input.slice(0, 18) + '…' : input;
                    const label = inputShort ? `${meta.label} · ${inputShort}` : meta.label;
                    const existingIdx = toolCalls.value.findIndex(tc => tc.callKey === callKey);
                    if (existingIdx >= 0) {
                        // 同一调用被重复推送（如同一 input 后端重试）：只刷新状态，不新建
                        toolCalls.value[existingIdx].status = 'running';
                    } else {
                        toolCalls.value = [...toolCalls.value, {
                            toolKey, callKey, input, label, icon: meta.icon,
                            iconClass: meta.iconClass, status: 'running', resultCount: 0
                        }];
                    }
                    thinkingSteps.value = [...thinkingSteps.value, data.message || '执行任务：' + meta.label];
                    if (thinkingSteps.value.length === 1) thinkingOpen.value = true;
                    progressLogs.value.push({ type, message: data.message || '' });
                } else if (type === 'tool_result') {
                    // 任务39 UI 改版：web_search 存结构化结果，但【不自动弹出右侧面板】——
                    // 改成在思考流里放一个"🔍 搜索网页 · N 个结果"的可点击行，用户点击后才展开（模仿 Kimi）
                    const items = data.items || [];
                    const resultProvider = data.provider || '';
                    const resultInput = String(data.input || '').trim();
                    const resultKey = toolCallKey(resultProvider, resultInput);
                    if (resultProvider === 'web_search' && items.length > 0) {
                        // 按 callKey 保存每轮搜索结果，支持多次搜索各自展开
                        searchResults.value = { ...searchResults.value, [resultKey]: items };
                        const idx = toolCalls.value.findIndex(tc => tc.callKey === resultKey);
                        if (idx >= 0) {
                            toolCalls.value[idx].resultCount = items.length;
                            toolCalls.value[idx].status = 'done';
                        }
                    } else {
                        // 非 web_search 或空结果：把对应调用标记为 done
                        const idx = toolCalls.value.findIndex(tc => tc.callKey === resultKey);
                        if (idx >= 0) toolCalls.value[idx].status = 'done';
                    }
                    if (data.message) {
                        thinkingSteps.value = [...thinkingSteps.value, data.message];
                    }
                    progressLogs.value.push({ type, message: data.message || '' });
                } else if (type === 'thinking') {
                    // 任务39+：模型推理流。delta:true → 实时追加到"思考流"（逐字滚动）；
                    // 无 delta → 一次性完整推理块（非流式降级路径），直接覆盖设置。
                    if (data.delta) {
                        streamingThinking.value += (data.message || '');
                    } else {
                        streamingThinking.value = data.message || '';
                    }
                    // 推理出现即展开思考卡，让用户看到实时滚动
                    if (!thinkingOpen.value) thinkingOpen.value = true;
                } else {
                    // loading 期间：info 事件同时进入思考流（实时显示）+ 旧进度面板
                    if (loading.value && data.message) {
                        thinkingSteps.value = [...thinkingSteps.value, data.message];
                    }
                    progressLogs.value.push({ type, message: data.message || '' });
                }
            } else if (eventName === 'done') {
                // 任务39 UI 改版：将所有 running 的工具标签标记为 done
                // （不清空 toolCalls：保留"🔍 搜索网页 · N 个结果"入口，生成完仍可点开看搜索结果）
                toolCalls.value.forEach(tc => {
                    if (tc.status === 'running') tc.status = 'done';
                });
                // 任务39：生成完成后自动收起思考卡片（变成一行"思考已完成"，点击可展开）
                thinkingOpen.value = false;
                // 任务38-B：把后端回传的 RAG 命中写入展示变量，触发"知识库引用"面板
                if (data.ragSources) ragSources.value = data.ragSources;
                if (data.ragScores) ragScores.value = data.ragScores;
                if (data.ragContext) ragContext.value = data.ragContext;
                if (data.ragHits) ragHits.value = data.ragHits;
                if (data.ragDegraded) ragDegraded.value = data.ragDegraded;
                if (data.format === 'mermaid' && data.mermaid) {
                    // 任务37：Mermaid 走前端渲染，不依赖后端 SVG（await 保证 svg 设好后才结束 loading）
                    mermaidSource.value = data.mermaid;
                    await renderMermaid(data.mermaid);
                } else {
                    lastRawSvg.value = data.svg;
                    svgContent.value = stripSvgWhiteBg(data.svg);
                    plantUmlSource.value = data.plantUml;
                }
            } else if (eventName === 'error') {
                let msg = data.message || '生成失败';
                // FixA：SSE 校验失败时后端会带 issues 列表，拼出具体错在哪（field/reason/hint）
                if (data.issues && data.issues.length) {
                    msg += '\n' + data.issues.map(i => `• [${i.field}] ${i.reason}${i.hint ? '（建议：' + i.hint + '）' : ''}`).join('\n');
                }
                errorMessage.value = msg;
                // 透传后端 aiSummary（≤200 字 + 句子完整），折叠区展示"AI 实际说了什么"；空则清掉折叠区
                let summary = (data.aiSummary || '').trim();
                // 修：MiMo 在 json_object 模式遇到"你好"等非绘图输入时，content 会被逼成空 JSON 骨架
                // 如 {"type":"architecture","nodes":[],"edges":[]}——这种"只有格式没人话"的值毫无意义。
                // 此时改取模型真正说的话（reasoning_content，即"思考过程"流里真实输出的人话）。
                const looksLikeEmptyJson = /^\s*\{[^}]*\}\s*$/.test(summary) &&
                    (summary.includes('"nodes":[]') || summary.includes('"nodes": [') || summary.includes('"edges":[]') ||
                     !summary.match(/[一-龥a-zA-Z]{4,}/));
                if (looksLikeEmptyJson) {
                    // 无意义 JSON 骨架：优先补模型真正说的话；思考流也没有就干脆隐藏折叠区
                    summary = streamingThinking.value.trim() || '';
                }
                aiSummary.value = summary;
                aiSummaryExpanded.value = false;
            }
        }

        /** 下载（按需渲染） */
        async function downloadFile(dlFormat) {
            // 任务37：Mermaid 格式直接下载源码为 .mmd 文本（无需后端渲染）
            if (format.value === 'mermaid') {
                if (!mermaidSource.value) return;
                const blob = new Blob([mermaidSource.value], { type: 'text/plain;charset=utf-8' });
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = (typeNames[chartType.value] === '流程图' ? 'flowchart' : chartType.value) + '.mmd';
                document.body.appendChild(a);
                a.click();
                document.body.removeChild(a);
                URL.revokeObjectURL(url);
                return;
            }
            if (!plantUmlSource.value) return;
            try {
                const res = await fetch('/api/download', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ plantUml: plantUmlSource.value, format: dlFormat })
                });
                const blob = await res.blob();
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                // 文件名按图表类型区分：flowchart.svg / mindmap.png / architecture.png
                a.download = (typeNames[chartType.value] === '流程图' ? 'flowchart' : chartType.value) + '.' + dlFormat;
                document.body.appendChild(a);
                a.click();
                document.body.removeChild(a);
                URL.revokeObjectURL(url);
            } catch (err) {
                showToast('下载失败: ' + err.message);
            }
        }

        /** 任务37：用 CDN 的 mermaid.js 把 Mermaid 源码渲染成 SVG，结果存入 svgContent（复用现有显示/缩放） */
        async function renderMermaid(src) {
            if (window.mermaid && typeof window.mermaid.render === 'function') {
                try {
                    // startOnLoad:false 避免自动扫描整页；theme 跟随当前明暗主题
                    window.mermaid.initialize({
                        startOnLoad: false,
                        theme: theme.value === 'dark' ? 'dark' : 'default',
                        securityLevel: 'loose'
                    });
                    const id = 'mmd-' + Date.now();
                    const { svg } = await window.mermaid.render(id, src);
                    lastRawSvg.value = svg;
                    svgContent.value = stripSvgWhiteBg(svg);
                    return;
                } catch (e) {
                    console.error('Mermaid 渲染失败：', e);
                }
            }
            // 兜底：mermaid.js 没加载成功，直接显示源码，至少不白屏
            svgContent.value = '<pre class="mermaid-fallback">' + escapeHtml(src) + '</pre>';
        }

        /** 转义 HTML 特殊字符（兜底显示 Mermaid 源码时用） */
        function escapeHtml(s) {
            return String(s)
                .replace(/&/g, '&amp;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;');
        }

        /**
         * 深色主题下去掉 SVG 图自带的白色背景，让深色画布透出来。
         * 主要两类来源：
         *  - mermaid.js 注入的 <style>#id { background-color: white; }</style>
         *  - PlantUML 输出的整幅白色底矩形 / 白色填充
         */
        function stripSvgWhiteBg(svg) {
            const isDark = document.body.getAttribute('data-theme') === 'dark';
            if (!isDark) return svg;
            return svg
                // mermaid 注入的白色背景
                .replace(/background-color:\s*white\s*;?/gi, 'background-color: transparent;')
                .replace(/background-color:\s*#(?:ffffff|fff)\s*;?/gi, 'background-color: transparent;')
                // PlantUML：整幅白色底（inline style 里 fill:#FFFFFF / 属性 fill="#FFFFFF"）
                .replace(/style="([^"]*?)fill:#(?:ffffff|fff)[^"]*?"/gi, (m, pre) => m.replace(/fill:#(?:ffffff|fff)/gi, 'fill:transparent'))
                .replace(/style="([^"]*?)fill:\s*white[^"]*?"/gi, (m) => m.replace(/fill:\s*white/gi, 'fill:transparent'))
                .replace(/fill="(?:#ffffff|#fff|white)"/gi, 'fill="transparent"');
        }

        // ===== SVG 缩放 / 拖拽 =====

        /** 应用 transform 到 wrapper */
        function applyTransform() {
            // Vue 的 :style 绑定会自动响应 scale/translateX/translateY 的变化
            // 所以这里不需要手动操作 DOM，只要改 ref 值就行
        }

        function zoomIn() {
            scale.value = Math.min(scale.value * 1.2, 5);
        }
        function zoomOut() {
            scale.value = Math.max(scale.value / 1.2, 0.2);
        }
        function resetZoom() {
            scale.value = 1;
            translateX.value = 0;
            translateY.value = 0;
        }

        /** 滚轮缩放 */
        function onWheel(e) {
            const delta = e.deltaY > 0 ? -0.1 : 0.1;
            scale.value = Math.max(0.2, Math.min(5, scale.value + delta));
        }

        /** 拖拽开始 */
        function onMouseDown(e) {
            isDragging.value = true;
            dragStart.x = e.clientX;
            dragStart.y = e.clientY;
            dragStart.tx = translateX.value;
            dragStart.ty = translateY.value;
        }

        /** 拖拽移动 */
        function onMouseMove(e) {
            if (!isDragging.value) return;
            translateX.value = dragStart.tx + (e.clientX - dragStart.x);
            translateY.value = dragStart.ty + (e.clientY - dragStart.y);
        }

        /** 拖拽结束 */
        function onMouseUp() {
            isDragging.value = false;
        }

        // 任务39/39+：思考流与推理流每新增/追加，滚动到卡片底部（优先滚正文，退而滚外层）
        function scrollThinkingToBottom() {
            nextTick(() => {
                const el = thinkingBodyRef.value || thinkingRef.value;
                if (el) el.scrollTop = el.scrollHeight;
            });
        }
        watch(thinkingSteps, scrollThinkingToBottom);
        watch(streamingThinking, scrollThinkingToBottom);
        // 任务39：输入框多行自动增高（模仿 Kimi）
        watch(textInput, () => { autosize(); });
        // 任务39+：切换主题时重新处理已渲染 SVG 的白色背景（dark→去白底透出深色画布 / light→恢复白底）
        watch(theme, () => {
            if (lastRawSvg.value) svgContent.value = stripSvgWhiteBg(lastRawSvg.value);
        });

        // ===== 生命周期：页面加载时恢复主题 =====
        onMounted(() => {
            const saved = localStorage.getItem('flowai-theme') || 'light';
            theme.value = saved;
            document.body.setAttribute('data-theme', saved);
            autosize(); // 任务39：输入框初始也做一次自动增高，避免预填内容时出现滚动条
        });

        // 把所有需要模板访问的数据和方法 return 出去
        return {
            textInput, loading, svgContent, plantUmlSource, errorMessage,
            theme, chartType, selectedModel, format, mermaidSource,
            useRag, useTool, ragSources, ragScores, ragContext, ragHits, ragDegraded,
            thinkingSteps, streamingThinking, toolCalls, searchResults, activeSearchKey, currentSearchResults, currentSearchInput, searchPanelOpen, thinkingOpen, thinkingRef, thinkingBodyRef, inputRef,
            progressLogs, tokenInfo, tokenReady, currentProvider,
            toastMsg, toastVisible,
            aiSummary, aiSummaryExpanded,
            scale, translateX, translateY, isDragging, svgTransform,
            hasResult, buttonText, placeholderText,
            fillExample, showToast, toggleTheme, onModelChange, setChartType, setFormat, toggleSearch,
            generate, stopGenerate, downloadFile, autosize,
            zoomIn, zoomOut, resetZoom,
            onWheel, onMouseDown, onMouseMove, onMouseUp
        };
    }
}).mount('#app');
