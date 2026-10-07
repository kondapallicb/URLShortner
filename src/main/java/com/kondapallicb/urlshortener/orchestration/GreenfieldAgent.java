package com.kondapallicb.urlshortener.orchestration;

import java.util.List;
import java.util.ArrayList;

// A minimal original application template, not a copy of the orchestration host.
public class GreenfieldAgent {
    public List<FileOperation> scaffold() {
        var files = new ArrayList<FileOperation>();
        files.add(new FileOperation("pom.xml", """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>3.3.5</version><relativePath/></parent>
              <groupId>com.kondapallicb</groupId><artifactId>url-shortener-agentic</artifactId><version>0.0.1-SNAPSHOT</version>
              <properties><java.version>21</java.version></properties>
              <dependencies>
                <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
                <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
                <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
              </dependencies>
              <build><plugins>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><configuration><argLine>@{argLine} -Xmx384m</argLine></configuration></plugin>
                <plugin><groupId>org.jacoco</groupId><artifactId>jacoco-maven-plugin</artifactId><version>0.8.12</version><executions>
                  <execution><goals><goal>prepare-agent</goal></goals></execution>
                  <execution><id>coverage</id><phase>verify</phase><goals><goal>report</goal></goals></execution>
                  <execution><id>coverage-check</id><phase>verify</phase><goals><goal>check</goal></goals><configuration><rules><rule><element>CLASS</element><includes><include>*CustomAliasController</include></includes><limits>
                    <limit><counter>LINE</counter><value>COVEREDRATIO</value><minimum>0.80</minimum></limit>
                    <limit><counter>BRANCH</counter><value>COVEREDRATIO</value><minimum>0.70</minimum></limit>
                  </limits></rule></rules></configuration></execution>
                </executions></plugin>
                <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin>
              </plugins></build>
            </project>
            """));
        add(files, "UrlShortenerApplication", """
            package com.kondapallicb.urlshortener;
            @org.springframework.boot.autoconfigure.SpringBootApplication
            public class UrlShortenerApplication {
                public static void main(String[] args) { org.springframework.boot.SpringApplication.run(UrlShortenerApplication.class, args); }
                @org.springframework.context.annotation.Bean java.time.Clock clock() { return java.time.Clock.systemUTC(); }
            }
            """);
        add(files, "domain/ShortUrl", """
            package com.kondapallicb.urlshortener.domain;
            public record ShortUrl(String slug, java.net.URI longUrl, java.time.Instant createdAt, java.time.Instant expiresAt) {}
            """);
        add(files, "domain/SlugConflictException", """
            package com.kondapallicb.urlshortener.domain;
            @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CONFLICT)
            public class SlugConflictException extends RuntimeException {
                public SlugConflictException(String slug) { super("Alias already exists or is reserved: " + slug); }
            }
            """);
        add(files, "domain/UrlMappingRepository", """
            package com.kondapallicb.urlshortener.domain;
            @org.springframework.stereotype.Repository
            public class UrlMappingRepository {
                private final java.util.concurrent.ConcurrentHashMap<String, ShortUrl> mappings = new java.util.concurrent.ConcurrentHashMap<>();
                public ShortUrl save(ShortUrl mapping) {
                    if (mappings.putIfAbsent(mapping.slug(), mapping) != null) throw new SlugConflictException(mapping.slug());
                    return mapping;
                }
                public ShortUrl find(String slug) { return mappings.get(slug); }
            }
            """);
        add(files, "api/UrlController", """
            package com.kondapallicb.urlshortener.api;
            @org.springframework.web.bind.annotation.RestController
            public class UrlController {
                private final com.kondapallicb.urlshortener.domain.UrlMappingRepository repository;
                private final java.time.Clock clock;
                public UrlController(com.kondapallicb.urlshortener.domain.UrlMappingRepository repository, java.time.Clock clock) {
                    this.repository = repository; this.clock = clock;
                }
                @org.springframework.web.bind.annotation.GetMapping("/{slug}")
                public org.springframework.http.ResponseEntity<Void> resolveAndRecordClick(@org.springframework.web.bind.annotation.PathVariable String slug) {
                    var mapping = repository.find(slug);
                    if (mapping == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
                    if (!java.time.Instant.now(clock).isBefore(mapping.expiresAt()))
                        throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.GONE);
                    return org.springframework.http.ResponseEntity.status(302).location(mapping.longUrl()).build();
                }
            }
            """);
        files.add(new FileOperation("src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker", "mock-maker-subclass\n"));
        return List.copyOf(files);
    }
    private void add(List<FileOperation> files, String name, String content) {
        files.add(new FileOperation("src/main/java/com/kondapallicb/urlshortener/" + name + ".java", content));
    }
}
