package com.monta.ocpp.emulator.database

import com.monta.ocpp.emulator.common.util.appRoot
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.io.File

class DatabaseInitiator(
    databaseName: String,
    directory: File = appRoot,
) : AutoCloseable {

    private val logger = KotlinLogging.logger {}

    private val databasePath = File(directory, databaseName).absolutePath
    private val dataSource: HikariDataSource
    val database: Database

    init {
        try {
            logger.debug("starting database $databasePath")

            dataSource = HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = "jdbc:sqlite:$databasePath"
                    username = "sa"
                    password = ""
                    poolName = "SQLiteConnectionPool"
                    maximumPoolSize = 1
                },
            )
            database = Database.connect(dataSource)
        } catch (exception: Exception) {
            logger.error("database error", exception)
            throw exception
        }
    }

    override fun close() {
        try {
            TransactionManager.closeAndUnregister(database)
        } finally {
            dataSource.close()
        }
    }
}
