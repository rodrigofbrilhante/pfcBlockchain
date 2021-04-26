package net.corda.testing.node

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

object DatabaseSnapshot {
    private const val previousCordaVersion: String = "4.5.1"
    private const val databaseName: String = "persistence.mv.db"

    private fun getDatabaseSnapshotStream(): InputStream {
        val resourceUri = this::class.java.getResource("/databasesnapshots/${previousCordaVersion}/$databaseName")
        return resourceUri.openStream()
    }

    fun databaseFilename(baseDirectory: Path) = baseDirectory.resolve(databaseName)

    fun copyDatabaseSnapshot(baseDirectory: Path) {
        getDatabaseSnapshotStream().use { stream ->
            Files.createDirectories(baseDirectory)
            Files.copy(stream, databaseFilename(baseDirectory))
        }
    }
}