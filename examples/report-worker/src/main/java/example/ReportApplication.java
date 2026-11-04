package example;

import io.github.alex59.pgtasklease.PgTaskQueue;
import io.github.alex59.pgtasklease.TaskRequest;
import io.github.alex59.pgtasklease.TaskState;
import io.github.alex59.pgtasklease.spring.TaskHandlerRegistration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
    TaskHandlerRegistration reportHandler(@Value("${reports.directory:reports}") String directory) {
        return new TaskHandlerRegistration("reports", lease -> Mono.fromCallable(() -> {
            // The task UUID is the output key; repeating an attempt replaces the same deterministic report.
            // UUID задачи служит ключом результата; повтор попытки заменяет тот же детерминированный отчёт.
            if (!lease.payload().matches("[0-9]{4}-Q[1-4]")) throw new IllegalArgumentException("Expected a quarter, such as 2026-Q4");
            Path folder = Files.createDirectories(Path.of(directory));
            Path target = folder.resolve(lease.id() + ".csv");
            Path temporary = Files.createTempFile(folder, lease.id() + "-", ".tmp");
            try {
                // Replace these sample totals with a read of your application data.
                // Замените демонстрационные итоги чтением данных вашего приложения.
                Files.writeString(temporary, "quarter,invoices,total\n" + lease.payload() + ",3,450.00\n");
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
            return target;
        // File I/O runs outside Reactor event loops and heartbeat timers.
        // Файловый ввод-вывод выполняется вне event loop Reactor и таймеров heartbeat.
        }).subscribeOn(Schedulers.boundedElastic()).then());
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
