package example;

import io.github.alex59.pgtasklease.PgTaskQueue;
import io.github.alex59.pgtasklease.TaskRequest;
import io.github.alex59.pgtasklease.TaskSnapshot;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Service
public class ReportService {
    public static final String QUEUE = "reports";
    private final PgTaskQueue queue;
    public ReportService(PgTaskQueue queue) { this.queue = queue; }

    public Mono<Void> request(UUID id, String quarter) {
        if (!quarter.matches("[0-9]{4}-Q[1-4]"))
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected YYYY-Q1..Q4"));
        // The caller retains this UUID across network retries; five attempts, normal priority, no delay.
        // Клиент сохраняет UUID при сетевых повторах: пять попыток, обычный приоритет, без задержки.
        return queue.submit(TaskRequest.ready(id, QUEUE, quarter)).flatMap(inserted -> inserted ? Mono.empty()
                : find(id).then());
    }

    public Mono<String> status(UUID id) { return find(id).map(task -> task.state().name()); }

    private Mono<TaskSnapshot> find(UUID id) {
        // In a multi-user application, enforce ownership here before returning status or accepting a duplicate.
        // В многопользовательском приложении здесь проверяйте владельца до выдачи статуса или приёма дубликата.
        return queue.find(id).filter(task -> QUEUE.equals(task.queue()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }
}
