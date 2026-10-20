import { reactive, ref } from 'vue'
import {
  completeDocumentUpload,
  fetchUploadStatus,
  initDocumentUpload,
  uploadDocumentChunk,
} from '@/api/document'

/**
 * 分片上传的组合式函数。
 *
 * <h3>为什么需要</h3>
 * 后端已完整实现分片上传（`/upload/init`、`/upload/chunks`、
 * `/upload/{uploadId}`、`/upload/{uploadId}/complete`），
 * 但前端此前只有单次上传通道，受 10MB 限制。大文件（培养方案、扫描件可达上百 MB）
 * 只能靠分片：先按哈希判断能否秒传，再逐片上传、断点续传、最后合并。
 *
 * <h3>流程</h3>
 * 1. `hashing` —— 计算整文件指纹（用于秒传判断）
 * 2. `init` —— 调用初始化接口；若哈希命中已有文件则直接秒传返回
 * 3. `uploading` —— 逐片计算 SHA-256 并上传，跳过服务端已收到的分片（断点续传）
 * 4. `completing` —— 校验分片齐全后合并为最终文档
 *
 * <h3>两个关键约束（来自后端实现）</h3>
 * - `chunkHash` 会被服务端**校验**：必须是该分片字节的 SHA-256 小写十六进制，
 *   否则报「分片校验失败，数据可能已损坏」
 * - 单分片不得超过 10MB、整文件不得超过 256MB
 */

/** 每个分片的大小：4MB（后端上限 10MB，取 4MB 兼顾重试成本与请求数） */
const CHUNK_SIZE = 4 * 1024 * 1024

/** 计算整文件指纹时的切片大小，用于限制内存占用 */
const HASH_SLICE_SIZE = 8 * 1024 * 1024

/** 上传阶段 */
export type ChunkedUploadStage = 'hashing' | 'init' | 'uploading' | 'completing' | null

/** 分片进度 */
export interface ChunkProgress {
  /** 已上传（含服务端已有的）分片数 */
  uploaded: number
  /** 分片总数 */
  total: number
}

/** 计算字节内容的 SHA-256 小写十六进制值 */
async function sha256Hex(data: BufferSource): Promise<string> {
  const digest = await globalThis.crypto.subtle.digest('SHA-256', data)
  const bytes = new Uint8Array(digest)
  let hex = ''
  for (const byte of bytes) {
    hex += byte.toString(16).padStart(2, '0')
  }
  return hex
}

/**
 * 计算整文件指纹。
 *
 * <p>不直接把整个文件读进内存 —— 256MB 的文件会一次性占用同等大小的
 * ArrayBuffer，低配机器上容易失败。这里按 {@link HASH_SLICE_SIZE} 切片，
 * 先分别求每片的 SHA-256，再对「各片摘要 + 文件大小」求一次 SHA-256。</p>
 *
 * <p>这样做的性质：文件任意位置变化都会改变对应片的摘要，从而改变最终指纹，
 * 秒传判断依然可靠；同时内存占用只与切片大小有关。</p>
 *
 * @param file 待计算的文件
 * @param onSlice 每完成一片的回调（done 已完成片数，total 总片数）
 * @returns 64 字符小写十六进制指纹
 */
async function computeFileHash(
  file: File,
  onSlice?: (done: number, total: number) => void,
): Promise<string> {
  const sliceCount = Math.max(1, Math.ceil(file.size / HASH_SLICE_SIZE))
  const digests: Uint8Array[] = []

  for (let index = 0; index < sliceCount; index += 1) {
    const start = index * HASH_SLICE_SIZE
    const end = Math.min(file.size, start + HASH_SLICE_SIZE)
    const buffer = await file.slice(start, end).arrayBuffer()
    digests.push(new Uint8Array(await globalThis.crypto.subtle.digest('SHA-256', buffer)))
    onSlice?.(index + 1, sliceCount)
  }

  const digestLength = digests.reduce((sum, item) => sum + item.length, 0)
  const sizeTag = new TextEncoder().encode(String(file.size))
  const combined = new Uint8Array(digestLength + sizeTag.length)
  let offset = 0
  for (const digest of digests) {
    combined.set(digest, offset)
    offset += digest.length
  }
  combined.set(sizeTag, offset)

  return sha256Hex(combined)
}

/**
 * 创建分片上传状态与操作。
 *
 * <p>返回值可直接交给 `reactive()` 使用，组件里访问 `chunked.isUploading`
 * 等属性会得到解包后的值。</p>
 */
export function useChunkedUpload() {
  /** 是否正在分片上传（含指纹计算阶段） */
  const isUploading = ref(false)
  /** 总体进度百分比（0-100） */
  const progress = ref(0)
  /** 错误信息，为空串表示无错误 */
  const error = ref('')
  /** 当前阶段 */
  const stage = ref<ChunkedUploadStage>(null)
  /** 指纹计算阶段的描述文字 */
  const hashingProgress = ref('')
  /** 分片进度 */
  const chunkProgress = reactive<ChunkProgress>({ uploaded: 0, total: 0 })

  /** 中止控制器，用于取消上传 */
  let controller: AbortController | null = null

  /** 重置全部状态（不中止进行中的请求，配合 cancel 使用） */
  function reset() {
    isUploading.value = false
    progress.value = 0
    error.value = ''
    stage.value = null
    hashingProgress.value = ''
    chunkProgress.uploaded = 0
    chunkProgress.total = 0
    controller = null
  }

  /** 取消当前上传 */
  function cancel() {
    controller?.abort()
    controller = null
    isUploading.value = false
    stage.value = null
    hashingProgress.value = ''
    error.value = ''
  }

  /**
   * 执行分片上传。
   *
   * @param file    待上传文件
   * @param groupId 目标群组 ID
   * @returns 最终文档 ID（秒传时返回命中文件的既有文档 ID）
   */
  async function uploadFile(file: File, groupId: number): Promise<number> {
    if (isUploading.value) {
      throw new Error('已有上传任务正在进行')
    }

    reset()
    controller = new AbortController()
    const signal = controller.signal
    isUploading.value = true

    try {
      // 1. 计算整文件指纹，用于秒传判断
      stage.value = 'hashing'
      hashingProgress.value = '正在计算文件指纹...'
      const fileHash = await computeFileHash(file, (done, total) => {
        hashingProgress.value = `正在计算文件指纹 ${done}/${total}`
        progress.value = Math.round((done / total) * 10)
      })
      hashingProgress.value = ''

      // 2. 初始化上传会话（可能命中秒传）
      stage.value = 'init'
      const chunkCount = Math.ceil(file.size / CHUNK_SIZE)
      const initResult = await initDocumentUpload(
        {
          groupId,
          fileName: file.name,
          fileSize: file.size,
          contentType: file.type || 'application/octet-stream',
          fileHash,
          chunkSize: CHUNK_SIZE,
          chunkCount,
        },
        signal,
      )

      if (initResult.instantUpload) {
        if (initResult.documentId === null) {
          throw new Error('秒传失败：服务端未返回文档 ID')
        }
        progress.value = 100
        stage.value = null
        return initResult.documentId
      }

      const uploadId = initResult.uploadId
      if (!uploadId) {
        throw new Error('初始化上传失败：未返回上传会话 ID')
      }

      // 3. 逐片上传；服务端已有的分片直接跳过（断点续传）
      const totalChunks = initResult.chunkCount ?? chunkCount
      const effectiveChunkSize = initResult.chunkSize ?? CHUNK_SIZE
      const alreadyUploaded = new Set(initResult.uploadedChunks ?? [])

      stage.value = 'uploading'
      progress.value = 10
      let completedChunks = alreadyUploaded.size
      chunkProgress.total = totalChunks
      chunkProgress.uploaded = completedChunks

      for (let index = 0; index < totalChunks; index += 1) {
        if (signal.aborted) {
          throw new Error('上传已取消')
        }
        if (alreadyUploaded.has(index)) {
          continue
        }
        const start = index * effectiveChunkSize
        const end = Math.min(file.size, start + effectiveChunkSize)
        const chunk = file.slice(start, end)
        const chunkHash = await sha256Hex(await chunk.arrayBuffer())

        await uploadDocumentChunk({ uploadId, chunkIndex: index, chunkHash, chunk }, undefined, signal)

        completedChunks += 1
        chunkProgress.uploaded = completedChunks
        // 分片阶段占总体 10% ~ 95%
        progress.value = Math.min(95, 10 + Math.round((completedChunks / totalChunks) * 85))
      }

      // 上传完成后与后端对齐一次真实状态，避免本地计数偏差
      const status = await fetchUploadStatus(uploadId)
      chunkProgress.uploaded = status.uploadedChunkCount
      if (status.chunkCount !== null) {
        chunkProgress.total = status.chunkCount
      }

      // 4. 合并分片
      stage.value = 'completing'
      progress.value = 95
      const documentId = await completeDocumentUpload(uploadId, signal)

      progress.value = 100
      stage.value = null
      return documentId
    } catch (err) {
      const aborted = signal.aborted
      error.value = aborted ? '上传已取消' : toMessage(err, '分片上传失败')
      stage.value = null
      hashingProgress.value = ''
      throw err
    } finally {
      isUploading.value = false
      controller = null
    }
  }

  return {
    isUploading,
    progress,
    error,
    stage,
    hashingProgress,
    chunkProgress,
    uploadFile,
    reset,
    cancel,
  }
}

/** 把未知异常转成可读文案 */
function toMessage(err: unknown, fallback: string): string {
  if (err instanceof Error && err.message) {
    return err.message
  }
  if (typeof err === 'string' && err) {
    return err
  }
  return fallback
}
