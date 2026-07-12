package src.main.java.com.gauntlet.submission;


import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import com.gauntlet.execution.CodeExecutionService;

@RestController
@RequestMapping("/api/submissions")
public class SubmissionController {

    private final CodeExecutionService executionService;

    public SubmissionController(CodeExecutionService executionService) {
        this.executionService = executionService;
    }

    @PostMapping("/run")
    public ResponseEntity<CodeExecutionService.ExecutionResult> run(
            @RequestBody SubmissionRequest request) throws IOException {

        if (request == null || request.language() == null || request.language().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "language is required");
        }
        if (request.sourceCode() == null || request.sourceCode().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceCode is required");
        }

        var language = parseLanguage(request.language());
        // Programs may legitimately read no stdin; default null to an empty string.
        var stdin = request.stdin() == null ? "" : request.stdin();
        var result = executionService.run(language, request.sourceCode(), stdin);
        return ResponseEntity.ok(result);
    }

    private CodeExecutionService.Language parseLanguage(String language) {
        try {
            return CodeExecutionService.Language.valueOf(language.toUpperCase());
        } catch (IllegalArgumentException e) {
            String supported = Arrays.stream(CodeExecutionService.Language.values())
                    .map(l -> l.name().toLowerCase())
                    .collect(Collectors.joining(", "));
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "unsupported language '" + language + "'; supported: " + supported);
        }
    }
}

record SubmissionRequest(String language, String sourceCode, String stdin) {}