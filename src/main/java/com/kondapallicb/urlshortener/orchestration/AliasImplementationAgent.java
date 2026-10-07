package com.kondapallicb.urlshortener.orchestration;

import java.util.List;

// This bounded agent supports one declared capability; unsupported work must be clarified.
public class AliasImplementationAgent {
    public List<FileOperation> implement(RequirementSpec.AliasOptions options) {
        return implement().stream().map(op -> new FileOperation(op.path(), op.content()
                .replace("{3,64}", "{" + options.minLength() + "," + options.maxLength() + "}")
                .replace("2592000", Long.toString(options.ttlSeconds()))
                .replace("request.alias().equals(\"api\") || request.alias().equals(\"actuator\")",
                        options.reservedAliases().stream().noneMatch(value -> value.length() >= options.minLength() && value.length() <= options.maxLength()) ? "false" : options.reservedAliases().stream()
                            .filter(value -> value.length() >= options.minLength() && value.length() <= options.maxLength())
                            .map(value -> "request.alias().equals(\"" + value + "\")").collect(java.util.stream.Collectors.joining(" || ")))
                .replace("[A-Za-z0-9_-]", options.alphabet() == RequirementSpec.AliasOptions.Alphabet.ALPHANUMERIC
                        ? "[A-Za-z0-9]" : "[A-Za-z0-9_-]"))).toList();
    }
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
                            throw new SlugConflictException(request.alias());
                        }
                        Instant now = Instant.now(clock);
                        ShortUrl mapping = new ShortUrl(request.alias(), URI.create(request.longUrl()),
                            now, now.plusSeconds(2592000));
                        try { return repository.save(mapping); }
                        catch (SlugConflictException conflict) { throw conflict; }
                    }
                }
                """));
    }
}
