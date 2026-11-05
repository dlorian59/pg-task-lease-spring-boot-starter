package example;

import io.github.alex59.pgtasklease.TaskLease;
import io.github.alex59.pgtasklease.spring.PgTaskHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// A normal eagerly created Spring bean; the annotation only registers its handler method.
// Обычный bean Spring, создаваемый при запуске; аннотация только регистрирует метод обработчика.
@Component
public class ReportJobs {
    private final Path directory;

    public ReportJobs(@Value("${reports.directory:reports}") String directory) {
        this.directory = Path.of(directory);
    }

    // The starter moves this explicit blocking handler off event loops and renews the lease while it runs.
    // Стартер выносит этот явно блокирующий обработчик с event loop и продлевает аренду во время работы.
    @PgTaskHandler(value = ReportService.QUEUE, blocking = true)
    public void generate(TaskLease lease) throws java.io.IOException {
        // The task UUID is the output key; repeating an attempt replaces the same deterministic report.
        // UUID задачи служит ключом результата; повтор попытки заменяет тот же детерминированный отчёт.
        if (!lease.payload().matches("[0-9]{4}-Q[1-4]")) throw new IllegalArgumentException("Expected a quarter, such as 2026-Q4");
        Path folder = Files.createDirectories(directory);
        Path target = folder.resolve(lease.id() + ".csv");
        Path temporary = Files.createTempFile(folder, lease.id() + "-", ".tmp");
        try {
            // Replace these sample totals with a read of your application data.
            // Замените демонстрационные итоги чтением данных вашего приложения.
            Files.writeString(temporary, "quarter,invoices,total\n" + lease.payload() + ",3,450.00\n");
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
