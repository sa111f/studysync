package StudySyncer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Phase 8 — give every @Scheduled method a dedicated thread pool.
 *
 * Spring's default scheduler is single-threaded, which means a slow email
 * send in NotificationScheduler would block AccountabilityScheduler's
 * rollover check (and vice-versa). A 4-thread pool is plenty for our
 * current load (three jobs, each per-user serial, all under a few hundred
 * users).
 *
 * {@code @EnableScheduling} is already declared on StudySyncerApplication;
 * providing a {@link TaskScheduler} bean here makes Spring use it in place
 * of the default single-threaded one.
 */
@Configuration
public class SchedulerConfig implements SchedulingConfigurer {

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("ss-scheduler-");
        // Wait for in-flight jobs on shutdown so we don't lose a half-sent batch,
        // but cap at 30s to keep container restarts snappy.
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(taskScheduler());
    }
}
