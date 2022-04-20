package nl.knaw.huc.annorepo

import com.codahale.metrics.health.HealthCheck
import com.fasterxml.jackson.databind.module.SimpleModule
import io.dropwizard.Application
import io.dropwizard.configuration.EnvironmentVariableSubstitutor
import io.dropwizard.configuration.SubstitutingSourceProvider
import io.dropwizard.jdbi3.JdbiFactory
import io.dropwizard.jdbi3.bundles.JdbiExceptionsBundle
import io.dropwizard.setup.Bootstrap
import io.dropwizard.setup.Environment
import io.federecio.dropwizard.swagger.SwaggerBundle
import io.federecio.dropwizard.swagger.SwaggerBundleConfiguration
import nl.knaw.huc.annorepo.api.ARConst
import nl.knaw.huc.annorepo.cli.EnvCommand
import nl.knaw.huc.annorepo.health.ServerHealthCheck
import nl.knaw.huc.annorepo.resources.AboutResource
import nl.knaw.huc.annorepo.resources.HomePageResource
import nl.knaw.huc.annorepo.resources.RuntimeExceptionMapper
import nl.knaw.huc.annorepo.resources.W3CResource
import nl.knaw.huc.annorepo.service.LocalDateTimeSerializer
import org.apache.commons.lang3.StringUtils
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.postgres.PostgresPlugin
import org.jdbi.v3.sqlobject.SqlObjectPlugin
import org.slf4j.LoggerFactory
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean

class AnnoRepoApplication : Application<AnnoRepoConfiguration?>() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun getName(): String = "AnnoRepo"

    override fun initialize(bootstrap: Bootstrap<AnnoRepoConfiguration?>) {
        bootstrap.configurationSourceProvider = SubstitutingSourceProvider(
            bootstrap.configurationSourceProvider, EnvironmentVariableSubstitutor()
        )
        bootstrap.addBundle(getSwaggerBundle())
        bootstrap.addBundle(JdbiExceptionsBundle())
        bootstrap.addCommand(EnvCommand())
    }

    private fun getSwaggerBundle() = object : SwaggerBundle<AnnoRepoConfiguration>() {
        override fun getSwaggerBundleConfiguration(configuration: AnnoRepoConfiguration): SwaggerBundleConfiguration =
            configuration.swaggerBundleConfiguration
    }

    override fun run(configuration: AnnoRepoConfiguration?, environment: Environment) {
        log.info(
            "AR_ environment variables:\n\n" +
                    ARConst.EnvironmentVariable.values()
                        .joinToString("\n") { e ->
                            "  ${e.name}:\t${System.getenv(e.name) ?: "(not set, using default)"}"
                        } +
                    "\n"
        )
        log.info("db.url = {}", configuration!!.database.url)
        log.info("db.user = {}", configuration.database.user)
        log.info("db.password = {}", configuration.database.password)

        val jdbi = createJdbi(environment, configuration)

        val appVersion = javaClass.getPackage().implementationVersion
        environment.jersey().apply {
            register(AboutResource(configuration, name, appVersion))
            register(HomePageResource())
            register(W3CResource(configuration, jdbi))
            register(RuntimeExceptionMapper())
        }
        environment.healthChecks().apply {
            register("server", ServerHealthCheck())
        }

        customizeObjectMaper(environment)

        doHealthChecks(environment)

        log.info(
            "\n\n  Starting $name (v$appVersion), externally accessible at ${configuration.externalBaseUrl}\n"
        )
    }

    private fun customizeObjectMaper(environment: Environment) {
        val objectMapper = environment.objectMapper
        objectMapper.dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
        val module = SimpleModule()
        module.addSerializer(LocalDateTime::class.java, LocalDateTimeSerializer("yyyy-MM-dd'T'HH:mm:ss"))
        objectMapper.registerModule(module)
    }

    private fun createJdbi(
        environment: Environment,
        configuration: AnnoRepoConfiguration
    ): Jdbi {
        val factory = JdbiFactory()
        val jdbi = factory.build(environment, configuration.database, "postgresql")
        jdbi.installPlugin(SqlObjectPlugin())
        jdbi.installPlugin(PostgresPlugin())
        return jdbi
    }

    private fun doHealthChecks(environment: Environment) {
        val results = environment.healthChecks().runHealthChecks()
        val healthy = AtomicBoolean(true)
        log.info("Health checks:")
        results.forEach { (name: String?, result: HealthCheck.Result) ->
            log.info(
                "  {}: {}, message='{}'",
                name,
                if (result.isHealthy) "healthy" else "unhealthy",
                StringUtils.defaultIfBlank(result.message, "")
            )
            healthy.set(healthy.get() && result.isHealthy)
        }
        if (!healthy.get()) {
            throw RuntimeException("Failing health check(s)")
        }
    }

    companion object {
        @Throws(Exception::class)
        @JvmStatic
        fun main(args: Array<String>) {
            AnnoRepoApplication().run(*args)
        }
    }
}