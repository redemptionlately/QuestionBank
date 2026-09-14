package com.allen.questionbank.importjob;

import com.allen.questionbank.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/import-jobs")
/* /jobs 是 ADMIN 专属导航项（app.js NAV roles:['ADMIN']），这里却按 STUDENT 放行 —— 角色写反，
 * 管理员点"作业"页必然 403。改为 ADMIN 主导 + STUDENT 兼容。 */
@PreAuthorize("hasAnyRole('ADMIN','STUDENT')")
public class ImportJobController {
    private final ImportJobService service;
    public ImportJobController(ImportJobService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<JobView> submit(@Valid @RequestBody CreateJobRequest request) {
        ImportJob job = service.submit(CurrentUser.require(), request.sourceName());
        return ResponseEntity.accepted().location(URI.create("/api/import-jobs/" + job.getId())).body(view(job));
    }

    @GetMapping("/{id}")
    public JobView get(@PathVariable Long id) { return view(service.require(CurrentUser.require(), id)); }

    private static JobView view(ImportJob job) {
        return new JobView(job.getId(), job.getSourceName(), job.getStatus(), job.getProgress(), job.getAttempt(), job.getError());
    }
    public record CreateJobRequest(@NotBlank @Size(max = 200) String sourceName) {}
    public record JobView(Long id, String sourceName, ImportJobStatus status, int progress, int attempt, String error) {}
}
