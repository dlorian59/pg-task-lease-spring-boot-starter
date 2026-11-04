package example;

import io.github.alex59.pgtasklease.PgTaskQueue;
import io.github.alex59.pgtasklease.TaskRequest;
import io.github.alex59.pgtasklease.TaskState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;

// A finite background-report application; PostgreSQL is prepared before startup.
// Конечный пример фонового отчёта; PostgreSQL подготавливается до запуска приложения.
@SpringBootApplication
public class ReportApplication {
    public static void main(String[] args) {
        // Closing the context stops polling, heartbeat and recovery subscriptions.
        // Закрытие контекста останавливает опрос, heartbeat и восстановление задач.
        try (var context = SpringApplication.run(ReportApplication.class, args)) { }
    }

    @Bean
    ApplicationRunner submitAndVerify(PgTaskQueue queue, @Value("${reports.directory:reports}") String directory) {
        return args -> {
            // Keep the same task ID when retrying submission in a real producer workflow.
            // При повторной отправке в рабочем процессе производителя сохраняйте тот же ID задачи.
            UUID id = UUID.randomUUID();
            queue.submit(new TaskRequest(id, "reports", "2026-Q4", 0, 3, Duration.ZERO)).block(Duration.ofSeconds(5));
            var result = Flux.interval(Duration.ZERO, Duration.ofMillis(50))
                    .concatMap(tick -> queue.find(id))
                    .filter(task -> task.state() == TaskState.DONE || task.state() == TaskState.DEAD)
                    .next().block(Duration.ofSeconds(20));
            if (result == null || result.state() != TaskState.DONE) throw new IllegalStateException("Report did not complete");
            Path report = Path.of(directory).resolve(id + ".csv");
            if (!Files.readString(report).equals("quarter,invoices,total\n2026-Q4,3,450.00\n"))
                throw new IllegalStateException("Unexpected report contents");
            System.out.println("REPORT_DONE task=" + id + " attempts=" + result.attempts() + " path=" + report);
        };
    }
}
