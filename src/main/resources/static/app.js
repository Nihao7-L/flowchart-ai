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
const { createApp, ref, computed, onMounted } = Vue;

createApp({
    setup() {
        // ===== 响应式数据 =====
        const textInput = ref('');           // 输入框内容
        const loading = ref(false);          // 生成中？
        const svgContent = ref('');          // 后端返回的 SVG 字符串
        const plantUmlSource = ref('');      // 后端返回的 PlantUML 源码（下载用）
        const errorMessage = ref('');        // 错误信息
        const theme = ref('light');          // 当前主题
        const chartType = ref('flowchart');  // 图表类型（flowchart / mindmap / architecture）
        const selectedModel = ref('mimo');   // 模型选择（mimo 主 / kimi 备）
        const format = ref('svg');            // 输出格式：svg（后端 PlantUML 渲染）/ mermaid（前端 mermaid.js 渲染，任务37）
        const mermaidSource = ref('');        // 后端返回的 Mermaid 源码（任务37，下载/复制用）

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
        }

        /** 切换图表类型：切换时清空旧结果，避免两种图混在一起 */
        function setChartType(type) {
            chartType.value = type;
            svgContent.value = '';
            plantUmlSource.value = '';
            mermaidSource.value = '';
            errorMessage.value = '';
            resetZoom();
        }

        /** 切换输出格式：svg / mermaid（任务37） */
        function setFormat(f) {
            format.value = f;
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

        /** 核心：发起生成请求（任务37：改用 SSE 流式接口，实时显示进度与 Token） */
        async function generate() {
            const text = textInput.value.trim();

            // 边界检查
            if (!text) { errorMessage.value = '请输入流程描述'; return; }
            if (text.length > 2000) { errorMessage.value = '输入内容过长，请精简到 2000 字以内'; return; }

            // 进入 loading：清空旧内容 + 进度面板
            loading.value = true;
            errorMessage.value = '';
            svgContent.value = '';
            plantUmlSource.value = '';
            mermaidSource.value = '';
            progressLogs.value = [];
            tokenInfo.value = { promptTokens: 0, completionTokens: 0, totalTokens: 0 };
            tokenReady.value = false;
            currentProvider.value = '';
            resetZoom();

            // 110 秒超时（后端 SSE 超时 120s，前端略短留余量）
            // model：把下拉框选中的模型（mimo/kimi）传给后端，实现"选哪个就优先打哪个"
            // format：输出格式（svg / mermaid，任务37）
            const params = new URLSearchParams({ text, type: chartType.value, model: selectedModel.value, format: format.value });
            const controller = new AbortController();
            const timeoutId = setTimeout(() => controller.abort(), 110000);

            try {
                const res = await fetch('/api/generate/stream?' + params.toString(), {
                    signal: controller.signal
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
                console.error('请求失败:', err);
                if (err.name === 'AbortError') {
                    errorMessage.value = '请求超时（110秒），请检查网络或后端是否正常';
                } else {
                    errorMessage.value = '生成失败: ' + err.message;
                }
            } finally {
                clearTimeout(timeoutId);
                loading.value = false;
            }
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
                } else {
                    progressLogs.value.push({ type, message: data.message || '' });
                }
            } else if (eventName === 'done') {
                if (data.format === 'mermaid' && data.mermaid) {
                    // 任务37：Mermaid 走前端渲染，不依赖后端 SVG（await 保证 svg 设好后才结束 loading）
                    mermaidSource.value = data.mermaid;
                    await renderMermaid(data.mermaid);
                } else {
                    svgContent.value = data.svg;
                    plantUmlSource.value = data.plantUml;
                }
            } else if (eventName === 'error') {
                errorMessage.value = data.message || '生成失败';
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
                    svgContent.value = svg;
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

        // ===== 生命周期：页面加载时恢复主题 =====
        onMounted(() => {
            const saved = localStorage.getItem('flowai-theme') || 'light';
            theme.value = saved;
            document.body.setAttribute('data-theme', saved);
        });

        // 把所有需要模板访问的数据和方法 return 出去
        return {
            textInput, loading, svgContent, plantUmlSource, errorMessage,
            theme, chartType, selectedModel, format, mermaidSource,
            progressLogs, tokenInfo, tokenReady, currentProvider,
            toastMsg, toastVisible,
            scale, translateX, translateY, isDragging, svgTransform,
            hasResult, buttonText, placeholderText,
            fillExample, showToast, toggleTheme, onModelChange, setChartType, setFormat,
            generate, downloadFile,
            zoomIn, zoomOut, resetZoom,
            onWheel, onMouseDown, onMouseMove, onMouseUp
        };
    }
}).mount('#app');
