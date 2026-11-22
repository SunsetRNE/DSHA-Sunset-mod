package com.deepseekharness.app.transfer

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.deepseekharness.app.backup.BackupControl
import com.deepseekharness.app.backup.DocumentStreams
import com.deepseekharness.app.util.FileIntegrity
import java.io.File
import java.io.IOException

/** 固定缓冲发布：登记未公开目标、复制、重开摘要校验，最后提交可见性。 */
internal class TransferPublisher private constructor() {
    @JvmRecord
    internal data class Result(
        val uri: Uri?,
        val name: String?,
        val mime: String?,
        val location: String?,
    ) {
        final override fun toString(): String =
            "Result[uri=$uri, name=$name, mime=$mime, location=$location]"
    }

    internal interface Journal {
        @Throws(Exception::class) fun pending(uri: Uri, kind: String)

        /** 目标已提交后在引擎 gate 内调用。 */
        @Throws(Exception::class) fun complete(result: Result)
    }

    companion object {
        @JvmStatic
        @JvmName("publish")
        @Throws(Exception::class)
        internal fun publish(
            context: Context,
            source: File,
            artifact: TransferArtifact,
            destination: String?,
            taskId: String,
            target: Uri?,
            control: BackupControl,
            gate: Any,
            journal: Journal,
        ) {
            if (destination == "share") {
                share(context, source, artifact, taskId, control, gate, journal)
                return
            }
            var mime = artifact.mime
            val gallery = destination == "gallery"
            if (gallery) {
                val bounds = BitmapFactory.Options()
                bounds.inJustDecodeBounds = true
                TransferFiles.read(source).use { input ->
                    BitmapFactory.decodeStream(input, null, bounds)
                }
                if (
                    bounds.outWidth <= 0 ||
                        bounds.outHeight <= 0 ||
                        bounds.outMimeType == null ||
                        !bounds.outMimeType.startsWith("image/")
                )
                    throw IOException("FILE_NOT_REAL_IMAGE")
                mime = bounds.outMimeType
            }
            val pending: Uri
            val media = destination != "saveas"
            if (media) {
                val values = ContentValues()
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, artifact.name)
                values.put(MediaStore.MediaColumns.MIME_TYPE, mime)
                values.put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    (if (gallery) Environment.DIRECTORY_PICTURES
                    else Environment.DIRECTORY_DOWNLOADS) + "/DSHA/",
                )
                values.put(MediaStore.MediaColumns.IS_PENDING, 1)
                pending =
                    context.contentResolver.insert(
                        if (gallery) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        else MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        values,
                    ) ?: throw IOException("FILE_DESTINATION_CREATE_FAILED")
            } else {
                if (target == null || target.scheme != "content")
                    throw IOException("FILE_SAVE_TARGET_REQUIRED")
                pending = target
            }
            // 开流前记录精确目标行；中断不能触发 Downloads 范围清理。
            try {
                journal.pending(pending, if (media) "media" else "saf")
            } catch (failure: Exception) {
                if (media) {
                    try {
                        context.contentResolver.delete(pending, null, null)
                    } catch (ignored: Exception) {}
                }
                throw failure
            }
            TransferFiles.read(source).use { input ->
                DocumentStreams.output(context.contentResolver, pending, control).use { output ->
                    require(
                        artifact,
                        FileIntegrity.copy(input, output, artifact.bytes, control::check),
                    )
                }
            }
            DocumentStreams.input(context.contentResolver, pending, control).use { input ->
                require(artifact, FileIntegrity.copy(input, null, artifact.bytes, control::check))
            }
            var actualName: String? = artifact.name
            DocumentStreams.query(
                    context.contentResolver,
                    pending,
                    arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                    control,
                )
                .use { cursor ->
                    if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0))
                        actualName = cursor.getString(0)
                }
            synchronized(gate) {
                control.check()
                if (media) {
                    val ready = ContentValues()
                    ready.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    if (context.contentResolver.update(pending, ready, null, null) != 1)
                        throw IOException("FILE_PUBLICATION_FAILED")
                }
                journal.complete(
                    Result(
                        pending,
                        actualName,
                        mime,
                        if (media) (if (gallery) "Pictures/DSHA" else "Download/DSHA") else "所选文档",
                    )
                )
            }
        }

        @JvmStatic
        @Throws(Exception::class)
        private fun share(
            context: Context,
            source: File,
            artifact: TransferArtifact,
            taskId: String,
            control: BackupControl,
            gate: Any,
            journal: Journal,
        ) {
            val root = TransferFiles.directory(context.filesDir.canonicalFile, "transfers")
            val dir = TransferFiles.directory(TransferFiles.directory(root, "share"), taskId)
            val staged =
                TransferFiles.FS.child(TransferFiles.directory(root, taskId), "share-pending")
            val target = File(dir, artifact.name)
            if (TransferFiles.FS.stat(target).type == "FILE") {
                TransferFiles.read(target).use { input ->
                    require(
                        artifact,
                        FileIntegrity.copy(input, null, artifact.bytes, control::check),
                    )
                }
                synchronized(gate) {
                    control.check()
                    journal.complete(
                        Result(
                            FileProvider.getUriForFile(
                                context,
                                context.packageName + ".updates",
                                target,
                            ),
                            target.name,
                            artifact.mime,
                            "应用分享缓存",
                        )
                    )
                }
                return
            }
            TransferFiles.read(source).use { input ->
                TransferFiles.write(staged, 0).use { output ->
                    require(
                        artifact,
                        FileIntegrity.copy(input, output, artifact.bytes, control::check),
                    )
                }
            }
            TransferFiles.read(staged).use { input ->
                require(artifact, FileIntegrity.copy(input, null, artifact.bytes, control::check))
            }
            synchronized(gate) {
                control.check()
                TransferFiles.FS.move(staged, target)
                TransferFiles.FS.syncDirectory(dir)
                val uri =
                    FileProvider.getUriForFile(context, context.packageName + ".updates", target)
                journal.complete(Result(uri, target.name, artifact.mime, "应用分享缓存"))
            }
        }

        @JvmStatic
        @JvmName("require")
        @Throws(IOException::class)
        internal fun require(artifact: TransferArtifact, result: FileIntegrity.Result) {
            if (result.size != artifact.bytes || result.sha256 != artifact.sha256)
                throw IOException("FILE_SHA256_MISMATCH")
        }
    }
}
