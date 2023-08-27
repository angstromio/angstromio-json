package angstromio.json

import angstromio.util.io.TempFolder
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files

interface TestWithFileResources : TempFolder {

    fun writeStringToFile(
        directory: String,
        name: String,
        ext: String,
        data: String
    ): File {
        val file = Files.createTempFile(FileSystems.getDefault().getPath(directory), name, ext).toFile()
        return try {
            val out: OutputStream = FileOutputStream(file, false)
            out.write(data.toByteArray(StandardCharsets.UTF_8))
            file
        } finally {
            file.deleteOnExit()
        }
    }

    fun addSlash(directory: String): String =
        if (directory.endsWith("/")) {
            directory
        } else {
            "$directory/"
        }
}