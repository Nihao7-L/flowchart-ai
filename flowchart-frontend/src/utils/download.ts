/**
 * 下载当前可视化的视图：
 * - format=svg (.svg-view > svg)：下载为 image/svg+xml
 * - format=mermaid (.mermaid-view > svg)：同上
 * - 无 svg / ChartFlow 渲染：兜底下载 GraphJson（方便后续二次渲染 / 编辑）
 */
export async function downloadCurrentView(opts: {
  format?: 'svg' | 'mermaid' | ''
  graphJson?: unknown
}): Promise<{ ok: boolean; kind: string; msg?: string }> {
  const targetSvg = document.querySelector('.svg-view svg') || document.querySelector('.mermaid-view svg')
  if (targetSvg) {
    const clone = targetSvg.cloneNode(true) as SVGElement
    clone.removeAttribute('width')
    clone.removeAttribute('height')
    clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg')
    const svgText = new XMLSerializer().serializeToString(clone)
    const blob = new Blob([svgText], { type: 'image/svg+xml;charset=utf-8' })
    triggerBlobDownload(blob, `flowai-${opts.format || 'chart'}-${Date.now()}.svg`)
    return { ok: true, kind: 'svg' }
  }
  if (opts.graphJson) {
    const blob = new Blob([JSON.stringify(opts.graphJson, null, 2)], { type: 'application/json' })
    triggerBlobDownload(blob, `flowai-graphjson-${Date.now()}.json`)
    return { ok: true, kind: 'json' }
  }
  return { ok: false, kind: '', msg: '当前没有可下载的视图（请先生成图表）' }
}

function triggerBlobDownload(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  setTimeout(() => URL.revokeObjectURL(url), 0)
}
