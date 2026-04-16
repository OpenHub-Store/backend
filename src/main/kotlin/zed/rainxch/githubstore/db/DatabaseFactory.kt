package zed.rainxch.githubstore.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database

object DatabaseFactory {

    fun init() {
        val dataSource = hikari()
        runMigrations(dataSource)
        Database.connect(dataSource)
    }

    private fun hikari(): HikariDataSource {
        val config = HikariConfig().apply {
            driverClassName = "org.postgresql.Driver"
            jdbcUrl = env("DATABASE_URL", "jdbc:postgresql://localhost:5432/githubstore")
            username = env("DATABASE_USER", "githubstore")
            password = env("DATABASE_PASSWORD", "githubstore")
            maximumPoolSize = env("DATABASE_POOL_SIZE", "9").toInt() // (2 * 4 vCPU) + 1
            isAutoCommit = false
            connectionTimeout = 5_000
            validationTimeout = 3_000
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        }
        return HikariDataSource(config)
    }

    private fun runMigrations(dataSource: HikariDataSource) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    private fun env(name: String, default: String): String =
        System.getenv(name) ?: default
}
