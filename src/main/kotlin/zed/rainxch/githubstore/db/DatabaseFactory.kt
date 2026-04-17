package zed.rainxch.githubstore.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

object DatabaseFactory {

    private val log = LoggerFactory.getLogger(DatabaseFactory::class.java)

    fun init() {
        val dataSource = hikari()
        Database.connect(dataSource)
        runMigrations()
        log.info("Database initialized successfully")
    }

    private fun hikari(): HikariDataSource {
        val config = HikariConfig().apply {
            driverClassName = "org.postgresql.Driver"
            jdbcUrl = env("DATABASE_URL", "jdbc:postgresql://localhost:5432/githubstore")
            username = env("DATABASE_USER", "githubstore")
            password = env("DATABASE_PASSWORD", "githubstore")
            maximumPoolSize = env("DATABASE_POOL_SIZE", "9").toInt()
            isAutoCommit = false
            connectionTimeout = 5_000
            validationTimeout = 3_000
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        }
        return HikariDataSource(config)
    }

    private fun runMigrations() {
        transaction {
            log.info("Running database migrations...")
            val migrationSql = this::class.java.classLoader
                .getResourceAsStream("db/migration/V1__initial_schema.sql")
                ?.bufferedReader()?.readText()
                ?: error("Migration file not found")

            // Check if schema already exists
            val tablesExist = exec("SELECT EXISTS (SELECT FROM information_schema.tables WHERE table_name = 'repos')") { rs ->
                rs.next() && rs.getBoolean(1)
            } ?: false

            if (!tablesExist) {
                log.info("Applying initial schema migration...")
                exec(migrationSql)
                log.info("Schema migration applied successfully")
            } else {
                log.info("Schema already exists, skipping initial migration")
            }

            // Apply incremental migrations
            val migrations = listOf(
                "V2__add_download_count.sql",
                "V3__search_miss_processing.sql",
            )
            for (migration in migrations) {
                val sql = this::class.java.classLoader
                    .getResourceAsStream("db/migration/$migration")
                    ?.bufferedReader()?.readText() ?: continue
                try {
                    exec(sql)
                } catch (_: Exception) {
                    // Column/table already exists — safe to ignore
                }
            }
        }
    }

    private fun env(name: String, default: String): String =
        System.getenv(name) ?: default
}
