package example;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

// WebFlux subscribes to the returned publisher; controllers must not call subscribe() or block().
// WebFlux подписывается на результат: контроллеру не нужны subscribe() или block().
@RestController
@RequestMapping("/reports")
public class ReportController {
    private final ReportService reports;
    public ReportController(ReportService reports) { this.reports = reports; }

    @PutMapping("/{id}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Void> create(@PathVariable UUID id, @RequestParam String quarter) {
        return reports.request(id, quarter);
    }

    @GetMapping(value = "/{id}", produces = "text/plain")
    public Mono<String> status(@PathVariable UUID id) { return reports.status(id); }
}
