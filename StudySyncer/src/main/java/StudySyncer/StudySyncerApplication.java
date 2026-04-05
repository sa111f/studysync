package StudySyncer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;

@SpringBootApplication
public class StudySyncerApplication {

    private static final Logger log = LoggerFactory.getLogger(StudySyncerApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(StudySyncerApplication.class, args);
    }

    /**
     * Logs the active port and Spring profiles immediately after the context is
     * ready — gives a clear "app is up" marker in Railway's deploy logs.
     */
    @Bean
    public CommandLineRunner onStartup(Environment env) {
        return args -> {
            String port     = env.getProperty("server.port", "8080");
            String profiles = String.join(", ", env.getActiveProfiles());
            System.out.println("=== StudySync started on port " + port
                               + " | profiles: " + (profiles.isEmpty() ? "default" : profiles)
                               + " ===");
        };
    }

    /**
     * Runs once on startup. Opens one connection from the pool, reads the DB
     * metadata, and logs the confirmation. Fails fast with a clear message if
     * the PostgreSQL connection cannot be established.
     */
    @Bean
    public CommandLineRunner dbStartupCheck(DataSource dataSource) {
        return args -> {
            try (Connection conn = dataSource.getConnection()) {
                DatabaseMetaData meta = conn.getMetaData();
                log.info("=================================================");
                log.info("Database : {} {}", meta.getDatabaseProductName(),
                                              meta.getDatabaseProductVersion());
                log.info("JDBC URL : {}", meta.getURL());
                log.info("User     : {}", meta.getUserName());
                log.info("=================================================");
            } catch (Exception e) {
                log.error("=================================================");
                log.error("FAILED to connect to PostgreSQL: {}", e.getMessage());
                log.error("Check that PostgreSQL is running, the 'studysyncer'");
                log.error("database exists, and the credentials in");
                log.error("application.properties are correct.");
                log.error("=================================================");
                throw e; // stop startup — no point running with no DB
            }
        };
    }
}
