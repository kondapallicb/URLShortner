package com.kondapallicb.urlshortener.orchestration;

import java.util.List;

// This bounded agent supports one declared capability; unsupported work must be clarified.
public class AliasImplementationAgent {
    public List<FileOperation> implement() {
        return List.of(new FileOperation(
                "src/main/java/com/kondapallicb/urlshortener/api/CustomAliasController.java", """
                package com.kondapallicb.urlshortener.api;
                import com.kondapallicb.urlshortener.domain.*;
                import jakarta.validation.Valid;
                import jakarta.validation.constraints.*;
                import java.net.URI;
                import java.time.Clock;
                import java.time.Instant;
                import org.springframework.http.HttpStatus;
                import org.springframework.web.bind.annotation.*;
                import org.springframework.web.server.ResponseStatusException;
                @RestController
                @RequestMapping("/api/aliases")
                public class CustomAliasController {
                    private final UrlMappingRepository repository;
                    private final Clock clock;
                    public CustomAliasController(UrlMappingRepository repository, Clock clock) {
                        this.repository = repository; this.clock = clock;
                    }
                    public record Request(@Pattern(regexp="[A-Za-z0-9_-]{3,64}") @NotBlank String alias,
                        @Pattern(regexp="https?://[^\\\\s]+") @NotBlank String longUrl) {}
                    @PostMapping
                    @ResponseStatus(HttpStatus.CREATED)
                    public ShortUrl create(@Valid @RequestBody Request request) {
                        if (request.alias().equals("api") || request.alias().equals("actuator")) {
                            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reserved alias");
                        }
                        Instant now = Instant.now(clock);
                        ShortUrl mapping = new ShortUrl(request.alias(), URI.create(request.longUrl()),
                            now, now.plusSeconds(2592000));
                        try { return repository.save(mapping); }
                        catch (IllegalStateException conflict) {
                            throw new ResponseStatusException(HttpStatus.CONFLICT, "Alias already exists");
                        }
                    }
                }
                """));
    }
}
