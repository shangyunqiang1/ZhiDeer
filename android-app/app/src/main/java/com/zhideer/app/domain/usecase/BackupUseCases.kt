package com.zhideer.app.domain.usecase

import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteStatement
import com.zhideer.app.data.db.ZhiDeerDatabase
import com.zhideer.app.domain.model.AiChatSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.CoroutineContext

/** 备份失败：携带面向用户的中文提示，绝不包含口令或密钥。 */
class BackupException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** 导出结果摘要：文件位置、版本信息与各表计数清单（供界面展示与恢复核对）。 */
data class BackupSummary(
    val file: File,
    val fileSizeBytes: Long,
    val formatVersion: Int,
    val dbVersion: Int,
    val exportedAt: Long,
    val tableCounts: Map<String, Int>,
    val aiSessionCount: Int,
)

/** 校验结果：清单计数与实际解密行数的核对（[integrityOk] = 两者一致）。 */
data class BackupVerification(
    val formatVersion: Int,
    val dbVersion: Int,
    val exportedAt: Long,
    val tableCounts: Map<String, Int>,
    val actualTableCounts: Map<String, Int>,
    val aiSessionCount: Int,
    val actualAiSessionCount: Int,
    val integrityOk: Boolean,
)

/** 备份明文负载：全部用户数据表 JSON 行 + 会话历史 + 版本与计数清单。 */
@Serializable
internal data class BackupPayload(
    val formatVersion: Int,
    val dbVersion: Int,
    val exportedAt: Long,
    val tableCounts: Map<String, Int>,
    val tables: Map<String, JsonArray>,
    val aiSessionCount: Int,
    val aiSessions: List<AiChatSession> = emptyList(),
)

/**
 * 手动加密备份导出（T31/#32）统一入口。
 *
 * 备份范围（issue #32：日历、待办、便签、会话及相应本地关系）：
 * - Room v12 全部用户数据表（[BACKUP_TABLES]，含回收站与已停用记录——备份是全量数据）；
 * - AI 会话历史（DataStore JSON，经 [sessionHistory] 读取）；
 * - 绝不包含：API Key 与本机配置（EncryptedSharedPreferences/SharedPreferences，
 *   备份路径从不读取任何配置存储——安全边界，测试断言保障）；
 * - 操作提案草稿（AiProposalStore）为临时草稿，不属于用户事项数据，本任务不纳入。
 *
 * 加密方案（仅 javax.crypto 标准库，无第三方依赖）：
 * - 密钥派生：PBKDF2WithHmacSHA256，随机 16 字节盐，600,000 次迭代，256 位密钥；
 * - 对称加密：AES-256-GCM（NoPadding，128 位认证标签），随机 12 字节 IV；
 * - 文件格式（版本化，v1，多字节均为大端）：magic "ZDBK"(4) + 格式版本 int32(4) +
 *   盐(16) + 迭代次数 int32(4) + IV(12) + 密文（JSON 负载，含 GCM 标签）。
 *   迭代次数随文件存储，未来调参不破坏旧文件解密。
 *
 * 完整性约定（issue #32：失败时不报告成功的可用备份）：
 * - GCM 认证标签保证密文完整性；负载内含格式版本、数据库版本与各表计数清单；
 * - 导出写盘后立即回读自校验（解密 + 解析 + 计数核对），失败即删除目标文件并返回失败；
 * - 写盘先写 .tmp 临时文件再原子改名，中断不留半成品目标文件；
 * - 所有失败以中文 [BackupException] 返回，不把英文裸异常抛给界面，口令不出现在任何提示中。
 *
 * 导出位置：由调用方给定 [targetFile]（MVP 默认应用外部文件目录 + 展示绝对路径；
 * SAF「另存为」由后续任务按 issue 正文补齐）。恢复还原（导入）属 T32/#33。
 */
class BackupUseCases(
    private val database: ZhiDeerDatabase,
    private val sessionHistory: suspend () -> List<AiChatSession>,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val secureRandom = SecureRandom()

    /** 手动触发整库导出：加密写入 [targetFile]，成功返回摘要，失败不留损坏文件。 */
    suspend fun exportAll(password: String, targetFile: File): Result<BackupSummary> =
        withContext(ioDispatcher) {
            try {
                Result.success(exportInternal(password, targetFile))
            } catch (e: BackupException) {
                Result.failure(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(BackupException("备份导出失败：${e.message ?: e.javaClass.simpleName}", e))
            }
        }

    /** 校验既有备份文件：解密并核对版本与计数清单；错误口令返回中文失败提示。 */
    suspend fun verify(file: File, password: String): Result<BackupVerification> =
        withContext(ioDispatcher) {
            try {
                Result.success(verifyInternal(file, password.toCharArray()))
            } catch (e: BackupException) {
                Result.failure(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(BackupException("备份校验失败：${e.message ?: e.javaClass.simpleName}", e))
            }
        }

    /** 解密备份文件，返回明文负载字节（仅供测试与 #33 恢复任务使用）。 */
    internal suspend fun decryptPayloadBytes(file: File, password: String): Result<ByteArray> =
        withContext(ioDispatcher) {
            try {
                Result.success(decryptToPayloadBytes(file, password.toCharArray()))
            } catch (e: BackupException) {
                Result.failure(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(BackupException("备份读取失败：${e.message ?: e.javaClass.simpleName}", e))
            }
        }

    /** 解密并解析备份负载（仅供测试与 #33 恢复任务使用）。 */
    internal suspend fun readPayload(file: File, password: String): Result<BackupPayload> =
        decryptPayloadBytes(file, password).mapCatching { bytes -> parsePayload(bytes) }

    // ---------- 导出 ----------

    private suspend fun exportInternal(password: String, targetFile: File): BackupSummary {
        if (password.isEmpty()) {
            throw BackupException("备份口令不能为空：请先设置独立备份口令")
        }
        val exportedAt = now()
        // 1. 读取全部用户数据表（原样行数据，含回收站记录）
        val tables = readAllTables()
        // 2. 读取会话历史：失败即整体失败，绝不生成缺会话的“成功”备份
        val sessions = try {
            sessionHistory()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BackupException("读取会话历史失败，备份未生成：${e.message ?: e.javaClass.simpleName}", e)
        }
        // 3. 组装负载并加密
        val payload = BackupPayload(
            formatVersion = FORMAT_VERSION,
            dbVersion = readDbVersion(),
            exportedAt = exportedAt,
            tableCounts = tables.mapValues { it.value.size },
            tables = tables,
            aiSessionCount = sessions.size,
            aiSessions = sessions,
        )
        val plainBytes = json.encodeToString(BackupPayload.serializer(), payload)
            .toByteArray(StandardCharsets.UTF_8)
        val encrypted = encrypt(plainBytes, password.toCharArray())
        // 4. 临时文件 + 原子改名写入，中断不留半成品
        writeAtomically(targetFile, encrypted)
        // 5. 回读自校验：失败即删除，绝不报告成功的可用备份
        val check = verifyInternal(targetFile, password.toCharArray())
        if (!check.integrityOk) {
            targetFile.delete()
            throw BackupException("备份文件自校验失败（计数清单不一致），已删除不完整文件：请重试或检查磁盘空间")
        }
        return BackupSummary(
            file = targetFile,
            fileSizeBytes = targetFile.length(),
            formatVersion = check.formatVersion,
            dbVersion = check.dbVersion,
            exportedAt = check.exportedAt,
            tableCounts = check.tableCounts,
            aiSessionCount = check.aiSessionCount,
        )
    }

    /** 顺序读取全部用户数据表：走 Room 只读连接原样导出行数据，不改任何 DAO。 */
    private suspend fun readAllTables(): Map<String, JsonArray> =
        database.useConnection(isReadOnly = true) { connection ->
            val tables = LinkedHashMap<String, JsonArray>()
            BACKUP_TABLES.forEach { table ->
                tables[table] = connection.usePrepared("SELECT * FROM $table") { statement ->
                    readRows(statement)
                }
            }
            tables
        }

    /** 读取 Room 数据库版本（PRAGMA user_version，即 ZhiDeerDatabase.version）。 */
    private suspend fun readDbVersion(): Int =
        database.useConnection(isReadOnly = true) { connection ->
            connection.usePrepared("PRAGMA user_version") { statement ->
                if (statement.step()) statement.getLong(0).toInt() else 0
            }
        }

    private fun readRows(statement: SQLiteStatement): JsonArray {
        val columns = statement.getColumnNames()
        return buildJsonArray {
            while (statement.step()) {
                add(
                    buildJsonObject {
                        columns.forEachIndexed { index, name ->
                            put(
                                name,
                                when (statement.getColumnType(index)) {
                                    SQLITE_DATA_INTEGER -> JsonPrimitive(statement.getLong(index))
                                    SQLITE_DATA_FLOAT -> JsonPrimitive(statement.getDouble(index))
                                    SQLITE_DATA_TEXT -> JsonPrimitive(statement.getText(index))
                                    // v1 schema 无 BLOB 列；如出现按 Base64 字符串保留原值
                                    SQLITE_DATA_BLOB ->
                                        JsonPrimitive(Base64.getEncoder().encodeToString(statement.getBlob(index)))
                                    else -> JsonNull
                                },
                            )
                        }
                    },
                )
            }
        }
    }

    // ---------- 校验与解密 ----------

    private fun verifyInternal(file: File, password: CharArray): BackupVerification {
        val bytes = decryptToPayloadBytes(file, password)
        val payload = parsePayload(bytes)
        if (payload.formatVersion != FORMAT_VERSION) {
            throw BackupException("备份内容版本不受支持（负载版本 ${payload.formatVersion}，当前支持 $FORMAT_VERSION）")
        }
        val actualCounts = payload.tables.mapValues { it.value.size }
        val integrityOk = actualCounts == payload.tableCounts &&
            payload.aiSessions.size == payload.aiSessionCount
        return BackupVerification(
            formatVersion = payload.formatVersion,
            dbVersion = payload.dbVersion,
            exportedAt = payload.exportedAt,
            tableCounts = payload.tableCounts,
            actualTableCounts = actualCounts,
            aiSessionCount = payload.aiSessionCount,
            actualAiSessionCount = payload.aiSessions.size,
            integrityOk = integrityOk,
        )
    }

    private fun decryptToPayloadBytes(file: File, password: CharArray): ByteArray {
        val raw = try {
            file.readBytes()
        } catch (e: IOException) {
            throw BackupException("读取备份文件失败：${e.message ?: e.javaClass.simpleName}", e)
        }
        if (raw.size <= headerLength() || !raw.copyOfRange(0, FILE_HEADER_MAGIC.size).contentEquals(FILE_HEADER_MAGIC)) {
            throw BackupException("不是知时鹿备份文件或文件已损坏")
        }
        var offset = FILE_HEADER_MAGIC.size
        val formatVersion = readIntBE(raw, offset)
        offset += FORMAT_VERSION_BYTES
        if (formatVersion != FORMAT_VERSION) {
            throw BackupException("备份文件版本不受支持（版本 $formatVersion，当前支持 $FORMAT_VERSION）")
        }
        val salt = raw.copyOfRange(offset, offset + SALT_LENGTH_BYTES)
        offset += SALT_LENGTH_BYTES
        val iterations = readIntBE(raw, offset)
        offset += ITERATIONS_BYTES
        if (iterations < MIN_PBKDF2_ITERATIONS || iterations > MAX_PBKDF2_ITERATIONS) {
            throw BackupException("备份文件已损坏（密钥派生参数非法）")
        }
        val iv = raw.copyOfRange(offset, offset + IV_LENGTH_BYTES)
        offset += IV_LENGTH_BYTES
        val cipherText = raw.copyOfRange(offset, raw.size)
        val key = deriveKey(password, salt, iterations)
        return try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            cipher.doFinal(cipherText)
        } catch (e: AEADBadTagException) {
            // GCM 认证标签不匹配：口令不对或密文被篡改
            throw BackupException("口令错误或备份文件已损坏，无法解密备份内容", e)
        } catch (e: GeneralSecurityException) {
            throw BackupException("备份文件解密失败：${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private fun parsePayload(bytes: ByteArray): BackupPayload = try {
        json.decodeFromString(BackupPayload.serializer(), bytes.toString(StandardCharsets.UTF_8))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw BackupException("备份内容已损坏，无法解析：${e.message ?: e.javaClass.simpleName}", e)
    }

    // ---------- 加密与文件写入 ----------

    private fun encrypt(plainBytes: ByteArray, password: CharArray): ByteArray {
        val salt = ByteArray(SALT_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val key = deriveKey(password, salt, PBKDF2_ITERATIONS)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val cipherText = cipher.doFinal(plainBytes)
        return FILE_HEADER_MAGIC +
            intToBytes(FORMAT_VERSION) +
            salt +
            intToBytes(PBKDF2_ITERATIONS) +
            iv +
            cipherText
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance(KDF_ALGORITHM)
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun writeAtomically(targetFile: File, bytes: ByteArray) {
        val parent = targetFile.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw BackupException("无法创建备份目录：${parent.absolutePath}")
        }
        val tempFile = File(parent, "${targetFile.name}.tmp")
        try {
            FileOutputStream(tempFile).use { output -> output.write(bytes) }
            Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    private fun headerLength(): Int =
        FILE_HEADER_MAGIC.size + FORMAT_VERSION_BYTES + SALT_LENGTH_BYTES + ITERATIONS_BYTES + IV_LENGTH_BYTES

    private fun readIntBE(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    companion object {
        /** 备份文件格式版本（写入文件头，版本化）。 */
        const val FORMAT_VERSION = 1

        /** 备份覆盖的全部用户数据表（Room v12，ZhiDeerDatabase 实体顺序）。 */
        val BACKUP_TABLES = listOf(
            "tasks",
            "sub_tasks",
            "calendar_entries",
            "notes",
            "note_list_items",
            "reminders",
            "task_occurrences",
            "calendar_reminders",
            "occurrence_subtask_records",
            "memories",
        )

        /** 文件头 magic（ASCII），用于快速识别知时鹿备份文件。 */
        private val FILE_HEADER_MAGIC = "ZDBK".toByteArray(Charsets.US_ASCII)

        private const val FORMAT_VERSION_BYTES = 4
        private const val SALT_LENGTH_BYTES = 16
        private const val ITERATIONS_BYTES = 4
        private const val IV_LENGTH_BYTES = 12

        private const val KEY_BITS = 256
        private const val GCM_TAG_LENGTH_BITS = 128

        /** PBKDF2-HMAC-SHA256 迭代次数（导出为一次性操作，取当前 OWASP 推荐量级）。 */
        private const val PBKDF2_ITERATIONS = 600_000

        /** 解密时对文件内迭代次数的合法性区间（防恶意超参拖慢）。 */
        private const val MIN_PBKDF2_ITERATIONS = 10_000
        private const val MAX_PBKDF2_ITERATIONS = 10_000_000

        private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
