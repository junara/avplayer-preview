package io.github.junara.avpreview
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon
class VideoFileType private constructor() : FileType {
    companion object { @JvmField val INSTANCE = VideoFileType() }
    override fun getName() = "AVPlayer Video"
    override fun getDescription() = "Local video (AVPlayer)"
    override fun getDefaultExtension() = "mp4"
    override fun getIcon(): Icon? = null
    override fun isBinary() = true
    override fun getCharset(file: VirtualFile, content: ByteArray): String? = null
}
